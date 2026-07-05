package dev.deskmine.room;

import dev.deskmine.DeskmineConfig;
import dev.deskmine.dock.DockItem;
import dev.deskmine.dock.DockService;
import dev.deskmine.frames.FrameService;
import dev.deskmine.fs.DirEntry;
import dev.deskmine.fs.DirectoryScanner.ScanResult;
import dev.deskmine.fs.FileCategory;
import dev.deskmine.fs.ZoneResolver;
import dev.deskmine.mansion.MansionPlan;
import dev.deskmine.menubar.MenuBarService;
import dev.deskmine.menubar.MenuSnapshot;
import dev.deskmine.theme.RoomTheme;
import net.kyori.adventure.text.Component;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Places the actual blocks of a room — mansion, underground, sky (iCloud),
 * or nether (network).
 *
 * Layout (identical in every room): the entry is the south-wall center, menu
 * chests line the entry wall on the obsidian strip, the Dock runs along the
 * west wall (hidden until its lever is flipped), subfolder doors sit on the
 * north and east walls, and files are item frames on their category blocks —
 * book & quill for text, book for other files, the app icon on a pedestal
 * for .app bundles — each with a filename sign.
 *
 * Mansion rooms are fixed-size, share walls, and get their structural
 * doorways (now with name signs) re-placed on every rebuild; the hall adds
 * the front double door, the staircase to the landing, and the special
 * fixtures: the Macintosh HD and iCloud doors flanking the entrance, and the
 * Finder-menus lever. Underground rooms are carved out of the terrain,
 * nether rooms out of the netherrack, and sky rooms float at cloud height.
 * Rebuilds are idempotent.
 */
public final class RoomBuilder {

    private static final int WALL_TOP = 4;  // walls span floorY+1 .. floorY+4
    private static final int CEIL = 5;      // ceiling slab height
    private static final int CLEAR_TOP = 6; // cleared airspace above the floor

    /** iCloud rooms: white platforms in the sky (spec 5.1's "original" look). */
    private static final RoomTheme CLOUD_THEME = new RoomTheme(
            Material.WHITE_WOOL, Material.WHITE_CONCRETE,
            Material.WHITE_STAINED_GLASS, Material.BIRCH_DOOR);

    /** Network rooms: carved out of the nether. */
    private static final RoomTheme NETHER_THEME = new RoomTheme(
            Material.POLISHED_BLACKSTONE, Material.NETHER_BRICKS,
            Material.ORANGE_STAINED_GLASS, Material.CRIMSON_DOOR);

    /** The "/" room: Macintosh HD, the drive itself. */
    private static final RoomTheme DRIVE_THEME = new RoomTheme(
            Material.POLISHED_DIORITE, Material.POLISHED_DEEPSLATE,
            Material.GLASS, Material.COPPER_DOOR);

    private record DoorSlot(int lx, int lz, BlockFace inward) {}

    private final DeskmineConfig cfg;
    private final DockService dock;
    private final MenuBarService menuBar;
    private final FrameService frames;
    private final MansionPlan plan;
    private final ZoneResolver zones;
    private final Map<Integer, List<DoorSlot>> doorCache = new HashMap<>();
    private final Map<Integer, List<int[]>> fileCache = new HashMap<>();

    /** World the current build call is placing blocks in (main thread only). */
    private World w;

    public RoomBuilder(DeskmineConfig cfg, DockService dock, MenuBarService menuBar,
                       FrameService frames, MansionPlan plan, ZoneResolver zones) {
        this.cfg = cfg;
        this.dock = dock;
        this.menuBar = menuBar;
        this.frames = frames;
        this.plan = plan;
        this.zones = zones;
    }

    /** Caps used when scanning: what the biggest room can physically hold. */
    public int maxDoorCapacity() { return doorSlots(cfg.roomSize()).size(); }
    public int maxFileCapacity() { return fileSlots(cfg.roomSize()).size() - 1; }

    private List<DoorSlot> doorSlots(int s) {
        return doorCache.computeIfAbsent(s, RoomBuilder::computeDoorSlots);
    }

    private List<int[]> fileSlots(int s) {
        return fileCache.computeIfAbsent(s, RoomBuilder::computeFileSlots);
    }

    private int sizeOf(RoomState st) {
        if (st.size() > 0) return st.size();
        return st.mansion() ? st.fixedSize() : cfg.roomSize();
    }

    /** Where players arrive: just inside the south (entry) wall, facing the room. */
    public Location entryLocation(RoomState st) {
        int s = sizeOf(st);
        double x = st.originX() + s / 2 + 0.5;
        double z = st.originZ() + s - 2 + 0.5;
        return new Location(st.world(), x, st.floorY() + 1, z, 180f, 0f); // facing north
    }

    /** The room's look: zone themes override the per-folder-name themes. */
    private RoomTheme themeFor(RoomState st) {
        if (st.zone() == Zone.SKY) return CLOUD_THEME;
        if (st.zone() == Zone.NETHER) return NETHER_THEME;
        if (st.dir().getNameCount() == 0) return DRIVE_THEME; // Macintosh HD
        var dirName = st.dir().getFileName();
        return RoomTheme.forName(dirName == null ? null : dirName.toString());
    }

