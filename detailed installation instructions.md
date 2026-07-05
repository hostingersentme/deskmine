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

The good news: on recent macOS versions, Homebrew can install Git, Java, and Gradle with one command.

Older macOS warning: Homebrew may say it needs a full Xcode installation to install Java. That usually means Homebrew cannot use a ready-made Java download for your macOS version and wants to build Java itself. That is not beginner-friendly, and full Xcode may not be available for old macOS versions. If that happens, do not try to build Java with Xcode. Use the older-Mac Java notes in Step 3.

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

On a recent Mac, run this Homebrew install command:

```bash
brew install git openjdk gradle
```

This installs:

- `git`, used to download Deskmine from GitHub.
- `openjdk`, which provides Java.
- `gradle`, which builds the Deskmine plugin.

If that command works, continue below to check the tools.

If Homebrew says it needs full Xcode to install Java, use this split approach instead:

```bash
brew install git gradle
```

Then install Java 25 or newer from a normal `.pkg` JDK installer instead of Homebrew. Good places to look are:

- Eclipse Temurin from Adoptium.
- Azul Zulu.

Choose a macOS JDK package for your Mac type:

- Apple Silicon means newer M1, M2, M3, or M4 Macs.
- Intel means older non-Apple-Silicon Macs.

After installing the JDK package, close Terminal and open it again.

When it finishes, check the tools:

```bash
git --version
java -version
gradle --version
```

For Java, Deskmine needs version 25 or newer.

If `java -version` shows an older version, one of two things can happen:

- If Deskmine can find a newer installed Java anyway, it prints `Using Java: ...` and continues.
- If Deskmine cannot find Java 25 or newer, it stops before starting the server and prints `Paper 26.1.2 requires Java 25+`.

If that happens on a recent macOS version, install or upgrade Java:

```bash
brew install openjdk
brew upgrade openjdk
```

If that happens on an older macOS version and Homebrew asks for full Xcode, install Java from a `.pkg` JDK installer instead.

Then try again:

```bash
./start.command
```

The start script looks for Java 25 using macOS's normal Java registry, Homebrew paths, and your default `java`. It uses the Java 25 installation it finds for both the Minecraft server and the Gradle plugin build.

If no Java 25 JDK installer supports your macOS version, that Mac cannot run this Deskmine/Paper version locally. At that point the practical choices are:

- Update macOS, if the Mac supports it.
- Run the Deskmine server on a newer Mac and join it from the older Mac over LAN.
- Use a future Deskmine build that targets an older Minecraft/Paper/Java combination, if one exists.

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

This means Deskmine could not find Java 25 or newer. Install or update OpenJDK:

```bash
brew install openjdk
brew upgrade openjdk
```

If Homebrew says it needs full Xcode to install Java, stop using Homebrew for Java on that Mac. Install a Java 25 or newer JDK from a `.pkg` installer, such as Eclipse Temurin from Adoptium or Azul Zulu, then close Terminal, open it again, and retry.

Then try again:

```bash
./start.command
```

If `java -version` still shows an older Java afterward, that can be okay as long as `./start.command` prints `Using Java:` with a Java 25 or newer path. If it still stops with the Java 25 warning, the installed JDK is either too old, not registered with macOS, or not supported on that macOS version.

If no Java 25 installer supports that Mac, run the Deskmine server on a newer Mac instead and connect to it from the older Mac over LAN.

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
