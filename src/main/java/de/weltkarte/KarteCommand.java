package de.weltkarte;

import de.weltkarte.model.Marker;
import de.weltkarte.model.MarkerType;
import de.weltkarte.hud.PlayerMapState;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

public class KarteCommand implements BasicCommand {

    private final WeltKartePlugin plugin;

    public KarteCommand(WeltKartePlugin plugin) { this.plugin = plugin; }

    @Override
    public String permission() { return "weltkarte.use"; }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Player p = source.getExecutor() instanceof Player ep ? ep : (sender instanceof Player sp ? sp : null);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("weltkarte.admin");

        switch (sub) {
            case "" -> {
                if (p == null) { help(sender, admin); return; }
                plugin.maps().giveToOffhand(p);
            }
            case "hilfe", "help" -> help(sender, admin);
            case "orte", "places" -> {
                if (p != null) plugin.placesMenu().open(p, null, null, 0);
            }
            case "web" -> {
                if (p != null) plugin.sendWebLink(p);
                else sender.sendMessage(plugin.publicUrl());
            }
            case "zoom" -> {
                if (p == null) return;
                if (args.length < 2) { plugin.maps().zoom(p, -1); return; }
                switch (args[1]) {
                    case "+", "rein", "in" -> plugin.maps().zoom(p, -1);
                    case "-", "raus", "out" -> plugin.maps().zoom(p, 1);
                    default -> {
                        try {
                            int lvl = Integer.parseInt(args[1]);
                            plugin.maps().setZoom(p, lvl - 1);
                        } catch (NumberFormatException e) {
                            sender.sendMessage(Component.text("/karte zoom <+|-|1-" + PlayerMapState.ZOOMS.length + ">", NamedTextColor.RED));
                        }
                    }
                }
            }
            case "stop" -> {
                if (p != null) { plugin.maps().navigate(p, null); p.sendActionBar(Component.empty()); }
            }
            case "weg", "entfernen" -> {
                if (p != null) plugin.maps().removeFromPlayer(p);
            }
            case "control", "steuerung" -> {
                if (!admin) { noPerm(sender); return; }
                if (p == null) { sender.sendMessage("Nur im Spiel möglich."); return; }
                plugin.adminMenu().open(p);
            }
            case "update", "updaten", "publish" -> {
                if (!admin) { noPerm(sender); return; }
                plugin.publish(sender);
            }
            case "editor" -> {
                if (!admin) { noPerm(sender); return; }
                if (p != null) plugin.sendEditorLink(p);
            }
            case "pin" -> {
                if (!admin) { noPerm(sender); return; }
                if (p == null || args.length < 2) {
                    sender.sendMessage(Component.text("/karte pin <name> [ort|stadt|gebaeude]", NamedTextColor.RED));
                    return;
                }
                MarkerType type = MarkerType.ORT;
                List<String> nameParts = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
                if (nameParts.size() > 1) {
                    MarkerType t = MarkerType.parse(nameParts.getLast());
                    if (t != null && t != MarkerType.WEGSTEIN) { type = t; nameParts.removeLast(); }
                }
                Marker m = new Marker();
                m.type = type;
                m.name = String.join(" ", nameParts);
                m.world = p.getWorld().getName();
                m.x = p.getLocation().getBlockX();
                m.y = (double) p.getLocation().getBlockY();
                m.z = p.getLocation().getBlockZ();
                m.color = switch (type) { case STADT -> "#fdd835"; case GEBAEUDE -> "#fb8c00"; default -> "#e53935"; };
                plugin.markers().addDraft(m);
                sender.sendMessage(Component.text("✔ " + type.displayName() + " '" + m.name + "' im Entwurf gespeichert. /karte update zum Veröffentlichen.", NamedTextColor.GREEN));
            }
            case "wegstein" -> {
                if (!admin) { noPerm(sender); return; }
                if (p == null || args.length < 2) {
                    sender.sendMessage(Component.text("/karte wegstein <name>", NamedTextColor.RED));
                    return;
                }
                Marker m = plugin.waystones().placeHere(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                sender.sendMessage(Component.text("✔ Wegstein '" + m.name + "' gesetzt (Entwurf). /karte update zum Veröffentlichen.", NamedTextColor.GREEN));
            }
            case "render" -> {
                if (!admin) { noPerm(sender); return; }
                if (args.length >= 2 && (args[1].equalsIgnoreCase("stop") || args[1].equalsIgnoreCase("abbrechen"))) {
                    plugin.renderer().cancelFullRender();
                    return;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
                    String st = plugin.renderer().fullRenderStatus();
                    sender.sendMessage(st == null ? "Kein Render aktiv." : st);
                    return;
                }
                World w = p != null ? p.getWorld() : Bukkit.getWorlds().getFirst();
                int radius = plugin.getConfig().getInt("render.default-full-render-radius", 3000);
                int cx = p != null ? p.getLocation().getBlockX() : w.getSpawnLocation().getBlockX();
                int cz = p != null ? p.getLocation().getBlockZ() : w.getSpawnLocation().getBlockZ();
                for (int i = 1; i < args.length; i++) {
                    String a = args[i];
                    if (a.matches("\\d+")) radius = Integer.parseInt(a);
                    else if (a.equalsIgnoreCase("spawn")) { cx = w.getSpawnLocation().getBlockX(); cz = w.getSpawnLocation().getBlockZ(); }
                    else if (Bukkit.getWorld(a) != null) {
                        w = Bukkit.getWorld(a);
                        if (p == null || !p.getWorld().equals(w)) { cx = w.getSpawnLocation().getBlockX(); cz = w.getSpawnLocation().getBlockZ(); }
                    }
                }
                if (!plugin.renderer().isWorldEnabled(w)) {
                    sender.sendMessage(Component.text("Welt '" + w.getName() + "' ist in config.yml (render.worlds) nicht freigegeben.", NamedTextColor.RED));
                    return;
                }
                plugin.renderer().startFullRender(w, cx, cz, Math.min(radius, 100_000), sender);
            }
            case "reload" -> {
                if (!admin) { noPerm(sender); return; }
                plugin.reloadConfig();
                sender.sendMessage(Component.text("config.yml neu geladen (Web-Port/Threads erst nach Neustart).", NamedTextColor.GREEN));
            }
            default -> help(sender, admin);
        }
    }

