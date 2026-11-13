package de.weltkarte.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Hält die gerenderten Kacheln im Speicher (LRU) und auf der Festplatte
 * (plugins/WeltKarte/tiles/&lt;welt&gt;/r.X.Z.png). Erzeugt außerdem verkleinerte
 * Übersichtskacheln für die Web-Karte.
 */
public class TileStore {

    private final File baseDir;
    private final Logger log;
    private final int maxTiles;
    private final LinkedHashMap<String, Tile> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WeltKarte-IO");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> zoomChecked = new ConcurrentHashMap<>();
    private final Object zoomLock = new Object();

    public TileStore(File baseDir, Logger log, int maxTiles) {
        this.baseDir = baseDir;
        this.log = log;
        this.maxTiles = Math.max(16, maxTiles);
        baseDir.mkdirs();
    }

    private static String key(String world, int rx, int rz) { return world + "/" + rx + "/" + rz; }

    public File worldDir(String world) { return new File(baseDir, world); }

    public File regionFile(String world, int rx, int rz) {
        return new File(worldDir(world), "r." + rx + "." + rz + ".png");
    }

    private File metaFile(String world, int rx, int rz) {
        return new File(worldDir(world), "r." + rx + "." + rz + ".bin");
    }

    /** Gibt die Kachel zurück; lädt sie bei Bedarf synchron von der Platte oder erzeugt sie. */
    public Tile getOrCreate(String world, int rx, int rz) {
        String k = key(world, rx, rz);
        synchronized (cache) {
            Tile t = cache.get(k);
            if (t != null) { t.lastAccess = System.currentTimeMillis(); return t; }
        }
        Tile t = loadFromDisk(world, rx, rz);
        if (t == null) t = new Tile(world, rx, rz);
        return put(k, t);
    }

    /** Nicht blockierend: Kachel aus dem Speicher, sonst wird sie im Hintergrund geladen (Rückgabe null). */
    public Tile getIfLoaded(String world, int rx, int rz, boolean loadAsync) {
        String k = key(world, rx, rz);
        synchronized (cache) {
            Tile t = cache.get(k);
            if (t != null) { t.lastAccess = System.currentTimeMillis(); return t; }
        }
        if (loadAsync && regionFile(world, rx, rz).exists() && loading.add(k)) {
            io.execute(() -> {
                try {
                    synchronized (cache) { if (cache.containsKey(k)) return; }
                    Tile t = loadFromDisk(world, rx, rz);
                    if (t != null) put(k, t);
                } finally {
                    loading.remove(k);
                }
            });
        }
        return null;
    }

    private Tile put(String k, Tile t) {
        List<Tile> evicted = new ArrayList<>();
        synchronized (cache) {
            Tile existing = cache.get(k);
            if (existing != null) return existing;
            cache.put(k, t);
            while (cache.size() > maxTiles) {
                Iterator<Map.Entry<String, Tile>> it = cache.entrySet().iterator();
                Tile old = it.next().getValue();
                it.remove();
                if (old.dirty) evicted.add(old);
            }
        }
        for (Tile old : evicted) io.execute(() -> saveTile(old));
        return t;
    }

