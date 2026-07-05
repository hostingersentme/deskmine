package dev.deskmine.fs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads a directory into sorted, capped entry lists.
 * Sorted-by-name ordering is the deterministic allocation rule of spec 4.2;
 * the caps implement the overflow handling of spec 5.3.
 */
public final class DirectoryScanner {

    /** Hard cap on entries examined per scan; guards against pathological folders. */
    private static final int MAX_SCAN = 5000;

    public record ScanResult(
            List<DirEntry> dirs,
            List<DirEntry> files,
            int moreDirs,
            int moreFiles,
            boolean truncatedScan,
            boolean readFailed
    ) {}

    public ScanResult scan(Path dir, int dirCap, int fileCap) {
        List<DirEntry> dirs = new ArrayList<>();
        List<DirEntry> files = new ArrayList<>();
        boolean truncated = false;
        boolean failed = false;
        int seen = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (++seen > MAX_SCAN) {
                    truncated = true;
                    break;
                }
                DirEntry e = read(dir, child);
                if (e == null) continue;
                if (e.directory()) dirs.add(e);
                else files.add(e);
            }
        } catch (IOException | SecurityException ex) {
            failed = true;
        }
        Comparator<DirEntry> byName = Comparator
                .comparing(DirEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DirEntry::name);
        dirs.sort(byName);
        files.sort(byName);
        int moreDirs = Math.max(0, dirs.size() - dirCap);
        int moreFiles = Math.max(0, files.size() - fileCap);
        List<DirEntry> dShown = moreDirs > 0 ? dirs.subList(0, dirCap) : dirs;
        List<DirEntry> fShown = moreFiles > 0 ? files.subList(0, fileCap) : files;
        return new ScanResult(List.copyOf(dShown), List.copyOf(fShown),
                moreDirs, moreFiles, truncated, failed);
    }

    private DirEntry read(Path parent, Path child) {
        try {
            String name = child.getFileName().toString();
            boolean symlink = Files.isSymbolicLink(child);
            boolean isDir = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
            boolean hidden = name.startsWith(".");
            boolean readable = Files.isReadable(child);
            String linkTarget = null;
            if (symlink) {
                try {
                    Path t = Files.readSymbolicLink(child);
                    linkTarget = parent.resolve(t).toAbsolutePath().normalize().toString();
                } catch (IOException ignored) {
                }
                isDir = false; // symlinks render as portals, not doors (spec 5.1)
            }
            boolean appBundle = isDir && name.endsWith(".app");
            if (appBundle) isDir = false; // .app bundles read as launchable things, not folders
            long size = 0;
            if (!isDir && !appBundle) {
                try {
                    size = Files.size(child);
                } catch (IOException ignored) {
                }
            }
            FileCategory cat = appBundle ? FileCategory.EXECUTABLE
                    : isDir ? FileCategory.UNKNOWN
                    : FileCategory.fromName(name);
            return new DirEntry(name, isDir, symlink, hidden, readable, size, linkTarget, cat);
        } catch (Exception e) {
            return null;
        }
    }
}