    private void noPerm(CommandSender s) {
        s.sendMessage(Component.text("Dafür musst du Admin/Owner sein.", NamedTextColor.RED));
    }

    private void help(CommandSender s, boolean admin) {
        s.sendMessage(Component.text("—— 🗺 Weltkarte ——", NamedTextColor.GOLD));
        line(s, "/karte", "Karte in die linke Hand legen");
        line(s, "[F] / [Shift+F]", "Minimap hinein-/herauszoomen (Taste änderbar)");
        line(s, "/karte zoom <+|-|1-7>", "Zoomstufe setzen");
        line(s, "/karte orte", "Alle Orte, Navigation & Wegstein-Reisen");
        line(s, "/karte web", "Link zur hochauflösenden Web-Karte");
        line(s, "/karte stop", "Navigation beenden");
        if (admin) {
            s.sendMessage(Component.text("Admin:", NamedTextColor.RED));
            line(s, "/karte control", "Steuerungs-Menü öffnen");
            line(s, "/karte editor", "Browser-Editor (Pins, Flächen, Farben)");
            line(s, "/karte pin <name> [stadt|gebaeude]", "Pin an deiner Position");
            line(s, "/karte wegstein <name>", "Wegstein setzen");
            line(s, "/karte update", "Karte für alle veröffentlichen");
            line(s, "/karte render [radius] [welt|spawn|stop|status]", "Welt komplett rendern");
        }
    }

    private void line(CommandSender s, String cmd, String desc) {
        s.sendMessage(Component.text(" " + cmd, NamedTextColor.YELLOW).append(Component.text(" – " + desc, NamedTextColor.GRAY)));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        boolean admin = source.getSender().hasPermission("weltkarte.admin");
        List<String> subs = new ArrayList<>(List.of("orte", "web", "zoom", "stop", "weg", "hilfe"));
        if (admin) subs.addAll(List.of("control", "editor", "pin", "wegstein", "update", "render", "reload"));
        if (args.length <= 1) {
            String pre = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return subs.stream().filter(s -> s.startsWith(pre)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("zoom")) return List.of("+", "-", "1", "2", "3", "4", "5", "6", "7");
        if (args.length >= 2 && args[0].equalsIgnoreCase("render") && admin) {
            List<String> r = new ArrayList<>(List.of("1000", "3000", "5000", "spawn", "stop", "status"));
            Bukkit.getWorlds().forEach(w -> r.add(w.getName()));
            return r;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("pin")) return List.of("stadt", "gebaeude", "ort");
        return List.of();
    }
}
