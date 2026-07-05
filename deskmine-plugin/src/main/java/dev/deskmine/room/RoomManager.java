package dev.deskmine.room;

import dev.deskmine.DeskmineConfig;
import dev.deskmine.dock.DockService;
import dev.deskmine.frames.FrameService;
import dev.deskmine.fs.DirectoryScanner;
import dev.deskmine.fs.FsWatcherService;
import dev.deskmine.fs.ZoneResolver;
import dev.deskmine.mansion.MansionPlan;
import dev.deskmine.mapping.Cell;
import dev.deskmine.mapping.RoomIndex;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the set of materialized rooms. Mansion rooms (the well-known home
 * folders) are placed by the MansionPlan, built at startup, and never
 * unloaded; every other folder gets a grid cell when first accessed (lazy
 * generation, spec 4.4). Where that cell is realized depends on the zone:
 * regular folders are carved out beneath the mansion, iCloud folders float
 * at cloud height, and network folders live in the nether dimension.
 */
public final class RoomManager {

    /** Floor height of the iCloud rooms' cloud layer (vanilla clouds ~192). */
    private static final int SKY_BASE_Y = 196;
    private static final int SKY_TOP_Y = 288;

    /** Floor height of the first nether (network) rooms. */
    private static final int NETHER_BASE_Y = 64;
    private static final int NETHER_MIN_Y = 36; // stay above the lava seas

    private final DeskmineConfig cfg;
    private final RoomIndex index;
    private final RoomBuilder builder;
    private final DirectoryScanner scanner;
    private final FsWatcherService watcher;
    private final DockService dock;
    private final FrameService frames;
    private final MansionPlan plan;
    private final ZoneResolver zones;
    private final World overworld;
    private final World nether; // null when the nether dimension is unavailable
    private final int gridOffX;
    private final int gridOffZ;

    private final Map<Long, RoomState> byKey = new HashMap<>();
    private final Map<String, RoomState> byPath = new HashMap<>();

    public RoomManager(DeskmineConfig cfg, RoomIndex index, RoomBuilder builder,
                       DirectoryScanner scanner, FsWatcherService watcher,
                       DockService dock, FrameService frames, MansionPlan plan,
                       ZoneResolver zones, World overworld, World nether) {
        this.cfg = cfg;
        this.index = index;
        this.builder = builder;
        this.scanner = scanner;
        this.watcher = watcher;
        this.dock = dock;
        this.frames = frames;
        this.plan = plan;
        this.zones = zones;
        this.overworld = overworld;
        this.nether = nether;
        // Center the grid beneath (or above) the mansion.
        this.gridOffX = plan.baseX() + plan.footprintWidth() / 2 - cfg.roomSize() / 2;
        this.gridOffZ = plan.baseZ() + plan.footprintDepth() / 2 - cfg.roomSize() / 2;
    }

    public MansionPlan plan() { return plan; }
    public ZoneResolver zones() { return zones; }

    /** Whether this world belongs to Deskmine (overworld or its nether). */
    public boolean isDeskWorld(World w) {
        return overworld.equals(w) || (nether != null && nether.equals(w));
    }

    /** Builds every mansion room (called once at startup, after the frame). */
    public void materializeMansion() {
        for (String path : List.copyOf(plan.paths())) {
            Path p = Path.of(path);
            if (Files.isDirectory(p)) getOrCreate(p);
        }
    }

    /** Materializes (or returns) the room for a directory. Main thread only. */
    public RoomState getOrCreate(Path dirRaw) {
        Path dir = dirRaw.toAbsolutePath().normalize();
        String key = dir.toString();
        RoomState existing = byPath.get(key);
        if (existing != null) return existing;
        RoomState state;
        MansionPlan.Slot slot = plan.slotFor(dir);
        if (slot != null) {
            state = RoomState.mansion(dir, slot.key(), overworld, plan.originX(slot),
                    plan.originZ(slot), plan.floorY(slot), plan.roomSize());
        } else {
            Zone zone = zones.zoneFor(dir);
            World world = zone == Zone.NETHER && nether != null ? nether : overworld;
            if (zone == Zone.NETHER && nether == null) zone = Zone.UNDERGROUND;
            Cell cell = index.cellFor(key);
            state = new RoomState(dir, cell, world, zone,
                    gridOffX + cell.x() * cfg.pitch(),
                    gridOffZ + cell.z() * cfg.pitch(),
                    floorYFor(zone, dir));
        }
        rebuild(state);
        byKey.put(state.key(), state);
        byPath.put(key, state);
        watcher.watch(dir);
        return state;
    }

    /** Floor height of a grid room, by zone. */
    private int floorYFor(Zone zone, Path dir) {
        return switch (zone) {
            case SKY -> Math.min(SKY_TOP_Y,
                    SKY_BASE_Y + zones.depthInZone(dir) * cfg.depthStep());
            case NETHER -> Math.max(NETHER_MIN_Y,
                    NETHER_BASE_Y - zones.depthInZone(dir) * cfg.depthStep());
            default -> Math.max(-40, plan.groundY() - cfg.cellarDrop()
                    - cfg.depthOf(dir) * cfg.depthStep());
        };
    }

