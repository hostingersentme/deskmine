package dev.deskmine.mansion;

import dev.deskmine.theme.RoomTheme;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The mansion: where it stands (found once on high ground, then persisted)
 * and which well-known folder owns which room. Slots form a 3-wide, 2-deep
 * grid per floor; the ground-floor center-front slot is the entrance hall
 * (the spawn folder), the slot above it is the stair landing. Neighbouring
 * rooms share a wall, so slot origins are spaced (roomSize - 1) apart.
 */
public final class MansionPlan {

    /** Folders that get a mansion room, in assignment order (hall excluded). */
    public static final List<String> KNOWN_DIRS = List.of(
            "Documents", "Downloads", "Applications", "Pictures",
            "Music", "Movies", "Public", "Library");

    /** col 0..2 (west->east), row 0..1 (0 = back/north), floor 0..1. */
    public record Slot(int col, int row, int floor) {
        public long key() {
            return 0x4D00000000000000L | ((long) floor << 8) | ((long) row << 4) | col;
        }
    }

    public static final Slot HALL = new Slot(1, 1, 0);
    public static final Slot LANDING = new Slot(1, 1, 1);

    /** Assignment order: flank the hall first, then the back row, then upstairs. */
    private static final List<Slot> FILL_ORDER = List.of(
            new Slot(0, 1, 0), new Slot(2, 1, 0), new Slot(1, 0, 0),
            new Slot(0, 0, 0), new Slot(2, 0, 0),
            new Slot(0, 1, 1), new Slot(2, 1, 1), new Slot(1, 0, 1),
            new Slot(0, 0, 1), new Slot(2, 0, 1));

    /** Height from one floor level to the next (floor slab to floor slab). */
    public static final int FLOOR_STEP = 6;

    /** A structural doorway between two mansion rooms (or a room and the landing). */
    public record Door(int x, int z, int floorY, Material material, boolean alongX) {}

    /** A structural doorway plus the two slots it connects (for name signs). */
    public record DoorLink(Door door, Slot a, Slot b) {
        public Slot otherThan(Slot self) { return a.equals(self) ? b : a; }
    }

    private final int ax, ay, az; // world origin of slot (0,0) and ground-floor Y
    private final int m;          // mansion room size
    private final Map<String, Slot> slotByPath = new LinkedHashMap<>();
    private final Map<Long, String> pathBySlot = new HashMap<>();

