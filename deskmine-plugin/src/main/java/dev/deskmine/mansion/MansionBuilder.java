package dev.deskmine.mansion;

import dev.deskmine.mansion.MansionPlan.Slot;
import net.kyori.adventure.text.Component;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.util.BoundingBox;

import java.nio.file.Path;

/**
 * Builds the parts of the mansion that no single directory-room owns: the
 * hillside site prep, the foundation, the stair landing above the hall, the
 * sealed lit attic over unused upper slots, the front porch, and the nether
 * portal beside it (the way to the Network rooms). Room interiors (and the
 * doors on their walls) are built by RoomBuilder.
 */
public final class MansionBuilder {

    private final World world;
    private final MansionPlan plan;

    public MansionBuilder(World world, MansionPlan plan) {
        this.world = world;
        this.plan = plan;
    }

    /** Highest dry column near the origin: {x, floorY, z} for the mansion site. */
    public static int[] findHighGround(World world) {
        int bestY = Integer.MIN_VALUE, bx = 0, bz = 0;
        for (int x = -160; x <= 160; x += 16) {
            for (int z = -160; z <= 160; z += 16) {
                Block top = world.getHighestBlockAt(x, z);
                if (top.isLiquid()) continue;
                if (top.getY() > bestY) {
                    bestY = top.getY();
                    bx = x;
                    bz = z;
                }
            }
        }
        if (bestY == Integer.MIN_VALUE) {
            bx = 0;
            bz = 0;
            bestY = world.getHighestBlockYAt(0, 0);
        }
        return new int[]{bx, Math.min(bestY + 1, 220), bz};
    }

    /** Site prep + shared structure. Idempotent; run before rooms materialize. */
    public void buildFrame() {
        int ax = plan.baseX(), ay = plan.groundY(), az = plan.baseZ();
        int w = plan.footprintWidth(), d = plan.footprintDepth();

        // Airspace: carve the hill out of the mansion's volume.
        for (int x = ax - 1; x <= ax + w; x++) {
            for (int z = az - 1; z <= az + d; z++) {
                for (int y = ay; y <= ay + 16; y++) {
                    set(x, y, z, Material.AIR);
                }
            }
        }

        // Foundation: fill from below the floor down to solid ground.
        for (int x = ax; x < ax + w; x++) {
            for (int z = az; z < az + d; z++) {
                for (int y = ay - 1; y >= ay - 24; y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType().isSolid()) break;
                    b.setType(Material.STONE_BRICKS, false);
                }
            }
        }

