package dev.deskmine.frames;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a text file's real contents as an in-game book (request #6).
 * Strictly read-only: the file is read off the main thread, capped in size,
 * and shown in the client's book UI — nothing is ever written back.
 */
public final class BookViewer {

    private static final int MAX_BYTES = 100_000; // read at most ~100 KB
    private static final int PAGE_CHARS = 700;    // safe under the 1024 page cap
    private static final int MAX_PAGES = 100;     // book UI limit

    private final Plugin plugin;

    public BookViewer(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Reads the file async and opens it as a book for the player. */
    public void open(Player p, Path file) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String name = file.getFileName() == null
                    ? file.toString() : file.getFileName().toString();
            List<Component> pages = buildPages(file);
            Book book = Book.book(
                    Component.text(trim(name, 32)),
                    Component.text("Deskmine (read-only)"),
                    pages);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) p.openBook(book);
            });
        });
    }

    private List<Component> buildPages(Path file) {
        byte[] bytes;
        long realSize;
        try {
            realSize = Files.size(file);
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes(MAX_BYTES);
            }
        } catch (Exception e) {
            return List.of(Component.text("(could not read file)\n\n" + e.getMessage(),
                    NamedTextColor.DARK_RED));
        }
        for (byte b : bytes) {
            if (b == 0) {
                return List.of(Component.text(
                        "(binary file — not readable as text)", NamedTextColor.DARK_GRAY));
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\t", "  ")
                .replaceAll("[\\p{Cc}&&[^\n]]", "");
        if (text.isBlank()) {
            return List.of(Component.text("(empty file)", NamedTextColor.DARK_GRAY));
        }

        List<Component> pages = new ArrayList<>();
        int i = 0;
        while (i < text.length() && pages.size() < MAX_PAGES - 1) {
            int end = Math.min(text.length(), i + PAGE_CHARS);
            if (end < text.length()) { // prefer breaking at a line boundary
                int nl = text.lastIndexOf('\n', end);
                if (nl > i + PAGE_CHARS / 2) end = nl + 1;
            }
            pages.add(Component.text(text.substring(i, end)));
            i = end;
        }
        boolean truncated = i < text.length() || realSize > bytes.length;
        if (truncated) {
            pages.add(Component.text("… truncated —\nthe full file is\n"
                    + humanSize(realSize) + " on disk.", NamedTextColor.DARK_GRAY));
        }
        return pages;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String humanSize(long bytes) {
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
