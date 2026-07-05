package dev.deskmine.room;

import dev.deskmine.mapping.Cell;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory state of a materialized room: which directory it shows, where it
 * sits (mansion slot or underground grid cell), its built size, per-room
 * settings (dock shown/hidden), and which blocks are interactive. This is the
 * coordinate -> path half of the bidirectional mapping at block granularity.
 */
public final class RoomState {

    public enum TargetType {
        FOLDER, PARENT, SYMLINK, FILE_INFO, DOCK, MENU,
        TOGGLE_HIDDEN, TOGGLE_DOCK, TOGGLE_FINDER
    }

    public record Target(TargetType type, String path, String label) {}

    private final Path dir;
    private final Cell cell;      // grid rooms only; null for mansion rooms
    private final long key;       // cell key, or the synthetic mansion slot key
    private final World world;    // overworld, or the nether for network rooms
    private final Zone zone;
    private final int originX;
    private final int originZ;
    private final int floorY;
    private final boolean mansion;
    private final int fixedSize;  // > 0 forces the built size (mansion rooms)
    private int size;             // set when built
    private boolean showDock = false; // default hidden so mobs can't trip plates
    private final Map<Long, Target> targets = new HashMap<>();
    private volatile long lastOccupiedMs = System.currentTimeMillis();

    /** A grid room (underground, sky, or nether). */
    public RoomState(Path dir, Cell cell, World world, Zone zone,
                     int originX, int originZ, int floorY) {
        this(dir, cell, cell.key(), world, zone, originX, originZ, floorY, false, 0);
    }

    /** A mansion room occupying a plan slot. */
    public static RoomState mansion(Path dir, long slotKey, World world,
                                    int originX, int originZ, int floorY, int fixedSize) {
        return new RoomState(dir, null, slotKey, world, Zone.MANSION,
                originX, originZ, floorY, true, fixedSize);
    }

    private RoomState(Path dir, Cell cell, long key, World world, Zone zone,
                      int originX, int originZ, int floorY, boolean mansion, int fixedSize) {
        this.dir = dir;
        this.cell = cell;
        this.key = key;
        this.world = world;
        this.zone = zone;
        this.originX = originX;
        this.originZ = originZ;
        this.floorY = floorY;
        this.mansion = mansion;
        this.fixedSize = fixedSize;
    }

    public Path dir() { return dir; }
    public Cell cell() { return cell; }
    public long key() { return key; }
    public World world() { return world; }
    public Zone zone() { return zone; }
    public int originX() { return originX; }
    public int originZ() { return originZ; }
    public int floorY() { return floorY; }
    public boolean mansion() { return mansion; }
    public int fixedSize() { return fixedSize; }
    public int size() { return size; }
    public void setSize(int size) { this.size = size; }
    public boolean showDock() { return showDock; }
    public void setShowDock(boolean showDock) { this.showDock = showDock; }

    private static long posKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) (y + 64) & 0xFFFL);
    }

    public void clearTargets() { targets.clear(); }

    public void addTarget(int x, int y, int z, Target t) {
        targets.put(posKey(x, y, z), t);
    }

    public Target target(int x, int y, int z) {
        return targets.get(posKey(x, y, z));
    }

    public void touch() { lastOccupiedMs = System.currentTimeMillis(); }
    public long lastOccupiedMs() { return lastOccupiedMs; }
}
