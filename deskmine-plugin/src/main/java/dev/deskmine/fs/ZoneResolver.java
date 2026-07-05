package dev.deskmine.fs;

import dev.deskmine.DeskmineConfig;
import dev.deskmine.room.Zone;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which zone a directory belongs to (mansion placement is decided
 * separately by the MansionPlan):
 *
 *   - iCloud Drive and anything inside it        -> SKY (rooms in the clouds)
 *   - /Network, and network-mounted volumes
 *     under /Volumes (smb/afp/nfs/webdav)        -> NETHER (through the portal)
 *   - everything else                            -> UNDERGROUND
 */
public final class ZoneResolver {

    private static final Path VOLUMES = Path.of("/Volumes");
    private static final Set<String> NETWORK_FS_TYPES = Set.of(
            "smbfs", "afpfs", "nfs", "nfs4", "webdav", "cifs", "ftp", "acfs");

    private final Path icloudRoot;
    private final Path networkRoot;
    private final Map<String, Boolean> volumeIsNetwork = new ConcurrentHashMap<>();

    public ZoneResolver(DeskmineConfig cfg) {
        this.icloudRoot = cfg.icloudDir().toAbsolutePath().normalize();
        this.networkRoot = cfg.networkDir().toAbsolutePath().normalize();
    }

    public Path icloudRoot() { return icloudRoot; }
    public Path networkRoot() { return networkRoot; }

    public Zone zoneFor(Path dirRaw) {
        Path dir = dirRaw.toAbsolutePath().normalize();
        if (dir.startsWith(icloudRoot)) return Zone.SKY;
        if (dir.startsWith(networkRoot)) return Zone.NETHER;
        Path vol = volumeRootOf(dir);
        if (vol != null && isNetworkVolume(vol)) return Zone.NETHER;
        return Zone.UNDERGROUND;
    }

    /** Directory depth below its zone's root folder (0 = the zone root itself). */
    public int depthInZone(Path dirRaw) {
        Path dir = dirRaw.toAbsolutePath().normalize();
        Path base = null;
        if (dir.startsWith(icloudRoot)) base = icloudRoot;
        else if (dir.startsWith(networkRoot)) base = networkRoot;
        else {
            Path vol = volumeRootOf(dir);
            if (vol != null) base = vol;
        }
        if (base == null || dir.equals(base)) return 0;
        try {
            return base.relativize(dir).getNameCount();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** /Volumes/<name> ancestor of a path, or null if not under /Volumes. */
    private static Path volumeRootOf(Path dir) {
        if (!dir.startsWith(VOLUMES) || dir.getNameCount() < 2) return null;
        return VOLUMES.resolve(dir.getName(1));
    }

    /** Whether a /Volumes entry is a network mount (cached; filesystem type check). */
    private boolean isNetworkVolume(Path vol) {
        return volumeIsNetwork.computeIfAbsent(vol.toString(), k -> {
            try {
                if (!Files.isDirectory(vol)) return false;
                FileStore store = Files.getFileStore(vol);
                return NETWORK_FS_TYPES.contains(store.type().toLowerCase(Locale.ROOT));
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** Friendly display name for special roots; plain folder name otherwise. */
    public String displayName(Path dirRaw) {
        Path dir = dirRaw.toAbsolutePath().normalize();
        if (dir.getNameCount() == 0) return "Macintosh HD";
        if (dir.equals(icloudRoot)) return "iCloud Drive";
        if (dir.equals(networkRoot)) return "Network";
        Path name = dir.getFileName();
        return name == null ? dir.toString() : name.toString();
    }
}