    private Tile loadFromDisk(String world, int rx, int rz) {
        File png = regionFile(world, rx, rz);
        if (!png.exists()) return null;
        try {
            BufferedImage img = ImageIO.read(png);
            if (img == null) return null;
            Tile t = new Tile(world, rx, rz);
            img.getRGB(0, 0, Tile.SIZE, Tile.SIZE, t.pixels, 0, Tile.SIZE);
            File meta = metaFile(world, rx, rz);
            if (meta.exists()) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(meta)))) {
                    for (int i = 0; i < 16; i++) t.rendered[i] = in.readLong();
                }
            }
            return t;
        } catch (Exception e) {
            log.warning("Kachel " + png.getName() + " konnte nicht geladen werden: " + e.getMessage());
            return null;
        }
    }

    private void saveTile(Tile t) {
        try {
            BufferedImage img = toImage(t);
            t.dirty = false;
            long[] rendered;
            synchronized (t) { rendered = t.rendered.clone(); }
            File dir = worldDir(t.world);
            dir.mkdirs();
            File png = regionFile(t.world, t.rx, t.rz);
            File tmp = new File(dir, png.getName() + ".tmp");
            ImageIO.write(img, "png", tmp);
            Files.move(tmp.toPath(), png.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(metaFile(t.world, t.rx, t.rz))))) {
                for (long l : rendered) out.writeLong(l);
            }
        } catch (Exception e) {
            t.dirty = true;
            log.warning("Kachel konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    public BufferedImage toImage(Tile t) {
        BufferedImage img = new BufferedImage(Tile.SIZE, Tile.SIZE, BufferedImage.TYPE_INT_ARGB);
        synchronized (t) {
            img.setRGB(0, 0, Tile.SIZE, Tile.SIZE, t.pixels, 0, Tile.SIZE);
        }
        return img;
    }

    /** Speichert alle geänderten Kacheln (im Hintergrund). */
    public void flushAsync() {
        List<Tile> dirty = new ArrayList<>();
        synchronized (cache) {
            for (Tile t : cache.values()) if (t.dirty) dirty.add(t);
        }
        for (Tile t : dirty) io.execute(() -> saveTile(t));
    }

    public void shutdown() {
        flushAsync();
        io.shutdown();
        try {
            if (!io.awaitTermination(60, TimeUnit.SECONDS)) log.warning("Speichern der Kacheln hat zu lange gedauert.");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isChunkRendered(String world, int cx, int cz) {
        Tile t = getIfLoaded(world, cx >> 5, cz >> 5, false);
        if (t == null) {
            if (!regionFile(world, cx >> 5, cz >> 5).exists()) return false;
            t = getOrCreate(world, cx >> 5, cz >> 5);
        }
        return t.isChunkRendered(cx & 31, cz & 31);
    }

    public void writeChunk(String world, int cx, int cz, int[] colors) {
        Tile t = getOrCreate(world, cx >> 5, cz >> 5);
        t.writeChunk(cx & 31, cz & 31, colors);
    }

    // ------------------------------------------------------------------
    // Web-Kacheln
    // ------------------------------------------------------------------

    /**
     * Liefert PNG-Bytes für eine Web-Kachel. zoom 0 = 1 Pixel pro Block (512 Blöcke pro Kachel),
     * zoom -1 = 2 Blöcke pro Pixel usw. Rückgabe null, wenn es dort nichts gibt.
     */
    public byte[] webTile(String world, int zoom, int tx, int tz) throws IOException {
        if (zoom == 0) {
            Tile t = getIfLoaded(world, tx, tz, false);
            if (t != null && t.dirty) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
                ImageIO.write(toImage(t), "png", bos);
                return bos.toByteArray();
            }
            File f = regionFile(world, tx, tz);
            return f.exists() ? Files.readAllBytes(f.toPath()) : null;
        }
        File f = ensureZoomTile(world, zoom, tx, tz);
        return f == null ? null : Files.readAllBytes(f.toPath());
    }

    private File ensureZoomTile(String world, int zoom, int tx, int tz) throws IOException {
        if (zoom == 0) {
            File f = regionFile(world, tx, tz);
            return f.exists() ? f : null;
        }
        File out = new File(worldDir(world), "z" + (-zoom) + File.separator + tx + "_" + tz + ".png");
        String k = world + "|" + zoom + "|" + tx + "|" + tz;
        Long checked = zoomChecked.get(k);
        long now = System.currentTimeMillis();
        if (checked != null && now - checked < 30_000) {
            return out.exists() ? out : null;
        }
        File[] children = new File[4];
        long newest = 0;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            File c = ensureZoomTile(world, zoom + 1, tx * 2 + (i & 1), tz * 2 + (i >> 1));
            children[i] = c;
            if (c != null) { any = true; newest = Math.max(newest, c.lastModified()); }
        }
        zoomChecked.put(k, now);
        if (!any) {
            if (out.exists()) out.delete();
            return null;
        }
        if (out.exists() && out.lastModified() >= newest) return out;

        synchronized (zoomLock) {
            BufferedImage img = new BufferedImage(Tile.SIZE, Tile.SIZE, BufferedImage.TYPE_INT_ARGB);
            int half = Tile.SIZE / 2;
            int[] src = new int[Tile.SIZE * Tile.SIZE];
            int[] dst = new int[half * half];
            for (int i = 0; i < 4; i++) {
                if (children[i] == null) continue;
                BufferedImage ci = ImageIO.read(children[i]);
                if (ci == null) continue;
                ci.getRGB(0, 0, Tile.SIZE, Tile.SIZE, src, 0, Tile.SIZE);
                for (int y = 0; y < half; y++) {
                    for (int x = 0; x < half; x++) {
                        dst[y * half + x] = average(src[(2 * y) * Tile.SIZE + 2 * x], src[(2 * y) * Tile.SIZE + 2 * x + 1],
                                src[(2 * y + 1) * Tile.SIZE + 2 * x], src[(2 * y + 1) * Tile.SIZE + 2 * x + 1]);
                    }
                }
                img.setRGB((i & 1) * half, (i >> 1) * half, half, half, dst, 0, half);
            }
            out.getParentFile().mkdirs();
            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
            ImageIO.write(img, "png", tmp);
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    private static int average(int a, int b, int c, int d) {
        int n = 0, r = 0, g = 0, bl = 0;
        for (int p : new int[]{a, b, c, d}) {
            if ((p >>> 24) == 0) continue;
            n++;
            r += (p >> 16) & 0xFF;
            g += (p >> 8) & 0xFF;
            bl += p & 0xFF;
        }
        if (n == 0) return 0;
        return 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (bl / n);
    }

    /** Löscht alle Kacheln einer Welt (für einen kompletten Neu-Render). */
    public void clearWorld(String world) {
        synchronized (cache) {
            cache.entrySet().removeIf(e -> e.getValue().world.equals(world));
        }
        zoomChecked.clear();
        File dir = worldDir(world);
        io.execute(() -> deleteRecursive(dir));
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }
}
