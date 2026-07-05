package dev.deskmine.mapping;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Persistent bidirectional index: path <-> room grid cell (spec 4.3).
 * Cells are allocated on a spiral around the origin, in first-materialized
 * order, and never reused — so a path keeps its plot forever, live updates
 * find the right blocks, and any coordinate walks back to a path.
 */
public final class RoomIndex {

    private final Path file;
    private final Logger log;
    private final Map<String, Cell> byPath = new HashMap<>();
    private final Map<Long, String> byCell = new HashMap<>();

    public RoomIndex(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** Returns the cell for a path, allocating (and persisting) a new one if needed. */
    public synchronized Cell cellFor(String path) {
        Cell existing = byPath.get(path);
        if (existing != null) return existing;
        Cell c = null;
        for (int n = 0; c == null; n++) {
            Cell cand = spiral(n);
            if (!byCell.containsKey(cand.key())) c = cand;
        }
        byPath.put(path, c);
        byCell.put(c.key(), path);
        save();
        return c;
    }

    /** Reverse lookup: which path owns this cell (null if unallocated). */
    public synchronized String pathAt(Cell c) {
        return byCell.get(c.key());
    }

    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length != 3) continue;
                Cell c = new Cell(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                String path = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
                byPath.put(path, c);
                byCell.put(c.key(), path);
            }
            log.info("Deskmine index: " + byPath.size() + " room(s) known.");
        } catch (Exception e) {
            log.warning("Failed to load room index: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Deskmine path<->cell index: cx<TAB>cz<TAB>url-encoded-path");
            for (Map.Entry<Long, String> e : byCell.entrySet()) {
                Cell c = Cell.fromKey(e.getKey());
                lines.add(c.x() + "\t" + c.z() + "\t"
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("Failed to save room index: " + e.getMessage());
        }
    }

    /** n-th cell of a square spiral around the origin: 0 -> (0,0), then outward. */
    static Cell spiral(int n) {
        if (n == 0) return new Cell(0, 0);
        int x = 0, z = 0, dx = 1, dz = 0;
        int leg = 1, stepsInLeg = 0, legsDone = 0;
        for (int i = 0; i < n; i++) {
            x += dx;
            z += dz;
            if (++stepsInLeg == leg) {
                stepsInLeg = 0;
                int t = dx;
                dx = -dz;
                dz = t;
                if (++legsDone == 2) {
                    legsDone = 0;
                    leg++;
                }
            }
        }
        return new Cell(x, z);
    }
}
