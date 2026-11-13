package de.weltkarte.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Kleine Basis für Kisten-Menüs mit Klick-Aktionen. */
public class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action); else actions.remove(slot);
    }

    public void handle(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> a = actions.get(e.getRawSlot());
        if (a != null) a.accept(e);
    }

    public void open(Player p) { p.openInventory(inventory); }

    public void fill(Material glass) {
        ItemStack filler = item(glass, Component.text(" "));
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
    }

    public static ItemStack item(Material mat, Component name, String... lore) {
        ItemStack it = new ItemStack(mat);
        it.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    public static ItemStack item(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        it.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            List<Component> l = new ArrayList<>();
            for (Component c : lore) l.add(c.decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }
}
