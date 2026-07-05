package dev.deskmine.fs;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Spec 5.2 — file category -> block. */
public enum FileCategory {
    IMAGE(Material.GLOWSTONE),
    TEXT(Material.BOOKSHELF),
    AUDIO(Material.NOTE_BLOCK),
    VIDEO(Material.SEA_LANTERN),
    ARCHIVE(Material.CHEST),
    DOCUMENT(Material.LECTERN),
    EXECUTABLE(Material.FURNACE),
    UNKNOWN(Material.STONE);

    private final Material block;

    FileCategory(Material block) {
        this.block = block;
    }

    public Material block() {
        return block;
    }

    private static final Map<FileCategory, Set<String>> EXTENSIONS = Map.of(
            IMAGE, Set.of("png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp", "heic",
                    "svg", "ico", "icns", "raw", "psd"),
            TEXT, Set.of("txt", "md", "markdown", "log", "java", "py", "js", "ts", "jsx", "tsx",
                    "c", "cpp", "cc", "h", "hpp", "swift", "rs", "go", "rb", "sh", "zsh", "bash",
                    "json", "yaml", "yml", "xml", "toml", "html", "htm", "css", "scss", "kt",
                    "kts", "gradle", "properties", "csv", "tsv", "sql", "lua", "php", "pl"),
            AUDIO, Set.of("mp3", "wav", "aac", "flac", "m4a", "ogg", "aiff", "aif", "wma",
                    "mid", "midi"),
            VIDEO, Set.of("mp4", "mov", "mkv", "avi", "webm", "m4v", "wmv", "flv", "mpg", "mpeg"),
            ARCHIVE, Set.of("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "dmg", "pkg",
                    "jar", "war", "iso"),
            DOCUMENT, Set.of("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "pages", "key",
                    "numbers", "rtf", "epub", "odt", "ods", "odp"),
            EXECUTABLE, Set.of("app", "exe", "bin", "command", "dylib", "so", "dll", "apk",
                    "msi", "run", "out")
    );

    public static FileCategory fromName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return UNKNOWN;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (Map.Entry<FileCategory, Set<String>> e : EXTENSIONS.entrySet()) {
            if (e.getValue().contains(ext)) return e.getKey();
        }
        return UNKNOWN;
    }
}
