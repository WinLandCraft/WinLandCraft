# WinLandCraft

A Minecraft 1.21.4 / Fabric mod that adds a VR-like desktop environment experience right into your game.

## Requirements

- Minecraft 1.21.4 with Fabric Loader 0.16.9+
- Fabric API 0.119.4+1.21.4
- The stock MCEF mod (it downloads its browser files on first launch, so you need internet)
- The WinLandCraft mod JAR in your `mods` folder

Dedicated servers only need the WinLandCraft JAR plus Fabric API.

Do keep in mind this mod does work client-side only, although not tested thoroughly. On a server without with the required mods, you should still be able to use this mod on your side.

## Quick start

1. Open **Options > Controls > Key Binds > WinLandCraft** and bind **Get controls**.
2. Press it in-game to get the **Tasks** and **Laser Pointer** items.
3. Right-click **Tasks** to open the taskbar, then click **Apps**.
4. Click **Browser** to open the web browser. Clicking it again recalls the same window instead of opening a second one.

### Custom webapps

Want a site as its own app window without the browser sidebar?

1. Go to **Options > WinLandCraft... > Manage webapps... > Add webapp**.
2. Enter a name and URL, then **Save and get icon**.
3. Open it anytime from **Apps**. Each webapp stays open alongside the Browser and other apps.

## Built-in apps

Open these from **Apps**:

- **File Manager** — browse your computer's files, also supports opening files, if there is an app that supports such filetypes. It can only read files; it can't create, rename, or delete them.
- **Notepad** — plain-text editor, inspired by Windows 11's notepad. Drag a text file from File Manager onto it, or just open a file. Save your work before quitting Minecraft — unsaved drafts are lost.
- **Image Viewer** — drop an image onto it or double-click an image file to view it. Supports PNG, JPEG, GIF, WebP, AVIF, BMP, ICO, and SVG.
- **Task Manager** — shows how much CPU/RAM the browser processes are using.
- **Browser** — Click the URL bar, type a website or search. Click a text field on a page, then press **G** to type, this locks your keys on a window, works with anything, not just the browser. Press **Esc** to stop typing and get your Minecraft keys back. Tabs are not saved between sessions.

## Moving and resizing windows

- Aim near any window edge to reveal its floating title handle, then hold left-click on the app name to move it. Scroll while moving to push it farther away or pull it closer.
- Browser and webapp corners have resize handles: drag one to change the page size, or hold **Ctrl** first to scale it without changing its resolution.
- Place two windows edge-to-edge and click **Group** to stick them together so they move and resize as one. **Ungroup** detaches them.
- Some windows can be bent with the **Curve** slider on the expanded handle.
- **Options > WinLandCraft...** has settings for panel rotation, interaction range, pointer style (gaze hover vs. laser), screen lighting, and removing size limits.

## Plugins: extra apps from other mods

Plugin mods add new apps to WinLandCraft - video players, capture tools, games, and more.

### Installing plugins (players)

1. Download the plugin's JAR (it will say it requires WinLandCraft).
2. Put it in your `mods` folder next to the WinLandCraft JAR. You do not need anything else — plugins share WinLandCraft's built-in browser.
3. Launch the game. The new apps show up automatically in **Apps** with their own icons.
4. Use them like any built-in app: open, move, resize, group, and close them the same way.

Notes:

- Only the player who has the plugin installed sees its windows. Viewers without the plugin cannot see them.
- If a plugin supports files (e.g. a media player), it appears in File Manager's placement choices and accepts dragged files, just like Notepad and Image Viewer.
- If an app misbehaves, removing its plugin JAR from `mods` removes it from Apps.

### Making plugins (developers)

See [PLUGINS.md](PLUGINS.md) for the v1 native/Chromium API and [PLUGINS-V2.md](PLUGINS-V2.md) for the v2 frames/audio API. The installable example lives in the separate `WLCP-Example-Plugin` project.

## Things to know

- Window positions are temporary: they reset when you die, change dimension, disconnect, or quit.
- The first launch takes a while — MCEF downloads and verifies its browser runtime.

The full technical details, including adblocking, icons, rendering, and screen lighting, live in [docs/](docs/). The previous long README is kept as [README.backup.md](README.backup.md).
