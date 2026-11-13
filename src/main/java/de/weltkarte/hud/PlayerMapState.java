package de.weltkarte.hud;

import de.weltkarte.model.Marker;

/** Einstellungen der Minimap pro Spieler. */
public class PlayerMapState {
    /** Blöcke pro Pixel. 0.5 = jeder Block 2x2 Pixel. */
    public static final double[] ZOOMS = {0.25, 0.5, 1, 2, 4, 8, 16};
    public static final int DEFAULT_ZOOM = 2; // 1 Block = 1 Pixel

    public int zoom = DEFAULT_ZOOM;
    public Marker navTarget;

    // Render-Cache
    public int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE, lastZoom = -1;
    public String lastWorld = "";
    public long lastFullRedraw = 0;
    public int lastMarkerVersion = -1;

    public double blocksPerPixel() { return ZOOMS[zoom]; }

    public static String describe(int zoom) {
        double bpp = ZOOMS[zoom];
        if (bpp < 1) {
            int px = (int) Math.round(1 / bpp);
            return "1 Block = " + px + "x" + px + " Pixel";
        }
        if (bpp == 1) return "1 Block = 1 Pixel";
        return "1 Pixel = " + (int) bpp + " Blöcke";
    }
}