    /** Clears the plot and rebuilds shell + contents; registers interact targets. */
    public void buildRoom(RoomState state, ScanResult scan, boolean hasParent, boolean isSpawnRoom) {
        this.w = state.world();
        state.clearTargets();
        int s = chooseSize(state, scan, isSpawnRoom);
        state.setSize(s);
        int fy = state.floorY();
        int ox = state.originX();
        int oz = state.originZ();

        RoomTheme theme = themeFor(state);
        boolean hasPortal = state.dir().equals(zones.networkRoot())
                && state.zone() == Zone.NETHER;

        clearEntities(state, ox, oz, fy);
        clearPlot(state, ox, oz, s, fy);
        buildShell(ox, oz, s, fy, theme, state.mansion());
        buildChrome(ox, oz, s, fy);
        buildMenuBar(state, menuBar.current());
        buildDockWall(state);
        buildAppleMarker(state);

        int doorSlotOffset = 0;
        boolean hasStaircase = false;
        if (state.mansion()) {
            MansionPlan.Slot slot = plan.slotFor(state.dir());
            if (slot != null) {
                for (MansionPlan.DoorLink link : plan.doorLinksTouching(slot)) {
                    MansionPlan.Door d = link.door();
                    placeDoor(d.x(), d.z(), d.floorY(),
                            d.alongX() ? BlockFace.NORTH : BlockFace.EAST, d.material());
                    // Name sign above the door, on this room's side (request #3).
                    MansionPlan.Slot other = link.otherThan(slot);
                    BlockFace in = MansionPlan.inwardFace(slot, other, d);
                    placeWallSign(d.x() + in.getModX(), d.floorY() + 3, d.z() + in.getModZ(),
                            in, "[ " + trim(slotLabel(other), 11) + " ]", "", "", "");
                }
                if (slot.equals(MansionPlan.HALL)) {
                    buildFrontDoors(ox, oz, s, fy);
                    buildHallStaircase(ox, oz, s, fy);
                    hasStaircase = true;
                }
            }
            if (hasParent) doorSlotOffset = buildMansionParentDoor(state, ox, oz, s, fy);
        } else if (hasParent) {
            buildBackDoor(state, ox, oz, s, fy);
        } else if (!isSpawnRoom) {
            buildHomeDoor(state, ox, oz, s, fy); // rooms with no parent (e.g. "/")
        }
        if (isSpawnRoom) {
            buildHiddenLever(state, ox, oz, s, fy);
            buildFinderLever(state, ox, oz, s, fy);
            buildSpecialDoors(state, ox, oz, s, fy);
        }
        if (hasPortal) buildReturnPortal(ox, oz, fy);
        if (state.mansion()) buildFurniture(ox, oz, s, fy);
        buildRoomLabel(state, ox, oz, s, fy, scan);
        buildFolderDoors(state, ox, oz, s, fy, scan, doorSlotOffset, hasStaircase, hasPortal);
        buildFileBlocks(state, ox, oz, s, fy, scan);
        buildCenterNotices(ox, oz, s, fy, scan);
    }

    /** Sign label for the mansion room (or landing) behind a structural door. */
    private String slotLabel(MansionPlan.Slot slot) {
        if (slot.equals(MansionPlan.LANDING)) return "landing";
        String path = plan.pathOf(slot);
        return path == null ? "room" : zones.displayName(Path.of(path));
    }

    /** Smallest size (spec: floor area scales with item count) that fits everything. */
    private int chooseSize(RoomState state, ScanResult scan, boolean isSpawnRoom) {
        if (state.mansion()) return state.fixedSize();
        if (isSpawnRoom) return cfg.roomSize();
        int dirs = scan.dirs().size();
        int files = scan.files().size() + (scan.moreFiles() > 0 ? 1 : 0);
        for (int s = cfg.minRoomSize(); s <= cfg.roomSize(); s += 2) {
            if (fileSlots(s).size() - 1 >= files && doorSlots(s).size() >= dirs) return s;
        }
        return cfg.roomSize();
    }

    // ---------------------------------------------------------------- shell

    /** Removes this room's tracked entities and any tagged strays in its plot. */
    private void clearEntities(RoomState state, int ox, int oz, int fy) {
        dock.clearRoom(state.key());
        frames.clearRoom(state.key());
        int extent = state.mansion() ? state.fixedSize() : cfg.roomSize();
        BoundingBox box = new BoundingBox(ox, fy, oz,
                ox + extent, fy + CLEAR_TOP + 1, oz + extent);
        dock.sweepRegion(w, box);
        frames.sweepRegion(w, box);
    }

