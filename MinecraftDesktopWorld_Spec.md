# Minecraft Desktop World — Design Specification

**Version:** 0.1 (read-only MVP + Dock as first interactive element)
**Platform:** macOS
**Status:** Design / early planning

---

## 1. Concept

A live Minecraft world that models the user's macOS system — the filesystem, the frontmost application's menu bar, the system menus, and the Dock. Rather than showing the desktop as a screen inside Minecraft, the system itself *becomes* the world: drives are dimensions, directories are rooms, files are blocks, menus are chests, and the Dock is a row of activatable launchers.

The world is **live** (it reflects changes to the real system as they happen) and **read-only first** (data flows from the system into the world; the world does not modify the system), with one deliberate exception: the Dock, which is the first outbound/interactive feature.

---

## 2. Core principles

1. **Live, not static.** The world reflects the real system state and updates as it changes. This rules out one-time world generation; the build is a running server-side plugin plus watchers.
2. **Read-only first.** Data flows system → world only. Block changes never write back to disk. The one exception is the Dock (launch apps), chosen as a safe, reversible first step into interactivity.
3. **Stable and reversible mapping.** Every path maps deterministically to a fixed world location, and every world location maps back to a path. This is required now (so live updates find the right block) and later (so interactivity knows which file a block represents).
4. **Lazy generation, bounded build.** The world *conceptually* spans whole drives, but only the folders the player has entered are physically built and actively watched. Rooms materialize on entry and can unload on exit.
5. **System layer vs content layer.** The OS's own UI (menu bar, Dock) is visually distinct "chrome," separated from the user's content (files and folders).

---

## 3. Architecture

### 3.1 Components

- **Paper plugin (Java).** Owns the world. Handles chunk load/unload, room generation, block placement, the path↔coordinate index, and the menu-bar / Dock fixtures. Uses Java `WatchService` for filesystem watching.
- **Swift helper (macOS).** A separate local process that bridges to macOS-only APIs the JVM cannot reach:
  - Frontmost application + its menu tree via the **Accessibility API (AXUIElement)**.
  - System status items (Wi-Fi, battery, clock, etc.).
  - The real Dock contents (pinned/running apps).
  - Launching apps via `NSWorkspace` (outbound action).
- **Local socket.** The Swift helper streams updates to the plugin and receives launch requests from it.

### 3.2 Why this split

