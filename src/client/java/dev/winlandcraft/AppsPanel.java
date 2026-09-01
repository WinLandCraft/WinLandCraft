package dev.winlandcraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

public final class AppsPanel extends WorldPanel {
    private final AppWindows apps;
    private boolean attached;
    private int first;
    public AppsPanel(AppWindows apps) { super(1.8f, 0.9f); this.apps = apps; }
    @Override public int pixelWidth() { return 360; }
    @Override public int pixelHeight() { return 180; }
    public boolean isAttached() { return attached; }
    @Override public boolean canResize() { return false; }
    public void openAttached() {
        if (!apps.tasks.isOpen()) return;
        attached = true;
        syncAttachment();
    }
    public void syncAttachment() {
        if (!attached) return;
        if (!apps.tasks.isOpen()) close();
        else alignAboveLeft(apps.tasks);
    }
    @Override public WorldPanel dragTarget() { return attached ? apps.tasks : this; }
    @Override public void close() { attached = false; super.close(); }
    @Override public void mouseDown(int x, int y, int button) {
        if (button != 0) return;
        if (x >= 18 && x < 342 && y >= 42 && y < 138) {
            var entries = apps.appEntries();
            first = Math.clamp(first, 0, Math.max(0, entries.size() - 3));
            int index = first + (y - 42) / 32;
            if (index < entries.size()) apps.launch(entries.get(index));
        }
        else if (x >= 320 && x < 360 && y >= 0 && y < 36) close();
    }
    @Override public void scroll(int x, int y, double amount) {
        first = Math.clamp(first - (int) Math.signum(amount), 0, Math.max(0, apps.appEntries().size() - 3));
    }
    @Override public void render(WorldRenderContext context) {
        try (var canvas = canvas(context)) {
            if (canvas == null) return;
            canvas.rect(0, 0, 360, 180, 0, 0xFF18212D);
            canvas.rect(0, 0, 360, 36, 0.1f, 0xFF314D63);
            canvas.text("Apps", 18, 14, 0xFFFFFFFF);
            canvas.text("X", 332, 14, 0xFFFFFFFF);
            var entries = apps.appEntries();
            first = Math.clamp(first, 0, Math.max(0, entries.size() - 3));
            for (int row = 0; row < 3 && first + row < entries.size(); row++) {
                var entry = entries.get(first + row); int y = 42 + row * 32;
                canvas.rect(18, y, 324, 30, 0.2f, 0xFF294454);
                apps.drawIcon(canvas, entry, 24, y + 3, 24);
                canvas.text(net.minecraft.client.Minecraft.getInstance().font.plainSubstrByWidth(entry.name(), 278), 56, y + 11, 0xFFFFFFFF);
            }
            canvas.text(entries.size() > 3 ? "Scroll for more apps" : "Point and click an app.", 18, 154, 0xFF9BAABD);
        }
    }
}
