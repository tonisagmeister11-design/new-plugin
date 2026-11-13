package de.weltkarte.gui;

import de.weltkarte.WeltKartePlugin;
import de.weltkarte.model.Marker;
import de.weltkarte.model.MarkerType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Liste aller veröffentlichten Orte: Navigation starten oder per Wegstein hinreisen. */
public class PlacesMenu {

    private final WeltKartePlugin plugin;

    public PlacesMenu(WeltKartePlugin plugin) { this.plugin = plugin; }

    public void open(Player p, Marker atWaystone, MarkerType filter, int page) {
        Location l = p.getLocation();
        List<Marker> list = new ArrayList<>();
        for (Marker m : plugin.markers().published()) {
            if (filter != null && m.type != filter) continue;
            list.add(m);
        }
        list.sort(Comparator.comparing((Marker m) -> !m.world.equals(l.getWorld().getName()))
                .thenComparingDouble(m -> m.distanceSq2D(l.getX(), l.getZ())));

        int pages = Math.max(1, (list.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        String title = atWaystone != null ? "✦ Wegstein: " + atWaystone.name : "🗺 Orte der Welt";
        Menu menu = new Menu(6, Component.text(title + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : "")));

        boolean canTravel = plugin.waystones().mayTeleportFromHere(p);
        for (int i = 0; i < 45 && pg * 45 + i < list.size(); i++) {
            Marker m = list.get(pg * 45 + i);
            Marker ws = plugin.waystones().waystoneFor(m);
            List<Component> lore = describe(m, l);
            lore.add(Component.empty());
            lore.add(Component.text("Linksklick: Navigation starten", NamedTextColor.YELLOW));
            if (ws != null && (atWaystone == null || !ws.id.equals(atWaystone.id))) {
                if (!plugin.waystones().canUse(p, ws)) {
                    lore.add(Component.text("Wegstein '" + ws.name + "' noch nicht entdeckt", NamedTextColor.DARK_GRAY));
                } else if (canTravel) {
                    lore.add(Component.text("Rechtsklick: Reisen über Wegstein '" + ws.name + "'", NamedTextColor.LIGHT_PURPLE));
                } else {
                    lore.add(Component.text("Reisen: stell dich an einen Wegstein", NamedTextColor.DARK_GRAY));
                }
            }
            menu.set(i, Menu.item(m.type.icon(), Component.text(m.name, TextColor.color(m.rgb())), lore), e -> {
                if (e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT) {
                    if (ws != null) plugin.waystones().teleport(p, ws);
                } else {
                    p.closeInventory();
                    plugin.maps().navigate(p, m);
                }
            });
        }

        if (pg > 0) menu.set(45, Menu.item(Material.ARROW, Component.text("← Zurück", NamedTextColor.WHITE)),
                e -> open(p, atWaystone, filter, pg - 1));
        if (pg < pages - 1) menu.set(53, Menu.item(Material.ARROW, Component.text("Weiter →", NamedTextColor.WHITE)),
                e -> open(p, atWaystone, filter, pg + 1));

        String filterName = filter == null ? "Alle" : filter.displayName();
        menu.set(47, Menu.item(Material.HOPPER, Component.text("Filter: " + filterName, NamedTextColor.AQUA), "Klicken zum Wechseln"), e -> {
            MarkerType[] types = MarkerType.values();
            MarkerType next = filter == null ? types[0] : (filter.ordinal() + 1 < types.length ? types[filter.ordinal() + 1] : null);
            open(p, atWaystone, next, 0);
        });
        menu.set(49, Menu.item(Material.FILLED_MAP, Component.text("Karte in die linke Hand", NamedTextColor.GOLD),
                "Gibt dir die hochauflösende Minimap", "[F] hineinzoomen, [Shift+F] herauszoomen"), e -> {
            p.closeInventory();
            plugin.maps().giveToOffhand(p);
        });
        menu.set(51, Menu.item(Material.ENDER_EYE, Component.text("Web-Karte öffnen", NamedTextColor.GREEN),
                "Die ganze Welt im Browser – Block für Block"), e -> {
            p.closeInventory();
            plugin.sendWebLink(p);
        });
        menu.set(52, Menu.item(Material.BARRIER, Component.text("Navigation beenden", NamedTextColor.RED)), e -> {
            plugin.maps().navigate(p, null);
            p.closeInventory();
        });
        menu.fill(Material.BLACK_STAINED_GLASS_PANE);
        menu.open(p);
    }

    public static List<Component> describe(Marker m, Location from) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(m.type.displayName() + (m.isArea() ? " (Fläche)" : ""), NamedTextColor.DARK_AQUA));
        if (m.description != null && !m.description.isBlank()) {
            for (String line : wrap(m.description, 38)) lore.add(Component.text(line, NamedTextColor.GRAY));
        }
        if (m.owner != null && !m.owner.isBlank()) lore.add(Component.text("Besitzer: " + m.owner, NamedTextColor.GOLD));
        lore.add(Component.text(String.format(Locale.GERMAN, "X %d  Z %d%s  (%s)", (int) m.centerX(), (int) m.centerZ(),
                m.hasY() ? "  Y " + m.blockY() : "", m.world), NamedTextColor.DARK_GRAY));
        if (from != null && from.getWorld().getName().equals(m.world)) {
            lore.add(Component.text("Entfernung: " + (int) Math.sqrt(m.distanceSq2D(from.getX(), from.getZ())) + " m", NamedTextColor.WHITE));
        }
        return lore;
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split("\\s+")) {
            if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out.size() > 6 ? out.subList(0, 6) : out;
    }
}
