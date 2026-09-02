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
        try (var surface = surface(context)) {
            if (surface == null || !surface.frontFacing()) return;
            var canvas=surface.canvas();
            canvas.rect(0, 0, 400, 48, 0, 0xFF536579);
            canvas.rect(1, 1, 398, 46, 0.15f, 0xFF18212D);
            canvas.rect(1, 45, 398, 2, 0.3f, 0xFF51CFDF);
            canvas.text("Tasks", 20, 20, 0xFFF0F5FC);
            int appsColor=apps.launcher.isAttached()?0xFF466B83:hoverColor(90,8,64,32,0xFF314D63,0xFF42637C);
            canvas.rect(90,8,64,32,.3f,appsColor);
            PixelIcon.APPS.draw(canvas,110,12,24,.4f,0xFFF0F5FC);
            var running = apps.runningApps();
            first = Math.clamp(first, 0, Math.max(0, running.size() - 5));
            for (int slot = 0; slot < 5 && first + slot < running.size(); slot++) {
                int left = 168 + slot * 44;
                canvas.rect(left,4,40,40,.3f,hoverColor(left,4,40,40,0xFF294454,0xFF385A6C));
                apps.drawIcon(canvas, running.get(first + slot), left + 8, 12, 24);
                canvas.rect(left+28,4,12,12,.5f,hoverColor(left+28,4,12,12,0xFF653D48,0xFF9A4D5B));
                PixelIcon.CLOSE.draw(canvas,left+28,4,12,.6f,-1);
                canvas.rect(left + 14, 41, 12, 2, 0.4f, 0xFF51CFDF);
            }
            if (running.isEmpty()) canvas.text("No active apps", 176, 20, 0xFF9BAABD);
            if (running.size() > 5) canvas.text((first + 1) + "/" + running.size(), 20, 32, 0xFF9BAABD);
        }
    }
}
