# Installation Instructions

This guide installs Deskmine from the public GitHub repository and starts a local Paper server. On first run, Deskmine generates a Minecraft world where the well-known home folders - Desktop, Documents, Downloads, Pictures, Music, Movies, Public, and Library - form a mansion on high ground. Desktop is the entrance hall and spawn room.

## Requirements

- macOS.
- Minecraft Java Edition client matching the server version used by this project, currently 26.1.x.
- Java 25 or newer.
- Git, so you can download the project.
- Accessibility permission for the terminal app that starts the server if you want menu-bar chests and real menu clicks to work.

Homebrew install examples:

```bash
brew install git
```

Install Java 25 or newer from Homebrew or from a normal macOS JDK package. Gradle does not need to be installed separately; Deskmine includes a Gradle wrapper that downloads the right Gradle version on first build.

## Download Deskmine

Clone the public repository:

```bash
git clone https://github.com/hostingersentme/deskmine.git
cd deskmine
```

## Start The Server

Run the launcher:

```bash
./start.command
```

If Terminal says permission is denied, or Finder says you do not have permission to open `start.command`, run this once:

```bash
chmod +x start.command server-setup.sh stop.command reset-world.command lan-on.command lan-off.command deskmine-plugin/gradlew
```

Then run `./start.command` again.

The first launch may take a while. The script downloads Paper if needed, downloads Gradle through the included wrapper if needed, builds the Deskmine plugin, installs it into the local `server/plugins` folder, and asks you to accept the Minecraft server EULA.

When the server is ready, open Minecraft Java Edition. The server may not appear automatically in the multiplayer server list; that is okay. Use:

```text
Multiplayer > Direct Connection
```

Then enter:

```text
localhost
```

## Get The Mansion Layout

Use a fresh Deskmine server world for the intended first-run layout. With the default configuration, Deskmine uses your home folders and creates:

- Desktop as the entrance hall and spawn room.
- Documents, Downloads, Pictures, Music, Movies, Public, and Library as connected mansion rooms.
- Regular subfolders as rooms underground beneath the mansion.
- iCloud Drive as cloud-height rooms, if present.
- Network folders in the nether path, if present.

The mansion site is saved after first generation in:

```text
server/plugins/Deskmine/mansion.tsv
```

The folder grid for rooms discovered later is saved in:

```text
server/plugins/Deskmine/index.tsv
```

If you want to regenerate the mansion from scratch, stop the server and run:

```bash
./reset-world.command
```

Then start again:

```bash
./start.command
```

## macOS Permissions

Deskmine can mirror the macOS menu bar into Minecraft chests and can trigger real menu actions. For that to work, macOS must allow Accessibility access for the terminal app that runs the server.

Open:

```text
System Settings > Privacy & Security > Accessibility
```

Enable the terminal app you use, such as Terminal, iTerm2, or another shell host. Restart the server after changing the permission.

## Useful Commands In Game

```text
/dm spawn
/dm goto <path>
/dm up
/dm whereami
/dm refresh
```

## LAN Mode

By default the server binds to `127.0.0.1`, so only the same Mac can join. To allow other devices on your local network:

```bash
./lan-on.command
```

To return to local-only mode:

```bash
./lan-off.command
```

## Troubleshooting

- If the server says Java is too old, install Java 25 or newer and make sure your terminal finds that version first.
- If Gradle appears to be missing, update your checkout from GitHub. Deskmine includes `deskmine-plugin/gradlew`, so a separate Gradle install should not be needed.
- If Finder says you do not have permission to open `start.command`, run `chmod +x start.command server-setup.sh stop.command reset-world.command lan-on.command lan-off.command deskmine-plugin/gradlew` from the Deskmine folder.
- If the menu chests show an Accessibility warning, grant Accessibility permission and restart the server.
- If the world does not look like a new high-ground mansion, stop the server, run `./reset-world.command`, and start again.
- If the server does not appear in the Minecraft server list, use Multiplayer > Direct Connection and enter `localhost`.
- If Minecraft cannot connect, confirm the server console says it is listening and try Direct Connection to `localhost` or `127.0.0.1`.
