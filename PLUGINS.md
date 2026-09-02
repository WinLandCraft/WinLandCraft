# Building WinLandCraft plugin mods

API **v1**, available in **WinLandCraft 0.1.82-dev and later**, lets another Fabric mod add apps without forking WinLandCraft. The public package is `dev.winlandcraft.api.v1`. It contains only Java types: no Minecraft mappings, internal panel classes, OpenGL, MCEF, or JCEF types appear in its signatures.

## What the host provides

Registered apps appear in Apps, with their own icon, and running windows appear on the taskbar. Every window gets the standard floating pill, dynamic title, close/move controls, normal resizing, Ctrl-scaling, grouping, curves, smoothing, interaction range, and complete-surface stream capture. The host also owns mouse/keyboard capture and locking, ray picking, focus, Escape, and input release. Plugins must not install their own Minecraft input hooks for these functions.

File extensions and exact names integrate with File Manager's double-click placement overlay and file drops. If several apps support a file, clicking **Open with** cycles through the candidates. Choosing an arrow creates a separate app window beside File Manager with the standard gap and fold; it does not move another open instance. Notepad retains its built-in associations and is the initial choice for its supported files; plugin candidates follow in app-ID order. A plugin never silently replaces another handler.

`NATIVE` apps draw Java UI without creating Chromium. `CHROMIUM` apps get a managed full-window browser. `HYBRID` apps combine a browser rectangle with native controls. There is currently one browser view per plugin window, using the host's shared Chromium runtime. Host-native browser menus and remote stream control are not enabled for plugin windows; HTML/DOM menus still work. Streaming viewers see the composed app without installing the plugin, but the owner needs it; the usual WinLandCraft server/codec requirements still apply. Browser audio uses the same capture path as built-in apps.

## Build and install

Requirements: Java 21, Minecraft 1.21.4, Fabric Loader 0.16.9+, and WinLandCraft with its normal dependencies. API-only plugins need no Minecraft mappings or Loom. Mods that also call Minecraft APIs can use their existing Fabric/Loom build.

Build WinLandCraft normally. The outputs include:

- `winlandcraft-0.1.82-dev.jar`: install on the client/host as usual.
- `winlandcraft-0.1.82-dev-plugin-api.jar`: compile-only public API; do **not** install, shade, include, or package it in your plugin.
- `winlandcraft-0.1.82-dev-example-plugin.jar`: optional test mod with native, Chromium, and hybrid example apps. Install alongside the main JAR, never instead of it.

There is no published Maven repository yet. Copy the API JAR into your plugin project's `libs/` folder. Minimal Gradle configuration:

```groovy
plugins { id 'java' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    compileOnly fileTree(dir: 'libs', include: '*-plugin-api.jar')
}
version = '1.0.0'
```

