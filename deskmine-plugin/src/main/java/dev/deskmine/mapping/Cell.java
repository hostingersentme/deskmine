package dev.deskmine.mapping;

/** A room's cell on the world grid. */
public record Cell(int x, int z) {

    public long key() {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    public static Cell fromKey(long k) {
        return new Cell((int) (k >> 32), (int) k);
    }
}
