package dev.deskmine.theme;

import dev.deskmine.fs.DirEntry;
import org.bukkit.Material;

import java.util.Locale;
import java.util.Map;

/**
 * Per-folder look: floor and wall materials for the room itself, and the door
 * type used to enter it from the parent. Matched by folder name, so every
 * "Library" (~/Library, /Library, /System/Library) reads as a library.
 */
public record RoomTheme(Material floor, Material wall, Material band, Material door) {

    public static final RoomTheme DEFAULT = new RoomTheme(
            Material.SMOOTH_STONE, Material.STONE_BRICKS, Material.GLASS, Material.SPRUCE_DOOR);

    private static final Map<String, RoomTheme> BY_NAME = Map.ofEntries(
            Map.entry("applications", new RoomTheme(
                    Material.SMOOTH_QUARTZ, Material.QUARTZ_BRICKS, Material.GLASS,
                    Material.COPPER_DOOR)),
            Map.entry("library", new RoomTheme(
                    Material.DARK_OAK_PLANKS, Material.BOOKSHELF, Material.GLASS,
                    Material.DARK_OAK_DOOR)),
            Map.entry("system", new RoomTheme(
                    Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_TILES, Material.GLASS,
                    Material.CRIMSON_DOOR)),
            Map.entry("users", new RoomTheme(
                    Material.OAK_PLANKS, Material.BRICKS, Material.GLASS,
                    Material.BIRCH_DOOR)),
            Map.entry("documents", new RoomTheme(
                    Material.BIRCH_PLANKS, Material.SMOOTH_SANDSTONE, Material.GLASS,
                    Material.BAMBOO_DOOR)),
            Map.entry("desktop", new RoomTheme(
                    Material.GRASS_BLOCK, Material.STONE_BRICKS, Material.GLASS,
                    Material.ACACIA_DOOR)),
            Map.entry("downloads", new RoomTheme(
                    Material.POLISHED_ANDESITE, Material.MOSSY_STONE_BRICKS, Material.GLASS,
                    Material.MANGROVE_DOOR)),
            Map.entry("pictures", new RoomTheme(
                    Material.CHERRY_PLANKS, Material.STONE_BRICKS, Material.GLOWSTONE,
                    Material.CHERRY_DOOR)),
            Map.entry("music", new RoomTheme(
                    Material.JUNGLE_PLANKS, Material.JUNGLE_PLANKS, Material.GLASS,
                    Material.JUNGLE_DOOR)),
            Map.entry("movies", new RoomTheme(
                    Material.RED_TERRACOTTA, Material.BLACK_CONCRETE, Material.GLASS,
                    Material.PALE_OAK_DOOR))
    );

    public static RoomTheme forName(String dirName) {
        if (dirName == null) return DEFAULT;
        return BY_NAME.getOrDefault(dirName.toLowerCase(Locale.ROOT), DEFAULT);
    }

    /** Door used in the parent room to enter this child; hidden folders read as locked. */
    public static Material doorFor(DirEntry child) {
        return child.hidden() ? Material.IRON_DOOR : forName(child.name()).door();
    }
}