    /**
     * Clears the plot. Grid rooms process the full max-size footprint: the
     * room's own columns become air, the rest is packed solid (stone below
     * ground, netherrack in the nether) so a shrunken room leaves no dark
     * cavity for mobs to spawn in. Sky rooms float, so their surround is air.
     */
    private void clearPlot(RoomState state, int ox, int oz, int s, int fy) {
        int extent = state.mansion() ? s : cfg.roomSize();
        int top = state.mansion() ? CEIL : CLEAR_TOP;
        Material packing = switch (state.zone()) {
            case SKY -> Material.AIR;
            case NETHER -> Material.NETHERRACK;
            default -> Material.STONE;
        };
        for (int lx = 0; lx < extent; lx++) {
            for (int lz = 0; lz < extent; lz++) {
                Material fill = (lx < s && lz < s) ? Material.AIR : packing;
                for (int dy = 0; dy <= top; dy++) {
                    set(ox + lx, fy + dy, oz + lz, fill);
                }
            }
        }
    }

    /**
     * Floor, walls, and a lit sealed ceiling. Mansion walls facing outdoors
     * wear a consistent facade — dark-oak planks with log pillars at the bay
     * junctions and a glass window band — so room themes only show inside.
     */
    private void buildShell(int ox, int oz, int s, int fy, RoomTheme theme, boolean mansion) {
        for (int lx = 0; lx < s; lx++) {
            for (int lz = 0; lz < s; lz++) {
                boolean perimeter = lx == 0 || lz == 0 || lx == s - 1 || lz == s - 1;
                set(ox + lx, fy, oz + lz, theme.floor());
                if (perimeter) {
                    int wx = ox + lx, wz = oz + lz;
                    boolean facade = mansion && mansionExterior(wx, wz);
                    boolean pillar = facade
                            && (wx - plan.baseX()) % (plan.roomSize() - 1) == 0
                            && (wz - plan.baseZ()) % (plan.roomSize() - 1) == 0;
                    for (int dy = 1; dy <= WALL_TOP; dy++) {
                        Material mat;
                        if (pillar) mat = Material.DARK_OAK_LOG;
                        else if (facade) mat = dy == 3 ? Material.GLASS : Material.DARK_OAK_PLANKS;
                        else mat = dy == 3 ? (mansion ? theme.band() : theme.wall()) : theme.wall();
                        set(wx, fy + dy, wz, mat);
                    }
                }
                boolean lantern = lx % 5 == 2 && lz % 5 == 2;
                set(ox + lx, fy + CEIL, oz + lz,
                        lantern ? Material.SEA_LANTERN
                                : mansion ? Material.SMOOTH_STONE : theme.wall());
            }
        }
    }

    /** Whether a column sits on the mansion's outer perimeter. */
    private boolean mansionExterior(int wx, int wz) {
        return wx == plan.baseX() || wx == plan.baseX() + plan.footprintWidth() - 1
                || wz == plan.baseZ() || wz == plan.baseZ() + plan.footprintDepth() - 1;
    }

    /**
     * The system-chrome strips: obsidian along the entry (south) wall for the
     * menu-bar chests, polished andesite along the west wall for the Dock.
     */
    private void buildChrome(int ox, int oz, int s, int fy) {
        int c = s / 2;
        for (int lx = 2; lx <= s - 3; lx++) {
            if (Math.abs(lx - c) <= 3) continue; // keep the entry clear
            set(ox + lx, fy, oz + s - 2, Material.OBSIDIAN);
        }
        for (int lz = 2; lz <= s - 3; lz++) {
            set(ox + 1, fy, oz + lz, Material.POLISHED_ANDESITE);
        }
    }

    /** Chest positions along the entry wall, west to east, skipping the doorway. */
    private List<Integer> menuSpots(int s) {
        int c = s / 2;
        List<Integer> spots = new ArrayList<>();
        for (int lx = 2; lx <= s - 3; lx += 2) {
            if (Math.abs(lx - c) > 3) spots.add(lx);
        }
        return spots;
    }

    /**
     * Menu bar (spec 6): chests along the entry wall, left-to-right in real
     * menu order. The Apple menu is the ender chest (filled per player by
     * MenuBarService); app menus are regular chests that refill on app switch.
     * Clicking an item inside a chest performs that menu action for real.
     */
    public void buildMenuBar(RoomState state, MenuSnapshot snap) {
        this.w = state.world();
        int s = sizeOf(state);
        int fy = state.floorY();
        int ox = state.originX();
        int oz = state.originZ();
        int z = oz + s - 2;
        int c = s / 2;
        List<Integer> spots = menuSpots(s);
        if (spots.size() < 2) return;

        for (int lx = 2; lx <= s - 3; lx++) {
            if (Math.abs(lx - c) <= 3) continue;
            set(ox + lx, fy + 1, z, Material.AIR); // clear old chests + status sign
        }
        set(ox + spots.get(0), fy + 1, z, Material.ENDER_CHEST); // Apple menu (spec 6.1)
        state.addTarget(ox + spots.get(0), fy + 1, z, new RoomState.Target(
                RoomState.TargetType.MENU, "", "Apple"));

        // No menu data yet: say why on the strip instead of leaving it bare.
        if (snap.menus().isEmpty()) {
            if (menuBar.permissionDenied()) {
                placeStandingSign(ox + spots.get(1), fy + 1, z,
                        "menu bar needs", "Accessibility", "permission for", "your terminal");
            } else if (!snap.appName().isBlank()) {
                placeStandingSign(ox + spots.get(1), fy + 1, z,
                        signText(snap.appName()), "has no", "readable", "menu bar");
            } else {
                placeStandingSign(ox + spots.get(1), fy + 1, z,
                        "menu bar:", "waiting for", "app menus…", "");
            }
            return;
        }

        int i = 1;
        for (MenuSnapshot.MenuEntry menu : snap.appMenus()) {
            if (i >= spots.size()) break; // small rooms show a truncated bar
            int x = ox + spots.get(i++);
            set(x, fy + 1, z, Material.CHEST);
            if (w.getBlockAt(x, fy + 1, z).getState()
                    instanceof org.bukkit.block.Chest chest) {
                chest.customName(Component.text(menu.title()));
                chest.update(true, false);
            }
            if (w.getBlockAt(x, fy + 1, z).getState()
                    instanceof org.bukkit.block.Chest chest) {
                chest.getBlockInventory().setContents(MenuBarService.menuStacks(menu));
            }
            state.addTarget(x, fy + 1, z, new RoomState.Target(
                    RoomState.TargetType.MENU, "", menu.title()));
        }
    }

