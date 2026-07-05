package dev.deskmine.listener;

import dev.deskmine.menubar.MenuBarService;
import dev.deskmine.room.RoomManager;
import dev.deskmine.room.RoomState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

/**
 * Menu chests are a projection of the real menu bar: items can't be taken,
 * but clicking one performs that menu action on the Mac (request #1) —
 * e.g. System Settings in the Apple menu opens System Settings. The Apple
 * menu is the ender chest; app menus are the named chests.
 */
public final class MenuGuardListener implements Listener {

    private final RoomManager rooms;
    private final MenuBarService menuBar;

    public MenuGuardListener(RoomManager rooms, MenuBarService menuBar) {
        this.rooms = rooms;
        this.menuBar = menuBar;
    }

    /** The menu title a top inventory mirrors: "" = Apple, null = not a menu. */
    private String menuTitleOf(Inventory top, Player p) {
        if (top.getType() == InventoryType.ENDER_CHEST) {
            // Ender chests are repurposed as the Apple menu on this server.
            return rooms.isDeskWorld(p.getWorld()) ? "" : null;
        }
        Location loc = top.getLocation();
        if (loc == null || loc.getWorld() == null || !rooms.isDeskWorld(loc.getWorld())) {
            return null;
        }
        RoomState.Target t = rooms.targetAt(loc.getWorld(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (t == null || t.type() != RoomState.TargetType.MENU) return null;
        return t.label().equals("Apple") ? "" : t.label();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        String title = menuTitleOf(top, p);
        if (title == null) return;
        e.setCancelled(true); // items never leave the chest
        if (e.getClickedInventory() != top) return; // ignore own-inventory clicks
        if (e.getCurrentItem() == null || e.getCurrentItem().getType().isAir()) return;
        menuBar.clickMenuItem(p, title.isEmpty() ? null : title, e.getSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (menuTitleOf(e.getView().getTopInventory(), p) != null) e.setCancelled(true);
    }
}
