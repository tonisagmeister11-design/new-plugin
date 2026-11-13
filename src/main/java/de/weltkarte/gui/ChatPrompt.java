package de.weltkarte.gui;

import de.weltkarte.WeltKartePlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Fragt einen Text im Chat ab (z. B. den Namen eines Pins). */
public class ChatPrompt implements Listener {

    private final WeltKartePlugin plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatPrompt(WeltKartePlugin plugin) { this.plugin = plugin; }

    public void ask(Player p, String question, Consumer<String> answer) {
        p.closeInventory();
        pending.put(p.getUniqueId(), answer);
        p.sendMessage(Component.text("✎ " + question, NamedTextColor.AQUA)
                .append(Component.text(" (im Chat eingeben, 'abbrechen' zum Abbrechen)", NamedTextColor.GRAY)));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> c = pending.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("abbrechen") || text.equalsIgnoreCase("cancel")) {
                p.sendMessage(Component.text("Abgebrochen.", NamedTextColor.GRAY));
                return;
            }
            c.accept(text.length() > 64 ? text.substring(0, 64) : text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { pending.remove(e.getPlayer().getUniqueId()); }
}
