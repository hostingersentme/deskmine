# Deskmine

Implementation of `MinecraftDesktopWorld_Spec.md`, evolved: your Mac's filesystem as a live Minecraft world with real terrain and mobs. The well-known home folders (Desktop, Documents, Downloads, Pictures, Music, Movies, Public, Library) form a **mansion on high ground** — Desktop is the entrance hall/spawn, rooms connect through regular doors (with name signs) and a staircase to the upper floor. Regular folders are carved out **beneath the mansion** on first access; **iCloud Drive floats at cloud height**; **Network folders live in the nether**, through the obsidian portal beside the porch; **Macintosh HD** (`/`) has its own door beside the front entrance, and the scope now spans the whole drive so you can always walk back up out of your home folder. Block changes never write back to disk; rooms rebuild from the real filesystem. Outbound actions: Dock pressure plates open real apps; clicking an item in a menu chest performs that menu action; text files open in-game as read-only books.

## What's here

    deskmine-plugin/   Paper plugin (Gradle, Java 25, targets Paper 26.1.2)
    server-setup.sh    Downloads Paper, builds + installs the plugin, runs the server
    start.command      Double-click to start; stop.command to stop

## Requirements

Java 25+ and Gradle, and a Minecraft Java Edition client matching the server version (26.1.x). The menu bar needs Accessibility permission for the process running the server (System Settings → Privacy & Security → Accessibility → your terminal).

## Quick start

    ./start.command

Join `localhost`. You spawn in the mansion's entrance hall — your Desktop. The first start takes longer: terrain generates and the mansion gets sited on the highest dry ground near the origin (persisted in `plugins/Deskmine/mansion.tsv`). The old void world folder (`server/deskmine`) is unused and can be deleted.

Commands: `/dm spawn`, `/dm goto <path>`, `/dm up`, `/dm whereami`, `/dm refresh`.

## The world

**Mansion (well-known folders).** A 3×2-room, two-story mansion; neighbouring rooms share walls and connect with regular themed doors you walk through — each with a name sign on both sides, like the folder doors elsewhere. A spruce staircase in the hall leads to the landing and the upper rooms. Each mansion room also has a warped teleport door back to your home folder, and its subfolders get teleport doors on the north/east walls. A porch steps down to the terrain from the hall's double front door, with the Network nether portal beside it. Flanking the front doors inside the hall: a copper door to **Macintosh HD** (the `/` room, deepslate-themed) and a birch door up to **iCloud Drive**.

**Underground (regular folders).** Any other folder materializes on first access as a room carved out of the rock beneath the mansion, on a persistent grid (`plugins/Deskmine/index.tsv`, spec 4.3) — deeper directories sit physically lower (`mansion.cellar-drop`, `room.depth-step`). Rooms are sealed and lit (sea-lantern ceilings) so nothing spawns inside; the terrain outside is vanilla, with mobs at night (`world.mobs`).

**Clouds (iCloud Drive).** Rooms for `~/Library/Mobile Documents/com~apple~CloudDocs` and everything inside it are white-wool platforms floating at cloud height — deeper folders sit higher in the stack. Enter through the hall's iCloud door.

**Nether (Network).** `/Network` and any network-mounted volume under `/Volumes` (smb/afp/nfs/webdav) build their rooms in a real nether dimension, carved from the netherrack in nether brick. Step through the obsidian portal beside the porch to reach `/Network`; the portal inside that room brings you home. Network volumes are reached through their doors in the `/Volumes` room.

**Room layout (identical everywhere).** Entry is the south-wall center. Menu-bar chests line the entry wall on the obsidian strip: the Apple menu is the ender chest (spec 6.1 — same contents everywhere, implemented per-player), marked by a floating apple, and each app menu is a named chest whose slots mirror menu positions — paper for items, gray dye for disabled ones, glass panes for separators, chest-items marking submenus. Chests refill when the frontmost app changes. **Clicking an item in a chest performs that menu action for real** (e.g. System Settings in the Apple menu opens System Settings); disabled items, separators, and submenus are inert. Since the frontmost app is usually the game itself while you play, a lever in the spawn hall (east wall) pins the chests to **Finder's** menus instead — persisted in `menubar.finder-menus`. Menus are bulk-read in a few Apple Events per menu; if macOS denies Accessibility permission, a sign on the strip says so.

The **Dock** runs along the west wall — pressure plates with floating named app items, one per pinned app; stepping on a plate opens the app for real. It is **hidden by default** so mobs can't trip the plates: each room has a "show dock" lever on the west wall. The spawn hall's east wall has the hide-hidden-files lever (global, persisted).

**Files** are item frames on their category blocks (spec 5.2 palette), each with a filename sign: a **book & quill** for text/code, a **book** for other files, and for `.app` bundles the app's icon item in a frame on a furnace pedestal. Frames are fixed and theft-proof. **Right-clicking a text/code file opens its real contents as an in-game book** (read-only; capped at ~100 KB, binary files politely decline); other files show their details in chat. Symlinks stay crying-obsidian portals, locked files iron bars, hidden files tinted glass; overflow collapses into "+N more" markers and scans stop at 5 000 entries (5.3).

**Live updates** (4.4): rooms rebuild in place when the OS reports changes; idle underground rooms detach their watcher and unload (mansion rooms never unload). The world is strictly read-only — block break/place/sign-edit cancelled, menu chests and frames guarded.

## Configuration

`server/plugins/Deskmine/config.yml` after first run: `root` (scope, default the whole drive), `spawn-dir` (hall folder, default ~/Desktop), `world.name` / `world.mobs`, `mansion.room-size` / `mansion.cellar-drop`, `room.*` for the underground grid, plus `menubar.*` (including `finder-menus`), `dock.*`, `hidden.hide`, `watch.unload-after-seconds`. Changing `mansion.room-size` re-sites the mansion on next start.

## Known limitations

macOS `WatchService` polls, so live updates can lag a few seconds. The mansion's slot count caps at 9 well-known folders + hall; the rest of home lives underground. First-run terrain + mansion siting takes noticeably longer than later starts (and the nether dimension adds a one-time generation cost). Menu clicks bring the target app frontmost on the real Mac — that's the point, but it means the game window loses focus. Submenu items aren't clickable yet.

## Next phases

Status items as minecart chests (phase 4) on the entry wall's remaining spots, and the Swift helper to replace osascript for faster, richer menu reads.
