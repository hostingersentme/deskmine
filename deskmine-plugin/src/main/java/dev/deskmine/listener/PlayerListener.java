package dev.deskmine.listener;

import dev.deskmine.DeskmineConfig;
import dev.deskmine.DeskminePlugin;
import dev.deskmine.dock.DockService;
import dev.deskmine.frames.BookViewer;
import dev.deskmine.fs.FileCategory;
import dev.deskmine.menubar.MenuBarService;
import dev.deskmine.room.RoomManager;
import dev.deskmine.room.RoomState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Join teleport, door/portal interaction, dock plates, per-room levers, and
 * the nether portals: the porch portal leads to the /Network room, the one
 * inside it leads back to the hall.
 */
public final class PlayerListener implements Listener {

    private final DeskminePlugin plugin;
    private final RoomManager rooms;
    private final DeskmineConfig cfg;
    private final DockService dock;
    private final MenuBarService menuBar;
    private final BookViewer viewer;
    private final BoundingBox porchPortal; // null when the nether is unavailable
    private final Map<UUID, Long> lastPortal = new ConcurrentHashMap<>();

    public PlayerListener(DeskminePlugin plugin, RoomManager rooms, DeskmineConfig cfg,
                          DockService dock, MenuBarService menuBar, BookViewer viewer,
                          BoundingBox porchPortal) {
        this.plugin = plugin;
        this.rooms = rooms;
        this.cfg = cfg;
        this.dock = dock;
        this.menuBar = menuBar;
        this.viewer = viewer;
        this.porchPortal = porchPortal;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                RoomState spawn = rooms.getOrCreate(cfg.spawnDir());
                e.getPlayer().teleport(rooms.entryLocation(spawn));
                menuBar.fillEnderChest(e.getPlayer()); // Apple menu (spec 6.1)
                e.getPlayer().sendMessage(Component.text(
                        "Deskmine — you are in " + spawn.dir(), NamedTextColor.GRAY));
            } catch (Exception ex) {
                plugin.getLogger().warning("Spawn teleport failed: " + ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------- portals

    /** Standing in a Deskmine nether portal teleports between worlds (req #5). */
    @EventHandler
    public void onPortalEnter(EntityPortalEnterEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        World w = p.getWorld();
        if (!rooms.isDeskWorld(w)) return;

        long now = System.currentTimeMillis();
        Long last = lastPortal.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;

        if (w.getEnvironment() == World.Environment.NETHER) {
            // The return portal inside the /Network room -> back to the hall.
            RoomState room = rooms.recoverAt(w, p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(), p.getLocation().getBlockZ());
            if (room == null || !room.dir().equals(cfg.networkDir())) return;
            lastPortal.put(p.getUniqueId(), now);
            Bukkit.getScheduler().runTask(plugin, () -> open(p, cfg.spawnDir()));
        } else if (porchPortal != null
                && porchPortal.contains(p.getLocation().toVector())) {
            // The porch portal -> the /Network room in the nether.
            if (!Files.isDirectory(cfg.networkDir())) return;
            lastPortal.put(p.getUniqueId(), now);
            Bukkit.getScheduler().runTask(plugin, () -> open(p, cfg.networkDir()));
        }
    }

    /** Vanilla portal travel is disabled in Deskmine worlds; we handle it. */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (rooms.isDeskWorld(e.getFrom().getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) {
        if (rooms.isDeskWorld(e.getFrom().getWorld())) e.setCancelled(true);
    }

    // ---------------------------------------------------------- interaction

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !rooms.isDeskWorld(b.getWorld())) return;

        // Stepping on a dock pressure plate = clicking the Dock item (spec 7).
        if (e.getAction() == Action.PHYSICAL) {
            RoomState.Target t = rooms.targetAt(b.getWorld(), b.getX(), b.getY(), b.getZ());
            if (t != null && t.type() == RoomState.TargetType.DOCK) {
                dock.launch(e.getPlayer(), t.path(), t.label());
            }
            return;
        }

        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        // Recover unloaded grid rooms (e.g. after a restart) first.
        RoomState room = rooms.recoverAt(b.getWorld(), b.getX(), b.getY(), b.getZ());
        RoomState.Target t = rooms.targetAt(b.getWorld(), b.getX(), b.getY(), b.getZ());
        if (t == null) return;

        Player p = e.getPlayer();
        if (t.type() == RoomState.TargetType.MENU) {
            return; // let the chest open normally; clicks inside perform actions
        }
        if (t.type() == RoomState.TargetType.TOGGLE_HIDDEN) {
            boolean hiding = cfg.toggleHideHidden(); // lever flips visually on rebuild
            rooms.rebuildLoaded();
            p.sendMessage(Component.text(
                    hiding ? "Hidden files and folders are now hidden."
                           : "Hidden files and folders are now shown.",
                    NamedTextColor.GRAY));
            return;
        }
        if (t.type() == RoomState.TargetType.TOGGLE_FINDER) {
            boolean finder = cfg.toggleFinderMenus();
            if (room != null) rooms.rebuild(room); // lever + sign state
            menuBar.pollSoon(); // chests refill when the new snapshot arrives
            p.sendMessage(Component.text(
                    finder ? "Menu chests now show Finder's menus."
                           : "Menu chests now show the frontmost app's menus.",
                    NamedTextColor.GRAY));
            return;
        }
        if (t.type() == RoomState.TargetType.TOGGLE_DOCK) {
            if (room != null) {
                boolean shown = rooms.toggleDock(room);
                p.sendMessage(Component.text(
                        shown ? "Dock shown in this room." : "Dock hidden in this room.",
                        NamedTextColor.GRAY));
            }
            return;
        }

        e.setCancelled(true);
        switch (t.type()) {
            case FOLDER, PARENT -> open(p, Path.of(t.path()));
            case SYMLINK -> {
                Path target = Path.of(t.path());
                if (Files.isDirectory(target) && cfg.inScope(target)) {
                    open(p, target);
                } else {
                    p.sendMessage(Component.text(t.label() + " -> " + t.path(),
                            NamedTextColor.LIGHT_PURPLE));
                }
            }
            case FILE_INFO -> {
                Path file = Path.of(t.path());
                String name = file.getFileName() == null
                        ? t.path() : file.getFileName().toString();
                if (FileCategory.fromName(name) == FileCategory.TEXT
                        && Files.isRegularFile(file) && Files.isReadable(file)) {
                    viewer.open(p, file); // read the real contents (request #6)
                } else {
                    p.sendMessage(Component.text(t.label(), NamedTextColor.GRAY));
                }
            }
            case DOCK -> dock.launch(p, t.path(), t.label());
            case MENU, TOGGLE_HIDDEN, TOGGLE_DOCK, TOGGLE_FINDER -> { /* handled above */ }
        }
    }

    private void open(Player p, Path dir) {
        if (!Files.isDirectory(dir)) {
            p.sendMessage(Component.text("That folder no longer exists.", NamedTextColor.RED));
            return;
        }
        RoomState s = rooms.getOrCreate(dir);
        p.teleport(rooms.entryLocation(s));
        p.sendMessage(Component.text(dir.toString(), NamedTextColor.GRAY));
    }
}