    private String signText(String text) {
        return text.length() <= 15 ? text : text.substring(0, 14) + "…";
    }

    /** A floating apple over the Apple-menu ender chest (pickup-able, respawns). */
    private void buildAppleMarker(RoomState state) {
        int s = sizeOf(state);
        List<Integer> spots = menuSpots(s);
        if (spots.isEmpty()) return;
        dock.spawnItem(state.key(),
                new Location(w, state.originX() + spots.get(0) + 0.5,
                        state.floorY() + 2.6, state.originZ() + s - 2 + 0.5),
                new DockItem("Apple", null, null), Material.APPLE);
    }

    /**
     * Dock (spec 7) along the west wall: a show/hide lever (hidden by default
     * so mobs can't trip the plates), and when shown, pressure plates with
     * floating named app items.
     */
    private void buildDockWall(RoomState state) {
        int s = sizeOf(state);
        int fy = state.floorY();
        int ox = state.originX();
        int oz = state.originZ();
        int x = ox + 1;

        for (int lz = 2; lz <= s - 3; lz++) {
            set(x, fy + 1, oz + lz, Material.AIR); // clear old plates
        }

        // The lever, mounted on the west wall.
        Switch lever = (Switch) Material.LEVER.createBlockData();
        lever.setAttachedFace(FaceAttachable.AttachedFace.WALL);
        lever.setFacing(BlockFace.EAST);
        lever.setPowered(state.showDock());
        w.getBlockAt(x, fy + 2, oz + 2).setBlockData(lever, false);
        placeWallSign(x, fy + 3, oz + 2, BlockFace.EAST,
                "lever:", "show dock", "", state.showDock() ? "(shown)" : "(hidden)");
        state.addTarget(x, fy + 2, oz + 2, new RoomState.Target(
                RoomState.TargetType.TOGGLE_DOCK, "", "dock"));

        if (!state.showDock() || !cfg.dockEnabled()) return;
        int i = 0;
        for (DockItem item : dock.current()) {
            int z = oz + 4 + i * 2;
            if (z > oz + s - 3) break; // small rooms show a truncated dock
            set(x, fy + 1, z, Material.POLISHED_BLACKSTONE_PRESSURE_PLATE);
            state.addTarget(x, fy + 1, z, new RoomState.Target(
                    RoomState.TargetType.DOCK, item.launchArg(), item.label()));
            dock.spawnItem(state.key(), new Location(w, x + 0.5, fy + 2.4, z + 0.5), item);
            i++;
        }
    }

    /** Re-places just the dock wall + apple after the lever is flipped. */
    public void rebuildDock(RoomState state) {
        this.w = state.world();
        dock.clearRoom(state.key()); // removes plates' items and the apple
        buildDockWall(state);
        buildAppleMarker(state);
    }

    // ------------------------------------------------------- mansion extras

    /** The hall's grand entrance: double doors in the south wall. */
    private void buildFrontDoors(int ox, int oz, int s, int fy) {
        int c = s / 2;
        placeDoor(ox + c, oz + s - 1, fy, BlockFace.NORTH, Material.DARK_OAK_DOOR,
                Door.Hinge.LEFT);
        placeDoor(ox + c + 1, oz + s - 1, fy, BlockFace.NORTH, Material.DARK_OAK_DOOR,
                Door.Hinge.RIGHT);
        placeWallSign(ox + c, fy + 3, oz + s - 2, BlockFace.NORTH,
                "[ outside ]", "the porch &", "nether portal", "");
    }

