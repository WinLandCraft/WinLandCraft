# WinLandCraft

Minecraft 1.21.4 / Fabric mod targeting Windows, Linux, and macOS: a world-anchored taskbar, Apps launcher, a Browser with vertical tabs, user-defined 1280x720 webapp panels sharing MCEF, and an experimental player-hosted browser stream relay.

## Setup and build

- JDK 21 (Temurin), Fabric Loader 0.16.9+, Fabric API 0.119.4+1.21.4, MCEF 2.1.6-1.21.4 (Fabric).
- Gradle 8.12 and Loom 1.9.2 are pinned; use the included wrapper.
- Linux build: `./dev.sh build`; development client: `./dev.sh runClient`. The helper locates JDK 21, keeps Gradle caches inside the checkout, and works even if the wrapper's executable bit was not preserved.
- macOS or direct wrapper use: `sh gradlew build` with `JAVA_HOME` pointing to JDK 21.
- Build: `powershell -ExecutionPolicy Bypass -File .\dev.ps1 build`
- Separate development client: `powershell -ExecutionPolicy Bypass -File .\dev.ps1 runClient`
- The helper scripts discover JDK 21 and store Gradle caches locally. An IDE can import build.gradle with JDK 21.
- Replace the old Prism mod JAR with `build/libs/winlandcraft-0.1.39-dev.jar`. Keep only one WinLandCraft version installed, alongside Fabric API and MCEF.
- MCEF downloads native browser files on its first launch. Online websites need an internet connection.

## Browser(streamable): video and audio

Open **Apps > Browser(streamable)** (purple compass). The creator runs the website locally and broadcasts its complete browser surface, including sidebar, tabs, titlebar, and quality controls. Other modded players in the same dimension see a video panel in the same world position. It is read-only by default; the creator can check **Allow remote control** to let those players point, click, scroll, and type in the shared browser. Only the creator can move, resize, scale, curve, close, or change stream settings. Shared/private grouping remains disabled.

The sidebar's **Stream quality** section sits above the tabs. Use its minus/plus buttons to change:

- Target FPS: 15, 24, 30 (default), or 60.
- Video bitrate: 500, 1,000, 2,000 (default), 4,000, 6,000, or 8,000 kbps.
- Maximum resolution: 360p, 480p, 720p (default), or 1080p. The full panel fits inside the selected 16:9 bounds without changing its aspect ratio or upscaling its internal resolution.
- Broadcast audio: ON (default) or OFF.
- Opus audio bitrate: 64, 96 (default), 128, or 192 kbps.
- Allow remote control: OFF by default. When checked, other players in the same dimension can operate the shared browser until it is unchecked or the stream ends.

Settings apply live and persist in `config/winlandcraft.json`. Remote input is relayed only while the checkbox is enabled, is bound to the active stream and dimension, and cannot reposition or close the replica. Controller identity, packet bounds, and rate are validated by the server; held keys/buttons are released on timeout, disconnect, or permission changes. System-modifier shortcuts are not forwarded, so a remote player cannot invoke the creator's clipboard shortcuts. Video selection tries hardware-preferred Annex-B H.264 first, hardware-preferred VP9 second, and software VP9 as the compatibility fallback; the receiver independently prefers hardware decoding for the selected codec. One-second keyframes support late joins and recovery. Audio is stereo 48 kHz Opus. The selected streamable-browser tab supplies audio through CEF's browser-scoped audio handler. Desktop audio, microphone input, private browsers, and inactive tabs are not broadcast. Because CEF capture replaces native audio output, a bounded Java Sound playback queue keeps the creator's selected tab audible even when broadcast audio is off.

