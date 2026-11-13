package de.weltkarte.hud;

import de.weltkarte.WeltKartePlugin;
import de.weltkarte.model.Marker;
import de.weltkarte.model.MarkerType;
import de.weltkarte.render.Tile;
import de.weltkarte.render.TileStore;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.map.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zeichnet die hochauflösende Minimap (128x128 Pixel) für jeden Spieler einzeln.
 * Standard: 1 Pixel = 1 Block, also ist wirklich jeder Block zu sehen.
 */
@SuppressWarnings({"deprecation", "removal"})
public class HudMapRenderer extends MapRenderer {

    private static final Map<Integer, Byte> PALETTE_CACHE = new ConcurrentHashMap<>();
    private static final int SIZE = 128;

    private final WeltKartePlugin plugin;
    private final TileStore tiles;

    public HudMapRenderer(WeltKartePlugin plugin, TileStore tiles) {
        super(true);
        this.plugin = plugin;
        this.tiles = tiles;
    }

    static byte paletteByte(int rgb) {
        rgb &= 0xFFFFFF;
        // Farben leicht quantisieren, damit der Cache klein bleibt
        int key = rgb & 0xFCFCFC;
        Byte b = PALETTE_CACHE.get(key);
        if (b != null) return b;
        byte m = MapPalette.matchColor((key >> 16) & 0xFF, (key >> 8) & 0xFF, key & 0xFF);
        PALETTE_CACHE.put(key, m);
        return m;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        PlayerMapState st = plugin.maps().state(player);
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        int cx = loc.getBlockX(), cz = loc.getBlockZ();
        long now = System.currentTimeMillis();
        int markerVersion = plugin.markers().version();

        boolean moved = cx != st.lastCx || cz != st.lastCz || st.zoom != st.lastZoom || !world.equals(st.lastWorld);
        boolean periodic = now - st.lastFullRedraw > 2000;
        if (moved || periodic || markerVersion != st.lastMarkerVersion) {
            drawTerrain(canvas, world, cx, cz, st.blocksPerPixel(), player);
            drawAreas(canvas, world, cx, cz, st.blocksPerPixel());
            st.lastCx = cx; st.lastCz = cz; st.lastZoom = st.zoom; st.lastWorld = world;
            st.lastFullRedraw = now; st.lastMarkerVersion = markerVersion;
        }
        if (player.getTicksLived() % 2 == 0 || moved) {
            canvas.setCursors(buildCursors(player, st, loc));
        }
    }

    private void drawTerrain(MapCanvas canvas, String world, int cx, int cz, double bpp, Player player) {
        boolean enabled = plugin.renderer().isWorldEnabled(player.getWorld());
        byte unknown = paletteByte(0x2A2A2A);
        byte unknownAlt = paletteByte(0x333333);
        Tile cached = null;
        int cachedRx = Integer.MIN_VALUE, cachedRz = Integer.MIN_VALUE;
        for (int py = 0; py < SIZE; py++) {
            int bz = (int) Math.floor(cz + (py - SIZE / 2) * bpp);
            int rz = bz >> 9;
            for (int px = 0; px < SIZE; px++) {
                int bx = (int) Math.floor(cx + (px - SIZE / 2) * bpp);
                int rx = bx >> 9;
                if (rx != cachedRx || rz != cachedRz) {
                    cached = enabled ? tiles.getIfLoaded(world, rx, rz, true) : null;
                    cachedRx = rx; cachedRz = rz;
                }
                int argb = cached == null ? 0 : cached.get(bx & 511, bz & 511);
                byte b;
                if ((argb >>> 24) == 0) {
                    b = (((bx >> 3) + (bz >> 3)) & 1) == 0 ? unknown : unknownAlt;
                } else {
                    b = paletteByte(argb);
                }
                canvas.setPixel(px, py, b);
            }
        }
    }

    private void drawAreas(MapCanvas canvas, String world, int cx, int cz, double bpp) {
        for (Marker m : plugin.markers().published()) {
            if (!m.isArea() || !world.equals(m.world)) continue;
            int x1 = (int) Math.round((Math.min(m.x, m.x2) - cx) / bpp) + SIZE / 2;
            int x2 = (int) Math.round((Math.max(m.x, m.x2) + 1 - cx) / bpp) + SIZE / 2;
            int z1 = (int) Math.round((Math.min(m.z, m.z2) - cz) / bpp) + SIZE / 2;
            int z2 = (int) Math.round((Math.max(m.z, m.z2) + 1 - cz) / bpp) + SIZE / 2;
            if (x2 < 0 || z2 < 0 || x1 >= SIZE || z1 >= SIZE) continue;
            byte col = paletteByte(m.rgb());
            for (int x = Math.max(0, x1); x <= Math.min(SIZE - 1, x2); x++) {
                if (z1 >= 0 && z1 < SIZE) canvas.setPixel(x, z1, col);
                if (z2 >= 0 && z2 < SIZE) canvas.setPixel(x, z2, col);
            }
            for (int z = Math.max(0, z1); z <= Math.min(SIZE - 1, z2); z++) {
                if (x1 >= 0 && x1 < SIZE) canvas.setPixel(x1, z, col);
                if (x2 >= 0 && x2 < SIZE) canvas.setPixel(x2, z, col);
            }
        }
    }

