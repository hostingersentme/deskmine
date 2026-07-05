package dev.deskmine.menubar;

import java.util.List;

/** The frontmost app's menu bar at one point in time (spec 6). */
public record MenuSnapshot(String appName, List<MenuEntry> menus) {

    public static final MenuSnapshot EMPTY = new MenuSnapshot("", List.of());

    /** One top-level menu; {@code apple} marks the system-owned Apple menu. */
    public record MenuEntry(String title, boolean apple, List<MenuItemEntry> items) {}

    /** One row of a menu, in position order (slot N = position N, spec 6.2). */
    public record MenuItemEntry(String name, boolean enabled, boolean separator, boolean submenu) {}

    public MenuEntry appleMenu() {
        for (MenuEntry m : menus) if (m.apple()) return m;
        return null;
    }

    /** All non-Apple menus, left-to-right. */
    public List<MenuEntry> appMenus() {
        return menus.stream().filter(m -> !m.apple()).toList();
    }
}
