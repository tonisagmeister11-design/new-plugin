package de.weltkarte;

import de.weltkarte.gui.AdminMenu;
import de.weltkarte.gui.ChatPrompt;
import de.weltkarte.gui.MenuListener;
import de.weltkarte.gui.PlacesMenu;
import de.weltkarte.hud.HudMapRenderer;
import de.weltkarte.hud.MapManager;
import de.weltkarte.model.MarkerStore;
import de.weltkarte.render.RenderManager;
import de.weltkarte.render.TileStore;
import de.weltkarte.web.WebServer;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

public final class WeltKartePlugin extends JavaPlugin {

    private MarkerStore markers;
    private TileStore tiles;
    private RenderManager renderer;
    private MapManager maps;
    private WaystoneManager waystones;
    private ChatPrompt prompt;
    private PlacesMenu placesMenu;
    private AdminMenu adminMenu;
    private WebServer web;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        markers = new MarkerStore(new File(getDataFolder(), "markers.json"), getLogger());
        tiles = new TileStore(new File(getDataFolder(), "tiles"), getLogger(), getConfig().getInt("render.tile-cache-size", 48));
        renderer = new RenderManager(this, tiles);
        maps = new MapManager(this);
        waystones = new WaystoneManager(this);
        prompt = new ChatPrompt(this);
        placesMenu = new PlacesMenu(this);
        adminMenu = new AdminMenu(this);

        maps.setup(new HudMapRenderer(this, tiles));

        var pm = getServer().getPluginManager();
        pm.registerEvents(renderer, this);
        pm.registerEvents(maps, this);
        pm.registerEvents(waystones, this);
        pm.registerEvents(prompt, this);
        pm.registerEvents(new MenuListener(), this);

        renderer.start();
        Bukkit.getScheduler().runTaskTimer(this, maps::tickNavigation, 20L, 10L);

        if (getConfig().getBoolean("web.enabled", true)) {
            web = new WebServer(this);
            try {
                web.start();
            } catch (Exception e) {
                getLogger().severe("Web-Karte konnte nicht gestartet werden (Port belegt?): " + e.getMessage());
                web = null;
            }
        }

        KarteCommand cmd = new KarteCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("karte", "Weltkarte: Minimap, Orte, Wegsteine und Admin-Steuerung",
                        List.of("map", "weltkarte"), cmd));

        // Spieler, die schon online sind (z. B. nach /reload), bekommen die Chunks um sich gerendert
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!renderer.isWorldEnabled(p.getWorld())) continue;
                int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
                for (int dx = -6; dx <= 6; dx++)
                    for (int dz = -6; dz <= 6; dz++)
                        if (p.getWorld().isChunkLoaded(cx + dx, cz + dz)) renderer.enqueue(p.getWorld(), cx + dx, cz + dz, false);
            }
        }, 40L);
    }

    @Override
    public void onDisable() {
        if (web != null) web.stop();
        if (renderer != null) renderer.stop();
        if (tiles != null) tiles.shutdown();
        if (markers != null) markers.save();
    }

    public MarkerStore markers() { return markers; }
    public TileStore tiles() { return tiles; }
    public RenderManager renderer() { return renderer; }
    public MapManager maps() { return maps; }
    public WaystoneManager waystones() { return waystones; }
    public ChatPrompt prompt() { return prompt; }
    public PlacesMenu placesMenu() { return placesMenu; }
    public AdminMenu adminMenu() { return adminMenu; }
    public WebServer web() { return web; }

    public String publicUrl() {
        String url = getConfig().getString("web.public-url", "");
        if (url == null || url.isBlank()) {
            String ip = Bukkit.getIp();
            if (ip == null || ip.isBlank() || ip.equals("0.0.0.0")) ip = "localhost";
            url = "http://" + ip + ":" + getConfig().getInt("web.port", 8100);
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** "Karte updaten": Entwurf für alle Spieler veröffentlichen. */
    public void publish(CommandSender by) {
        MarkerStore.PublishResult r = markers.publish();
        waystones.syncBlocks(r);
        Component msg = Component.text("🗺 Die Weltkarte wurde aktualisiert! ", NamedTextColor.GOLD)
                .append(Component.text(r.total() + " Markierungen – /karte orte", NamedTextColor.YELLOW));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
            p.playSound(p.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.7f, 1.0f);
        }
        getLogger().info("Karte veröffentlicht von " + by.getName() + " (Version " + r.version() + ", " + r.total() + " Markierungen)");
        if (!(by instanceof Player)) by.sendMessage("Karte veröffentlicht (" + r.total() + " Markierungen).");
    }

    public void sendWebLink(Player p) {
        if (web == null) {
            p.sendMessage(Component.text("Die Web-Karte ist deaktiviert (config.yml → web.enabled).", NamedTextColor.RED));
            return;
        }
        String url = publicUrl() + "/";
        p.sendMessage(Component.text("🌍 Web-Karte: ", NamedTextColor.GREEN)
                .append(Component.text(url, NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Im Browser öffnen")))));
    }

    public void sendEditorLink(Player p) {
        if (web == null) {
            p.sendMessage(Component.text("Die Web-Karte ist deaktiviert (config.yml → web.enabled).", NamedTextColor.RED));
            return;
        }
        String token = web.createEditorToken(p);
        String url = publicUrl() + "/#edit=" + token;
        p.sendMessage(Component.text("🛠 Karten-Editor (nur für dich, " + getConfig().getLong("web.editor-session-hours", 2) + " h gültig): ", NamedTextColor.GREEN)
                .append(Component.text("[Im Browser öffnen]", NamedTextColor.AQUA).decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Link nicht weitergeben – er erlaubt das Bearbeiten der Karte!", NamedTextColor.RED)))));
    }
}
