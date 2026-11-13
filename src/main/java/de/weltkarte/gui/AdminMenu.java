package de.weltkarte.gui;

import de.weltkarte.WeltKartePlugin;
import de.weltkarte.model.Marker;
import de.weltkarte.model.MarkerType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.*;

/** /karte control – Admin-Menü zum Markieren und Veröffentlichen ("Karte updaten"). */
public class AdminMenu {

    private static final String[] COLORS = {"#e53935", "#fb8c00", "#fdd835", "#43a047", "#00acc1", "#1e88e5", "#8e24aa", "#ec407a", "#6d4c41", "#ffffff", "#212121"};
    private static final String[] COLOR_NAMES = {"Rot", "Orange", "Gelb", "Grün", "Türkis", "Blau", "Lila", "Pink", "Braun", "Weiß", "Schwarz"};

    private final WeltKartePlugin plugin;
    private final Map<UUID, Location> areaCorner = new HashMap<>();

    public AdminMenu(WeltKartePlugin plugin) { this.plugin = plugin; }

    public void open(Player p) {
        int draftCount = plugin.markers().draft().size();
        int changes = plugin.markers().pendingChanges();
        Menu menu = new Menu(6, Component.text("🗺 Karten-Steuerung", NamedTextColor.DARK_RED));

        menu.set(10, Menu.item(Material.RED_BANNER, Component.text("Ort hier markieren", NamedTextColor.RED),
                "Setzt einen Pin an deiner Position"), e -> askPoint(p, MarkerType.ORT, "#e53935"));
        menu.set(11, Menu.item(Material.BELL, Component.text("Stadt hier markieren", NamedTextColor.GOLD),
                "Städte werden groß auf der Karte angezeigt"), e -> askPoint(p, MarkerType.STADT, "#fdd835"));
        menu.set(12, Menu.item(Material.BRICKS, Component.text("Gebäude hier markieren", NamedTextColor.YELLOW),
                "Markiert ein Gebäude (mit Besitzer)"), e -> askPoint(p, MarkerType.GEBAEUDE, "#fb8c00"));

        Location corner = areaCorner.get(p.getUniqueId());
        if (corner == null) {
            menu.set(13, Menu.item(Material.GOLDEN_AXE, Component.text("Gebäude-Fläche: Ecke 1 setzen", NamedTextColor.YELLOW),
                    "Stell dich an eine Ecke des Gebäudes", "und klicke hier."), e -> {
                areaCorner.put(p.getUniqueId(), p.getLocation());
                p.sendMessage(Component.text("Ecke 1 gesetzt. Geh zur gegenüberliegenden Ecke und klicke im Menü auf 'Ecke 2'.", NamedTextColor.GREEN));
                open(p);
            });
        } else {
            menu.set(13, Menu.item(Material.DIAMOND_AXE, Component.text("Gebäude-Fläche: Ecke 2 setzen", NamedTextColor.AQUA),
                    "Ecke 1: X " + corner.getBlockX() + " Z " + corner.getBlockZ(), "Stell dich an die gegenüberliegende Ecke.",
                    "Rechtsklick: Ecke 1 verwerfen"), e -> {
                if (e.getClick().isRightClick()) {
                    areaCorner.remove(p.getUniqueId());
                    open(p);
                    return;
                }
                Location c2 = p.getLocation();
                if (!c2.getWorld().equals(corner.getWorld())) {
                    p.sendMessage(Component.text("Beide Ecken müssen in derselben Welt sein.", NamedTextColor.RED));
                    return;
                }
                areaCorner.remove(p.getUniqueId());
                plugin.prompt().ask(p, "Name des Gebäudes?", name -> plugin.prompt().ask(p, "Besitzer (Spielername oder '-')?", owner -> {
                    Marker m = base(p, MarkerType.GEBAEUDE, name, "#fb8c00");
                    m.x = Math.min(corner.getBlockX(), c2.getBlockX());
                    m.z = Math.min(corner.getBlockZ(), c2.getBlockZ());
                    m.x2 = Math.max(corner.getBlockX(), c2.getBlockX());
                    m.z2 = Math.max(corner.getBlockZ(), c2.getBlockZ());
                    m.y = (double) Math.max(corner.getBlockY(), c2.getBlockY());
                    m.owner = owner.equals("-") ? "" : owner;
                    plugin.markers().addDraft(m);
                    added(p, m);
                }));
            });
        }
        menu.set(14, Menu.item(Material.LODESTONE, Component.text("Wegstein hier setzen", NamedTextColor.LIGHT_PURPLE),
                "Platziert einen Wegstein (Magnetit).", "Spieler können sich zwischen", "Wegsteinen teleportieren."), e ->
                plugin.prompt().ask(p, "Name des Wegsteins?", name -> {
                    Marker m = plugin.waystones().placeHere(p, name);
                    added(p, m);
                }));
        menu.set(16, Menu.item(Material.RECOVERY_COMPASS, Component.text("Karten-Editor im Browser", NamedTextColor.GREEN),
                "Die komplette Welt in voller Auflösung:", "Pins setzen, verschieben, Flächen zeichnen,", "Farben & Beschreibungen ändern."), e -> {
            p.closeInventory();
            plugin.sendEditorLink(p);
        });

        menu.set(28, Menu.item(Material.WRITABLE_BOOK, Component.text("Entwurf verwalten (" + draftCount + ")", NamedTextColor.AQUA),
                "Alle Markierungen ansehen, umbenennen,", "einfärben oder löschen"), e -> openDraft(p, 0));
        menu.set(29, Menu.item(Material.BOOK, Component.text("Live-Karte ansehen", NamedTextColor.WHITE),
                "So sehen es die Spieler gerade"), e -> plugin.placesMenu().open(p, null, null, 0));

        String status = plugin.renderer().fullRenderStatus();
        menu.set(31, Menu.item(Material.GRASS_BLOCK, Component.text("Welt rendern", NamedTextColor.DARK_GREEN),
                status != null ? new String[]{"Läuft: " + status, "Rechtsklick: abbrechen"}
                        : new String[]{"Rendert alle generierten Chunks", "um dich herum (Radius aus config.yml).", "Linksklick: starten"}), e -> {
            if (e.getClick().isRightClick()) {
                plugin.renderer().cancelFullRender();
            } else {
                int r = plugin.getConfig().getInt("render.default-full-render-radius", 3000);
                plugin.renderer().startFullRender(p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockZ(), r, p);
            }
            p.closeInventory();
        });
        menu.set(33, Menu.item(Material.FILLED_MAP, Component.text("Minimap nehmen", NamedTextColor.GOLD),
                "Legt die Karte in deine linke Hand"), e -> {
            p.closeInventory();
            plugin.maps().giveToOffhand(p);
        });
        menu.set(34, Menu.item(Material.ENDER_EYE, Component.text("Link zur öffentlichen Web-Karte", NamedTextColor.GREEN)), e -> {
            p.closeInventory();
            plugin.sendWebLink(p);
        });

        menu.set(49, Menu.item(changes > 0 ? Material.EMERALD_BLOCK : Material.EMERALD,
                Component.text("✔ KARTE UPDATEN", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                changes > 0 ? new String[]{changes + " Änderung(en) gegenüber der Live-Karte.", "Klicken: für ALLE Spieler veröffentlichen"}
                        : new String[]{"Keine offenen Änderungen.", "Klicken: trotzdem neu veröffentlichen"}), e -> {
            p.closeInventory();
            plugin.publish(p);
        });
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        menu.open(p);
    }

    private Marker base(Player p, MarkerType type, String name, String color) {
        Location l = p.getLocation();
        Marker m = new Marker();
        m.type = type;
        m.name = name;
        m.world = l.getWorld().getName();
        m.x = l.getBlockX();
        m.y = (double) l.getBlockY();
        m.z = l.getBlockZ();
        m.color = color;
        return m;
    }

    private void askPoint(Player p, MarkerType type, String color) {
        plugin.prompt().ask(p, "Name für " + type.displayName() + "?", name -> {
            if (type == MarkerType.GEBAEUDE) {
                plugin.prompt().ask(p, "Besitzer (Spielername oder '-')?", owner -> {
                    Marker m = base(p, type, name, color);
                    m.owner = owner.equals("-") ? "" : owner;
                    plugin.markers().addDraft(m);
                    added(p, m);
                });
            } else {
                Marker m = base(p, type, name, color);
                plugin.markers().addDraft(m);
                added(p, m);
            }
        });
    }

    private void added(Player p, Marker m) {
        p.sendMessage(Component.text("✔ " + m.type.displayName() + " '" + m.name + "' zum Entwurf hinzugefügt. ", NamedTextColor.GREEN)
                .append(Component.text("Öffne /karte control → 'Karte updaten', damit es alle sehen.", NamedTextColor.GRAY)));
    }

    public void openDraft(Player p, int page) {
        List<Marker> list = plugin.markers().draft();
        list.sort(Comparator.comparing((Marker m) -> m.type).thenComparing(m -> m.name.toLowerCase(Locale.ROOT)));
        int pages = Math.max(1, (list.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, Component.text("Entwurf (" + list.size() + ")" + (pages > 1 ? " " + (pg + 1) + "/" + pages : "")));
        for (int i = 0; i < 45 && pg * 45 + i < list.size(); i++) {
            Marker m = list.get(pg * 45 + i);
            List<Component> lore = PlacesMenu.describe(m, p.getLocation());
            lore.add(Component.empty());
            lore.add(Component.text("Linksklick: hinteleportieren", NamedTextColor.YELLOW));
            lore.add(Component.text("Rechtsklick: Farbe wechseln", NamedTextColor.YELLOW));
            lore.add(Component.text("Shift+Linksklick: umbenennen", NamedTextColor.YELLOW));
            lore.add(Component.text("Shift+Rechtsklick: Beschreibung setzen", NamedTextColor.YELLOW));
            lore.add(Component.text("Q (Werfen-Taste): löschen", NamedTextColor.RED));
            menu.set(i, Menu.item(m.type.icon(), Component.text(m.name, TextColor.color(m.rgb())), lore), e -> {
                ClickType c = e.getClick();
                if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) {
                    plugin.markers().removeDraft(m.id);
                    p.sendMessage(Component.text("'" + m.name + "' gelöscht (im Entwurf).", NamedTextColor.YELLOW));
                    openDraft(p, pg);
                } else if (c == ClickType.SHIFT_LEFT) {
                    plugin.prompt().ask(p, "Neuer Name für '" + m.name + "'?", name -> {
                        m.name = name;
                        plugin.markers().updateDraft(m);
                        openDraft(p, pg);
                    });
                } else if (c == ClickType.SHIFT_RIGHT) {
                    plugin.prompt().ask(p, "Beschreibung für '" + m.name + "'?", d -> {
                        m.description = d;
                        plugin.markers().updateDraft(m);
                        openDraft(p, pg);
                    });
                } else if (c.isRightClick()) {
                    int idx = Arrays.asList(COLORS).indexOf(m.color.toLowerCase(Locale.ROOT));
                    int next = (idx + 1) % COLORS.length;
                    m.color = COLORS[next];
                    plugin.markers().updateDraft(m);
                    p.sendActionBar(Component.text("Farbe: " + COLOR_NAMES[next], TextColor.color(m.rgb())));
                    openDraft(p, pg);
                } else {
                    Location loc = m.location();
                    if (loc != null) {
                        p.closeInventory();
                        p.teleportAsync(loc);
                    }
                }
            });
        }
        if (pg > 0) menu.set(45, Menu.item(Material.ARROW, Component.text("← Zurück")), e -> openDraft(p, pg - 1));
        if (pg < pages - 1) menu.set(53, Menu.item(Material.ARROW, Component.text("Weiter →")), e -> openDraft(p, pg + 1));
        menu.set(49, Menu.item(Material.OAK_DOOR, Component.text("Zurück zur Steuerung", NamedTextColor.WHITE)), e -> open(p));
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        menu.open(p);
    }
}