For Loom-based mods, the API JAR is still `compileOnly`. Add the full WinLandCraft JAR with `modLocalRuntime` (or your Loom version's local runtime configuration) when running a development client; do not bundle the host mod. Keep the required Fabric API on the development runtime too.

Put this in `src/main/resources/fabric.mod.json` (change IDs, entrypoint, and names):

```json
{
  "schemaVersion": 1,
  "id": "my_apps",
  "version": "1.0.0",
  "name": "My Apps",
  "environment": "client",
  "entrypoints": {
    "winlandcraft:plugins": ["example.MyPlugin"]
  },
  "depends": {
    "fabricloader": ">=0.16.9",
    "java": ">=21",
    "minecraft": "1.21.4",
    "winlandcraft": ">=0.1.82-dev"
  }
}
```

The custom entrypoint runs once during WinLandCraft client initialization. Do not also register it as a Fabric client initializer. Entry-point implementation must have a public zero-argument constructor. Dedicated servers do not instantiate it. Deploy the resulting plugin JAR beside WinLandCraft in the client's `mods` directory.

## Register an app

```java
package example;
import dev.winlandcraft.api.v1.*;

public final class MyPlugin implements WinLandCraftPlugin {
    @Override public void register(PluginRegistry registry) {
        registry.register(AppDefinition.builder(
            "my_apps:viewer", "My Viewer", AppKind.NATIVE, Viewer::new)
            .size(1280, 720)
            .icon("my_apps:textures/app_icon.png")
            .extensions("txt", "mytext")
            .fileNames("README")
            .build());
    }
}
```

The namespace before `:` must match your Fabric mod ID. IDs must be unique and permanent. Icons are ordinary resource textures at `assets/my_apps/textures/app_icon.png`; omit `.icon(...)` for the host's default icon. `size` defaults to 1280x720, accepts widths 320–4096 and heights 180–4096, and sets the starting viewport. No file association is required. Extensions are case-insensitive and may have an initial dot; exact names support extensionless files. Matching never reads file contents. The host limits registrations to 64 apps per entrypoint and 512 overall. Failed registration is rolled back for that entrypoint and logged; it does not register a partial set.

The factory must return a **new App instance** each time and must not do expensive work. Launcher windows create a fresh instance on each open session. Additional file-opening windows can coexist and appear on the taskbar; the host retains up to 64 extra plugin window slots. State that must survive closing belongs in your own model or storage, not a discarded `WindowContext`.

## Native drawing and input

```java
public final class Viewer implements App {
    private WindowContext window;
    private String text = "Click to type";

    public void onOpen(WindowContext window) { this.window = window; }
    public void render(Canvas c) {
        c.rectangle(0, 0, c.width(), c.height(), 0xFF202830);
        c.text(text, 20, 30, 0xFFFFFFFF, 1.5f);
    }
    public void onPointerDown(int x, int y, int button) {
        if (button == 0) window.requestKeyboard(true);
    }
    public void onCharacter(char c, int modifiers) {
        if (!Character.isISOControl(c)) text += c;
    }
}
```

Coordinates start at the content's top-left, in logical pixels. They exclude the pill and resize handles. `Canvas` offers rectangles, text, text measurement, and resource textures; colors are ARGB. Later calls draw above earlier calls. The content framebuffer clips at the window boundary; for inner scrolling regions, fit/truncate your text and draw only visible rows. A Canvas expires when `render` returns: never cache it, pass it to a worker, or issue GL calls. At most 8,192 drawing calls are accepted per frame. Rendering is also used for stream capture; do not mutate your app model inside `render`.

Input contract:

- `onPointerMove(x,y)` supplies local coordinates. `(-1,-1)` means leave; captured dragging can report coordinates beyond the window. Match hover rectangles to drawing rectangles.
- `onPointerDown` / `onPointerUp` use GLFW button numbers: left 0, right 1, middle 2. Release is routed to the same native/browser recipient that received the press, even if the pointer moves away.
- `onScroll(x,y,amount)` uses positive values for wheel-up. The host prevents Minecraft hotbar scrolling during app interaction.
- `window.requestKeyboard(true)` requests typing for the interacting/focused window. It cannot steal focus from another window. On the next click the host can also honor a pending request. `false` releases typing. Escape is reserved for returning to Minecraft.
- `onFocusChanged` reports typing mode entering/leaving. `onKey` uses GLFW key/scancode/action/modifier values: release 0, press 1, repeat 2; Shift 1, Ctrl 2, Alt 4, Super 8. `onCharacter` supplies UTF-16 text units; key presses alone are not text. The host synthesizes releases on focus loss.
- Ordinary window resizing updates logical pixels and calls `onResize(width,height)`. Recompute layout and hit regions there or from `Canvas.width/height`. Ctrl-scaling preserves logical pixels and aspect ratio, so it does not call `onResize`. Dragging, grouping, and curve geometry are handled by the host.

Never lock/unlock the cursor, manipulate Minecraft key state, subscribe to global mouse events, or forward browser keys yourself.

## Chromium and hybrid apps

```java
public final class WebApp implements App {
    private WindowContext window;
    public void onOpen(WindowContext window) {
        this.window = window;
        window.browser().navigate("https://example.com/");
    }
    public void onBrowserTitleChanged(String title) {
        window.title(title + " - My Web App");
    }
}
```

Register that as `AppKind.CHROMIUM`. It fills the entire viewport automatically. If you override `render`, do not paint an opaque background over the browser unless you intend to hide it.

For `HYBRID`, reserve a native toolbar in `onResize`:

```java
public void onResize(int width, int height) {
    window.browser().bounds(0, 52, width, Math.max(1, height - 52));
}
public void render(Canvas c) {
    c.rectangle(0, 0, c.width(), 52, 0xFF254052);
    c.text("Reload", 14, 18, 0xFFFFFFFF, 1.5f);
}
public void onPointerDown(int x, int y, int button) {
    if (button == 0 && y < 52) window.browser().reload();
}
```

The host renders the view before your native UI and automatically routes input inside its rectangle to Chromium. Native UI should occupy the space outside that rectangle; drawing over a browser does not make an input-blocking overlay. Native pointer callbacks receive only the native portion (plus captured native drags). Clicking Chromium requests host-managed typing. `fillWindow()` restores automatic full-window sizing. Custom bounds are clamped to the available content when the window shrinks.

`BrowserView` provides `navigate`, `address`, `title`, `ready`, `back`, `forward`, `reload`, `executeJavaScript`, `bounds`, and `fillWindow`. Navigation before creation supplies the initial URL; the host creates the view once Chromium is ready. Back/forward/reload are no-ops before readiness. JavaScript returns false until a native view exists, and does not return a JS result. Use `onBrowserLoaded` for DOM-dependent scripts; it fires on main-frame load completion. Address/title changes arrive through their corresponding callbacks, including same-document navigation.

For local HTML, use a file URI from `Path.toUri().toString()` for a real file, or a `data:text/html;base64,...` URL for self-contained bundled HTML. A JAR resource is not a filesystem path: read it in a worker and build a data URL, or explicitly extract your own assets. Normal browser origin/CORS restrictions still apply. No Java-to-JavaScript privileged message bridge is exposed in v1. Never expose arbitrary filesystem or command execution to untrusted pages. Plugins must not create another CefApp or bundle MCEF/JCEF. Native views are closed on the render thread, including disconnects.

## Files, workers, and lifecycle

`openFile(Path)` receives the host's selected/dropped path after `onOpen` and initial `onResize`. It must schedule reading off-thread and handle missing files, permissions, binary/invalid data, and size limits. Associations only choose a handler; they do not guarantee the file is readable or safe for your parser. `dragFileAt(x,y)` can return a Path to participate in host-managed drag-and-drop; return null for non-file UI. Dropped files are filtered by the receiving app's registered associations. Do not move/delete files as a consequence of drag-and-drop unless that is an explicit feature of your app.

Use a session-bound completion:

```java
public void openFile(java.nio.file.Path path) {
    WindowContext session = window;
    Thread.startVirtualThread(() -> {
        String result;
        try (var input = java.nio.file.Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new java.io.IOException("File too large");
            result = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) {
            result = "Could not open: " + error.getMessage();
        }
        String finished = result;
        session.execute(() -> { text = finished; session.title(path.getFileName().toString()); });
    });
}
```

All App callbacks run on Minecraft's client/render thread. `onTick` is a game-tick callback, not a frame timer. Do not block callbacks. `execute` is the thread-safe completion path; callbacks from expired/closed sessions are ignored. Each context permits at most 128 pending completions; overflow throws instead of growing a queue indefinitely. Coalesce high-rate work and cancel your workers in `onClose`. Guard your own shared state and discard superseded requests.

`WindowContext` also provides logical `width/height`, `title`, `close`, `dataDirectory`, and clipboard getter/setter. Titles are capped at 512 characters. `dataDirectory()` returns `config/winlandcraft/plugins/<modid>/<app-path>` without creating it; perform directory creation and I/O in a worker. Other methods are client-thread-only except `execute` and obtaining the data-directory path. Do not retain a context across sessions. Clipboard access must be driven by your app's user controls.

`onClose` runs once per created App session, including world exit. There is no close-veto API in v1: persist or autosave drafts instead of relying on a blocking close dialog. Host-created browser resources are automatically released; release your own resources in `onClose`. Runtime exceptions, linkage errors, and assertion failures in registration/callbacks are logged and contained; app failure leaves a readable error panel until closed. VM-fatal errors, runaway threads, native crashes, and malicious mods cannot be isolated inside one JVM. Plugin mods are trusted local Java code, not sandboxed web extensions.

## Compatibility and verification

Do not depend on `dev.winlandcraft.*` implementation classes outside `api.v1`, reflect into the host, or mix into its window/input implementation. Only v1 is supported. Keep your plugin's minimum WinLandCraft dependency at the first host version providing the APIs you actually use.

Within supported Minecraft/Fabric versions, newer WinLandCraft builds must keep old v1 plugin binaries working. Existing types, signatures, enum values, and documented behavior remain supported. New callbacks must have default implementations; new builder options must preserve existing defaults. An incompatible future API must coexist in another versioned package rather than replacing v1. Cross-Minecraft compatibility still depends on Fabric and any Minecraft APIs your own mod uses.

The repository's `verifyPluginApi` gate checks the frozen public-signature baseline and rejects new mandatory interface callbacks. It also compiles [the example plugin](examples/plugin/) against **only** the API JAR, exercises registration/file handlers/input/geometry, and tests registration rollback. Do not regenerate the baseline to approve a breaking change.

To build the standalone example, copy the API JAR into `examples/plugin/libs/`, enter that directory, then run the root wrapper (`../../gradlew -p . build`, or `..\..\gradlew.bat -p . build` on Windows with Java 21). The host build also produces its installable example JAR automatically, without requiring that copy.

Before releasing a plugin, smoke-test all three relevant paths: open from Apps, double-click a supported file and choose each edge, and drag a file between windows. Check typing/Escape, pointer release outside the window, Ctrl-scaling versus resizing, groups/curves, taskbar close/reopen, and saving/quitting. Chromium/hybrid plugins additionally need navigation, resize, close/reopen, and audio/stream tests on the target operating systems. Automated checks do not validate native CEF or GPU behavior.
