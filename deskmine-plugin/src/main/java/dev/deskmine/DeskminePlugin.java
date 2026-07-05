package dev.deskmine;

import dev.deskmine.command.DeskmineCommand;
import dev.deskmine.dock.DockService;
import dev.deskmine.frames.BookViewer;
import dev.deskmine.frames.FrameService;
import dev.deskmine.fs.DirectoryScanner;
import dev.deskmine.fs.FsWatcherService;
import dev.deskmine.fs.ZoneResolver;
import dev.deskmine.listener.DockListener;
import dev.deskmine.listener.FrameListener;
import dev.deskmine.listener.MenuGuardListener;
import dev.deskmine.listener.PlayerListener;
import dev.deskmine.mansion.MansionBuilder;
import dev.deskmine.mansion.MansionPlan;
import dev.deskmine.mapping.RoomIndex;
import dev.deskmine.menubar.MenuBarService;
import dev.deskmine.room.RoomBuilder;
import dev.deskmine.room.RoomManager;
import dev.deskmine.room.RoomState;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deskmine — a live Minecraft world that models the macOS filesystem.
 * The well-known home folders form a mansion on high ground; regular folders
 * are carved out beneath it on first access, iCloud Drive floats at cloud
 * height, and Network folders live in the nether, through the porch portal.
 * Files are item frames on category blocks (text files open as books), the
 * mirrored menu bar's chests perform real menu actions when clicked, and the
 * Dock hides behind a per-room lever.
 */
public final class DeskminePlugin extends JavaPlugin {

    private DeskmineConfig cfg;
    private World world;
    private World netherWorld;
    private RoomIndex index;
    private FsWatcherService watcher;
    private DockService dockService;
    private MenuBarService menuBar;
    private FrameService frames;
    private RoomManager rooms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.cfg = new DeskmineConfig(this);