    /** Stairs from the hall up to the landing (regular stairs, spec update). */
    private void buildHallStaircase(int ox, int oz, int s, int fy) {
        int x = ox + s - 2;
        for (int z = oz + 2; z <= oz + 5; z++) {
            set(x, fy + CEIL, z, Material.AIR); // ceiling opening above the run
        }
        for (int i = 0; i <= 5; i++) {
            int y = fy + 1 + i;
            int z = oz + 7 - i;
            for (int yy = fy + 1; yy < y; yy++) {
                set(x, yy, z, Material.SPRUCE_PLANKS); // solid under each step
            }
            Stairs st = (Stairs) Material.SPRUCE_STAIRS.createBlockData();
            st.setFacing(BlockFace.NORTH); // ascend walking north
            w.getBlockAt(x, y, z).setBlockData(st, false);
        }
        placeWallSign(x - 1, fy + 3, oz + 7, BlockFace.WEST, "upstairs", "", "", "");
    }

    /** Mansion rooms: a teleport door back to the home folder (first door slot). */
    private int buildMansionParentDoor(RoomState state, int ox, int oz, int s, int fy) {
        List<DoorSlot> slots = doorSlots(s);
        if (slots.isEmpty()) return 0;
        DoorSlot slot = slots.get(0);
        String parent = state.dir().getParent().toString();
        int x = ox + slot.lx();
        int z = oz + slot.lz();
        placeDoor(x, z, fy, slot.inward(), Material.WARPED_DOOR);
        int sx = x + slot.inward().getModX();
        int sz = z + slot.inward().getModZ();
        placeWallSign(sx, fy + 3, sz, slot.inward(),
                "[ home ]", "down to", trim(parentName(state), 15), "");
        RoomState.Target t = new RoomState.Target(RoomState.TargetType.PARENT, parent, "..");
        state.addTarget(x, fy + 1, z, t);
        state.addTarget(x, fy + 2, z, t);
        state.addTarget(sx, fy + 3, sz, t);
        return 1;
    }

    /** A light furnishing pass so mansion rooms read as lived-in. */
    private void buildFurniture(int ox, int oz, int s, int fy) {
        set(ox + 2, fy + 1, oz + 2, Material.POTTED_FERN);
        set(ox + s - 3, fy + 1, oz + s - 3, Material.POTTED_BAMBOO);
        for (int i = 0; i < 2; i++) { // bench near the entry
            Stairs st = (Stairs) Material.SPRUCE_STAIRS.createBlockData();
            st.setFacing(BlockFace.SOUTH); // seat opens into the room
            w.getBlockAt(ox + 5 + i, fy + 1, oz + s - 3).setBlockData(st, false);
        }
        set(ox + s - 4, fy + 1, oz + 3, Material.SPRUCE_FENCE); // side table
        TrapDoor top = (TrapDoor) Material.SPRUCE_TRAPDOOR.createBlockData();
        top.setHalf(Bisected.Half.TOP);
        w.getBlockAt(ox + s - 4, fy + 2, oz + 3).setBlockData(top, false);
    }

    // -------------------------------------------------------------- levers

    /** Spawn-room lever (east wall): hide/show hidden files and folders everywhere. */
    private void buildHiddenLever(RoomState state, int ox, int oz, int s, int fy) {
        int x = ox + s - 2;
        int z = oz + s - 3;
        Switch lever = (Switch) Material.LEVER.createBlockData();
        lever.setAttachedFace(FaceAttachable.AttachedFace.WALL);
        lever.setFacing(BlockFace.WEST); // mounted on the east wall
        lever.setPowered(cfg.hideHidden());
        w.getBlockAt(x, fy + 2, z).setBlockData(lever, false);
        placeWallSign(x, fy + 3, z, BlockFace.WEST,
                "lever:", "hide hidden", "files+folders", cfg.hideHidden() ? "(hiding)" : "(showing)");
        RoomState.Target t = new RoomState.Target(RoomState.TargetType.TOGGLE_HIDDEN, "", "hidden");
        state.addTarget(x, fy + 2, z, t);
    }

    /**
     * Spawn-room lever (east wall, next to the hidden-files one): pin the menu
     * chests to Finder's menus instead of the frontmost app's (request #1 —
     * while you play, the frontmost app is always the game itself).
     */
    private void buildFinderLever(RoomState state, int ox, int oz, int s, int fy) {
        int x = ox + s - 2;
        int z = oz + s - 4;
        Switch lever = (Switch) Material.LEVER.createBlockData();
        lever.setAttachedFace(FaceAttachable.AttachedFace.WALL);
        lever.setFacing(BlockFace.WEST); // mounted on the east wall
        lever.setPowered(cfg.finderMenus());
        w.getBlockAt(x, fy + 2, z).setBlockData(lever, false);
        placeWallSign(x, fy + 3, z, BlockFace.WEST,
                "lever:", "menu chests", "show menus of:",
                cfg.finderMenus() ? "Finder" : "frontmost app");
        RoomState.Target t = new RoomState.Target(RoomState.TargetType.TOGGLE_FINDER, "", "finder");
        state.addTarget(x, fy + 2, z, t);
    }

    // ----------------------------------------------------- special fixtures

