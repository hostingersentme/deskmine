package dev.deskmine.dock;

/** One pinned Dock app. */
public record DockItem(String label, String bundleId, String path) {

    /** Argument used to launch: an absolute .app path, or a bundle id. */
    public String launchArg() {
        return path != null ? path : bundleId;
    }
}
