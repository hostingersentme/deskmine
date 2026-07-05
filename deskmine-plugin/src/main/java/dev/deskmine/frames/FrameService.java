package dev.deskmine.frames;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the item-frame file displays: a book & quill for text files, a book
 * for everything else, an app icon on a pedestal for .app bundles. Frames are
 * fixed, invulnerable, and tagged so they survive mobs and players, get
 * removed on room rebuilds, and strays from restarts can be swept.
 */
public final class FrameService {

    private final NamespacedKey tag;
    private final NamespacedKey labelKey;
    private final NamespacedKey pathKey;
    private final Map<UUID, Long> frameRoom = new ConcurrentHashMap<>();
    private final Map<Long, List<UUID>> roomFrames = new ConcurrentHashMap<>();

    public FrameService(Plugin plugin) {
        this.tag = new NamespacedKey(plugin, "file-frame");
        this.labelKey = new NamespacedKey(plugin, "file-frame-label");
        this.pathKey = new NamespacedKey(plugin, "file-frame-path");
    }

    /** Spawns a flat frame lying on top of the block below (x, y, z). */
    public void spawnFloorFrame(long roomKey, World world, int x, int y, int z,
                                ItemStack item, String label, String path) {
        Location loc = new Location(world, x, y, z);
        ItemFrame frame = world.spawn(loc, ItemFrame.class, f -> {
            f.setFacingDirection(BlockFace.UP, true);
            f.setItem(item, false);
            f.setFixed(true);
            f.setInvulnerable(true);
            f.setPersistent(true);
            f.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            f.getPersistentDataContainer().set(labelKey, PersistentDataType.STRING, label);
            if (path != null) {
                f.getPersistentDataContainer().set(pathKey, PersistentDataType.STRING, path);
            }
        });
        frameRoom.put(frame.getUniqueId(), roomKey);
        roomFrames.computeIfAbsent(roomKey, k -> new ArrayList<>()).add(frame.getUniqueId());
    }

    public boolean isFileFrame(Entity e) {
        return e instanceof ItemFrame
                && e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    /** The file name/details stored on a frame (null for foreign frames). */
    public String labelOf(Entity e) {
        return e.getPersistentDataContainer().get(labelKey, PersistentDataType.STRING);
    }

    /** The real filesystem path stored on a frame (null for foreign frames). */
    public String pathOf(Entity e) {
        return e.getPersistentDataContainer().get(pathKey, PersistentDataType.STRING);
    }

    /** Removes all tracked frames of a room. */
    public void clearRoom(long roomKey) {
        List<UUID> list = roomFrames.remove(roomKey);
        if (list == null) return;
        for (UUID id : list) {
            frameRoom.remove(id);
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    /** Removes stray tagged frames in a region (leftovers from restarts). */
    public void sweepRegion(World world, BoundingBox box) {
        for (Entity e : world.getNearbyEntities(box)) {
            if (isFileFrame(e) && !frameRoom.containsKey(e.getUniqueId())) e.remove();
        }
    }

    public void clearAll() {
        for (Long key : List.copyOf(roomFrames.keySet())) clearRoom(key);
    }
}
