package dev.winlandcraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;

public final class TasksPanel extends WorldPanel {
    private final AppWindows apps;
    private int first;
    @Override public boolean canResize() { return false; }
    public TasksPanel(AppWindows apps) { super(2.4f, 0.288f); this.apps = apps; }
    public void open(Minecraft client) {
        if (client.level != null && client.player != null) bringToView(client.level, client.gameRenderer.getMainCamera());
    }
    @Override public void mouseDown(int x, int y, int button) {
        if (button != 0) return;
        if (x >= 90 && x < 154 && y >= 8 && y < 40) apps.toggleStartMenu();
        var running = apps.runningApps();
        first = Math.clamp(first, 0, Math.max(0, running.size() - 5));
        if (x >= 168 && x < 388 && y >= 4 && y < 44) {
            int slot = (x - 168) / 44, local = (x - 168) % 44;
            if (local < 40 && first + slot < running.size()) {
                var entry = running.get(first + slot);
                if (local >= 28 && y < 16) entry.panel().close(); else apps.launch(entry);
            }
        }
    }
    @Override public void scroll(int x, int y, double amount) {
        first = Math.clamp(first - (int) Math.signum(amount), 0, Math.max(0, apps.runningApps().size() - 5));
    }
    @Override public void close() {
        if (apps.launcher.isAttached()) apps.launcher.close();
        super.close();
    }
    @Override public void render(WorldRenderContext context) {
        try (var canvas = canvas(context)) {
            if (canvas == null) return;
            canvas.rect(0, 0, 400, 48, 0, 0xFF536579);
            canvas.rect(1, 1, 398, 46, 0.15f, 0xFF18212D);
            canvas.rect(1, 45, 398, 2, 0.3f, 0xFF51CFDF);
            canvas.text("Tasks", 20, 20, 0xFFF0F5FC);
            canvas.rect(90, 8, 64, 32, 0.3f, apps.launcher.isAttached() ? 0xFF466B83 : 0xFF314D63);
            canvas.text("Apps", 110, 20, 0xFFF0F5FC);
            var running = apps.runningApps();
            first = Math.clamp(first, 0, Math.max(0, running.size() - 5));
            for (int slot = 0; slot < 5 && first + slot < running.size(); slot++) {
                int left = 168 + slot * 44;
                canvas.rect(left, 4, 40, 40, 0.3f, 0xFF294454);
                apps.drawIcon(canvas, running.get(first + slot), left + 8, 12, 24);
                canvas.rect(left + 28, 4, 12, 12, 0.5f, 0xFF653D48);
                // Pixel strokes keep the close button legible at this small size.
                for (int i = 0; i < 6; i++) {
                    canvas.rect(left + 31 + i, 7 + i, 1, 1, 0.6f, 0xFFFFFFFF);
                    canvas.rect(left + 36 - i, 7 + i, 1, 1, 0.6f, 0xFFFFFFFF);
                }
                canvas.rect(left + 14, 41, 12, 2, 0.4f, 0xFF51CFDF);
            }
            if (running.isEmpty()) canvas.text("No active apps", 176, 20, 0xFF9BAABD);
            if (running.size() > 5) canvas.text((first + 1) + "/" + running.size(), 20, 32, 0xFF9BAABD);
        }
    }
}
