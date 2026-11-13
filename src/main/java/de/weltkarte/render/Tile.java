package de.weltkarte.render;

/** Eine Karten-Kachel = eine Minecraft-Region (512 x 512 Blöcke, 1 Pixel pro Block). */
public final class Tile {
    public static final int SIZE = 512;

    public final String world;
    public final int rx, rz;
    /** ARGB pro Block; 0 = noch nicht gerendert. */
    public final int[] pixels = new int[SIZE * SIZE];
    /** Bitmaske der 32x32 Chunks, die schon gerendert wurden. */
    public final long[] rendered = new long[16];
    public volatile boolean dirty;
    public volatile long lastAccess = System.currentTimeMillis();
    public volatile long lastChange = 0;

    public Tile(String world, int rx, int rz) {
        this.world = world;
        this.rx = rx;
        this.rz = rz;
    }

    public int get(int localX, int localZ) {
        return pixels[localZ * SIZE + localX];
    }

    public boolean isChunkRendered(int localCx, int localCz) {
        int idx = localCz * 32 + localCx;
        return (rendered[idx >> 6] & (1L << (idx & 63))) != 0;
    }

    public synchronized void writeChunk(int localCx, int localCz, int[] colors) {
        int ox = localCx * 16, oz = localCz * 16;
        for (int z = 0; z < 16; z++) {
            System.arraycopy(colors, z * 16, pixels, (oz + z) * SIZE + ox, 16);
        }
        int idx = localCz * 32 + localCx;
        rendered[idx >> 6] |= (1L << (idx & 63));
        dirty = true;
        lastChange = System.currentTimeMillis();
    }
}
