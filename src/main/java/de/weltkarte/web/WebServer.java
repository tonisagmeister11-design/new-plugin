package de.weltkarte.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.weltkarte.WeltKartePlugin;
import de.weltkarte.model.Marker;
import de.weltkarte.model.MarkerStore;
import de.weltkarte.model.MarkerType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Eingebauter Webserver: liefert die hochauflösende Web-Karte (1 Pixel pro Block, bis 32x vergrößerbar),
 * die Markierungen, Spielerpositionen und den Admin-Editor (Zugang nur per Einmal-Link aus /karte control).
 */
public class WebServer {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    private final WeltKartePlugin plugin;
    private HttpServer server;
    private ExecutorService pool;
    private byte[] indexHtml;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile String playersJson = "[]";
    private volatile String configJson = "{}";
    private volatile Set<String> worldNames = Set.of();
    private BukkitTask snapshotTask;

    private record Session(UUID player, String name, long expires) {}

    public WebServer(WeltKartePlugin plugin) { this.plugin = plugin; }

    public void start() throws IOException {
        try (InputStream in = plugin.getResource("web/index.html")) {
            indexHtml = in == null ? "<h1>index.html fehlt</h1>".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
        }
        String bind = plugin.getConfig().getString("web.bind", "0.0.0.0");
        int port = plugin.getConfig().getInt("web.port", 8100);
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        pool = Executors.newFixedThreadPool(Math.max(2, plugin.getConfig().getInt("web.threads", 4)), r -> {
            Thread t = new Thread(r, "WeltKarte-Web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        snapshotTask = Bukkit.getScheduler().runTaskTimer(plugin, this::snapshot, 1L, 20L);
        plugin.getLogger().info("Web-Karte läuft auf Port " + port + " → " + plugin.publicUrl());
    }

    public void stop() {
        if (snapshotTask != null) snapshotTask.cancel();
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    /** Erstellt einen Einmal-Zugang für den Browser-Editor (gültig laut config, Standard 2 h). */
    public String createEditorToken(Player p) {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        long hours = plugin.getConfig().getLong("web.editor-session-hours", 2);
        sessions.put(token, new Session(p.getUniqueId(), p.getName(), System.currentTimeMillis() + hours * 3_600_000L));
        sessions.entrySet().removeIf(e -> e.getValue().expires < System.currentTimeMillis());
        return token;
    }

    /** Läuft auf dem Hauptthread: Spielerpositionen & Weltliste für die Web-Karte einsammeln. */
    private void snapshot() {
        Set<String> names = new HashSet<>();
        JsonArray worlds = new JsonArray();
        for (World w : Bukkit.getWorlds()) {
            if (!plugin.renderer().isWorldEnabled(w)) continue;
            names.add(w.getName());
            JsonObject o = new JsonObject();
            o.addProperty("name", w.getName());
            o.addProperty("environment", w.getEnvironment().name());
            Location s = w.getSpawnLocation();
            o.addProperty("spawnX", s.getBlockX());
            o.addProperty("spawnZ", s.getBlockZ());
            worlds.add(o);
        }
        worldNames = names;
        JsonObject cfg = new JsonObject();
        cfg.addProperty("title", plugin.getConfig().getString("web.title", "Weltkarte"));
        cfg.add("worlds", worlds);
        cfg.addProperty("showPlayers", plugin.getConfig().getBoolean("web.show-players", true));
        cfg.addProperty("version", plugin.markers().version());
        configJson = cfg.toString();

        JsonArray players = new JsonArray();
        if (plugin.getConfig().getBoolean("web.show-players", true)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isInvisible() || p.hasPermission("weltkarte.hidden")) continue;
                if (!names.contains(p.getWorld().getName())) continue;
                Location l = p.getLocation();
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getName());
                o.addProperty("world", l.getWorld().getName());
                o.addProperty("x", Math.round(l.getX() * 10) / 10.0);
                o.addProperty("z", Math.round(l.getZ() * 10) / 10.0);
                o.addProperty("yaw", Math.round(l.getYaw()));
                players.add(o);
            }
        }
        playersJson = players.toString();
    }

    // ------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", indexHtml, "no-cache");
            } else if (path.startsWith("/tiles/")) {
                tile(ex, path);
            } else if (path.equals("/api/config")) {
                json(ex, 200, configJson);
            } else if (path.equals("/api/markers")) {
                json(ex, 200, MarkerStore.GSON.toJson(plugin.markers().published()));
            } else if (path.equals("/api/players")) {
                json(ex, 200, playersJson);
            } else if (path.startsWith("/api/editor/")) {
                editor(ex, path.substring("/api/editor/".length()), method);
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "Nicht gefunden".getBytes(StandardCharsets.UTF_8), "no-cache");
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Web-Fehler: " + e);
            try {
                send(ex, 500, "text/plain; charset=utf-8", "Fehler".getBytes(StandardCharsets.UTF_8), "no-cache");
            } catch (Exception ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private void tile(HttpExchange ex, String path) throws IOException {
        // /tiles/<welt>/<zoom>/<x>/<y>.png
        String[] parts = path.split("/");
        if (parts.length != 6 || !parts[5].endsWith(".png")) { notFound(ex); return; }
        String world = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
        if (!worldNames.contains(world)) { notFound(ex); return; }
        int zoom, x, y;
        try {
            zoom = Integer.parseInt(parts[3]);
            x = Integer.parseInt(parts[4]);
            y = Integer.parseInt(parts[5].substring(0, parts[5].length() - 4));
        } catch (NumberFormatException e) { notFound(ex); return; }
        if (zoom > 0 || zoom < -8 || Math.abs(x) > 1_000_000 || Math.abs(y) > 1_000_000) { notFound(ex); return; }
        byte[] png = plugin.tiles().webTile(world, zoom, x, y);
        if (png == null) {
            ex.getResponseHeaders().set("Cache-Control", "max-age=30");
            ex.sendResponseHeaders(204, -1);
            return;
        }
        send(ex, 200, "image/png", png, zoom == 0 ? "max-age=15" : "max-age=60");
    }

    private void editor(HttpExchange ex, String action, String method) throws IOException {
        String token = ex.getRequestHeaders().getFirst("X-Token");
        Session s = token == null ? null : sessions.get(token);
        if (s == null || s.expires < System.currentTimeMillis()) {
            json(ex, 401, "{\"error\":\"Zugang abgelaufen. Bitte im Spiel erneut /karte control → Karten-Editor öffnen.\"}");
            return;
        }
        switch (action) {
            case "session" -> {
                JsonObject o = new JsonObject();
                o.addProperty("name", s.name);
                o.addProperty("expires", s.expires);
                json(ex, 200, o.toString());
            }
            case "draft" -> {
                if (method.equals("GET")) {
                    json(ex, 200, MarkerStore.GSON.toJson(plugin.markers().draft()));
                } else if (method.equals("POST")) {
                    byte[] body = ex.getRequestBody().readNBytes(4 * 1024 * 1024);
                    List<Marker> list = MarkerStore.parseList(new String(body, StandardCharsets.UTF_8));
                    List<Marker> clean = sanitize(list);
                    plugin.markers().setDraft(clean);
                    JsonObject o = new JsonObject();
                    o.addProperty("ok", true);
                    o.addProperty("count", clean.size());
                    o.addProperty("pending", plugin.markers().pendingChanges());
                    json(ex, 200, o.toString());
                } else json(ex, 405, "{}");
            }
            case "pending" -> json(ex, 200, "{\"pending\":" + plugin.markers().pendingChanges() + "}");
            case "publish" -> {
                if (!method.equals("POST")) { json(ex, 405, "{}"); return; }
                UUID who = s.player;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(who);
                    plugin.publish(p != null ? p : Bukkit.getConsoleSender());
                });
                json(ex, 200, "{\"ok\":true}");
            }
            default -> json(ex, 404, "{}");
        }
    }

    private List<Marker> sanitize(List<Marker> in) {
        List<Marker> out = new ArrayList<>();
        if (in == null) return out;
        Set<String> ids = new HashSet<>();
        for (Marker m : in) {
            if (m == null || out.size() >= 10_000) continue;
            if (m.type == null) m.type = MarkerType.ORT;
            if (m.world == null || !worldNames.contains(m.world)) continue;
            if (m.id == null || !ID.matcher(m.id).matches() || !ids.add(m.id)) {
                m.id = UUID.randomUUID().toString().substring(0, 8);
                ids.add(m.id);
            }
            m.name = clip(m.name == null || m.name.isBlank() ? "Unbenannt" : m.name, 64);
            m.description = clip(m.description, 500);
            m.owner = clip(m.owner, 32);
            if (m.color == null || !COLOR.matcher(m.color).matches()) m.color = "#e53935";
            m.x = clamp(m.x);
            m.z = clamp(m.z);
            if (m.y != null && (m.y.isNaN() || m.y < -2048 || m.y > 4096)) m.y = null;
            if (m.y != null) m.y = Math.floor(m.y);
            if (m.x2 != null) m.x2 = (int) clamp(m.x2);
            if (m.z2 != null) m.z2 = (int) clamp(m.z2);
            if ((m.x2 == null) != (m.z2 == null)) { m.x2 = null; m.z2 = null; }
            if (m.type == MarkerType.WEGSTEIN) { m.x = Math.floor(m.x); m.z = Math.floor(m.z); m.x2 = null; m.z2 = null; }
            out.add(m);
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}&&[^\n]]", "").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(-30_000_000, Math.min(30_000_000, v));
    }

    private void notFound(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(404, -1);
    }

    private void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), "no-cache");
    }

    private void send(HttpExchange ex, int code, String type, byte[] data, String cache) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", cache);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        if (data.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }
}
