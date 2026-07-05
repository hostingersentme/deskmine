package dev.deskmine.listener;

import dev.deskmine.dock.DockService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;

/** Keeps dock items alive and respawns them after pickup. */
public final class DockListener implements Listener {

    private final DockService dock;

    public DockListener(DockService dock) {
        this.dock = dock;
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (dock.isDockItem(e.getItem())) {
            dock.onPickedUp(e.getItem()); // pickup allowed; a replacement respawns
        }
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent e) {
        if (dock.isDockItem(e.getEntity())) e.setCancelled(true);
    }
}