        buildLanding();
        buildAttic();
        buildRoof();
        buildPorch();
    }

    private boolean exterior(int x, int z) {
        return x == plan.baseX() || x == plan.baseX() + plan.footprintWidth() - 1
                || z == plan.baseZ() || z == plan.baseZ() + plan.footprintDepth() - 1;
    }

    /** The stair landing above the hall, with doors to the occupied upper rooms. */
    private void buildLanding() {
        int m = plan.roomSize();
        int ox = plan.originX(MansionPlan.LANDING);
        int oz = plan.originZ(MansionPlan.LANDING);
        int fy = plan.floorY(MansionPlan.LANDING);
        buildBoxShell(ox, oz, fy, m, Material.DARK_OAK_PLANKS, Material.STONE_BRICKS, true);
        // Stairwell opening in the landing floor (the hall staircase arrives here).
        for (int z = oz + 3; z <= oz + 5; z++) {
            set(ox + m - 2, fy, z, Material.AIR);
        }
        for (MansionPlan.DoorLink link : plan.doorLinksTouching(MansionPlan.LANDING)) {
            placeDoor(link.door());
            // Name sign on the landing side of each door (request #3).
            Slot other = link.otherThan(MansionPlan.LANDING);
            String path = plan.pathOf(other);
            if (path == null) continue;
            Path p = Path.of(path);
            String name = p.getFileName() == null ? path : p.getFileName().toString();
            BlockFace in = MansionPlan.inwardFace(MansionPlan.LANDING, other, link.door());
            placeNameSign(link.door().x() + in.getModX(), link.door().floorY() + 3,
                    link.door().z() + in.getModZ(), in, name);
        }
    }

    private void placeNameSign(int x, int y, int z, BlockFace facing, String name) {
        if (name.length() > 11) name = name.substring(0, 10) + "…";
        WallSign data = (WallSign) Material.OAK_WALL_SIGN.createBlockData();
        data.setFacing(facing);
        Block block = world.getBlockAt(x, y, z);
        block.setBlockData(data, false);
        BlockState st = block.getState();
        if (st instanceof org.bukkit.block.Sign sign) {
            SignSide side = sign.getSide(Side.FRONT);
            side.line(0, Component.text("[ " + name + " ]"));
            sign.setWaxed(true);
            sign.update(true, false);
        }
    }

    /** Unused upper slots become sealed, lit voids so nothing can spawn there. */
    private void buildAttic() {
        int m = plan.roomSize();
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 2; r++) {
                Slot s = new Slot(c, r, 1);
                if (s.equals(MansionPlan.LANDING) || plan.occupied(s)) continue;
                Slot below = new Slot(c, r, 0);
                if (!below.equals(MansionPlan.HALL) && !plan.occupied(below)) continue;
                buildBoxShell(plan.originX(s), plan.originZ(s), plan.floorY(s), m,
                        Material.SMOOTH_STONE, Material.STONE_BRICKS, false);
            }
        }
    }

    /** Floor (lantern-dotted), perimeter walls, ceiling. Exterior walls wear the facade. */
    private void buildBoxShell(int ox, int oz, int fy, int m,
                               Material floor, Material wall, boolean windows) {
        for (int lx = 0; lx < m; lx++) {
            for (int lz = 0; lz < m; lz++) {
                boolean perimeter = lx == 0 || lz == 0 || lx == m - 1 || lz == m - 1;
                boolean lantern = lx % 5 == 2 && lz % 5 == 2;
                set(ox + lx, fy, oz + lz, lantern ? Material.SEA_LANTERN : floor);
                int wx = ox + lx, wz = oz + lz;
                boolean facade = perimeter && exterior(wx, wz);
                boolean pillar = facade
                        && (wx - plan.baseX()) % (m - 1) == 0
                        && (wz - plan.baseZ()) % (m - 1) == 0;
                for (int dy = 1; dy <= 4; dy++) {
                    Material mat;
                    if (!perimeter) mat = Material.AIR;
                    else if (pillar) mat = Material.DARK_OAK_LOG;
                    else if (facade) mat = dy == 3 && windows ? Material.GLASS
                            : Material.DARK_OAK_PLANKS;
                    else mat = dy == 3 && windows ? Material.GLASS : wall;
                    set(wx, fy + dy, wz, mat);
                }
                set(ox + lx, fy + 5, oz + lz,
                        lantern ? Material.SEA_LANTERN : Material.SMOOTH_STONE);
            }
        }
    }

    /** A low hip roof: an overhanging stair ring, two more rings, a flat deck. */
    private void buildRoof() {
        int ax = plan.baseX(), az = plan.baseZ();
        int w = plan.footprintWidth(), d = plan.footprintDepth();
        int ry = plan.groundY() + 2 * MansionPlan.FLOOR_STEP; // just above the top ceiling
        for (int i = 0; i < 3; i++) {
            int k = i - 1; // ring 0 overhangs the walls by one block
            int y = ry + i;
            int x0 = ax + k, x1 = ax + w - 1 - k;
            int z0 = az + k, z1 = az + d - 1 - k;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean n = z == z0, s = z == z1, wst = x == x0, e = x == x1;
                    if (!(n || s || wst || e)) continue;
                    if ((n || s) && (wst || e)) { // corner
                        set(x, y, z, Material.DARK_OAK_PLANKS);
                        continue;
                    }
                    Stairs st = (Stairs) Material.DARK_OAK_STAIRS.createBlockData();
                    st.setFacing(n ? BlockFace.SOUTH : s ? BlockFace.NORTH
                            : wst ? BlockFace.EAST : BlockFace.WEST);
                    world.getBlockAt(x, y, z).setBlockData(st, false);
                }
            }
        }
        for (int x = ax + 2; x <= ax + w - 3; x++) { // flat deck flush with ring 3
            for (int z = az + 2; z <= az + d - 3; z++) {
                set(x, ry + 2, z, Material.DARK_OAK_PLANKS);
            }
        }
    }

    /** Front platform and steps from the hall doors down to the terrain. */
    private void buildPorch() {
        int m = plan.roomSize();
        int hx = plan.originX(MansionPlan.HALL);
        int ay = plan.groundY();
        int z0 = plan.baseZ() + plan.footprintDepth(); // first row south of the wall
        int cx = hx + m / 2;

        for (int x = cx - 2; x <= cx + 3; x++) {
            set(x, ay, z0, Material.STONE_BRICKS); // landing pad at floor level
            for (int y = ay + 1; y <= ay + 3; y++) set(x, y, z0, Material.AIR);
        }
        set(cx - 2, ay + 1, z0, Material.LANTERN); // flank the front doors
        set(cx + 3, ay + 1, z0, Material.LANTERN);
        for (int k = 1; k <= 14; k++) {
            int y = ay - k;
            int z = z0 + k;
            boolean reachedGround = true;
            for (int x = cx - 2; x <= cx + 3; x++) {
                int terrain = world.getHighestBlockYAt(x, z);
                if (terrain < y) reachedGround = false;
            }
            if (reachedGround || y <= world.getMinHeight() + 5) break;
            for (int x = cx - 2; x <= cx + 3; x++) {
                Stairs st = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
                st.setFacing(BlockFace.NORTH); // ascending toward the mansion
                world.getBlockAt(x, y, z).setBlockData(st, false);
                set(x, y - 1, z, Material.STONE_BRICKS);
                for (int yy = y + 1; yy <= y + 3; yy++) set(x, yy, z, Material.AIR);
            }
        }
    }

    /**
     * The nether portal beside the porch (request #5): step through to visit
     * the Network rooms. Idempotent; call after buildFrame when the nether
     * dimension is available.
     */
    public void buildNetherPortal() {
        int[] a = netherPortalAnchor(plan); // {px, ay, zp}
        int px = a[0], ay = a[1], zp = a[2];
        int z0 = zp - 1;

        // Platform with clear air above, supported down to solid ground.
        for (int x = px - 1; x <= px + 4; x++) {
            for (int z = z0; z <= z0 + 2; z++) {
                set(x, ay, z, Material.STONE_BRICKS);
                for (int y = ay + 1; y <= ay + 5; y++) set(x, y, z, Material.AIR);
                for (int y = ay - 1; y >= ay - 24; y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType().isSolid()) break;
                    b.setType(Material.STONE_BRICKS, false);
                }
            }
        }

        // Obsidian frame in the X plane, portal blocks inside.
        for (int x = px; x <= px + 3; x++) {
            set(x, ay, zp, Material.OBSIDIAN);
            set(x, ay + 4, zp, Material.OBSIDIAN);
        }
        for (int dy = 1; dy <= 3; dy++) {
            set(px, ay + dy, zp, Material.OBSIDIAN);
            set(px + 3, ay + dy, zp, Material.OBSIDIAN);
        }
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(Axis.X);
        for (int x = px + 1; x <= px + 2; x++) {
            for (int dy = 1; dy <= 3; dy++) {
                world.getBlockAt(x, ay + dy, zp).setBlockData(portal, false);
            }
        }
        set(px - 1, ay + 1, z0, Material.OAK_SIGN);
        Block signBlock = world.getBlockAt(px - 1, ay + 1, z0);
        org.bukkit.block.data.type.Sign signData =
                (org.bukkit.block.data.type.Sign) Material.OAK_SIGN.createBlockData();
        ((Rotatable) signData).setRotation(BlockFace.SOUTH);
        signBlock.setBlockData(signData, false);
        BlockState st = signBlock.getState();
        if (st instanceof org.bukkit.block.Sign sign) {
            SignSide side = sign.getSide(Side.FRONT);
            side.line(0, Component.text("[ Network ]"));
            side.line(1, Component.text("nether portal"));
            side.line(2, Component.text("step through"));
            sign.setWaxed(true);
            sign.update(true, false);
        }
        set(px - 1, ay + 1, z0 + 2, Material.LANTERN);
        set(px + 4, ay + 1, z0 + 2, Material.LANTERN);
    }

    /** Portal geometry: {frame west X, platform floor Y, portal plane Z}. */
    public static int[] netherPortalAnchor(MansionPlan plan) {
        int m = plan.roomSize();
        int cx = plan.originX(MansionPlan.HALL) + m / 2;
        int z0 = plan.baseZ() + plan.footprintDepth();
        return new int[]{cx + 5, plan.groundY(), z0 + 1};
    }

    /** The volume of the porch portal's inner (portal-block) area. */
    public static BoundingBox netherPortalBox(MansionPlan plan) {
        int[] a = netherPortalAnchor(plan);
        return new BoundingBox(a[0] + 1, a[1] + 1, a[2],
                a[0] + 3, a[1] + 4, a[2] + 1);
    }

    private void placeDoor(MansionPlan.Door dd) {
        BlockFace facing = dd.alongX() ? BlockFace.NORTH : BlockFace.EAST;
        Door bottom = (Door) dd.material().createBlockData();
        bottom.setFacing(facing);
        bottom.setHalf(Bisected.Half.BOTTOM);
        Door top = (Door) dd.material().createBlockData();
        top.setFacing(facing);
        top.setHalf(Bisected.Half.TOP);
        world.getBlockAt(dd.x(), dd.floorY() + 1, dd.z()).setBlockData(bottom, false);
        world.getBlockAt(dd.x(), dd.floorY() + 2, dd.z()).setBlockData(top, false);
    }

    private void set(int x, int y, int z, Material mat) {
        world.getBlockAt(x, y, z).setType(mat, false);
    }
}
