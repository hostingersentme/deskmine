package dev.deskmine.room;

/** Which part of the world a room is built in. */
public enum Zone {
    /** Well-known home folder inside the mansion (overworld, ground level). */
    MANSION,
    /** Regular folder carved out beneath the mansion (overworld, underground). */
    UNDERGROUND,
    /** iCloud Drive folder floating at cloud height (overworld, sky). */
    SKY,
    /** Network folder or network-mounted volume (nether dimension). */
    NETHER
}
