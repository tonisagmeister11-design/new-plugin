package de.weltkarte.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class MenuListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu menu) {
            e.setCancelled(true);
            if (e.getClickedInventory() == e.getView().getTopInventory()) menu.handle(e);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }
}