    /** The world a directory's room would be built in (without materializing it). */
    private World worldFor(Path dir) {
        if (plan.slotFor(dir) != null) return overworld;
        return zones.zoneFor(dir) == Zone.NETHER && nether != null ? nether : overworld;
    }

    /** Rescans the directory and re-places the room's contents. */
    public void rebuild(RoomState state) {
        var scan = scanner.scan(state.dir(), builder.maxDoorCapacity(),
                Math.min(cfg.fileCap(), builder.maxFileCapacity()));
        Path parent = state.dir().getParent();
        boolean hasParent = parent != null
                && cfg.inScope(parent)
                && !state.dir().equals(cfg.root());
        boolean isSpawnRoom = state.dir().equals(cfg.spawnDir());
        builder.buildRoom(state, scan, hasParent, isSpawnRoom);
    }

    /** Rebuilds every loaded room (used when the Dock or hidden-toggle changes). */
    public void rebuildLoaded() {
        for (RoomState s : List.copyOf(byPath.values())) rebuild(s);
    }

    /** Snapshot of currently loaded rooms (used for menu-bar refills). */
    public List<RoomState> loadedStates() {
        return List.copyOf(byPath.values());
    }

    public void onDirectoryChanged(Path dir) {
        RoomState s = byPath.get(dir.toAbsolutePath().normalize().toString());
        if (s != null) rebuild(s);
    }

    /** Which loaded room contains this position, if any. */
    public RoomState roomAt(World w, int x, int y, int z) {
        if (overworld.equals(w) && plan.inMansionAirspace(x, y, z)) {
            String path = plan.pathAtPosition(x, y, z);
            return path == null ? null : byPath.get(path);
        }
        Cell c = cellFromWorld(x, z);
        if (c == null) return null;
        RoomState s = byKey.get(c.key());
        return s != null && s.world().equals(w) ? s : null;
    }

    /**
     * The interactive target at a block, across every loaded room that could
     * own it (mansion walls are shared, so neighbours are checked too).
     */
    public RoomState.Target targetAt(World w, int x, int y, int z) {
        if (overworld.equals(w)) {
            for (String path : plan.candidatePathsAt(x, z)) {
                RoomState s = byPath.get(path);
                if (s != null) {
                    RoomState.Target t = s.target(x, y, z);
                    if (t != null) return t;
                }
            }
        }
        Cell c = cellFromWorld(x, z);
        if (c != null) {
            RoomState s = byKey.get(c.key());
            if (s != null && s.world().equals(w)) return s.target(x, y, z);
        }
        return null;
    }

    /**
     * Like {@link #roomAt}, but if the position belongs to a previously-indexed
     * grid room whose state was unloaded (e.g. after a restart), the room is
     * re-materialized from the persisted index — coordinate -> path (spec 4.3).
     */
    public RoomState recoverAt(World w, int x, int y, int z) {
        RoomState s = roomAt(w, x, y, z);
        if (s != null) return s;
        if (overworld.equals(w) && plan.inMansionAirspace(x, y, z)) {
            return null; // mansion rooms stay loaded
        }
        Cell c = cellFromWorld(x, z);
        if (c == null) return null;
        String path = index.pathAt(c);
        if (path == null) return null;
        Path p = Path.of(path);
        if (!Files.isDirectory(p)) return null;
        if (!worldFor(p).equals(w)) return null; // cell grids overlap across worlds
        return getOrCreate(p);
    }

    /** World column -> grid cell, or null when in the gap between rooms. */
    public Cell cellFromWorld(int x, int z) {
        int pitch = cfg.pitch();
        int cx = Math.floorDiv(x - gridOffX, pitch);
        int cz = Math.floorDiv(z - gridOffZ, pitch);
        int lx = x - gridOffX - cx * pitch;
        int lz = z - gridOffZ - cz * pitch;
        if (lx >= cfg.roomSize() || lz >= cfg.roomSize()) return null;
        return new Cell(cx, cz);
    }

    public Location entryLocation(RoomState s) {
        return builder.entryLocation(s);
    }

    /** Flips a room's dock visibility and rebuilds just its dock wall. */
    public boolean toggleDock(RoomState s) {
        s.setShowDock(!s.showDock());
        builder.rebuildDock(s);
        return s.showDock();
    }

    /** Detaches watchers and drops state for rooms nobody has occupied recently. */
    public void unloadIdleRooms() {
        List<Player> players = new ArrayList<>(overworld.getPlayers());
        if (nether != null) players.addAll(nether.getPlayers());
        for (Player p : players) {
            RoomState s = roomAt(p.getWorld(), p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(), p.getLocation().getBlockZ());
            if (s != null) s.touch();
        }
        long now = System.currentTimeMillis();
        long timeout = cfg.unloadAfterSeconds() * 1000L;
        List<RoomState> idle = new ArrayList<>();
        for (RoomState s : byPath.values()) {
            if (s.mansion()) continue; // the mansion never unloads
            if (now - s.lastOccupiedMs() > timeout && !s.dir().equals(cfg.spawnDir())) {
                idle.add(s);
            }
        }
        for (RoomState s : idle) {
            watcher.unwatch(s.dir());
            dock.clearRoom(s.key());
            frames.clearRoom(s.key());
            byPath.remove(s.dir().toString());
            byKey.remove(s.key());
        }
    }
}