    public MansionPlan(int ax, int ay, int az, int roomSize, Path hallDir, List<Path> dirs) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.m = roomSize;
        assign(HALL, hallDir);
        assignMissing(dirs);
    }

    /** Gives any yet-unplaced directories the next free slots (persist afterwards). */
    public void assignMissing(List<Path> dirs) {
        for (Path d : dirs) {
            String key = normalize(d);
            if (slotByPath.containsKey(key)) continue;
            Slot free = null;
            for (Slot s : FILL_ORDER) {
                if (!pathBySlot.containsKey(s.key())) {
                    free = s;
                    break;
                }
            }
            if (free == null) return;
            assign(free, d);
        }
    }

    private void assign(Slot s, Path dir) {
        String key = normalize(dir);
        slotByPath.put(key, s);
        pathBySlot.put(s.key(), key);
    }

    private static String normalize(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    // ------------------------------------------------------------- geometry

    public int roomSize() { return m; }
    public int baseX() { return ax; }
    public int baseZ() { return az; }
    public int groundY() { return ay; }
    public int footprintWidth() { return 3 * (m - 1) + 1; }
    public int footprintDepth() { return 2 * (m - 1) + 1; }

    public int originX(Slot s) { return ax + s.col() * (m - 1); }
    public int originZ(Slot s) { return az + s.row() * (m - 1); }
    public int floorY(Slot s) { return ay + s.floor() * FLOOR_STEP; }

    /** Top of the mansion airspace (roof level). */
    public int roofY() { return ay + 2 * FLOOR_STEP - 1; }

    // ------------------------------------------------------------- lookups

    public boolean isMansion(Path dir) { return slotByPath.containsKey(normalize(dir)); }

    public Slot slotFor(Path dir) { return slotByPath.get(normalize(dir)); }

    public String pathOf(Slot s) { return pathBySlot.get(s.key()); }

    public boolean occupied(Slot s) { return pathBySlot.containsKey(s.key()); }

    /** All directory paths with a mansion room, hall first. */
    public Collection<String> paths() { return slotByPath.keySet(); }

    private boolean inFootprint(int x, int z) {
        return x >= ax && x < ax + footprintWidth() && z >= az && z < az + footprintDepth();
    }

    /** Whether a coordinate is in the mansion's part of the world (vs the underground). */
    public boolean inMansionAirspace(int x, int y, int z) {
        return y >= ay - 2 && inFootprint(x, z);
    }

    /**
     * Mansion paths whose room could own this column. Shared walls belong to
     * two rooms and floors stack, so up to four candidates come back; callers
     * check each state's exact-coordinate target map.
     */
    public List<String> candidatePathsAt(int x, int z) {
        if (!inFootprint(x, z)) return List.of();
        List<String> out = new ArrayList<>(4);
        for (int c = 0; c < 3; c++) {
            int ox = ax + c * (m - 1);
            if (x < ox || x > ox + m - 1) continue;
            for (int r = 0; r < 2; r++) {
                int oz = az + r * (m - 1);
                if (z < oz || z > oz + m - 1) continue;
                for (int f = 0; f < 2; f++) {
                    String p = pathBySlot.get(new Slot(c, r, f).key());
                    if (p != null && !out.contains(p)) out.add(p);
                }
            }
        }
        return out;
    }

    /** Best-effort room at a position (for occupancy checks and /dm whereami). */
    public String pathAtPosition(int x, int y, int z) {
        if (!inFootprint(x, z)) return null;
        int f = y >= ay + FLOOR_STEP ? 1 : 0;
        int c = Math.min(2, Math.max(0, (x - ax) / (m - 1)));
        int r = Math.min(1, Math.max(0, (z - az) / (m - 1)));
        return pathBySlot.get(new Slot(c, r, f).key());
    }

    // --------------------------------------------------------------- doors

    /** The five possible doorway pairs per floor (center = hall / landing). */
    private static final int[][] PAIRS = {
            {0, 1, 1, 1}, // west room <-> center
            {2, 1, 1, 1}, // east room <-> center
            {1, 0, 1, 1}, // back-center <-> center
            {0, 0, 0, 1}, // back-west  <-> west
            {2, 0, 2, 1}, // back-east  <-> east
    };

    private boolean exists(Slot s) {
        if (s.col() == 1 && s.row() == 1) return true; // hall / landing always exist
        return occupied(s);
    }

    /** Structural doorways on this slot's walls (both rooms re-place them on rebuild). */
    public List<Door> doorsTouching(Slot slot) {
        return doorLinksTouching(slot).stream().map(DoorLink::door).toList();
    }

    /** Direction pointing from a shared doorway into {@code self}'s room. */
    public static BlockFace inwardFace(Slot self, Slot other, Door d) {
        if (d.alongX()) { // door in the E-W wall between north/south neighbours
            return self.row() == 0 ? BlockFace.NORTH : BlockFace.SOUTH;
        }
        return self.col() < other.col() ? BlockFace.WEST : BlockFace.EAST;
    }

    /** Like {@link #doorsTouching}, with the two connected slots attached. */
    public List<DoorLink> doorLinksTouching(Slot slot) {
        List<DoorLink> out = new ArrayList<>();
        int f = slot.floor();
        for (int[] p : PAIRS) {
            Slot a = new Slot(p[0], p[1], f);
            Slot b = new Slot(p[2], p[3], f);
            if (!a.equals(slot) && !b.equals(slot)) continue;
            if (!exists(a) || !exists(b)) continue;
            // The themed side is the room farther from the center.
            Slot themed = (b.col() == 1 && b.row() == 1) ? a
                    : (a.row() == 0 ? a : b);
            String themedPath = pathOf(themed);
            Material door = themedPath == null ? Material.OAK_DOOR
                    : RoomTheme.forName(Path.of(themedPath).getFileName().toString()).door();
            if (door == Material.IRON_DOOR) door = Material.OAK_DOOR; // must open by hand
            int x, z;
            boolean alongX;
            if (a.row() != b.row()) { // north-south neighbours: door in the E-W wall
                x = ax + a.col() * (m - 1) + m / 2;
                z = az + (m - 1);
                alongX = true;
            } else {                  // east-west neighbours: door in the N-S wall
                x = ax + Math.max(a.col(), b.col()) * (m - 1);
                z = az + a.row() * (m - 1) + m / 2;
                alongX = false;
            }
            out.add(new DoorLink(new Door(x, z, ay + f * FLOOR_STEP, door, alongX), a, b));
        }
        return out;
    }

    // --------------------------------------------------------- persistence

    public void save(Path file, Logger log) {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Deskmine mansion: anchor + slot assignments");
            lines.add("A\t" + ax + "\t" + ay + "\t" + az + "\t" + m);
            for (Map.Entry<String, Slot> e : slotByPath.entrySet()) {
                Slot s = e.getValue();
                lines.add("S\t" + s.col() + "\t" + s.row() + "\t" + s.floor() + "\t"
                        + URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("Failed to save mansion plan: " + e.getMessage());
        }
    }

    /** Loads a persisted plan; null if none saved (or unreadable / size changed). */
    public static MansionPlan load(Path file, int expectedRoomSize, Logger log) {
        if (!Files.exists(file)) return null;
        try {
            Integer ax = null, ay = null, az = null, m = null;
            List<int[]> slots = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] t = line.split("\t");
                if (t[0].equals("A") && t.length == 5) {
                    ax = Integer.parseInt(t[1]);
                    ay = Integer.parseInt(t[2]);
                    az = Integer.parseInt(t[3]);
                    m = Integer.parseInt(t[4]);
                } else if (t[0].equals("S") && t.length == 5) {
                    slots.add(new int[]{Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                            Integer.parseInt(t[3])});
                    paths.add(URLDecoder.decode(t[4], StandardCharsets.UTF_8));
                }
            }
            if (ax == null || m == null || m != expectedRoomSize) return null;
            MansionPlan plan = new MansionPlan(ax, ay, az, m);
            for (int i = 0; i < slots.size(); i++) {
                int[] s = slots.get(i);
                plan.assign(new Slot(s[0], s[1], s[2]), Path.of(paths.get(i)));
            }
            return plan;
        } catch (Exception e) {
            log.warning("Failed to load mansion plan: " + e.getMessage());
            return null;
        }
    }

    private MansionPlan(int ax, int ay, int az, int m) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.m = m;
    }
}