    /**
     * The hall's special doors, flanking the front entrance (requests #4/#7):
     * west of the doors, Macintosh HD (the "/" room); east of them, iCloud
     * Drive (the rooms in the clouds), when iCloud exists.
     */
    private void buildSpecialDoors(RoomState state, int ox, int oz, int s, int fy) {
        int c = s / 2;
        // Macintosh HD — the whole drive as a special room.
        if (!cfg.inScope(Path.of("/"))) {
            buildIcloudDoor(state, ox, oz, s, fy);
            return; // scope was narrowed in config; no drive door
        }
        placeDoor(ox + c - 3, oz + s - 1, fy, BlockFace.NORTH, Material.COPPER_DOOR);
        placeWallSign(ox + c - 3, fy + 3, oz + s - 2, BlockFace.NORTH,
                "[ Mac HD ]", "Macintosh HD", "the whole drive", "");
        RoomState.Target hd = new RoomState.Target(
                RoomState.TargetType.FOLDER, "/", "Macintosh HD");
        state.addTarget(ox + c - 3, fy + 1, oz + s - 1, hd);
        state.addTarget(ox + c - 3, fy + 2, oz + s - 1, hd);
        state.addTarget(ox + c - 3, fy + 3, oz + s - 2, hd);

        buildIcloudDoor(state, ox, oz, s, fy);
    }

    /** iCloud Drive — up in the clouds (east of the front doors). */
    private void buildIcloudDoor(RoomState state, int ox, int oz, int s, int fy) {
        int c = s / 2;
        Path icloud = zones.icloudRoot();
        if (!Files.isDirectory(icloud)) return;
        placeDoor(ox + c + 3, oz + s - 1, fy, BlockFace.NORTH, Material.BIRCH_DOOR);
        placeWallSign(ox + c + 3, fy + 3, oz + s - 2, BlockFace.NORTH,
                "[ iCloud ]", "iCloud Drive", "up in the", "clouds");
        RoomState.Target ic = new RoomState.Target(
                RoomState.TargetType.FOLDER, icloud.toString(), "iCloud Drive");
        state.addTarget(ox + c + 3, fy + 1, oz + s - 1, ic);
        state.addTarget(ox + c + 3, fy + 2, oz + s - 1, ic);
        state.addTarget(ox + c + 3, fy + 3, oz + s - 2, ic);
    }

    /**
     * The return nether portal inside the /Network room (north-west corner,
     * flush against the north wall). Stepping in takes you back to the hall.
     */
    private void buildReturnPortal(int ox, int oz, int fy) {
        int z = oz + 1;
        for (int lx = 1; lx <= 4; lx++) { // top + bottom beams
            set(ox + lx, fy, z, Material.OBSIDIAN);
            set(ox + lx, fy + 4, z, Material.OBSIDIAN);
        }
        for (int dy = 1; dy <= 3; dy++) { // pillars
            set(ox + 1, fy + dy, z, Material.OBSIDIAN);
            set(ox + 4, fy + dy, z, Material.OBSIDIAN);
        }
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(Axis.X);
        for (int lx = 2; lx <= 3; lx++) {
            for (int dy = 1; dy <= 3; dy++) {
                w.getBlockAt(ox + lx, fy + dy, z).setBlockData(portal, false);
            }
        }
        placeStandingSign(ox + 5, fy + 1, z,
                "[ portal ]", "back to", "the mansion", "");
    }

    // ------------------------------------------------------------ back door

    private void buildBackDoor(RoomState state, int ox, int oz, int s, int fy) {
        int lx = s / 2;
        String parent = state.dir().getParent().toString();
        placeDoor(ox + lx, oz + s - 1, fy, BlockFace.NORTH, Material.WARPED_DOOR);
        placeWallSign(ox + lx, fy + 3, oz + s - 2, BlockFace.NORTH,
                "[ .. ]", "up to", trim(parentName(state), 15), "");
        RoomState.Target t = new RoomState.Target(RoomState.TargetType.PARENT, parent, "..");
        state.addTarget(ox + lx, fy + 1, oz + s - 1, t);
        state.addTarget(ox + lx, fy + 2, oz + s - 1, t);
        state.addTarget(ox + lx, fy + 3, oz + s - 2, t);
    }

    /**
     * Rooms whose parent is outside the scope (Macintosh HD at "/") still need
     * an exit: a warped door straight back to the mansion hall.
     */
    private void buildHomeDoor(RoomState state, int ox, int oz, int s, int fy) {
        int lx = s / 2;
        placeDoor(ox + lx, oz + s - 1, fy, BlockFace.NORTH, Material.WARPED_DOOR);
        placeWallSign(ox + lx, fy + 3, oz + s - 2, BlockFace.NORTH,
                "[ home ]", "back to", "the mansion", "");
        RoomState.Target t = new RoomState.Target(RoomState.TargetType.PARENT,
                cfg.spawnDir().toString(), "home");
        state.addTarget(ox + lx, fy + 1, oz + s - 1, t);
        state.addTarget(ox + lx, fy + 2, oz + s - 1, t);
        state.addTarget(ox + lx, fy + 3, oz + s - 2, t);
    }

    private String parentName(RoomState state) {
        var parent = state.dir().getParent();
        if (parent == null) return "/";
        return zones.displayName(parent);
    }

