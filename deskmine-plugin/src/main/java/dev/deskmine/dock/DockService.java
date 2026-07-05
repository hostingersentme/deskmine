package dev.deskmine.dock;

import dev.deskmine.DeskmineConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Dock (spec 7): keeps the current pinned-app list fresh, owns the
 * floating named item entities over the pressure plates, respawns items that
 * players pick up, and performs the outbound launch action via `open` —
 * the world's first (and only) write-back to the real system.
 */
public final class DockService {

    private record Slot(long cellKey, Location loc, DockItem item, Material material) {}

    private final Plugin plugin;
    private final DeskmineConfig cfg;
    private final NamespacedKey tag;
    private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();
    private final Map<Long, List<UUID>> roomItems = new ConcurrentHashMap<>();
    private final Map<String, Long> lastLaunch = new ConcurrentHashMap<>();
    private volatile List<DockItem> current = List.of();
    private Runnable onChange = () -> {};

    public DockService(Plugin plugin, DeskmineConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.tag = new NamespacedKey(plugin, "dock-item");
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Current pinned Dock apps (empty until the first successful read). */
    public List<DockItem> current() {
        return current;
    }

    public void start() {
        if (!cfg.dockEnabled()) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            plugin.getLogger().info("Dock disabled: not running on macOS.");
            return;
        }
        long period = cfg.dockRefreshSeconds() * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            List<DockItem> items = DockReader.read();
            if (items != null && !items.equals(current)) {
                current = items;
                Bukkit.getScheduler().runTask(plugin, onChange);
            }
        }, 0L, period);
    }

    // -------------------------------------------------------- item entities

    /** Spawns a floating, named, pickup-able item over a dock plate. */
    public void spawnItem(long cellKey, Location loc, DockItem item) {
        spawnItem(cellKey, loc, item, null);
    }

    /** Same, with an explicit look (e.g. the apple over the Apple-menu chest). */
    public void spawnItem(long cellKey, Location loc, DockItem item, Material material) {
        World world = loc.getWorld();
        Item ent = world.dropItem(loc, stackFor(item, material));
        ent.setVelocity(new Vector(0, 0, 0));
        ent.setGravity(false);
        ent.setPersistent(true);
        ent.setUnlimitedLifetime(true);
        ent.customName(Component.text(item.label(), NamedTextColor.AQUA));
        ent.setCustomNameVisible(true);
        ent.setPickupDelay(20);
        slots.put(ent.getUniqueId(), new Slot(cellKey, loc.clone(), item, material));
        roomItems.computeIfAbsent(cellKey, k -> new ArrayList<>()).add(ent.getUniqueId());
    }

    /** A player grabbed a dock item: let them keep it, respawn a fresh one shortly. */
    public void onPickedUp(Item entity) {
        Slot slot = slots.remove(entity.getUniqueId());
        if (slot == null) return;
        List<UUID> list = roomItems.get(slot.cellKey());
        if (list != null) list.remove(entity.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (roomItems.containsKey(slot.cellKey())) { // room still built/loaded
                spawnItem(slot.cellKey(), slot.loc(), slot.item(), slot.material());
            }
        }, 40L);
    }

    public boolean isDockItem(Item entity) {
        if (slots.containsKey(entity.getUniqueId())) return true;
        ItemStack stack = entity.getItemStack();
        return stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    /** Removes all tracked item entities of a room. */
    public void clearRoom(long cellKey) {
        List<UUID> list = roomItems.remove(cellKey);
        if (list == null) return;
        for (UUID id : list) {
            slots.remove(id);
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    /** Removes stray tagged items in a region (leftovers from restarts). */
    public void sweepRegion(World world, BoundingBox box) {
        for (Entity e : world.getNearbyEntities(box)) {
            if (e instanceof Item it && !slots.containsKey(it.getUniqueId()) && isDockItem(it)) {
                it.remove();
            }
        }
    }

    public void clearAll() {
        for (Long cellKey : List.copyOf(roomItems.keySet())) clearRoom(cellKey);
    }

    // --------------------------------------------------------------- launch

    /** Steps-on-plate -> open the app on the real machine (spec 7.2, debounced). */
    public void launch(Player p, String arg, String label) {
        if (arg == null) return;
        String key = p.getUniqueId() + "|" + arg;
        long now = System.currentTimeMillis();
        Long last = lastLaunch.get(key);
        if (last != null && now - last < 3000) return;
        lastLaunch.put(key, now);
        p.sendMessage(Component.text("Opening " + label + "...", NamedTextColor.AQUA));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> cmd = arg.startsWith("/")
                        ? List.of("open", arg)
                        : List.of("open", "-b", arg);
                new ProcessBuilder(cmd).start();
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to launch " + label + ": " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- looks

    private static final Map<String, Material> KNOWN_APPS = Map.ofEntries(
            Map.entry("com.apple.safari", Material.COMPASS),
            Map.entry("com.apple.mail", Material.WRITABLE_BOOK),
            Map.entry("com.apple.music", Material.MUSIC_DISC_CAT),
            Map.entry("com.apple.photos", Material.PAINTING),
            Map.entry("com.apple.terminal", Material.COMMAND_BLOCK),
            Map.entry("com.apple.systempreferences", Material.COMPARATOR),
            Map.entry("com.apple.finder", Material.SPYGLASS),
            Map.entry("com.apple.ical", Material.CLOCK),
            Map.entry("com.apple.addressbook", Material.NAME_TAG),
            Map.entry("com.apple.notes", Material.BOOK),
            Map.entry("com.apple.mobilesms", Material.BELL),
            Map.entry("com.apple.facetime", Material.ENDER_EYE),
            Map.entry("com.google.chrome", Material.ENDER_PEARL),
            Map.entry("com.microsoft.vscode", Material.REPEATING_COMMAND_BLOCK),
            Map.entry("com.anthropic.claudefordesktop", Material.NETHER_STAR)
    );

    private static final Material[] PALETTE = {
            Material.AMETHYST_SHARD, Material.DIAMOND, Material.EMERALD,
            Material.GOLD_INGOT, Material.BLAZE_POWDER, Material.PRISMARINE_SHARD,
            Material.QUARTZ, Material.LAPIS_LAZULI, Material.ECHO_SHARD
    };

    /** The icon stack for an app (also used by the file-frame pedestals). */
    public ItemStack iconFor(DockItem item) {
        return stackFor(item, null);
    }

    private ItemStack stackFor(DockItem item, Material override) {
        Material m = override;
        if (m == null && item.bundleId() != null) {
            m = KNOWN_APPS.get(item.bundleId().toLowerCase(Locale.ROOT));
        }
        if (m == null) {
            m = PALETTE[Math.floorMod(item.label().hashCode(), PALETTE.length)];
        }
        ItemStack stack = new ItemStack(m);
        stack.editMeta(meta -> {
            meta.displayName(Component.text(item.label(), NamedTextColor.AQUA));
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }
}
