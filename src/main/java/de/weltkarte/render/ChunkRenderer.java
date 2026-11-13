package de.weltkarte.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Berechnet aus einem Chunk-Snapshot eine 16x16-Farbkachel (1 Pixel = 1 Block). */
public final class ChunkRenderer {

    private static final Map<BlockData, Integer> COLOR_CACHE = new ConcurrentHashMap<>();
    private static final int WATER = 0x3F76E4;

    private ChunkRenderer() {}

    static int mapColor(BlockData bd) {
        Integer cached = COLOR_CACHE.get(bd);
        if (cached != null) return cached;
        int c;
        try {
            c = bd.getMapColor().asRGB();
        } catch (Throwable t) {
            c = fallback(bd.getMaterial());
        }
        if (COLOR_CACHE.size() < 50_000) COLOR_CACHE.put(bd, c);
        return c;
    }

    private static int fallback(Material m) {
        if (m.isAir()) return 0;
        String n = m.name();
        if (n.contains("LEAVES")) return 0x007C00;
        if (n.contains("GRASS")) return 0x7FB238;
        if (n.contains("SAND")) return 0xF7E9A3;
        if (n.contains("WATER")) return WATER;
        return 0x707070;
    }

    private static boolean isWater(BlockData bd) {
        Material m = bd.getMaterial();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return bd instanceof Waterlogged w && w.isWaterlogged() && mapColor(bd) == 0;
    }

    public static int[] render(ChunkSnapshot snap, World.Environment env, int minY, int maxY) {
        int[] out = new int[256];
        int[] heights = new int[256];
        int[] base = new int[256];
        int[] depth = new int[256];

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y;
                if (env == World.Environment.NETHER) {
                    y = Math.min(maxY - 1, 126);
                    // unter die Bedrock-Decke gehen: bis zur ersten Luft absteigen
                    while (y > minY && mapColor(snap.getBlockData(x, y, z)) != 0) y--;
                } else {
                    y = Math.min(snap.getHighestBlockYAt(x, z), maxY - 1);
                }
                int color = 0;
                int waterDepth = 0;
                while (y >= minY) {
                    BlockData bd = snap.getBlockData(x, y, z);
                    if (isWater(bd)) {
                        waterDepth++;
                        y--;
                        if (waterDepth > 24) { color = 0; break; }
                        continue;
                    }
                    int c = mapColor(bd);
                    if (c != 0) { color = c; break; }
                    y--;
                }
                int i = z * 16 + x;
                heights[i] = y;
                base[i] = color;
                depth[i] = waterDepth;
            }
        }

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int color = base[i];
                int d = depth[i];
                int argb;
                if (d > 0) {
                    // Wasser: Grund leicht durchscheinen lassen, mit Tiefe dunkler
                    double floorMix = color == 0 ? 0 : Math.max(0, 0.45 - d * 0.06);
                    int mixed = color == 0 ? WATER : mix(WATER, color, floorMix);
                    double shade = Math.max(0.55, 1.0 - d * 0.03);
                    if (((x + z) & 1) == 0 && d > 2) shade -= 0.03;
                    argb = 0xFF000000 | scale(mixed, shade);
                } else if (color == 0) {
                    argb = 0xFF000000 | 0x101010; // leer / void
                } else {
                    int h = heights[i];
                    int hn = z > 0 ? heights[i - 16] : (z < 15 ? 2 * h - heights[i + 16] : h);
                    double shade = h > hn ? 1.0 : (h == hn ? 0.88 : 0.74);
                    int diff = Math.abs(h - hn);
                    if (diff > 3 && h < hn) shade = 0.66;
                    argb = 0xFF000000 | scale(color, shade);
                }
                out[i] = argb;
            }
        }
        return out;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int scale(int rgb, double f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }
}