    private void buildRoomLabel(RoomState state, int ox, int oz, int s, int fy, ScanResult scan) {
        String name = zones.displayName(state.dir());
        int dirCount = scan.dirs().size() + scan.moreDirs();
        int fileCount = scan.files().size() + scan.moreFiles();
        placeStandingSign(ox + s / 2 + 2, fy + 1, oz + s - 3,
                trim(name, 15),
                dirCount + " folder(s)",
                fileCount + (scan.truncatedScan() ? "+" : "") + " file(s)",
                "");
    }

    // -------------------------------------------------------------- content

    private void buildFolderDoors(RoomState state, int ox, int oz, int s, int fy,
                                  ScanResult scan, int slotOffset, boolean hasStaircase,
                                  boolean hasPortal) {
        List<DoorSlot> slots = new ArrayList<>(doorSlots(s));
        if (hasStaircase) { // the hall staircase runs along the east wall's north end
            slots.removeIf(d -> d.lx() == s - 1 && d.lz() <= 8);
        }
        if (hasPortal) { // the return portal sits against the north wall's west end
            slots.removeIf(d -> d.lz() == 0 && d.lx() <= 5);
        }
        int i = slotOffset;
        int shown = 0;
        int skippedHidden = 0;
        for (DirEntry dir : scan.dirs()) {
            if (cfg.hideHidden() && dir.hidden()) {
                skippedHidden++;
                continue;
            }
            if (i >= slots.size()) break;
            DoorSlot slot = slots.get(i++);
            shown++;
            int x = ox + slot.lx();
            int z = oz + slot.lz();
            placeDoor(x, z, fy, slot.inward(), RoomTheme.doorFor(dir));
            int sx = x + slot.inward().getModX();
            int sz = z + slot.inward().getModZ();
            String flags = dir.hidden() ? "(hidden)" : !dir.readable() ? "(locked)" : "";
            placeWallSign(sx, fy + 3, sz, slot.inward(), trim(dir.name(), 15), flags, "", "");
            String target = state.dir().resolve(dir.name()).toString();
            RoomState.Target t = new RoomState.Target(RoomState.TargetType.FOLDER, target, dir.name());
            state.addTarget(x, fy + 1, z, t);
            state.addTarget(x, fy + 2, z, t);
            state.addTarget(sx, fy + 3, sz, t);
        }
        int unshown = scan.moreDirs() + (scan.dirs().size() - shown - skippedHidden);
        if (unshown > 0) {
            placeStandingSign(ox + s / 2 - 2, fy + 1, oz + s - 3,
                    "+" + unshown + " more", "folder(s)", "/dm goto <path>", "");
        }
    }

    /**
     * Files as item frames with filename signs (spec update): book & quill for
     * text, book for other files, the app icon on a furnace pedestal for .app
     * bundles. The block under each frame stays the category block. Frames
     * carry the file's real path so text files can be read in-game.
     */
    private void buildFileBlocks(RoomState state, int ox, int oz, int s, int fy, ScanResult scan) {
        List<int[]> slots = fileSlots(s);
        int capacity = slots.size() - 1;
        int i = 0;
        int skippedHidden = 0;
        for (DirEntry f : scan.files()) {
            if (cfg.hideHidden() && f.hidden()) {
                skippedHidden++;
                continue;
            }
            if (i >= capacity) break;
            int[] slot = slots.get(i++);
            int x = ox + slot[0];
            int z = oz + slot[1];
            boolean appBundle = f.category() == FileCategory.EXECUTABLE
                    && f.name().endsWith(".app");
            String full = state.dir().resolve(f.name()).toString();
            String info = f.symlink()
                    ? f.name() + " -> " + (f.linkTarget() == null ? "?" : f.linkTarget())
                    : f.name() + " — " + humanSize(f.size());
            if (appBundle) {
                String label = f.name().substring(0, f.name().length() - 4);
                set(x, fy + 1, z, blockFor(f)); // pedestal
                frames.spawnFloorFrame(state.key(), w, x, fy + 2, z,
                        dock.iconFor(new DockItem(label, null, full)), f.name(), full);
                placeStandingSign(x, fy + 1, z + 1, trim(label, 15), "(app)", "", "");
            } else {
                set(x, fy, z, blockFor(f)); // category block flush in the floor
                Material frameItem = f.category() == FileCategory.TEXT
                        ? Material.WRITABLE_BOOK : Material.BOOK;
                ItemStack stack = new ItemStack(frameItem);
                stack.editMeta(meta -> meta.displayName(Component.text(f.name())));
                frames.spawnFloorFrame(state.key(), w, x, fy + 1, z, stack, info, full);
                String line2 = f.symlink() ? "-> link"
                        : !f.readable() ? "(locked)"
                        : f.hidden() ? "(hidden)"
                        : humanSize(f.size());
                placeStandingSign(x, fy + 1, z + 1, trim(f.name(), 15), line2, "", "");
            }
            RoomState.Target t;
            if (f.symlink() && f.linkTarget() != null) {
                t = new RoomState.Target(RoomState.TargetType.SYMLINK, f.linkTarget(), f.name());
            } else {
                t = new RoomState.Target(RoomState.TargetType.FILE_INFO, full,
                        f.name() + " — " + humanSize(f.size()));
            }
            state.addTarget(x, fy, z, t);
            state.addTarget(x, fy + 1, z, t);
            if (appBundle) state.addTarget(x, fy + 2, z, t);
        }
        int unshown = scan.moreFiles() + (scan.files().size() - i - skippedHidden);
        if (unshown > 0 && i < slots.size()) {
            int[] slot = slots.get(i);
            int x = ox + slot[0];
            int z = oz + slot[1];
            set(x, fy, z, Material.CHEST); // warehouse marker (spec 5.3)
            placeStandingSign(x, fy + 1, z + 1,
                    "+" + unshown + (scan.truncatedScan() ? "+" : ""),
                    "more file(s)", "", "");
        }
    }

