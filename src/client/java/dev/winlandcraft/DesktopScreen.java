package dev.winlandcraft;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLMouse;

/** 2D desktop hybrid: shows the live composited textures of the real 3D windows and taskbar.
 * Clicking a window maximizes it into the view with full mouse and keyboard input; Back
 * returns to the grid, Bring recalls the maximized panel in front of the camera. */
final class DesktopScreen extends Screen {
    private static final int BOX_W = 208, BOX_H = 176, GAP = 8, TASKBAR_H = 34, TOP = 44;
    private final AppWindows apps;
    private final AppWindows.AppEntry taskbar;
    private AppWindows.AppEntry maximized;
    private int first;
    private String status = "";
    private AppWindows.AppEntry pressed;
    private int pressedButton;
    private boolean pressedBar;
    private final Set<Integer> heldKeys = new HashSet<>();
    DesktopScreen(AppWindows apps) {
        super(Component.literal("Desktop"));
        this.apps = apps;
        taskbar = new AppWindows.AppEntry("", apps.tasks, null);
    }
    private List<AppWindows.AppEntry> entries() {
        return apps.runningApps().stream().filter(e -> e.panel().isOpen()).toList();
    }
    private int columns() { return Math.max(1, (width - 16) / (BOX_W + GAP)); }
    private int rows() { return Math.max(1, (height - TOP - TASKBAR_H - 26) / (BOX_H + GAP)); }
    @Override protected void init() {
        // The desktop shows the real taskbar texture, so keep the 3D taskbar open behind it.
        if (minecraft != null && minecraft.level != null && !apps.tasks.isOpen()) apps.tasks.open(minecraft);
        if (maximized == null) {
            addRenderableWidget(Button.builder(Component.literal("Recall all"), b -> recallAll())
                    .bounds(width - 216, 8, 100, 20).build()).active = !entries().isEmpty();
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                    .bounds(width - 108, 8, 100, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
                releaseAll();
                maximized = null;
                rebuildWidgets();
            }).bounds(width - 324, 8, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Bring"), b -> {
                var entry = maximized;
                releaseAll();
                maximized = null;
                recall(entry);
            }).bounds(width - 216, 8, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Close window"), b -> {
                status = "Closed " + maximized.name() + ".";
                maximized.panel().close();
                releaseAll();
                maximized = null;
                rebuildWidgets();
            }).bounds(width - 108, 8, 100, 20).build());
        }
    }
    /** Screen-space rect of an entry's live thumbnail, or null when it is scrolled out of view. */
    private int[] rectOf(AppWindows.AppEntry entry) {
        var entries = entries();
        int visible = entries.indexOf(entry) - first;
        if (visible < 0) return null;
        int cols = columns();
        int row = visible / cols, col = visible % cols;
        if (row >= rows()) return null;
        int texW = entry.panel().pixelWidth(), texH = entry.panel().pixelHeight() + entry.panel().titlebarHeight();
        float scale = Math.min((BOX_W - 8f) / texW, (BOX_H - 28f) / texH);
        int w = Math.max(1, Math.round(texW * scale)), h = Math.max(1, Math.round(texH * scale));
        int x = 8 + col * (BOX_W + GAP) + (BOX_W - w) / 2, y = TOP + row * (BOX_H + GAP) + 20 + (BOX_H - 28 - h) / 2;
        return new int[]{x, y, w, h};
    }
    /** Screen-space rect of the maximized window's live texture. */
    private int[] maximizedRect() {
        if (maximized == null) return null;
        int texW = maximized.panel().pixelWidth(), texH = maximized.panel().pixelHeight() + maximized.panel().titlebarHeight();
        float scale = Math.min((width - 16f) / texW, (height - TOP - TASKBAR_H - 32f) / texH);
        int w = Math.max(1, Math.round(texW * scale)), h = Math.max(1, Math.round(texH * scale));
        return new int[]{(width - w) / 2, TOP + (height - TOP - TASKBAR_H - 16 - h) / 2, w, h};
    }
    /** Maps a screen point inside a thumbnail rect to panel content pixels. */
    private int[] mapToPanel(AppWindows.AppEntry entry, int[] rect, double x, double y) {
        var panel = entry.panel();
        float scale = rect[2] / (float) panel.pixelWidth();
        int titlebar = panel.titlebarHeight();
        int previewH = panel.pixelHeight() + titlebar;
        float scaleY = rect[3] / (float) previewH;
        int px = Math.clamp(Math.round((x - rect[0]) / scale), 0, panel.pixelWidth() - 1);
        int py = Math.clamp(Math.round((y - rect[1]) / scaleY - titlebar), 0, panel.pixelHeight() - 1);
        return new int[]{px, py};
    }
    private AppWindows.AppEntry entryAt(double x, double y) {
        for (var entry : entries()) {
            var rect = rectOf(entry);
            if (rect != null && x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3]) return entry;
        }
        return null;
    }
    private int[] taskbarRect() {
        if (!apps.tasks.isOpen()) return null;
        int texW = apps.tasks.pixelWidth(), texH = apps.tasks.pixelHeight() + apps.tasks.titlebarHeight();
        float scale = Math.min((TASKBAR_H - 8f) / texH, (width - 16f) / texW);
        int w = Math.max(1, Math.round(texW * scale));
        int h = Math.max(1, Math.round(texH * scale));
        return new int[]{8, height - TASKBAR_H + (TASKBAR_H - h) / 2, w, h};
    }
    private static boolean over(int[] rect, double x, double y) {
        return rect != null && x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3];
    }
    private void recall(AppWindows.AppEntry entry) {
        var client = minecraft;
        if (client == null || client.level == null) return;
        if (!entry.panel().isOpen() || entry.panel().level != client.level) {
            status = "That window is gone.";
            rebuildWidgets();
            return;
        }
        entry.panel().bringToView(client.level, client.gameRenderer.mainCamera());
        onClose();
    }
    /** Surround-sphere recall: windows ring the player starting where they look, grouped
     * clusters moving rigidly via their representative. Rings alternate above/below eye level. */
    private static final int RING_SLOTS = 8;
    private void recallAll() {
        var client = minecraft;
        if (client == null || client.level == null) return;
        var units = new java.util.ArrayList<WorldPanel>();
        var placed = new java.util.HashSet<WorldPanel>();
        for (var entry : entries()) {
            var panel = entry.panel();
            if (panel.level != client.level || !placed.add(panel)) continue;
            units.add(panel);
            placed.addAll(WindowGroups.members(panel));
        }
        if (units.isEmpty()) {
            status = "No open windows.";
            rebuildWidgets();
            return;
        }
        var camera = client.gameRenderer.mainCamera();
        Vec3 eye = camera.getPosition();
        var forward = new Vector3f(0, 0, -1).rotate(camera.rotation());
        double lookYaw = Math.atan2(forward.x, forward.z);
        int rings = (units.size() + RING_SLOTS - 1) / RING_SLOTS;
        for (int ring = 0; ring < rings; ring++) {
            var row = units.subList(ring * RING_SLOTS, Math.min(units.size(), (ring + 1) * RING_SLOTS));
            double total = 0;
            for (var unit : row) total += WindowGroups.frame(unit).worldWidth() + 0.5;
            double radius = Math.clamp(total / (2 * Math.PI), 2.5, 12);
            double step = 2 * Math.PI / row.size();
            double dy = ring == 0 ? -0.45 : (ring % 2 == 1 ? 1.3 : -1.9) * ((ring + 1) / 2);
            for (int slot = 0; slot < row.size(); slot++) {
                var panel = row.get(slot);
                double yaw = lookYaw + ring * step / 2 + slot * step;
                Vec3 oldPosition = panel.position;
                Quaternionf oldRotation = panel.orientation == null ? null : new Quaternionf(panel.orientation);
                panel.position = new Vec3(eye.x + Math.sin(yaw) * radius, eye.y + dy, eye.z + Math.cos(yaw) * radius);
                panel.orientation = PanelRotation.allowed(new Quaternionf().rotationY(
                        (float) Math.atan2(eye.x - panel.position.x, eye.z - panel.position.z)));
                WindowGroups.moved(panel, oldPosition, oldRotation);
                if (client.player != null) {
                    float title = panel.titlebarHeight() * panel.worldHeight() / Math.max(1, panel.pixelHeight());
                    float halfH = panel.worldHeight() / 2;
                    if (SpawnSpace.blocked(client.level, client.player, eye, panel.position, panel.orientation,
                            panel.worldWidth(), halfH + title, halfH)) {
                        Vec3 retry = panel.position.add(Math.sin(yaw) * 2.0, 0, Math.cos(yaw) * 2.0);
                        if (SpawnSpace.blocked(client.level, client.player, eye, retry, panel.orientation,
                                panel.worldWidth(), halfH + title, halfH)) Beacon.mark(panel);
                        else {
                            Vec3 pushed = panel.position;
                            panel.position = retry;
                            WindowGroups.moved(panel, pushed, new Quaternionf(panel.orientation));
                        }
                    }
                }
            }
        }
        status = "Recalled " + units.size() + " window" + (units.size() == 1 ? "." : "s.");
        onClose();
    }
    private void press(AppWindows.AppEntry entry, int[] rect, boolean bar, double x, double y, int button) {
        var at = mapToPanel(entry, rect, x, y);
        pressed = entry;
        pressedButton = button;
        pressedBar = bar;
        entry.panel().pointerMoved(at[0], at[1]);
        entry.panel().mouseDown(at[0], at[1], button);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int button = event.button();
        double x = event.x(), y = event.y();
        if (button != SDLMouse.SDL_BUTTON_LEFT && button != SDLMouse.SDL_BUTTON_RIGHT) return super.mouseClicked(event, doubled);
        var bar = taskbarRect();
        if (button == SDLMouse.SDL_BUTTON_LEFT && over(bar, x, y)) {
            press(taskbar, bar, true, x, y, button);
            return true;
        }
        if (maximized != null) {
            var rect = maximizedRect();
            if (over(rect, x, y) && maximized.panel().isOpen()) {
                press(maximized, rect, false, x, y, button);
                return true;
            }
            pressed = null;
            return super.mouseClicked(event, doubled);
        }
        var entry = entryAt(x, y);
        if (entry != null) {
            if (button == SDLMouse.SDL_BUTTON_RIGHT) {
                entry.panel().close();
                status = "Closed " + entry.name() + ".";
                rebuildWidgets();
                return true;
            }
            releaseAll();
            maximized = entry;
            status = "";
            rebuildWidgets();
            return true;
        }
        pressed = null;
        return super.mouseClicked(event, doubled);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        int button = event.button();
        double x = event.x(), y = event.y();
        if (pressed != null && button == pressedButton) {
            var panel = pressed.panel();
            var rect = pressedBar ? taskbarRect() : maximized != null && pressed.equals(maximized) ? maximizedRect() : rectOf(pressed);
            if (panel.isOpen() && rect != null) {
                var at = mapToPanel(pressed, rect, x, y);
                panel.pointerMoved(at[0], at[1]);
                panel.mouseUp(at[0], at[1], button);
            }
            pressed = null;
            return true;
        }
        return super.mouseReleased(event);
    }
    @Override public void mouseMoved(double x, double y) {
        if (pressed != null && !pressedBar && pressed.panel().isOpen()) {
            var rect = maximized != null && pressed.equals(maximized) ? maximizedRect() : rectOf(pressed);
            if (rect != null) {
                var at = mapToPanel(pressed, rect, x, y);
                pressed.panel().pointerMoved(at[0], at[1]);
            }
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        var bar = taskbarRect();
        if (over(bar, x, y)) {
            var at = mapToPanel(taskbar, bar, x, y);
            apps.tasks.scroll(at[0], at[1], vertical);
            return true;
        }
        if (maximized != null) {
            var rect = maximizedRect();
            if (over(rect, x, y) && maximized.panel().isOpen()) {
                var at = mapToPanel(maximized, rect, x, y);
                maximized.panel().scroll(at[0], at[1], vertical);
                return true;
            }
            return super.mouseScrolled(x, y, horizontal, vertical);
        }
        var entry = entryAt(x, y);
        if (entry != null && entry.panel().isOpen()) {
            var at = mapToPanel(entry, rectOf(entry), x, y);
            entry.panel().scroll(at[0], at[1], vertical);
            return true;
        }
        var entries = entries();
        first = Math.clamp(first - (int) Math.signum(vertical) * columns(), 0, Math.max(0, entries.size() - 1));
        return true;
    }
    private void releaseAll() {
        pressed = null;
        if (maximized != null && maximized.panel().isOpen() && !heldKeys.isEmpty())
            for (int key : heldKeys) maximized.panel().key(key, 0, 0, 0);
        heldKeys.clear();
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (maximized != null && maximized.panel().isOpen()) {
            if (event.keycode() == SDLKeycode.SDLK_ESCAPE) return super.keyPressed(event);
            int key = event.keycode();
            maximized.panel().key(key, event.key(), heldKeys.contains(key) ? 2 : 1, event.modifiers());
            heldKeys.add(key);
            return true;
        }
        return super.keyPressed(event);
    }
    @Override public boolean keyReleased(KeyEvent event) {
        if (maximized != null && heldKeys.remove(event.keycode())) {
            if (maximized.panel().isOpen()) maximized.panel().key(event.keycode(), event.key(), 0, event.modifiers());
            return true;
        }
        return super.keyReleased(event);
    }
    @Override public boolean charTyped(CharacterEvent event) {
        if (maximized != null && maximized.panel().isOpen()) {
            maximized.panel().character((char) event.codepoint(), 0);
            return true;
        }
        return super.charTyped(event);
    }
    /** Redraws the panel live at thumbnail scale. Native UI redraws through the GUI canvas;
     * browser pages blit their registered view the same way the world pass does. */
    private void blitLive(GuiGraphicsExtractor g, AppWindows.AppEntry entry, int[] rect) {
        var panel = entry.panel();
        int titlebar = panel.titlebarHeight();
        float sx = rect[2] / (float) panel.pixelWidth();
        float sy = rect[3] / (float) (panel.pixelHeight() + titlebar);
        g.enableScissor(rect[0], rect[1], rect[2], rect[3]);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(rect[0], rect[1] + titlebar * sy);
        pose.scale(sx, sy);
        try (var canvas = new PanelCanvas(g)) {
            panel.drawSurface(canvas);
        } finally {
            pose.popMatrix();
            g.disableScissor();
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, 0xF0142A3A);
        g.fill(0, height - TASKBAR_H, width, height, 0xF00B1118);
        if (maximized != null && !maximized.panel().isOpen()) {
            releaseAll();
            maximized = null;
            rebuildWidgets();
        }
        if (maximized != null) {
            renderMaximized(g, mouseX, mouseY);
            return;
        }
        g.text(font, title.getString(), 8, 14, -1);
        g.centeredText(font, "Click: open | Right-click: close | Wheel on window: scroll, elsewhere: pages",
                width / 2, 30, 0xFF9BAABD);
        var entries = entries();
        first = Math.clamp(first, 0, Math.max(0, entries.size() - 1));
        var hovered = entryAt(mouseX, mouseY);
        for (var entry : entries()) {
            if (entry != hovered && entry != pressed) entry.panel().pointerMoved(-1, -1);
        }
        for (var entry : entries()) {
            var rect = rectOf(entry);
            if (rect == null) continue;
            int cols = columns();
            int visible = entries.indexOf(entry) - first;
            int bx = 8 + (visible % cols) * (BOX_W + GAP), by = TOP + (visible / cols) * (BOX_H + GAP);
            g.fill(bx, by, bx + BOX_W, by + BOX_H, entry == hovered ? 0xFF2A4356 : 0xFF101820);
            g.text(font, font.plainSubstrByWidth(entry.name(), BOX_W - 12), bx + 6, by + 6, -1);
            blitLive(g, entry, rect);
        }
        if (entries.isEmpty()) g.centeredText(font, "No open windows.", width / 2, TOP + 20, 0xFFB8CBDE);
        renderTaskbar(g);
        if (!status.isEmpty()) g.centeredText(font, font.plainSubstrByWidth(status, width - 20), width / 2, height - TASKBAR_H - 14, 0xFFB8CBDE);
    }
    private void renderMaximized(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, maximized.name(), 8, 14, -1);
        var rect = maximizedRect();
        blitLive(g, maximized, rect);
        if (over(rect, mouseX, mouseY)) {
            var at = mapToPanel(maximized, rect, mouseX, mouseY);
            maximized.panel().pointerMoved(at[0], at[1]);
        } else maximized.panel().pointerMoved(-1, -1);
        renderTaskbar(g);
        if (!status.isEmpty()) g.centeredText(font, font.plainSubstrByWidth(status, width - 20), width / 2, height - TASKBAR_H - 14, 0xFFB8CBDE);
    }
    private void renderTaskbar(GuiGraphicsExtractor g) {
        var bar = taskbarRect();
        if (bar != null) blitLive(g, taskbar, bar);
        else g.text(font, "Taskbar opening...", 8, height - TASKBAR_H + 12, 0xFF9BAABD);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        releaseAll();
        minecraft.gui.setScreen(null);
    }
}
