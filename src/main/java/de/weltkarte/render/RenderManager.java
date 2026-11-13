package de.weltkarte.render;

import de.weltkarte.WeltKartePlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Plant und verteilt das Rendern von Chunks (live beim Laden/Bauen und als kompletter Welt-Render). */
public class RenderManager implements Listener {

    private final WeltKartePlugin plugin;
    private final TileStore tiles;
    private final ExecutorService workers;
    private final Map<String, Set<Long>> queued = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> dirty = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRendered = new ConcurrentHashMap<>();
    private final AtomicInteger pendingWork = new AtomicInteger();
    private final int snapshotsPerTick;
    private final long rerenderMillis;
    private FullRender fullRender;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public RenderManager(WeltKartePlugin plugin, TileStore tiles) {
        this.plugin = plugin;
        this.tiles = tiles;
        int threads = Math.max(1, plugin.getConfig().getInt("render.threads", 2));
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "WeltKarte-Render");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        this.snapshotsPerTick = Math.max(1, plugin.getConfig().getInt("render.chunks-per-tick", 10));
        this.rerenderMillis = plugin.getConfig().getLong("render.rerender-loaded-chunks-after-minutes", 10) * 60_000L;
    }

    public void start() {
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::queueDirty, 100L, 100L));
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, tiles::flushAsync, 600L, 600L));
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        if (fullRender != null) fullRender.finish(false);
        workers.shutdown();
        try { workers.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public boolean isWorldEnabled(World w) {
        List<String> list = plugin.getConfig().getStringList("render.worlds");
        return list.isEmpty() || list.contains(w.getName());
    }

    private static long pack(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    private static int unpackX(long k) { return (int) (k >> 32); }
    private static int unpackZ(long k) { return (int) k; }

    // ------------------------------------------------------------------
    // Warteschlange
    // ------------------------------------------------------------------

    private final Deque<Object[]> mainQueue = new ConcurrentLinkedDeque<>();

    /** Chunk zum Rendern vormerken (darf von jedem Thread aufgerufen werden). */
    public void enqueue(World world, int cx, int cz, boolean urgent) {
        Set<Long> set = queued.computeIfAbsent(world.getName(), k -> ConcurrentHashMap.newKeySet());
        if (!set.add(pack(cx, cz))) return;
        Object[] entry = {world.getUID(), cx, cz};
        if (urgent) mainQueue.addFirst(entry); else mainQueue.addLast(entry);
    }

    private void tick() {
        int budget = snapshotsPerTick;
        long start = System.nanoTime();
        while (budget-- > 0 && !mainQueue.isEmpty() && System.nanoTime() - start < 8_000_000L) {
            Object[] e = mainQueue.pollFirst();
            if (e == null) break;
            World w = Bukkit.getWorld((UUID) e[0]);
            int cx = (int) e[1], cz = (int) e[2];
            if (w == null) continue;
            Set<Long> set = queued.get(w.getName());
            if (set != null) set.remove(pack(cx, cz));
            if (!w.isChunkLoaded(cx, cz)) continue;
            snapshotAndRender(w.getChunkAt(cx, cz));
        }
        if (fullRender != null) fullRender.tick();
    }

    private void snapshotAndRender(Chunk chunk) {
        World w = chunk.getWorld();
        ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
        String worldName = w.getName();
        World.Environment env = w.getEnvironment();
        int minY = w.getMinHeight(), maxY = w.getMaxHeight();
        int cx = chunk.getX(), cz = chunk.getZ();
        lastRendered.put(worldName + ":" + cx + ":" + cz, System.currentTimeMillis());
        pendingWork.incrementAndGet();
        workers.execute(() -> {
            try {
                int[] colors = ChunkRenderer.render(snap, env, minY, maxY);
                tiles.writeChunk(worldName, cx, cz, colors);
            } catch (Throwable t) {
                plugin.getLogger().warning("Fehler beim Rendern von Chunk " + cx + "," + cz + ": " + t);
            } finally {
                pendingWork.decrementAndGet();
            }
        });
    }

    private void queueDirty() {
        for (Map.Entry<String, Set<Long>> e : dirty.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) continue;
            Iterator<Long> it = e.getValue().iterator();
            while (it.hasNext()) {
                long k = it.next();
                it.remove();
                enqueue(w, unpackX(k), unpackZ(k), true);
            }
        }
    }

    private void markDirty(Block b) {
        World w = b.getWorld();
        if (!isWorldEnabled(w)) return;
        dirty.computeIfAbsent(w.getName(), k -> ConcurrentHashMap.newKeySet()).add(pack(b.getX() >> 4, b.getZ() >> 4));
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        World w = e.getWorld();
        if (!isWorldEnabled(w) || !plugin.getConfig().getBoolean("render.render-on-chunk-load", true)) return;
        if (fullRender != null && fullRender.world.equals(w)) return;
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        Long last = lastRendered.get(w.getName() + ":" + cx + ":" + cz);
        if (last != null && System.currentTimeMillis() - last < rerenderMillis) return;
        String worldName = w.getName();
        if (e.isNewChunk()) { enqueue(w, cx, cz, false); return; }
        // Prüfung, ob schon gerendert, abseits des Hauptthreads (kann Festplattenzugriff brauchen)
        workers.execute(() -> {
            boolean rendered = tiles.isChunkRendered(worldName, cx, cz);
            if (!rendered || last == null) enqueue(w, cx, cz, false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { markDirty(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { markDirty(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { markDirty(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { markDirty(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { markDirty(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { markDirty(e.getToBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) { markDirty(e.getLocation().getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().forEach(this::markDirty); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().forEach(this::markDirty); }

    // ------------------------------------------------------------------
    // Kompletter Welt-Render
    // ------------------------------------------------------------------

    public boolean isFullRenderRunning() { return fullRender != null; }

    public void cancelFullRender() {
        if (fullRender != null) fullRender.finish(false);
    }

    public String fullRenderStatus() {
        return fullRender == null ? null : fullRender.status();
    }

    public void startFullRender(World world, int centerX, int centerZ, int radiusBlocks, CommandSender sender) {
        if (fullRender != null) {
            sender.sendMessage(Component.text("Es läuft bereits ein Render: " + fullRender.status(), NamedTextColor.RED));
            return;
        }
        fullRender = new FullRender(world, centerX >> 4, centerZ >> 4, Math.max(1, radiusBlocks >> 4), sender);
        sender.sendMessage(Component.text("Welt-Render für '" + world.getName() + "' gestartet (Radius " + radiusBlocks
                + " Blöcke). Es werden nur bereits generierte Chunks gerendert.", NamedTextColor.GREEN));
    }

    private final class FullRender {
        final World world;
        final int ccx, ccz, radius;
        final CommandSender sender;
        final BossBar bar;
        final long total;
        long checked = 0;
        int ring = 0, ringIndex = 0;
        int rendered = 0;
        final AtomicInteger inFlight = new AtomicInteger();
        final int maxInFlight = plugin.getConfig().getInt("render.full-render-parallel-chunks", 24);
        final long startedAt = System.currentTimeMillis();
        boolean scanning = true;

        FullRender(World world, int ccx, int ccz, int radius, CommandSender sender) {
            this.world = world;
            this.ccx = ccx;
            this.ccz = ccz;
            this.radius = radius;
            this.sender = sender;
            long side = 2L * radius + 1;
            this.total = side * side;
            this.bar = BossBar.bossBar(Component.text("Weltkarte wird gerendert…"), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            if (sender instanceof Player p) p.showBossBar(bar);
        }

        /** Nächste Chunk-Koordinate in einer Spirale (Ring für Ring) um die Mitte. */
        long[] next() {
            while (ring <= radius) {
                int side = ring * 2;
                int count = ring == 0 ? 1 : side * 4;
                if (ringIndex < count) {
                    int i = ringIndex++;
                    int x, z;
                    if (ring == 0) { x = 0; z = 0; }
                    else if (i < side) { x = -ring + i; z = -ring; }
                    else if (i < 2 * side) { x = ring; z = -ring + (i - side); }
                    else if (i < 3 * side) { x = ring - (i - 2 * side); z = ring; }
                    else { x = -ring; z = ring - (i - 3 * side); }
                    return new long[]{ccx + x, ccz + z};
                }
                ring++;
                ringIndex = 0;
            }
            return null;
        }

        void tick() {
            if (!scanning) {
                if (inFlight.get() == 0 && pendingWork.get() == 0) finish(true);
                return;
            }
            int checks = 0;
            long start = System.nanoTime();
            while (inFlight.get() < maxInFlight && checks < 2000 && System.nanoTime() - start < 10_000_000L) {
                long[] c = next();
                if (c == null) { scanning = false; break; }
                checks++;
                checked++;
                int cx = (int) c[0], cz = (int) c[1];
                if (!world.isChunkGenerated(cx, cz)) continue;
                inFlight.incrementAndGet();
                world.getChunkAtAsync(cx, cz, false).whenComplete((chunk, err) -> {
                    // läuft auf dem Hauptthread
                    try {
                        if (chunk != null && fullRender == this) {
                            snapshotAndRender(chunk);
                            rendered++;
                        }
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }
            if (Bukkit.getCurrentTick() % 20 == 0) {
                float progress = (float) Math.min(1.0, (double) checked / total);
                bar.progress(progress);
                bar.name(Component.text("Weltkarte: " + status()));
            }
        }

        String status() {
            return String.format(Locale.GERMAN, "%s – %.1f%% (%d Chunks gerendert)", world.getName(),
                    100.0 * checked / Math.max(1, total), rendered);
        }

        void finish(boolean ok) {
            fullRender = null;
            if (sender instanceof Player p) p.hideBossBar(bar);
            tiles.flushAsync();
            long secs = (System.currentTimeMillis() - startedAt) / 1000;
            sender.sendMessage(Component.text(ok
                    ? "✔ Welt-Render fertig: " + rendered + " Chunks in " + secs + " s."
                    : "Welt-Render abgebrochen (" + rendered + " Chunks gerendert).", ok ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }
    }
}
