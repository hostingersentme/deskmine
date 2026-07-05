package dev.deskmine.menubar;

import dev.deskmine.DeskmineConfig;
import dev.deskmine.menubar.MenuSnapshot.MenuEntry;
import dev.deskmine.menubar.MenuSnapshot.MenuItemEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The menu watcher (spec 3.3 #2), implemented with osascript + System Events
 * instead of the Swift helper: polls the frontmost app, and re-reads its menu
 * tree when it changes. Requires macOS Accessibility permission for the
 * process that runs the server (System Settings -> Privacy & Security ->
 * Accessibility -> your terminal).
 */
public final class MenuBarService {

    // Bulk-fetches each menu's rows in a handful of Apple Events ("name of
    // every menu item") instead of three round-trips per row — big apps would
    // otherwise blow past the script timeout. The %s is the process selector:
    // the frontmost app, or Finder when the spawn-room lever pins it.
    private static final String MENUS_SCRIPT_TEMPLATE = """
            tell application "System Events"
              set frontApp to %s
              set out to (name of frontApp) & linefeed
              tell frontApp
                if not (exists menu bar 1) then return out
                repeat with mbi in (menu bar items of menu bar 1)
                  set out to out & "##MENU##" & (name of mbi) & linefeed
                  try
                    set nms to name of every menu item of menu 1 of mbi
                    set ens to enabled of every menu item of menu 1 of mbi
                    set subs to {}
                    try
                      set subs to menus of every menu item of menu 1 of mbi
                    end try
                    repeat with idx from 1 to count of nms
                      set n to item idx of nms
                      if n is missing value or n is "" then
                        set out to out & "##SEP##" & linefeed
                      else
                        set en to "1"
                        try
                          if item idx of ens is false then set en to "0"
                        end try
                        set sub to "0"
                        try
                          if (count of subs) >= idx then
                            set ms to item idx of subs
                            if ms is not missing value then
                              if (count of ms) > 0 then set sub to "1"
                            end if
                          end if
                        end try
                        set out to out & "##ITEM##" & en & sub & n & linefeed
                      end if
                    end repeat
                  end try
                end repeat
              end tell
              return out
            end tell
            """;

    private final Plugin plugin;
    private final DeskmineConfig cfg;
    private volatile MenuSnapshot current = MenuSnapshot.EMPTY;
    private volatile String lastApp = null;
    private volatile long lastFetchMs = 0;
    private volatile long lastPermWarnMs = 0;
    private volatile boolean permissionDenied = false;
    private volatile boolean announcedFirstRead = false;
    private volatile String lastError = null;
    private volatile long lastErrWarnMs = 0;
    private Runnable onChange = () -> {};

    public MenuBarService(Plugin plugin, DeskmineConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public MenuSnapshot current() {
        return current;
    }

    /** True while macOS is refusing assistive access to the menu reader. */
    public boolean permissionDenied() {
        return permissionDenied;
    }

    public void start() {
        if (!cfg.menuBarEnabled()) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            plugin.getLogger().info("Menu bar disabled: not running on macOS.");
            return;
        }
        long period = cfg.menuBarPollSeconds() * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 40L, period);
    }

    /** AppleScript selector for the process whose menus we mirror. */
    private String processSelector() {
        return cfg.finderMenus() ? "process \"Finder\""
                : "first process whose frontmost is true";
    }

    private void poll() {
        String app;
        if (cfg.finderMenus()) {
            app = "Finder"; // pinned by the spawn-room lever
        } else {
            app = runScript(
                    "tell application \"System Events\" to get name of first process whose frontmost is true");
            if (app == null) return;
            app = app.trim();
        }
        long now = System.currentTimeMillis();
        if (app.equals(lastApp) && now - lastFetchMs < cfg.menuBarRefetchSeconds() * 1000L) return;

        boolean wasDenied = permissionDenied;
        String raw = runScript(MENUS_SCRIPT_TEMPLATE.formatted(processSelector()));
        if (raw == null) {
            // Permission just got denied: rebuild bars so the in-world sign appears.
            if (permissionDenied && !wasDenied) Bukkit.getScheduler().runTask(plugin, onChange);
            return;
        }
        permissionDenied = false;
        MenuSnapshot snap = parse(raw);
        if (snap == null) return;
        lastApp = snap.appName();
        lastFetchMs = now;
        if (!snap.equals(current) || wasDenied) {
            current = snap;
            if (!announcedFirstRead) {
                announcedFirstRead = true;
                plugin.getLogger().info("Menu bar: reading menus of \"" + snap.appName()
                        + "\" (" + snap.menus().size() + " menus).");
            }
            Bukkit.getScheduler().runTask(plugin, onChange);
        }
    }

    private String runScript(String script) {
        try {
            Process p = new ProcessBuilder("osascript", "-e", script).start();
            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                String e = new String(err, StandardCharsets.UTF_8).trim();
                lastError = e.length() > 300 ? e.substring(0, 300) : e;
                if (isPermissionError(e)) {
                    permissionDenied = true;
                    warnPermission();
                } else if (!isNoMenuBarError(e)) {
                    warnScriptError();
                } else {
                    permissionDenied = false;
                }
                return null;
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            lastError = e.toString();
            return null;
        }
    }

    private boolean isPermissionError(String e) {
        return e.contains("assistive") || e.contains("not allowed")
                || e.contains("not authorized") || e.contains("-1743")
                || e.contains("-25211");
    }

    private boolean isNoMenuBarError(String e) {
        return e.contains("-1719")
                && (e.contains("menu bar 1") || e.contains("Invalid index"));
    }

    private void warnScriptError() {
        long now = System.currentTimeMillis();
        if (now - lastErrWarnMs < 300_000) return;
        lastErrWarnMs = now;
        plugin.getLogger().warning("Menu script failed: " + lastError);
    }

    /** Last osascript stderr (for /dm menubar diagnostics). */
    public String lastError() {
        return lastError;
    }

    public String lastApp() {
        return lastApp;
    }

    /** Kicks off an immediate async poll (used by /dm menubar). */
    public void pollSoon() {
        lastFetchMs = 0; // bypass the refetch window
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::poll);
    }

    private void warnPermission() {
        long now = System.currentTimeMillis();
        if (now - lastPermWarnMs < 300_000) return;
        lastPermWarnMs = now;
        plugin.getLogger().warning(
                "Menu bar blocked by macOS: " + lastError + " | Enable your terminal app under "
                        + "System Settings -> Privacy & Security -> Accessibility AND Automation "
                        + "(System Events), then QUIT and REOPEN the terminal app and restart "
                        + "the server. /dm menubar shows live status.");
    }

    private MenuSnapshot parse(String raw) {
        String[] lines = raw.split("\n");
        if (lines.length == 0) return null;
        String appName = lines[0].trim();
        List<MenuEntry> menus = new ArrayList<>();
        String title = null;
        List<MenuItemEntry> items = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("##MENU##")) {
                if (title != null) {
                    menus.add(new MenuEntry(title, menus.isEmpty(), List.copyOf(items)));
                }
                title = line.substring("##MENU##".length()).trim();
                items = new ArrayList<>();
            } else if (line.startsWith("##SEP##")) {
                items.add(new MenuItemEntry("", true, true, false));
            } else if (line.startsWith("##ITEM##")) {
                String rest = line.substring("##ITEM##".length());
                if (rest.length() < 3) continue;
                boolean enabled = rest.charAt(0) == '1';
                boolean submenu = rest.charAt(1) == '1';
                String name = rest.substring(2).trim();
                items.add(new MenuItemEntry(name, enabled, false, submenu));
            }
        }
        if (title != null) {
            menus.add(new MenuEntry(title, menus.isEmpty(), List.copyOf(items)));
        }
        return new MenuSnapshot(appName, List.copyOf(menus));
    }

    // ----------------------------------------------------------- click-through

    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    /**
     * Performs a real menu action (request #1): chest slot N = menu item N+1.
     * Runs "click menu item …" via System Events against the process the
     * chests are mirroring, bringing it frontmost first. The Apple menu is
     * menu bar item 1 (menuTitle == null).
     */
    public void clickMenuItem(Player p, String menuTitle, int slot) {
        MenuSnapshot snap = current;
        MenuEntry menu;
        if (menuTitle == null) {
            menu = snap.appleMenu();
        } else {
            menu = null;
            for (MenuEntry m : snap.menus()) {
                if (!m.apple() && m.title().equals(menuTitle)) {
                    menu = m;
                    break;
                }
            }
        }
        if (menu == null) {
            p.sendMessage(Component.text("That menu is no longer available.",
                    NamedTextColor.GRAY));
            return;
        }
        if (slot < 0 || slot >= menu.items().size()) return; // empty slot
        if (slot == 26 && menu.items().size() > 27) {
            p.sendMessage(Component.text("Overflow marker — the rest of this menu "
                    + "doesn't fit in a chest yet.", NamedTextColor.GRAY));
            return;
        }
        MenuItemEntry item = menu.items().get(slot);
        if (item.separator()) return;
        if (!item.enabled()) {
            p.sendMessage(Component.text(item.name() + " is disabled right now.",
                    NamedTextColor.DARK_GRAY));
            return;
        }
        if (item.submenu()) {
            p.sendMessage(Component.text(item.name() + " ▶ has a submenu — "
                    + "submenus aren't clickable yet.", NamedTextColor.GRAY));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 1000) return; // debounce double clicks
        lastClick.put(p.getUniqueId(), now);

        String barItem = menuTitle == null
                ? "menu bar item 1"
                : "menu bar item \"" + escape(menuTitle) + "\"";
        String script = """
                tell application "System Events"
                  set targetProc to %s
                  tell targetProc
                    set frontmost to true
                    click menu item %d of menu 1 of %s of menu bar 1
                  end tell
                end tell
                """.formatted(processSelector(), slot + 1, barItem);
        p.sendMessage(Component.text("Clicking \"" + item.name() + "\"…",
                NamedTextColor.AQUA));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String out = runScript(script);
            if (out == null) {
                String why = permissionDenied
                        ? "macOS denied Accessibility permission."
                        : "the menu may have changed — try reopening the chest.";
                Bukkit.getScheduler().runTask(plugin, () ->
                        p.sendMessage(Component.text("Could not click \"" + item.name()
                                + "\": " + why, NamedTextColor.RED)));
            } else {
                pollSoon(); // menus often change after an action
            }
        });
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ----------------------------------------------------- item conversion

    /** Menu rows -> chest slots, position N = slot N (spec 6.2), capped at 27. */
    public static ItemStack[] menuStacks(MenuEntry menu) {
        ItemStack[] slots = new ItemStack[27];
        List<MenuItemEntry> items = menu.items();
        int n = Math.min(items.size(), 27);
        boolean overflow = items.size() > 27;
        for (int i = 0; i < n; i++) {
            if (overflow && i == 26) break;
            slots[i] = stackFor(items.get(i));
        }
        if (overflow) {
            slots[26] = named(new ItemStack(Material.PAPER),
                    "+" + (items.size() - 26) + " more…", NamedTextColor.GRAY, null);
        }
        return slots;
    }

    private static ItemStack stackFor(MenuItemEntry item) {
        if (item.separator()) {
            return named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "—",
                    NamedTextColor.DARK_GRAY, null);
        }
        if (item.submenu()) {
            return named(new ItemStack(Material.CHEST), item.name() + " ▶",
                    NamedTextColor.WHITE, null);
        }
        if (!item.enabled()) {
            return named(new ItemStack(Material.GRAY_DYE), item.name(),
                    NamedTextColor.DARK_GRAY, "(disabled)");
        }
        return named(new ItemStack(Material.PAPER), item.name(), NamedTextColor.WHITE, null);
    }

    private static ItemStack named(ItemStack stack, String name, NamedTextColor color, String lore) {
        stack.editMeta(meta -> {
            meta.displayName(Component.text(name, color));
            if (lore != null) meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        });
        return stack;
    }

    /**
     * The Apple menu is the ender chest (spec 6.1): same contents everywhere.
     * Ender chest inventories are per-player, so each player's is filled.
     * (Note: this repurposes players' ender chest storage on this server.)
     */
    public void fillEnderChest(Player p) {
        MenuEntry apple = current.appleMenu();
        Inventory inv = p.getEnderChest();
        inv.clear();
        if (apple != null) inv.setContents(menuStacks(apple));
    }
}
