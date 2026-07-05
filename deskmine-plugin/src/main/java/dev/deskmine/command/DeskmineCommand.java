package dev.deskmine.command;

import dev.deskmine.DeskmineConfig;
import dev.deskmine.menubar.MenuBarService;
import dev.deskmine.room.RoomManager;
import dev.deskmine.room.RoomState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /deskmine (alias /dm): spawn | goto <path> | up | whereami | refresh */
public final class DeskmineCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("spawn", "goto", "up", "whereami", "refresh", "menubar");

    private final RoomManager rooms;
    private final DeskmineConfig cfg;
    private final MenuBarService menuBar;

    public DeskmineCommand(RoomManager rooms, DeskmineConfig cfg, MenuBarService menuBar) {
        this.rooms = rooms;
        this.cfg = cfg;
        this.menuBar = menuBar;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Deskmine commands are in-game only.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawn" -> goTo(p, cfg.spawnDir());
            case "goto" -> {
                if (args.length < 2) {
                    err(p, "Usage: /dm goto <path>");
                    return true;
                }
                goTo(p, expand(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
            }
            case "up" -> {
                RoomState s = roomOf(p);
                if (s == null) {
                    err(p, "You are not inside a room.");
                    return true;
                }
                Path parent = s.dir().getParent();
                if (parent == null || !cfg.inScope(parent)) {
                    err(p, "Already at the top of the world's scope (" + cfg.root() + ").");
                    return true;
                }
                goTo(p, parent);
            }
            case "whereami" -> {
                RoomState s = roomOf(p);
                p.sendMessage(Component.text(
                        s == null ? "You are between rooms." : s.dir().toString(),
                        NamedTextColor.GRAY));
            }
            case "menubar" -> {
                var snap = menuBar.current();
                p.sendMessage(Component.text("Menu bar status:", NamedTextColor.GOLD));
                p.sendMessage(Component.text(
                        "frontmost app: " + (menuBar.lastApp() == null ? "(none read yet)" : menuBar.lastApp())
                                + "\nmenus read: " + snap.menus().size()
                                + "\npermission denied: " + menuBar.permissionDenied()
                                + "\nlast macOS error: "
                                + (menuBar.lastError() == null ? "(none)" : menuBar.lastError()),
                        NamedTextColor.GRAY));
                if (menuBar.permissionDenied()) {
                    p.sendMessage(Component.text(
                            "Enable your terminal app under Accessibility AND Automation "
                                    + "(System Events), then QUIT + REOPEN it and restart the server.",
                            NamedTextColor.YELLOW));
                }
                menuBar.pollSoon();
                p.sendMessage(Component.text("Re-polling now — run /dm menubar again in ~5s.",
                        NamedTextColor.GRAY));
            }
            case "refresh" -> {
                RoomState s = roomOf(p);
                if (s == null) {
                    err(p, "You are not inside a room.");
                    return true;
                }
                rooms.rebuild(s);
                p.sendMessage(Component.text("Room refreshed.", NamedTextColor.GRAY));
            }
            default -> {
                p.sendMessage(Component.text("Deskmine:", NamedTextColor.GOLD));
                p.sendMessage(Component.text(
                        "/dm spawn — go to the mansion hall (your Desktop)\n"
                                + "/dm goto <path> — jump to any folder\n"
                                + "/dm up — go to the parent folder\n"
                                + "/dm whereami — which folder is this room?\n"
                                + "/dm refresh — rescan and rebuild this room\n"
                                + "/dm menubar — menu-bar permission diagnostics",
                        NamedTextColor.GRAY));
            }
        }
        return true;
    }

    private void goTo(Player p, Path dir) {
        Path norm = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(norm)) {
            err(p, "Not a folder: " + norm);
            return;
        }
        if (!cfg.inScope(norm)) {
            err(p, "Outside the world's scope (" + cfg.root() + "). Adjust 'root' in config.yml.");
            return;
        }
        RoomState s = rooms.getOrCreate(norm);
        p.teleport(rooms.entryLocation(s));
        p.sendMessage(Component.text(norm.toString(), NamedTextColor.GRAY));
    }

    private RoomState roomOf(Player p) {
        return rooms.recoverAt(p.getWorld(), p.getLocation().getBlockX(),
                p.getLocation().getBlockY(), p.getLocation().getBlockZ());
    }

    private Path expand(String raw) {
        String s = raw.trim();
        Path home = Path.of(System.getProperty("user.home"));
        if (s.equals("~")) return home;
        if (s.startsWith("~/")) return home.resolve(s.substring(2));
        return Path.of(s);
    }

    private void err(Player p, String msg) {
        p.sendMessage(Component.text(msg, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