    private void buildCenterNotices(int ox, int oz, int s, int fy, ScanResult scan) {
        if (scan.readFailed()) {
            placeStandingSign(ox + s / 2, fy + 1, oz + s / 2,
                    "(no permission)", "folder is not", "readable", "");
        } else if (scan.dirs().isEmpty() && scan.files().isEmpty()) {
            placeStandingSign(ox + s / 2, fy + 1, oz + s / 2, "(empty folder)", "", "", "");
        }
    }

    private Material blockFor(DirEntry f) {
        if (f.symlink()) return Material.CRYING_OBSIDIAN;   // portal-ish (spec 5.1)
        if (!f.readable()) return Material.IRON_BARS;       // locked (spec 5.1)
        if (f.hidden()) return Material.TINTED_GLASS;       // translucent (spec 5.1)
        return f.category().block();
    }

    // ------------------------------------------------------------ placement

    private void set(int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void placeDoor(int x, int z, int fy, BlockFace inward, Material doorMaterial) {
        placeDoor(x, z, fy, inward, doorMaterial, Door.Hinge.LEFT);
    }

    private void placeDoor(int x, int z, int fy, BlockFace inward, Material doorMaterial,
                           Door.Hinge hinge) {
        Door bottom = (Door) doorMaterial.createBlockData();
        bottom.setFacing(inward);
        bottom.setHalf(Bisected.Half.BOTTOM);
        bottom.setHinge(hinge);
        Door top = (Door) doorMaterial.createBlockData();
        top.setFacing(inward);
        top.setHalf(Bisected.Half.TOP);
        top.setHinge(hinge);
        w.getBlockAt(x, fy + 1, z).setBlockData(bottom, false);
        w.getBlockAt(x, fy + 2, z).setBlockData(top, false);
    }

    private void placeWallSign(int x, int y, int z, BlockFace facing, String... lines) {
        WallSign data = (WallSign) Material.OAK_WALL_SIGN.createBlockData();
        data.setFacing(facing);
        Block block = w.getBlockAt(x, y, z);
        block.setBlockData(data, false);
        writeSign(block, lines);
    }

    private void placeStandingSign(int x, int y, int z, String... lines) {
        org.bukkit.block.data.type.Sign data =
                (org.bukkit.block.data.type.Sign) Material.OAK_SIGN.createBlockData();
        ((Rotatable) data).setRotation(BlockFace.SOUTH); // readable when facing north
        Block block = w.getBlockAt(x, y, z);
        block.setBlockData(data, false);
        writeSign(block, lines);
    }

    private void writeSign(Block block, String... lines) {
        BlockState st = block.getState();
        if (st instanceof org.bukkit.block.Sign sign) {
            SignSide side = sign.getSide(Side.FRONT);
            for (int i = 0; i < 4 && i < lines.length; i++) {
                side.line(i, Component.text(lines[i] == null ? "" : lines[i]));
            }
            sign.setWaxed(true); // nobody should edit chrome
            sign.update(true, false);
        }
    }

    // --------------------------------------------------------------- layout

    /**
     * Wall positions for subdirectory doors: north and east walls (south is
     * the entry + menu chests, west is the Dock), keeping clear of the wall
     * centers where mansion rooms have their structural doorways.
     */
    private static List<DoorSlot> computeDoorSlots(int s) {
        List<DoorSlot> slots = new ArrayList<>();
        int c = s / 2;
        for (int x = 4; x <= s - 5; x += 3) {
            if (Math.abs(x - c) <= 2) continue;
            slots.add(new DoorSlot(x, 0, BlockFace.SOUTH));       // north wall
        }
        for (int z = 5; z <= s - 5; z += 3) {
            if (Math.abs(z - c) <= 2) continue;
            slots.add(new DoorSlot(s - 1, z, BlockFace.WEST));    // east wall
        }
        return List.copyOf(slots);
    }

    /** Interior grid for file frames, spaced 2 apart, clear of chrome and walls. */
    private static List<int[]> computeFileSlots(int s) {
        List<int[]> slots = new ArrayList<>();
        for (int z = 5; z <= s - 5; z += 2) {
            for (int x = 3; x <= s - 3; x += 2) {
                slots.add(new int[]{x, z});
            }
        }
        return List.copyOf(slots);
    }

    // ---------------------------------------------------------------- misc

    static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format("%.1f %s", v, units[u]);
    }
}
