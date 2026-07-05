package dev.deskmine.fs;

/** One filesystem entry as rendered in a room. */
public record DirEntry(
        String name,
        boolean directory,
        boolean symlink,
        boolean hidden,
        boolean readable,
        long size,
        String linkTarget,   // absolute target path if symlink, else null
        FileCategory category
) {}