Encoding and playback use [Chromium's WebCodecs APIs](https://www.w3.org/TR/webcodecs/) through small internal MCEF views. A private loopback HTTP bridge transfers binary buffers between Java and these trusted codec pages; it binds only to 127.0.0.1, uses unguessable endpoint tokens, rejects foreign origins/hosts, and exposes no filesystem access. It is not an externally hosted service. Receiving players decode the stream in a local canvas/Web Audio view; they never load the source website. No FFmpeg installation, codec download, microphone permission, or new externally reachable port is needed. Unsupported codec or device errors appear in the app/log instead of falling back silently to JPEG.

On Linux, WinLandCraft enables Chromium 116's `VaapiVideoDecoder` and `VaapiVideoEncoder` features before MCEF initializes, selects Chromium's EGL backend, and applies the GPU-blocklist override. WebCodecs still probes every configuration before use and falls back safely when the driver, platform, or bundled CEF build does not expose a requested codec/acceleration combination.

Audio capture follows [CEF's audio-handler API](https://cef-builds.spotifycdn.com/docs/116.0/classCefAudioHandler.html). Receiver audio uses a small timestamped buffer, while video is presented immediately for low latency. Periodic keyframes support late joins and recovery. Bounded media queues avoid accumulating unlimited delay. Actual FPS depends on Minecraft rendering, Chromium, CPU/GPU capacity, and the connection.

Decoded video is painted directly from WebCodecs output callbacks. It does not use short JavaScript intervals, because Chromium throttles timers in hidden off-screen browser views. The loopback media endpoints use bounded long-polling so idle streams do not spin on empty HTTP requests and active frames wake the codec worker immediately. Capture FPS is not passed as a WebCodecs capability constraint: cadence is already enforced before encoding, and this keeps an available hardware encoder selected across live FPS changes.

Install this same build on **all clients and the world host/server**: stream protocol v5 adds opt-in remote-control packets and intentionally does not interoperate with older builds. Clients require Fabric API and MCEF; dedicated servers require only Fabric API and WinLandCraft. Essential/LAN still use the integrated server's existing Minecraft connection. The server validates ownership, media envelopes, and controller permission, then forwards bounded data; it never runs Chromium or encodes/decodes media. This remains a TCP Minecraft relay, not WebRTC. Bitrate increases also increase the world's host/relay bandwidth usage for every viewer.

The audio handler retains the sample rate from `getAudioParameters`, because MCEF 2.1.6's native adapter passes null parameters to `onAudioStreamStarted`. Unknown parameters disable capture instead of guessing a sample rate. Raw PCM crosses the loopback bridge in timestamp-preserving batches, avoiding dozens of sequential HTTP round trips per second; Opus backpressure is drained rather than converted into audible holes. Receiver audio uses a recoverable clock: a timestamp discontinuity or late packet re-anchors playback instead of leaving all later Opus packets permanently outside the scheduling window.

Streaming writes rate-limited health summaries to `logs/latest.log` on both clients and the server. Every ten seconds these identify raw capture, selected H.264/VP9 acceleration preference, Opus encode/decode, relay packet and budget drops, keyframe waits, queue resets, rendered frames, and the Web Audio state. Linux capture also records the OpenGL vendor/renderer and sampled RGB range; a solid-color source frame is called out explicitly. Search the log for `Stream capture`, `Stream audio`, `Stream codec`, or `Stream relay` when reporting a failure.

The relay allows one stream per player, up to 16 simultaneously, and physical window dimensions up to 4,096 blocks. Media units are limited to 768,000 bytes and split into at most 32 payloads of 24,000 bytes. Server-side media and per-controller token buckets bound relay traffic. Late joiners receive window state and wait for the next keyframe. Closing the app, owner death, dimension changes, and disconnect stop the stream; missing heartbeats expire after 10 seconds. Sessions and window placement are not persisted.

The build runs automated media/control-envelope, permission, batching, queue-bound, relay, geometry, and packaging checks. Native CEF codec selection, GPU acceleration, cross-platform audio, remote input, and the Linux OpenGL capture path still require an in-game test on the target drivers.
## Controls

**Options > WinLandCraft...** opens the mod settings page. **Free panel rotation** defaults to OFF: panels stay upright with horizontal top/bottom edges while still turning horizontally during dragging. Enable it to restore unrestricted tilt. Changes apply immediately to open panels and save in `config/winlandcraft.json`. Opening/recalling panels respects this preference; the attached start menu inherits the taskbar's orientation.

## File Manager

Open **Apps > File Manager** (yellow folder icon). It opens your home directory and shows quick locations on the left, with directories and files on the right. Double-click a folder to enter it; use Back, Forward, Up, Reload, or click the path bar and type/paste a path, then Enter. Esc returns to Minecraft controls. Scroll the file list or sidebar to see more entries. Existing Desktop/Downloads/Documents/Pictures/Music/Videos folders and filesystem roots appear in the sidebar; Linux XDG user-directory settings and macOS Movies are recognized.

This version only reads directory names and metadata. Selecting files does not open them, and no create/delete/rename/move operations exist. Listings load in the background, directories appear first, and errors/empty folders have explicit states. Very large directories show at most 10,000 entries. File Manager is native (no Chromium view) and supports titlebar dragging, resizing, grouping, and curving. Closing and reopening starts at home.

## Notepad

Open **Apps > Notepad** (cyan notebook icon) alongside File Manager. Hold left mouse on a file row, look toward Notepad while holding, then release over its panel. A filename badge follows your aim and turns green over a valid target. Dropping elsewhere or pressing Esc cancels; this never moves the file on disk. Folders are not draggable into Notepad.

Notepad is a native plain-text editor with up to 32 tabs, New/Save/Save As/Undo/Redo controls, line numbers, a caret, mouse selection, and a status bar. Click the editor to type; Esc releases keyboard capture. Ctrl (or Command) + A/C/X/V, Z/Y, S, Shift+S, N, and W provide selection/clipboard, undo/redo, save, Save As, new tab, and close tab. Arrow keys, Home/End, Backspace/Delete, Enter, and Tab edit text. Scroll vertically; Shift+scroll moves horizontally; scroll the tab strip to reach additional tabs.

Files load/save in a background worker. UTF-8 (with or without BOM) and BOM-marked UTF-16 are supported, with a 1 MiB file limit. Binary data, unsupported encodings, inaccessible files, and failures show an error inside Notepad. Saves preserve the loaded encoding/BOM and line-ending style. Save refuses an externally changed file; Save As requires a new destination name rather than overwriting an existing file. Closing a modified tab offers Save/Discard/Cancel. Closing the window keeps tabs/drafts in memory until Minecraft exits; save your edits before quitting the game.

Tests cover editing, selection replacement, undo/redo, Unicode deletion, encoding round trips, saving, external-change conflicts, oversized/binary/invalid files, and missing paths. In-game validation: drag two text files into separate tabs, edit/save one, cancel a dirty tab close, and drop an image to check the error message. File Manager itself still only reads directory metadata.

## Task Manager

Open **Apps > Task Manager** (green bar-chart icon). This native panel shows Chromium helper CPU, working-set RAM, process/thread counts, process I/O rates, and the largest helper processes. Minecraft's Java process, which also hosts part of CEF, is listed separately with Java heap usage. Open apps, CEF view counts, and estimated RGBA texture memory appear below; scroll for additional apps. It supports titlebar dragging, closing, and resizing.

Read-only process queries run on a background worker every two seconds while the panel is open. Windows uses direct Kernel32/PSAPI queries; Linux reads `/proc`; macOS and other JVM platforms use `ProcessHandle`, where RSS, thread, and I/O fields may be unavailable. Platform-native classes are selected lazily, so Linux and macOS do not load Windows libraries. Opening Task Manager creates no CEF view. Only Chromium helpers descended from this Minecraft process are included; your desktop browser is excluded. CPU is normalized across available logical processors. Working sets can count shared memory more than once; process I/O is not network throughput. Embedded CEF cannot be separated from Minecraft, and per-tab CPU/RAM and GPU utilization are unavailable. Texture memory is an estimate excluding extra browser buffers.

Counter calculations, backend selection, and Linux `/proc` parsing run during `build`. The explicit `smokeResourceCounters` task checks live process sampling. Windows live sampling passes; Linux and macOS still require in-game validation on those operating systems.

## Custom webapps

Open **Options > WinLandCraft... > Manage webapps... > Add webapp**. Enter a name and HTTP/HTTPS URL, then choose **Save and get icon**. Bare domains default to HTTPS; local servers such as `http://localhost:3000` also work. Click a saved app's name in settings to edit it, or Delete to remove it.

Saving temporarily loads the website in CEF, reads its favicon links, downloads a supported icon, and closes the temporary view. Cancellation, timeout (20 seconds), and game shutdown also close it on the render thread. If an icon cannot be found, the app still saves with an initial-letter fallback (or its previous icon when editing the same URL). Definitions and PNG icons persist together in `config/winlandcraft-webapps.json`; launcher icons do not need the website to be open.

Launch a saved app from **Apps**. Each app has its own full 1280x720 page with no browser sidebar and can remain open alongside Browser and other apps. Clicking its launcher/taskbar entry again recalls the same window. Point at it to click/scroll, use G for typing, and Window Drag to move it. Its context menu provides Back, Forward, Reload, App home, and Close app. Popup links navigate within the app's panel.

Scroll the Apps list to see more than three entries, or the taskbar to see more than five running apps. Each running app has a saved icon and its own close X. Editing an app's URL or deleting its definition closes that app's running panel. Closing a panel or leaving the world retains the saved definition but not the live page/window position.

## Window controls

**Options > WinLandCraft... > Remove sizing limitations** defaults to OFF. Enable it to bypass normal app minimum/maximum sizes, including grouped resizing. The settings page shows a red instability warning: extreme browser sizes can exhaust GPU/RAM resources, and very small sizes may make controls unusable. A tiny positive geometry floor and nonzero browser viewport are retained to avoid invalid dimensions. Tasks and Apps remain fixed-size. Disabling the option restores limits for subsequent resizing.

**Curve:** aim at or just below the bottom border of any app window, including an ungrouped window of any width. A small **Curve** slider appears below the window (or its group). Hold left mouse on the slider and look left/right to adjust it: 0 is flat; 100 bends the group through up to 110 degrees toward your viewing side. The control stays visible while dragging and briefly after looking away. Webpages, titlebars, icons, and text follow a shared smooth cylindrical surface. Rendering subdivides broad surfaces into narrow strips; pointer rays intersect the cylinder and map back to the original page pixels. Adjacent edges share the same curve. Curving preserves the original viewport resolution. The curve follows movement and resizing; group changes rebuild it for the remaining components. Like window placement, it is session-only.

Curve checks cover width eligibility, bottom-edge activation, seam continuity, inward-facing angles, flat reset, reverse viewing direction, movement, resizing, and cleanup when a group splits. In-game check: group three apps, look below the middle bottom edge, drag Curve left/right, click the webpages afterward, then move/resize the group and test closing a member.

**Window groups:** place app panels next to each other or stack them, with nearby edges (their angles can differ). Aim near their adjoining border or through the narrow gap for a small **Group** button, then left-click it. The window showing the button keeps its position and angle; the joining window snaps to its plane and edge, moving its existing group with it. Side-by-side joining also matches the full frame height and aligns the top/bottom edges, keeping widths unchanged. An incoming group scales vertically together to preserve its layout, subject to existing window size limits. Existing groups can be joined too. Tasks and Apps cannot be grouped. Dragging any member by its titlebar or Window Drag moves the entire group, including wheel distance adjustments. Recalling an app also recalls its group.

Grouped titlebars have **Ungroup**, which detaches that app. The group's four outside corner handles resize all members proportionally along each axis, preserving their layout and respecting every app's size limits. Closing or ungrouping a connecting app splits the remaining connected sections; lone windows lose their grouped state. Groups last for the current world session, like window placement.

Grouping checks cover side/stacked suggestions, bridge removal, singleton cleanup, movement/rotation, all four rotated resize handles, fixed opposite corners, mixed browser/native panels, and size limits. In Prism, try three apps in a row: group them, move/resize the group, then close the middle app and check both remaining apps move independently.

Point at a window to interact automatically: the crosshair becomes a white pointer. Left/right click and scroll go to the targeted panel; away from panels, normal Minecraft controls work. Tasks, Apps, and Window Drag retain their right-click item actions. The Interact item is no longer given, and old copies are removed from player inventories on world join or Get controls.

Browser and webapps have a titlebar above their content, showing the current page title and app name, with an X to close the whole window. Hold **left mouse on the titlebar** and look/walk to move it; release to leave it in place. While holding either the titlebar or the Window Drag item, **scroll up moves farther away; scroll down moves closer**. Both movement methods respect the rotation setting.

When hovering Browser or a webapp, cyan handles appear just outside its corners and grow with viewing distance so they remain targetable. Aim at one for a cyan diagonal **R** cursor, hold **left mouse**, and look to resize the viewport/resolution; release to finish. Hold **Ctrl** before grabbing a handle to switch both the handle and **S** cursor to yellow and scale the physical panel (or group) without changing its pixel resolution or aspect ratio. The opposite corner stays fixed in either mode, including on rotated panels. Browser/webapp viewports reflow after normal resizing, up to 2560x1440. Tasks and the Apps launcher are fixed-size, have no added titlebars, and remain movable with Window Drag. Both modes preserve the rotation lock setting.

Assign **Get controls** under Options > Controls > Key Binds > WinLandCraft. It starts unbound; previous assignments are preserved. Pressing it supplies only missing items, without requiring cheats:

| Item | Action |
| --- | --- |
| Tasks | Right-click to open/recall the taskbar. Sneak-right-click closes it. |
| Apps | Right-click to open/recall Apps independently of Tasks. Sneak-right-click closes it. |
| Window Drag | Aim at a panel, hold right-click, and look/walk to move it. Scroll up moves farther, down moves closer; release leaves it in place. |

Click **Apps** on Tasks, then click **Browser** in the launcher. Its cyan compass icon also appears on Tasks. Clicking Browser again in Apps or Tasks recalls the same window without reloading tabs. The taskbar X closes the entire browser and disposes all tabs.

The taskbar's Apps button toggles a start menu directly above its left edge, in the same plane and orientation. It follows taskbar movement and recall; dragging either attached panel moves both together. Closing Tasks closes its attached menu. Opening Apps with the inventory item instead places it independently in front of you.

The browser starts at 1280x720: a 260-pixel left sidebar and a 1020x720 web area. The sidebar contains a single-line URL bar, Back/Forward/Reload, New tab, and a scrolling vertical tab list. Each tab has its own page, history, title, favicon, and close X. Switching tabs keeps other pages alive (including playing audio). Closing the final tab opens a fresh Google tab. Tabs are not saved after closing the browser or leaving the world.

Click the URL bar to start typing immediately, with its contents selected. Enter opens a URL or searches Google for ordinary text. Bare domains default to HTTPS. Ctrl+A/C/X/V, Backspace/Delete, Left/Right, and Home/End work in the address bar. Ctrl+L focuses it, Ctrl+T opens a tab, and Ctrl+W closes the current tab while browser typing is enabled. Escape exits typing. Long URLs and tab titles are truncated to fit; the full URL remains editable and copyable. Links requesting a new window are routed to tabs through CEF's popup handler.

Tab favicons follow navigation using the page's declared icon, falling back to `/favicon.ico`. Icons download asynchronously, with the compass glyph while loading or if no supported icon is available. PNG, JPEG, GIF and common PNG/24-bit/32-bit ICO frames are supported; SVG and icons requiring browser cookies currently use the fallback glyph. All tab views share MCEF's Chromium runtime; no third-party favicon service is used.

Click a browser field and press **G** (rebindable: **Type in focused window**). A visible typing indicator appears. Keys now go to the browser, including WASD and number keys. **Esc** releases typing back to Minecraft. Mouse aiming still uses the camera. Keyboard mode is canceled when opening a Minecraft screen, losing focus, or leaving the world.

Mouse presses remain attached to their original window until release, including when aiming outside its bounds, so selecting text and dragging page controls work. Scroll over a panel goes to the panel instead of changing hotbar slots. Away from panels, the wheel normally selects hotbar slots. Clicks over panels do not attack/use blocks behind them; held mining is also blocked while aiming at a panel.

Right-click forwards a browser right-click. If Chromium requests its default context menu, a menu is drawn on the panel with Back, Forward, Reload, Open link here, Open in new tab, and Close tab. Websites that provide their own context menus continue to receive browser events.

Window picking respects player block-interaction reach and nearer world targets. Window Drag can target panels up to 4,096 blocks away, with movement distance adjustable from 0.5 to 4,096 blocks. The wheel moves distant panels in larger steps so they can be brought back quickly; nearby movement retains fine steps. Normal interaction uses Minecraft reach by default. Settings > Extend interaction range enables an adjacent distance field (default 16 blocks, positive values up to 4,096); the setting and distance persist. This applies to panel clicking, scrolling, and interaction targeting. Nearer blocks/entities still occlude targeting. Windows clear on death, world/dimension changes, and disconnect; browser views are closed. Inventory items persist. Window placement is not saved yet. Private apps remain local. Browser(streamable) adds experimental LAN/dedicated-server image sharing as described above.

## Verification

For this update, test titlebar dragging/close on Browser and custom apps, title changes when switching tabs, scroll up/down distance with both movement methods, and fixed-size Tasks/Apps. Automated checks cover titlebar picking on rotated windows, close/drag boundaries, retained content resolution, resize corners, and distance direction/limits. Cursor/input injection has a packaging gate for all eight mappings; native gameplay still needs an in-game check.

Disconnect cleanup is dispatched to the client/render thread because MCEF deletes OpenGL textures during browser close. A regression check verifies off-thread close deferral, render-thread execution, and repeated close without loading native CEF. In Prism, check Save and Quit with several browser tabs open, then rejoin and reopen Browser.

`build` checks the packaged input mixin refmap and all eight production mappings, 12 address/search cases, 10 favicon parsing/decoding cases, 9 custom-app URL/persistence cases, plus window geometry, four-corner resizing, attachment, rotation, and cleanup. Browser, taskbar, rotation settings, and disconnect cleanup were verified in Prism by the user. Custom apps need an in-game check: save two sites, verify icons after restarting, open both alongside Browser, test input/dragging, edit/delete an app, cancel an icon lookup, and Save and Quit with all panels open. Other interaction checks:

1. Get controls: only Tasks, Window Drag, and Apps are given. Old Interact items disappear from the inventory.
2. Open Apps through both the taskbar button and the item. Open Browser, then recall it without resetting its tabs.
3. Click a field, enable typing, type a query, and press Enter. Check Esc restores movement and hotbar keys.
4. Test wheel scrolling without hotbar changes, right-click menus, and text-selection dragging across panel edges.
5. Drag each panel; scroll during dragging changes distance instead of webpage scroll/hotbar selection.
6. Click the address bar, type a domain, press Enter; repeat with a search phrase. Check selection, paste, long URLs, and Escape.
7. Create several tabs; navigate, switch, scroll the tab list, close an inactive tab, close the current tab, and close the final tab. Confirm titles, favicons, and independent history.
8. Open a link in a new tab, then close Browser from Tasks and reopen. Test focus loss, death, and disconnect during mouse/keyboard capture.

## Implementation references

- [Fabric documentation](https://docs.fabricmc.net/)
- [MCEF](https://github.com/CinemaMod/mcef/tree/1.21.4)
- [WaylandCraft input routing](https://github.com/EVV1E/waylandcraft/blob/main/src/main/java/dev/evvie/waylandcraft/WaylandCraft.java)
- [WaylandCraft pointer grabs](https://github.com/EVV1E/waylandcraft/blob/main/src/main/java/dev/evvie/waylandcraft/grabs/PointerGrabMap.java)
- [WaylandCraft resizing](https://github.com/EVV1E/waylandcraft/blob/main/src/main/java/dev/evvie/waylandcraft/grabs/ResizeGrab.java)

Input behavior follows WaylandCraft's hover routing, press/release capture, separate keyboard capture, and separate window grabs, adapted to Minecraft 1.21.4 and CEF. No Wayland protocol/native code or WaylandCraft source blocks were copied. MCEF remains a separate dependency.
