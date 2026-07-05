# Detailed Installation Instructions

This is the beginner-friendly version. It assumes you have never used GitHub, Homebrew, Terminal, Java, Gradle, or a local Minecraft server before.

By the end, you should be able to open Minecraft Java Edition, join `localhost`, and spawn inside Deskmine: a Minecraft world where your well-known Mac home folders form a mansion on high ground.

The mansion rooms are:

- Desktop, which is the entrance hall and spawn room.
- Documents.
- Downloads.
- Pictures.
- Music.
- Movies.
- Public.
- Library.

Deskmine is read-only. It looks at your real folders, but breaking blocks or opening books in Minecraft does not delete or edit your real files.

## What You Need

You need:

- A Mac.
- Minecraft Java Edition.
- A Minecraft client version matching the Deskmine server version, currently 26.1.x.
- Homebrew, which is a common Mac installer for developer tools.
- Java 25 or newer.
- Gradle, which builds the Deskmine plugin.
- Git, which downloads the Deskmine source from GitHub.

The good news: Homebrew can install Git, Java, and Gradle with one command.

## Step 1: Open Terminal

Open the Terminal app:

1. Open Finder.
2. Go to Applications.
3. Open Utilities.
4. Open Terminal.

You will see a window with a text prompt. You can copy commands from this file, paste them into Terminal, and press Return.

## Step 2: Install Homebrew If You Do Not Have It

First check whether Homebrew is already installed:

```bash
brew --version
```

If Terminal prints a Homebrew version number, you already have it. Continue to Step 3.

If Terminal says `command not found: brew`, install Homebrew with this command:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Homebrew may print extra instructions at the end. Follow them if it asks you to add Homebrew to your shell profile.

After installing, close Terminal, open Terminal again, and check:

```bash
brew --version
```

## Step 3: Install Git, Java, And Gradle

Run this Homebrew install command:

```bash
brew install git openjdk gradle
```

This installs:

- `git`, used to download Deskmine from GitHub.
- `openjdk`, which provides Java.
- `gradle`, which builds the Deskmine plugin.

When it finishes, check the tools:

```bash
git --version
java -version
gradle --version
```

For Java, Deskmine needs version 25 or newer. If `java -version` shows an older version, do not panic. The Deskmine start script also checks the common Homebrew Java locations directly.

## Step 4: Choose Where To Put Deskmine

A simple place is your home folder. In Terminal, run:

```bash
cd ~
```

This means "go to my home folder".

## Step 5: Download Deskmine From GitHub

Run:

```bash
git clone https://github.com/hostingersentme/deskmine.git
```

This creates a folder called `deskmine`.

Go into it:

```bash
cd deskmine
```

You should now be inside the Deskmine project folder.

## Step 6: Start Deskmine

Run:

```bash
./start.command
```

The first start does several things:

- Creates a local `server` folder.
- Downloads the Paper Minecraft server.
- Builds the Deskmine plugin.
- Copies the plugin into the server.
- Asks whether you accept the Minecraft server EULA.
- Starts the server.

When it asks about the EULA, type:

```text
y
```

Then press Return.

The first start can take a while because the Minecraft server and world are being created.

## Step 7: Join From Minecraft

Open Minecraft Java Edition.

Use a client version matching the server version, currently 26.1.x.

Then:

1. Click Multiplayer.
2. Click Direct Connection.
3. Enter:

```text
localhost
```

4. Click Join Server.

You should spawn in the Desktop room, which is the entrance hall of the mansion.

## Step 8: Confirm The Mansion Appeared

On a fresh world, Deskmine places the well-known home folders as a mansion on high ground.

You should see:

- Desktop as the main entrance hall.
- Connected rooms for Documents, Downloads, Pictures, Music, Movies, Public, and Library.
- Doors and signs naming the folders.
- A staircase connecting the mansion floors.
- A porch outside the entrance.

The first run stores the mansion layout here:

```text
server/plugins/Deskmine/mansion.tsv
```

It also stores discovered folder-room positions here:

```text
server/plugins/Deskmine/index.tsv
```

You normally do not need to edit these files.

## Step 9: Give macOS Accessibility Permission

This step is optional for seeing the mansion, but useful for the full Deskmine experience.

Deskmine can show your Mac menu bar as Minecraft chests. It can also click real menu items when you click items in those chests. macOS blocks that unless you allow Accessibility access.

Open:

```text
System Settings > Privacy & Security > Accessibility
```

Enable the app that is running the server. Usually this is Terminal.

After changing this permission, stop and restart Deskmine.

## Step 10: Stop The Server Safely

In the Terminal window where the server is running, type:

```text
stop
```

Then press Return.

You can also use the included stop script from another Terminal window while inside the `deskmine` folder:

```bash
./stop.command
```

## Starting Again Later

Open Terminal and go back to the Deskmine folder:

```bash
cd ~/deskmine
```

Start it:

```bash
./start.command
```

Then open Minecraft and join:

```text
localhost
```

## Regenerate The Mansion From Scratch

If the world is not fresh, or you want Deskmine to build the high-ground mansion again, stop the server first.

Then run:

```bash
./reset-world.command
```

Start again:

```bash
./start.command
```

Join `localhost` in Minecraft again.

## Let Other Computers Join On Your Home Network

By default, Deskmine only listens on your own Mac at `localhost`.

To allow other computers on your local network to join, run this from the `deskmine` folder:

```bash
./lan-on.command
```

Then start the server:

```bash
./start.command
```

The server will print the local network address that other players can use.

To return to safer local-only mode:

```bash
./lan-off.command
```

## Useful In-Game Commands

Type these in Minecraft chat:

```text
/dm spawn
```

Teleports you back to the Desktop entrance hall.

```text
/dm whereami
```

Shows which real folder the current room represents.

```text
/dm goto <path>
```

Goes to a real folder path. Example:

```text
/dm goto ~/Downloads
```

```text
/dm up
```

Moves to the parent folder.

```text
/dm refresh
```

Rebuilds the current room from the real filesystem.

## Common Problems

### Terminal says `brew: command not found`

Homebrew is not installed, or Terminal cannot find it. Go back to Step 2.

### Terminal says `git: command not found`

Install Git:

```bash
brew install git
```

### Terminal says Java is too old

Install or update OpenJDK:

```bash
brew install openjdk
brew upgrade openjdk
```

Then try:

```bash
./start.command
```

### Terminal says Gradle is missing

Install Gradle:

```bash
brew install gradle
```

### Minecraft cannot connect to `localhost`

Check that the server is still running in Terminal. Wait until the server finishes starting, then try again.

### Menu chests do not work

Grant Accessibility permission to Terminal, then restart the server.

### The mansion is not on a fresh high-ground layout

Stop the server, reset the world, and start again:

```bash
./reset-world.command
./start.command
```

### You want to update Deskmine later

Stop the server first. Then run:

```bash
cd ~/deskmine
git pull
./start.command
```

The start script rebuilds and installs the latest plugin code.