        // Real terrain: the mansion needs a hill, and mobs need somewhere to lurk.
        this.world = new WorldCreator(cfg.worldName()).createWorld();
        if (world == null) {
            getLogger().severe("Could not create the deskmine world.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        world.setDifficulty(cfg.mobs() ? Difficulty.EASY : Difficulty.PEACEFUL);
        world.setGameRule(GameRules.SPAWN_MOBS, cfg.mobs());
        world.setGameRule(GameRules.ADVANCE_TIME, true);
        world.setGameRule(GameRules.ADVANCE_WEATHER, true);
        world.setGameRule(GameRules.FALL_DAMAGE, false);

        // The nether dimension: home of the Network rooms (request #5).
        if (Files.isDirectory(cfg.networkDir())) {
            this.netherWorld = new WorldCreator(cfg.worldName() + "_nether")
                    .environment(World.Environment.NETHER)
                    .createWorld();
            if (netherWorld != null) {
                netherWorld.setDifficulty(cfg.mobs() ? Difficulty.EASY : Difficulty.PEACEFUL);
                netherWorld.setGameRule(GameRules.SPAWN_MOBS, cfg.mobs());
                netherWorld.setGameRule(GameRules.FALL_DAMAGE, false);
            } else {
                getLogger().warning("Could not create the nether dimension; "
                        + "network rooms will be built underground instead.");
            }
        }

        this.index = new RoomIndex(getDataFolder().toPath().resolve("index.tsv"), getLogger());
        index.load();

        this.watcher = new FsWatcherService(getLogger());
        watcher.start();

        this.dockService = new DockService(this, cfg);
        this.menuBar = new MenuBarService(this, cfg);
        this.frames = new FrameService(this);
        BookViewer viewer = new BookViewer(this);
        ZoneResolver zones = new ZoneResolver(cfg);

        // The mansion: site it once on high ground, then keep the spot forever.
        Path planFile = getDataFolder().toPath().resolve("mansion.tsv");
        MansionPlan plan = MansionPlan.load(planFile, cfg.mansionRoomSize(), getLogger());
        List<Path> mansionDirs = knownHomeDirs();
        if (plan == null) {
            int[] site = MansionBuilder.findHighGround(world);
            int ax = site[0] - (3 * (cfg.mansionRoomSize() - 1) + 1) / 2;
            int az = site[2] - (2 * (cfg.mansionRoomSize() - 1) + 1) / 2;
            plan = new MansionPlan(ax, site[1], az, cfg.mansionRoomSize(),
                    cfg.spawnDir(), mansionDirs);
            getLogger().info("Mansion sited at " + site[0] + "," + site[1] + "," + site[2]);
        } else {
            plan.assignMissing(mansionDirs); // newly created home folders get rooms
        }
        plan.save(planFile, getLogger());

        RoomBuilder builder = new RoomBuilder(cfg, dockService, menuBar, frames, plan, zones);
        this.rooms = new RoomManager(cfg, index, builder, new DirectoryScanner(),
                watcher, dockService, frames, plan, zones, world, netherWorld);

        dockService.setOnChange(rooms::rebuildLoaded); // dock changed -> refresh loaded rooms
        dockService.start();
        menuBar.setOnChange(() -> { // app switched -> refill bars + ender chests
            for (RoomState s : rooms.loadedStates()) {
                builder.buildMenuBar(s, menuBar.current());
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (rooms.isDeskWorld(p.getWorld())) menuBar.fillEnderChest(p);
            }
        });
        menuBar.start();

        BoundingBox porchPortal = netherWorld != null
                ? MansionBuilder.netherPortalBox(plan) : null;
        getServer().getPluginManager().registerEvents(
                new PlayerListener(this, rooms, cfg, dockService, menuBar, viewer,
                        porchPortal), this);
        getServer().getPluginManager().registerEvents(new DockListener(dockService), this);
        getServer().getPluginManager().registerEvents(
                new MenuGuardListener(rooms, menuBar), this);
        getServer().getPluginManager().registerEvents(
                new FrameListener(frames, viewer), this);

        PluginCommand cmd = getCommand("deskmine");
        if (cmd != null) {
            DeskmineCommand executor = new DeskmineCommand(rooms, cfg, menuBar);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // Flush filesystem change events into room rebuilds (main thread).
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Path dirty : watcher.drainDirty()) {
                rooms.onDirectoryChanged(dirty);
            }
        }, 20L, 20L);
        // Detach watchers from rooms nobody is standing in (spec 4.4).
        Bukkit.getScheduler().runTaskTimer(this, rooms::unloadIdleRooms, 200L, 100L);

        // Raise the mansion: frame first, then every mansion room.
        try {
            MansionBuilder mansion = new MansionBuilder(world, plan);
            mansion.buildFrame();
            if (netherWorld != null) mansion.buildNetherPortal(); // porch portal (req #5)
            rooms.materializeMansion();
            RoomState spawn = rooms.getOrCreate(cfg.spawnDir());
            world.setSpawnLocation(rooms.entryLocation(spawn));
        } catch (Exception e) {
            getLogger().warning("Could not build the mansion: " + e.getMessage());
        }

        getLogger().info("Deskmine enabled. Root: " + cfg.root() + " | Spawn: " + cfg.spawnDir());
    }

    /** The well-known home folders that get mansion rooms (spawn dir excluded). */
    private List<Path> knownHomeDirs() {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> dirs = new ArrayList<>();
        for (String name : MansionPlan.KNOWN_DIRS) {
            Path p = home.resolve(name);
            if (Files.isDirectory(p) && cfg.inScope(p)
                    && !p.equals(cfg.spawnDir().toAbsolutePath().normalize())) {
                dirs.add(p);
            }
        }
        return dirs;
    }

    @Override
    public void onDisable() {
        if (dockService != null) dockService.clearAll();
        if (frames != null) frames.clearAll();
        if (watcher != null) watcher.close();
        if (index != null) index.save();
    }

    public World deskWorld() {
        return world;
    }
}