Everything filesystem-related lives naturally in one Java process (Paper's block API + chunk events + `WatchService`). But Java cannot read another app's menu bar, the system status items, or the Dock — those require the macOS Accessibility API and `NSWorkspace`, which are Swift/Objective-C territory. The Swift helper is therefore required for the UI half, and it is also the correct place to perform app launches rather than shelling out from Java.

### 3.3 Update sources

Three independent update streams feed the world:

1. **Filesystem watcher** (Java `WatchService`, plugin) — file/dir create, delete, modify, move, for currently-loaded directories only.
2. **Menu watcher** (Swift helper) — fires on frontmost-app change; streams the new app's menu tree.
3. **Status poller** (Swift helper) — timer-based; status items (clock, battery, Wi-Fi) update on their own cadence even when nothing else changes.

---

## 4. Spatial mapping (path → world location)

### 4.1 Drives → dimensions

- Main drive → Overworld.
- Each additional volume/external drive → its own dimension.
- Spawn = the user's Desktop / home, treated as the town square.

### 4.2 Directory plots (deterministic subdivision)

- The root (drive) owns the whole dimension's plot.
- A directory subdivides its plot among its children. Children are sorted by name and allocated sub-plots in that fixed order (grid or row layout), each getting a fixed-size or content-scaled sub-plot.
- Because the ordering (sorted names) and allocation rule are fixed, the same path always resolves to the same plot, and a coordinate can be walked back to a path.

### 4.3 Bidirectional index

- The plugin persists a two-way index: `path → plot + slot` and `coordinate → path`.
- Required now for in-place live updates; required later so interactivity can identify the file/folder at a given block.

### 4.4 Lazy generation

- A room's interior is built only when the player enters (triggered by chunk load).
- A `WatchService` is attached to a directory only while its room is loaded, and detached on unload.
- The world represents whole drives (unbounded navigable scope) but only builds/watches loaded rooms (bounded built scope).

---

## 5. Element mapping (system thing → Minecraft representation)

### 5.1 Filesystem

| Filesystem thing | Representation |
|---|---|
| Drive / volume | A dimension (Overworld = main; others = custom dims) |
| Directory | A room / plot; floor area scales with item count |
| Subdirectory | A door / staircase / archway to the child's plot |
| Regular file | A floor block, type by file category (below) |
| Desktop | Spawn / town square |
| Symlink | Portal / teleport pad pointing toward the target's plot |
| Hidden file (dotfile) | Buried, or behind a translucent / barrier wall |
| No read/write permission | Locked door, fence, or barrier around the block |
| File size | Block height / stack, or a sign showing the value |
| Empty folder | Bare room |

### 5.2 File category → block

| Category | Block |
|---|---|
| Image | Painting or filled map |
| Text / code | Bookshelf or lectern (can show contents later) |
| Audio | Note block / jukebox |
| Video | Sea lantern or framed item |
| Archive (zip/tar) | Chest |
| Document (pdf/doc) | Lectern or item frame |
| Executable / app | Beacon or furnace |
| Unknown | Plain stone / dirt |

### 5.3 Overflow / pathological folders

- A folder with thousands of files must not place thousands of individual blocks. Apply a per-room cap: beyond it, aggregate into a "warehouse" representation or paginated shelves.

---

## 6. The menu bar

Rendered as a row of **obsidian** set into the ground (the "bar"), with containers seated on it. Obsidian reads as solid, permanent chrome — visually distinct from the file-blocks that make up content. The bar is part of the standard room template, so it appears in every room (mirroring the real menu bar always being present).

### 6.1 Container types

The two axes are *app-independent vs app-specific* (chest behavior) and *command vs status* (chest type/appearance).

| Menu-bar element | Container | Rationale |
|---|---|---|
| Apple menu | **Ender chest** | System-owned, app-independent, command menu. Ender chests show the same contents everywhere — exactly the semantic of the Apple menu. |
| App-name + File / Edit / View / Go / Window / Help | **Regular chests** | App-specific; contents swap when the frontmost app changes. |
| Wi-Fi, battery, clock, Control Center, etc. | **Minecart chests** | System-owned status items — app-independent, but a visually distinct *kind* (status, not command; "on rails"). |

### 6.2 Menu contents → chest contents

- Each menu → a chest. Menu items → named items in slot order (menu position N → slot N), preserving order and keeping slot ↔ position reversible.
- **Submenus** → nested chests (a container inside a container), mirroring subdirectory = door.
- **Overflow** past 27 items → double chest (54) or paging.
- **Item state** carried on the item:
  - Disabled / greyed-out → different material or a "(disabled)" lore line.
  - Checkmark / toggle on → enchantment glint.
  - Keyboard shortcut → lore text.
  - Separator → empty slot or glass pane.
  - Label → item name.

### 6.3 Layout

- Left-to-right on the obsidian bar, mirroring the real menu bar: ender chest (Apple) → regular chests (app menus) on the left; minecart chests (status items) on the right.

### 6.4 Update rules

- Only the **regular chests** refill on frontmost-app change.
- The **ender chest** (Apple menu) is stable across apps — its contents never depend on the app.
- The **minecart chests** are stable across apps but update on their own cadence (clock ticks, battery drifts) via the status poller.

---

## 7. The Dock

A row of **pressure plates with items floating over them**, part of the room template (the real Dock is always available). A pressure plate models a *launcher* (activate), not a container (open) — the correct primitive for the Dock, which holds single-click launch targets rather than lists.

- **Floating item** over each plate = the app icon.
- **Stepping on the plate** = clicking the Dock item → **the app opens normally** on the real machine.
- Reflects the **real Dock** live: the specific apps the user has pinned/running, read from the system by the Swift helper.

### 7.1 First interactive feature

The Dock is the first place the world writes back to the real system (an *outbound* action), and is deliberately chosen as the safe on-ramp to interactivity:

- **Launching an app is low-stakes and reversible** (worst case: close the app). Good first outbound action.
- **Destructive actions** (e.g. breaking a file-block to delete a file) are far riskier and remain deferred until the read-only visualization is solid and trusted.

### 7.2 Flow

`Player steps on plate` → plugin detects plate event → plugin sends "launch app X" over the socket → Swift helper launches via `NSWorkspace`.

---

## 8. Room template (summary)

Every generated room contains four layers:

1. **File-blocks** — the directory's files, by category (read-only).
2. **Folder-doors** — subdirectories (navigation).
3. **Obsidian menu bar** — ender chest (Apple), regular chests (app menus), minecart chests (status items); read-only.
4. **Dock** — pressure plates with floating app items; the first interactive/outbound element.

A reserved region of each room's plot is set aside for the menu bar and Dock so file-blocks never collide with the chrome, keeping their position consistent across all rooms.

---

## 9. Constraints & known issues

- **Accessibility permission.** The Swift helper needs Accessibility permission (System Settings), a protected API.
- **Lazy menu population.** macOS sometimes only populates a menu's items when it is actually opened, which can make deep menu reads slow or incomplete.
- **`WatchService` on macOS** historically falls back to polling rather than native FSEvents — fine for the handful of loaded directories, unworkable if used to watch a whole drive at once (hence lazy watching).
- **Build height.** Vertical-layout ideas are capped by world height (~320 blocks); the plot-subdivision layout avoids relying on this.
- **Scale.** Real drives have millions of files; only lazy generation + per-room caps keep this tractable.

---

## 10. Build sequencing (suggested)

1. **Paper plugin scaffold** — chunk-load listener, room template, deterministic plot subdivision, path↔coordinate index.
2. **Filesystem half** — directory reader → file-blocks + folder-doors; `WatchService` live updates. (Fully functional without the helper.)
3. **Swift helper** — frontmost app + menu tree via Accessibility; socket to plugin; menu-bar chests.
4. **Status items** — minecart chests + status poller.
5. **Dock** — pressure plates reflecting the real Dock; first outbound launch action via the helper.
6. **Later** — interactivity beyond launch (destructive actions gated carefully).
