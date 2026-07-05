package dev.deskmine;

import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

/** Typed view over config.yml. */
public final class DeskmineConfig {

    private final Path root;
    private final Path spawnDir;
    private final int roomSize;
    private final int minRoomSize;
    private final int spacing;
    private final int fileCap;
    private final int depthStep;
    private final String worldName;
    private final boolean mobs;
    private final int mansionRoomSize;
    private final int cellarDrop;
    private final boolean dockEnabled;
    private final int dockRefreshSeconds;
    private final int unloadAfterSeconds;
    private final boolean menuBarEnabled;
    private final int menuBarPollSeconds;
    private final int menuBarRefetchSeconds;
    private final Path icloudDir;
    private final Path networkDir;
    private final DeskminePlugin plugin;
    private volatile boolean hideHidden;
    private volatile boolean finderMenus;

    public DeskmineConfig(DeskminePlugin plugin) {
        this.plugin = plugin;
        FileConfiguration c = plugin.getConfig();
        Path home = Path.of(System.getProperty("user.home"));

        // Default scope is the whole drive so navigation never dead-ends at home
        // (the old home default trapped players inside the user folder).
        String rootRaw = c.getString("root", "");
        this.root = (rootRaw == null || rootRaw.isBlank()) ? Path.of("/") : expand(rootRaw, home);

        String spawnRaw = c.getString("spawn-dir", "");
        if (spawnRaw == null || spawnRaw.isBlank()) {
            Path desktop = home.resolve("Desktop");
            this.spawnDir = Files.isDirectory(desktop) ? desktop : home;
        } else {
            this.spawnDir = expand(spawnRaw, home);
        }

        this.roomSize = oddAtLeast(c.getInt("room.size", 33), 17);
        this.minRoomSize = Math.min(oddAtLeast(c.getInt("room.min-size", 17), 17), roomSize);
        this.spacing = Math.max(4, c.getInt("room.spacing", 15));
        this.fileCap = Math.max(1, c.getInt("room.file-cap", 120));
        this.depthStep = Math.max(0, Math.min(32, c.getInt("room.depth-step", 6)));
        this.worldName = c.getString("world.name", "deskmine-hills");
        this.mobs = c.getBoolean("world.mobs", true);
        this.mansionRoomSize = oddAtLeast(c.getInt("mansion.room-size", 21), 17);
        this.cellarDrop = Math.max(8, Math.min(48, c.getInt("mansion.cellar-drop", 14)));
        this.dockEnabled = c.getBoolean("dock.enabled", true);
        this.dockRefreshSeconds = Math.max(10, c.getInt("dock.refresh-seconds", 60));
        this.unloadAfterSeconds = Math.max(10, c.getInt("watch.unload-after-seconds", 60));
        this.menuBarEnabled = c.getBoolean("menubar.enabled", true);
        this.menuBarPollSeconds = Math.max(1, c.getInt("menubar.poll-seconds", 2));
        this.menuBarRefetchSeconds = Math.max(5, c.getInt("menubar.refetch-seconds", 30));
        this.hideHidden = c.getBoolean("hidden.hide", false);
        this.finderMenus = c.getBoolean("menubar.finder-menus", false);
        this.icloudDir = home.resolve("Library/Mobile Documents/com~apple~CloudDocs");
        this.networkDir = Path.of("/Network");
    }

    private static int oddAtLeast(int v, int min) {
        if (v < min) v = min;
        return v % 2 == 0 ? v + 1 : v;
    }

    private static Path expand(String raw, Path home) {
        String s = raw.trim();
        if (s.equals("~")) return home;
        if (s.startsWith("~/")) return home.resolve(s.substring(2)).toAbsolutePath().normalize();
        return Path.of(s).toAbsolutePath().normalize();
    }

    public Path root() { return root; }
    public Path spawnDir() { return spawnDir; }
    public int roomSize() { return roomSize; }
    public int minRoomSize() { return minRoomSize; }
    public int spacing() { return spacing; }
    /** Distance between room origins on the grid (based on the max room size). */
    public int pitch() { return roomSize + spacing; }
    public int fileCap() { return fileCap; }
    public int depthStep() { return depthStep; }
    public String worldName() { return worldName; }
    public boolean mobs() { return mobs; }
    public int mansionRoomSize() { return mansionRoomSize; }
    public int cellarDrop() { return cellarDrop; }
    public boolean dockEnabled() { return dockEnabled; }
    public int dockRefreshSeconds() { return dockRefreshSeconds; }
    public int unloadAfterSeconds() { return unloadAfterSeconds; }
    public boolean menuBarEnabled() { return menuBarEnabled; }
    public int menuBarPollSeconds() { return menuBarPollSeconds; }
    public int menuBarRefetchSeconds() { return menuBarRefetchSeconds; }

    /** Whether hidden (dot) entries are currently omitted from rooms. */
    public boolean hideHidden() { return hideHidden; }

    /** Flips the hidden-entry setting (spawn-room lever) and persists it. */
    public boolean toggleHideHidden() {
        hideHidden = !hideHidden;
        plugin.getConfig().set("hidden.hide", hideHidden);
        plugin.saveConfig();
        return hideHidden;
    }

    /** When true, the menu chests mirror Finder instead of the frontmost app. */
    public boolean finderMenus() { return finderMenus; }

    /** Flips the Finder-menus setting (spawn-room lever) and persists it. */
    public boolean toggleFinderMenus() {
        finderMenus = !finderMenus;
        plugin.getConfig().set("menubar.finder-menus", finderMenus);
        plugin.saveConfig();
        return finderMenus;
    }

    /** The iCloud Drive folder (rooms in the clouds), if it exists. */
    public Path icloudDir() { return icloudDir; }

    /** The /Network folder (rooms in the nether). */
    public Path networkDir() { return networkDir; }

    /** Whether a path is inside the navigable scope. */
    public boolean inScope(Path p) {
        return p.toAbsolutePath().normalize().startsWith(root);
    }

    /** Directory depth below the scope root (0 = the root itself). */
    public int depthOf(Path dir) {
        try {
            if (!dir.equals(root)) return root.relativize(dir).getNameCount();
        } catch (IllegalArgumentException ignored) {
            // outside root (shouldn't happen; guarded by inScope)
        }
        return 0;
    }
}
