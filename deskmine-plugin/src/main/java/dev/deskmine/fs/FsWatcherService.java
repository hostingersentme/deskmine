package dev.deskmine.fs;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * One WatchService shared by all loaded rooms (spec 3.3 / 4.4).
 * Directories are registered while their room is loaded and cancelled on
 * unload, so the watched set stays bounded. Events are collapsed into a
 * "dirty directory" set that the main thread drains once a second.
 */
public final class FsWatcherService {

    private final Logger log;
    private final Map<String, WatchKey> keysByPath = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> pathsByKey = new ConcurrentHashMap<>();
    private final Set<Path> dirty = ConcurrentHashMap.newKeySet();
    private WatchService watchService;
    private Thread thread;
    private volatile boolean running;

    public FsWatcherService(Logger log) {
        this.log = log;
    }

    public void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.warning("Filesystem watching unavailable: " + e.getMessage());
            return;
        }
        running = true;
        thread = new Thread(this::run, "deskmine-fs-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (running) {
            try {
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                key.pollEvents(); // we rescan the whole dir; event details are not needed
                Path dir = pathsByKey.get(key);
                if (dir != null) dirty.add(dir);
                key.reset();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
        }
    }

    public void watch(Path dir) {
        if (watchService == null) return;
        String k = dir.toString();
        if (keysByPath.containsKey(k)) return;
        try {
            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            keysByPath.put(k, key);
            pathsByKey.put(key, dir);
        } catch (IOException | SecurityException e) {
            log.fine("Cannot watch " + dir + ": " + e.getMessage());
        }
    }

    public void unwatch(Path dir) {
        WatchKey key = keysByPath.remove(dir.toString());
        if (key != null) {
            key.cancel();
            pathsByKey.remove(key);
        }
    }

    /** Returns and clears the set of directories with pending changes. */
    public Set<Path> drainDirty() {
        if (dirty.isEmpty()) return Set.of();
        Set<Path> out = new HashSet<>(dirty);
        dirty.removeAll(out);
        return out;
    }

    public void close() {
        running = false;
        if (thread != null) thread.interrupt();
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {
        }
    }
}
