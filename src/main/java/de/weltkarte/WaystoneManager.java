package de.weltkarte;

import de.weltkarte.model.Marker;
import de.weltkarte.model.MarkerStore;
import de.weltkarte.model.MarkerType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;

/**
 * Wegsteine: Ein Magnetit-Block (Lodestone), der auf der Karte als Wegstein markiert ist.
 * Rechtsklick = Wegstein entdecken + Reiseziel-Menü. Von Wegstein zu Wegstein kann man sich teleportieren.
 */
public class WaystoneManager implements Listener {

    private final WeltKartePlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public WaystoneManager(WeltKartePlugin plugin) { this.plugin = plugin; }

    private MarkerStore store() { return plugin.markers(); }

    public Marker findAt(Block b) {
        String w = b.getWorld().getName();
        for (Marker m : store().published())
            if (m.type == MarkerType.WEGSTEIN && m.sameBlock(w, b.getX(), b.getY(), b.getZ())) return m;
        return null;
    }

    public Marker findDraftAt(Block b) {
        String w = b.getWorld().getName();
        for (Marker m : store().draft())
            if (m.type == MarkerType.WEGSTEIN && m.sameBlock(w, b.getX(), b.getY(), b.getZ())) return m;
        return null;
    }

    /** Wegstein, an dem der Spieler gerade steht (veröffentlicht). */
    public Marker nearbyWaystone(Player p) {
        double r = plugin.getConfig().getDouble("waystones.use-radius", 6);
        Location l = p.getLocation();
        Marker best = null;
        double bestD = r * r;
        for (Marker m : store().published()) {
            if (m.type != MarkerType.WEGSTEIN || !m.hasY() || !l.getWorld().getName().equals(m.world)) continue;
            double dx = m.x + 0.5 - l.getX(), dy = m.y - l.getY(), dz = m.z + 0.5 - l.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) { bestD = d; best = m; }
        }
        return best;
    }

    /** Nächster veröffentlichter Wegstein zu einem Ort (oder der Ort selbst, wenn er ein Wegstein ist). */
    public Marker waystoneFor(Marker place) {
        if (place.type == MarkerType.WEGSTEIN) return place;
        double r = plugin.getConfig().getDouble("waystones.destination-radius", 200);
        Marker best = null;
        double bestD = r * r;
        for (Marker m : store().published()) {
            if (m.type != MarkerType.WEGSTEIN || !Objects.equals(m.world, place.world)) continue;
            double d = m.distanceSq2D(place.centerX(), place.centerZ());
            if (d <= bestD) { bestD = d; best = m; }
        }
        return best;
    }

    public boolean canUse(Player p, Marker ws) {
        if (!plugin.getConfig().getBoolean("waystones.require-discovery", true)) return true;
        if (p.hasPermission("weltkarte.admin")) return true;
        return store().hasDiscovered(p.getUniqueId(), ws.id);
    }

    public boolean mayTeleportFromHere(Player p) {
        return plugin.getConfig().getBoolean("waystones.teleport-from-anywhere", false)
                || p.hasPermission("weltkarte.admin") || nearbyWaystone(p) != null;
    }

    public void teleport(Player p, Marker ws) {
        if (!ws.hasY()) {
            p.sendMessage(Component.text("Dieser Wegstein ist noch nicht platziert.", NamedTextColor.RED));
            return;
        }
        boolean admin = p.hasPermission("weltkarte.admin");
        if (!canUse(p, ws)) {
            p.sendMessage(Component.text("Du hast den Wegstein '" + ws.name + "' noch nicht entdeckt. Geh einmal hin und klicke ihn an!", NamedTextColor.RED));
            return;
        }
        if (!mayTeleportFromHere(p)) {
            p.sendMessage(Component.text("Du musst an einem Wegstein stehen, um zu reisen.", NamedTextColor.RED));
            return;
        }
        long cd = plugin.getConfig().getLong("waystones.cooldown-seconds", 20) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (!admin && last != null && System.currentTimeMillis() - last < cd) {
            long rest = (cd - (System.currentTimeMillis() - last)) / 1000 + 1;
            p.sendMessage(Component.text("Bitte warte noch " + rest + " s.", NamedTextColor.RED));
            return;
        }
        int cost = plugin.getConfig().getInt("waystones.xp-level-cost", 0);
        if (!admin && cost > 0) {
            if (p.getLevel() < cost) {
                p.sendMessage(Component.text("Du brauchst " + cost + " Level zum Reisen.", NamedTextColor.RED));
                return;
            }
            p.setLevel(p.getLevel() - cost);
        }
        World w = Bukkit.getWorld(ws.world);
        if (w == null) return;
        Location target = new Location(w, ws.x + 0.5, ws.y + 1, ws.z + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch());
        cooldowns.put(p.getUniqueId(), System.currentTimeMillis());
        p.closeInventory();
        Location from = p.getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.3);
        p.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
        p.teleportAsync(target).thenAccept(ok -> {
            if (!ok) return;
            p.playSound(target, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.6f, 1.6f);
            w.spawnParticle(Particle.REVERSE_PORTAL, target.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.05);
            p.sendActionBar(Component.text("✦ Gereist nach " + ws.name, NamedTextColor.LIGHT_PURPLE));
        });
    }

    // ------------------------------------------------------------------
    // Blöcke beim Veröffentlichen platzieren / entfernen
    // ------------------------------------------------------------------

    public void syncBlocks(MarkerStore.PublishResult result) {
        for (Marker m : result.removedWaystones()) {
            World w = Bukkit.getWorld(m.world);
            if (w == null || !m.hasY()) continue;
            w.getChunkAtAsync(m.blockX() >> 4, m.blockZ() >> 4).thenAccept(c -> {
                Block b = w.getBlockAt(m.blockX(), m.blockY(), m.blockZ());
                boolean stillUsed = store().published().stream().anyMatch(o -> o.type == MarkerType.WEGSTEIN
                        && o.sameBlock(m.world, b.getX(), b.getY(), b.getZ()));
                if (!stillUsed && b.getType() == Material.LODESTONE) b.setType(Material.AIR);
            });
        }
        for (Marker m : result.addedWaystones()) {
            World w = Bukkit.getWorld(m.world);
            if (w == null) continue;
            w.getChunkAtAsync(m.blockX() >> 4, m.blockZ() >> 4).thenAccept(c -> {
                int y = m.hasY() ? m.blockY() : w.getHighestBlockYAt(m.blockX(), m.blockZ()) + 1;
                Block b = w.getBlockAt(m.blockX(), y, m.blockZ());
                if (b.getType() != Material.LODESTONE) b.setType(Material.LODESTONE);
                if (!m.hasY()) store().fixY(m.id, y);
            });
        }
    }

    /** Admin: Wegstein am Blick-/Standort setzen (als Entwurf, Block wird sofort platziert). */
    public Marker placeHere(Player p, String name) {
        Block target = p.getTargetBlockExact(5);
        Block b = target != null && !target.getType().isAir() ? target.getRelative(0, 1, 0) : p.getLocation().getBlock();
        if (!b.getType().isAir() && !b.isReplaceable()) b = p.getLocation().getBlock();
        b.setType(Material.LODESTONE);
        Marker m = new Marker();
        m.type = MarkerType.WEGSTEIN;
        m.name = name;
        m.world = b.getWorld().getName();
        m.x = b.getX();
        m.y = (double) b.getY();
        m.z = b.getZ();
        m.color = "#7e57c2";
        store().addDraft(m);
        b.getWorld().spawnParticle(Particle.ENCHANT, b.getLocation().add(0.5, 1.2, 0.5), 80, 0.4, 0.4, 0.4, 0.8);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
        return m;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.LODESTONE) return;
        Player p = e.getPlayer();
        Marker ws = findAt(b);
        if (ws == null) {
            Marker draft = findDraftAt(b);
            if (draft != null) {
                e.setCancelled(true);
                p.sendMessage(Component.text("Der Wegstein '" + draft.name + "' ist noch nicht veröffentlicht. "
                        + (p.hasPermission("weltkarte.admin") ? "Klicke in /karte control auf 'Karte updaten'." : ""), NamedTextColor.YELLOW));
            }
            return;
        }
        e.setCancelled(true);
        if (store().discover(p.getUniqueId(), ws.id)) {
            p.showTitle(net.kyori.adventure.title.Title.title(Component.text("Wegstein entdeckt", NamedTextColor.LIGHT_PURPLE),
                    Component.text(ws.name, NamedTextColor.GOLD)));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        }
        plugin.placesMenu().open(p, ws, null, 0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.LODESTONE) return;
        Marker pub = findAt(b);
        Marker draft = findDraftAt(b);
        if (pub == null && draft == null) return;
        Player p = e.getPlayer();
        if (p.hasPermission("weltkarte.admin") && p.isSneaking()) {
            if (draft != null) store().removeDraft(draft.id);
            p.sendMessage(Component.text("Wegstein entfernt. Mit 'Karte updaten' wird das für alle übernommen.", NamedTextColor.YELLOW));
            return;
        }
        e.setCancelled(true);
        p.sendMessage(Component.text("Das ist ein Wegstein und kann nicht abgebaut werden."
                + (p.hasPermission("weltkarte.admin") ? " (Admins: beim Schleichen abbauen)" : ""), NamedTextColor.RED));
    }

    private boolean isWaystoneBlock(Block b) {
        return b.getType() == Material.LODESTONE && (findAt(b) != null || findDraftAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isWaystoneBlock); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::isWaystoneBlock); }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (e.getBlocks().stream().anyMatch(this::isWaystoneBlock)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (e.getBlocks().stream().anyMatch(this::isWaystoneBlock)) e.setCancelled(true);
    }
}
