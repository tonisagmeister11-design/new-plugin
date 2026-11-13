package de.weltkarte.hud;

import de.weltkarte.WeltKartePlugin;
import de.weltkarte.model.Marker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verwaltet die Minimap-Gegenstände. Die Karte liegt in der Zweithand und wird damit
 * LINKS auf dem Bildschirm angezeigt. Mit der Taste "Gegenstände in Hände tauschen"
 * (Standard F, in den Minecraft-Steuerungseinstellungen frei änderbar) wird gezoomt.
 */
public class MapManager implements Listener {

    private final WeltKartePlugin plugin;
    private final NamespacedKey itemKey;
    private final Map<UUID, PlayerMapState> states = new ConcurrentHashMap<>();
    private MapView view;

    public MapManager(WeltKartePlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "weltkarte");
    }

    @SuppressWarnings("deprecation")
    public void setup(HudMapRenderer renderer) {
        int id = plugin.getConfig().getInt("internal.map-id", -1);
        if (id >= 0) view = Bukkit.getMap(id);
        if (view == null) {
            view = Bukkit.createMap(Bukkit.getWorlds().getFirst());
            plugin.getConfig().set("internal.map-id", view.getId());
            plugin.saveConfig();
        }
        for (MapRenderer r : List.copyOf(view.getRenderers())) view.removeRenderer(r);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setScale(MapView.Scale.CLOSEST);
        view.addRenderer(renderer);
    }

    public PlayerMapState state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new PlayerMapState());
    }

    public boolean isMapItem(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        item.editMeta(MapMeta.class, meta -> {
            meta.setMapView(view);
            meta.displayName(Component.text("Weltkarte", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("In die Zweithand legen → Karte links", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("[F] = hineinzoomen, [Shift+F] = herauszoomen", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Taste änderbar: Steuerung → \"Gegenstände tauschen\"", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    /** Legt die Karte in die Zweithand (links). Ein dort liegender Gegenstand wandert ins Inventar. */
    public void giveToOffhand(Player p) {
        PlayerInventory inv = p.getInventory();
        // vorhandene Kopien entfernen
        for (int i = 0; i < inv.getSize(); i++) {
            if (isMapItem(inv.getItem(i))) inv.setItem(i, null);
        }
        ItemStack off = inv.getItemInOffHand();
        if (isMapItem(off)) off = null;
        inv.setItemInOffHand(createItem());
        if (off != null && off.getType() != Material.AIR) {
            Map<Integer, ItemStack> rest = inv.addItem(off);
            rest.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
        }
        p.sendMessage(Component.text("🗺 Die Weltkarte ist jetzt links (Zweithand). ", NamedTextColor.GREEN)
                .append(Component.text("[F] zoomt hinein, [Shift+F] heraus.", NamedTextColor.GRAY)));
    }

    public void removeFromPlayer(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isMapItem(inv.getItem(i))) inv.setItem(i, null);
        if (isMapItem(inv.getItemInOffHand())) inv.setItemInOffHand(null);
    }

    public void zoom(Player p, int delta) {
        PlayerMapState st = state(p);
        st.zoom = Math.max(0, Math.min(PlayerMapState.ZOOMS.length - 1, st.zoom + delta));
        p.sendActionBar(Component.text("🗺 Zoom: " + PlayerMapState.describe(st.zoom), NamedTextColor.GOLD));
        p.playSound(p.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.4f, 1.6f);
    }

    public void setZoom(Player p, int index) {
        PlayerMapState st = state(p);
        st.zoom = Math.max(0, Math.min(PlayerMapState.ZOOMS.length - 1, index));
        p.sendActionBar(Component.text("🗺 Zoom: " + PlayerMapState.describe(st.zoom), NamedTextColor.GOLD));
    }

    // ------------------------------------------------------------------
    // Tastenbelegung: "Gegenstände tauschen" (Standard F)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        // Nach dem Tausch wäre main = offhandItem und umgekehrt
        if (!isMapItem(e.getMainHandItem()) && !isMapItem(e.getOffHandItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        zoom(p, p.isSneaking() ? 1 : -1); // kleinerer Index = näher dran
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("minimap.give-on-join", true) || !p.hasPermission("weltkarte.use")) return;
        PlayerInventory inv = p.getInventory();
        boolean has = isMapItem(inv.getItemInOffHand());
        for (ItemStack it : inv.getContents()) if (isMapItem(it)) has = true;
        if (has) return;
        if (inv.getItemInOffHand().getType() == Material.AIR) {
            inv.setItemInOffHand(createItem());
        } else {
            inv.addItem(createItem());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        // Karte nicht fallen lassen – nach dem Respawn wieder in die Zweithand
        if (e.getDrops().removeIf(this::isMapItem)) {
            UUID id = e.getEntity().getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    PlayerInventory inv = p.getInventory();
                    if (inv.getItemInOffHand().getType() == Material.AIR) inv.setItemInOffHand(createItem());
                    else inv.addItem(createItem());
                }
            }, 40L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isMapItem(e.getItemDrop().getItemStack())) {
            e.getItemDrop().remove();
            e.getPlayer().sendMessage(Component.text("Karte weggelegt. Mit /karte bekommst du sie zurück.", NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------
    // Navigation (Actionbar)
    // ------------------------------------------------------------------

    public void navigate(Player p, Marker target) {
        state(p).navTarget = target;
        if (target != null) {
            p.sendMessage(Component.text("➤ Navigation zu ", NamedTextColor.GREEN)
                    .append(Component.text(target.name, NamedTextColor.GOLD))
                    .append(Component.text(" gestartet. (/karte stop zum Beenden)", NamedTextColor.GRAY)));
        }
    }

    public void tickNavigation() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerMapState st = states.get(p.getUniqueId());
            if (st == null || st.navTarget == null) continue;
            Marker t = st.navTarget;
            Location l = p.getLocation();
            if (!l.getWorld().getName().equals(t.world)) {
                p.sendActionBar(Component.text("➤ " + t.name + " liegt in einer anderen Welt (" + t.world + ")", NamedTextColor.YELLOW));
                continue;
            }
            double dx = t.centerX() + 0.5 - l.getX(), dz = t.centerZ() + 0.5 - l.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 6) {
                p.sendActionBar(Component.text("✔ Du bist bei " + t.name + " angekommen!", NamedTextColor.GREEN));
                p.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                st.navTarget = null;
                continue;
            }
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = ((targetYaw - l.getYaw()) % 360 + 540) % 360 - 180;
            String[] arrows = {"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};
            String arrow = arrows[(int) Math.floorMod(Math.round(rel / 45.0), 8)];
            p.sendActionBar(Component.text(arrow + " " + t.name + "  ", NamedTextColor.GOLD)
                    .append(Component.text((int) dist + " m", NamedTextColor.WHITE)));
        }
    }
}