    private MapCursorCollection buildCursors(Player player, PlayerMapState st, Location loc) {
        MapCursorCollection cursors = new MapCursorCollection();
        double bpp = st.blocksPerPixel();
        int cx = loc.getBlockX(), cz = loc.getBlockZ();
        String world = loc.getWorld().getName();
        double halfRange = SIZE / 2.0 * bpp;
        boolean captions = bpp <= plugin.getConfig().getDouble("minimap.captions-up-to-blocks-per-pixel", 4);

        // Markierungen (nächste zuerst)
        List<Marker> visible = new ArrayList<>();
        for (Marker m : plugin.markers().published()) {
            if (!world.equals(m.world)) continue;
            if (Math.abs(m.centerX() - cx) > halfRange || Math.abs(m.centerZ() - cz) > halfRange) continue;
            visible.add(m);
        }
        visible.sort(Comparator.comparingDouble(m -> m.distanceSq2D(cx, cz)));
        int max = plugin.getConfig().getInt("minimap.max-markers", 25);
        for (int i = 0; i < Math.min(max, visible.size()); i++) {
            Marker m = visible.get(i);
            byte x = toCursor(m.centerX() + 0.5 - cx, bpp);
            byte y = toCursor(m.centerZ() + 0.5 - cz, bpp);
            MapCursor.Type type = cursorType(m);
            cursors.addCursor(new MapCursor(x, y, (byte) 8, type, true, captions ? Component.text(m.name) : null));
        }

        // Andere Spieler
        if (plugin.getConfig().getBoolean("minimap.show-other-players", true)) {
            for (Player other : loc.getWorld().getPlayers()) {
                if (other == player || !player.canSee(other) || other.isInvisible()) continue;
                Location ol = other.getLocation();
                if (Math.abs(ol.getX() - cx) > halfRange || Math.abs(ol.getZ() - cz) > halfRange) continue;
                cursors.addCursor(new MapCursor(toCursor(ol.getX() - cx, bpp), toCursor(ol.getZ() - cz, bpp),
                        direction(ol.getYaw()), MapCursor.Type.BLUE_MARKER, true, captions ? Component.text(other.getName()) : null));
            }
        }

        // Navigationsziel
        Marker target = st.navTarget;
        if (target != null && world.equals(target.world)) {
            double dx = target.centerX() + 0.5 - cx, dz = target.centerZ() + 0.5 - cz;
            boolean inside = Math.abs(dx) <= halfRange && Math.abs(dz) <= halfRange;
            if (inside) {
                cursors.addCursor(new MapCursor(toCursor(dx, bpp), toCursor(dz, bpp), (byte) 0, MapCursor.Type.TARGET_X, true,
                        Component.text("Ziel")));
            } else {
                double f = halfRange / Math.max(Math.abs(dx), Math.abs(dz));
                cursors.addCursor(new MapCursor(toCursor(dx * f, bpp), toCursor(dz * f, bpp), (byte) 0, MapCursor.Type.RED_MARKER, true, (Component) null));
            }
        }

        // Eigener Spieler (immer in der Mitte, zuletzt = oben)
        cursors.addCursor(new MapCursor(toCursor(loc.getX() - cx - 0.5, bpp), toCursor(loc.getZ() - cz - 0.5, bpp),
                direction(loc.getYaw()), MapCursor.Type.PLAYER, true, (Component) null));
        return cursors;
    }

    private static MapCursor.Type cursorType(Marker m) {
        return switch (m.type) {
            case WEGSTEIN -> MapCursor.Type.TARGET_POINT;
            case STADT -> MapCursor.Type.MANSION;
            case GEBAEUDE -> bannerFor(m.rgb());
            case ORT -> MapCursor.Type.RED_X;
        };
    }

    /** Wählt die Banner-Farbe, die der Markierungsfarbe am nächsten kommt. */
    private static MapCursor.Type bannerFor(int rgb) {
        Object[][] banners = {
                {0xF9FFFE, MapCursor.Type.BANNER_WHITE}, {0xF9801D, MapCursor.Type.BANNER_ORANGE},
                {0xC74EBD, MapCursor.Type.BANNER_MAGENTA}, {0x3AB3DA, MapCursor.Type.BANNER_LIGHT_BLUE},
                {0xFED83D, MapCursor.Type.BANNER_YELLOW}, {0x80C71F, MapCursor.Type.BANNER_LIME},
                {0xF38BAA, MapCursor.Type.BANNER_PINK}, {0x474F52, MapCursor.Type.BANNER_GRAY},
                {0x9D9D97, MapCursor.Type.BANNER_LIGHT_GRAY}, {0x169C9C, MapCursor.Type.BANNER_CYAN},
                {0x8932B8, MapCursor.Type.BANNER_PURPLE}, {0x3C44AA, MapCursor.Type.BANNER_BLUE},
                {0x835432, MapCursor.Type.BANNER_BROWN}, {0x5E7C16, MapCursor.Type.BANNER_GREEN},
                {0xB02E26, MapCursor.Type.BANNER_RED}, {0x1D1D21, MapCursor.Type.BANNER_BLACK}};
        MapCursor.Type best = MapCursor.Type.BANNER_RED;
        long bestD = Long.MAX_VALUE;
        for (Object[] b : banners) {
            int c = (int) b[0];
            long dr = ((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
            long dg = ((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
            long db = (c & 0xFF) - (rgb & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) { bestD = d; best = (MapCursor.Type) b[1]; }
        }
        return best;
    }

    private static byte toCursor(double blocksOffset, double bpp) {
        int v = (int) Math.round(blocksOffset / bpp * 2.0);
        return (byte) Math.max(-127, Math.min(127, v));
    }

    private static byte direction(float yaw) {
        double y = yaw;
        return (byte) (((int) ((y < 0 ? y - 8.0 : y + 8.0) * 16.0 / 360.0)) & 15);
    }
}
