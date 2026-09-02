package dev.winlandcraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

public final class AppsPanel extends WorldPanel {
    private final AppWindows apps;
    private boolean attached;
    private int first;
    private AppWindows.AppEntry menu;
    private int menuX,menuY;
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
    @Override public void close() { attached = false; menu=null; super.close(); }
    @Override public void mouseDown(int x, int y, int button) {
        if(button!=0&&button!=1)return;
        if(menu!=null) {
            var selected=menu;menu=null;
            if(button==0&&x>=menuX&&x<menuX+152&&y>=menuY&&y<menuY+32) {
                if(apps.appEntries().stream().anyMatch(e->e.panel()==selected.panel())) {
                    if(selected.panel().streaming())apps.streams.stop(selected.panel());else apps.stream(selected);
                }
                return;
            }
            if(button==0)return;
        }
        if (x >= 18 && x < 342 && y >= 42 && y < 138) {
            var entries = apps.appEntries();
            first = Math.clamp(first, 0, Math.max(0, entries.size() - 3));
            int index = first + (y - 42) / 32;
            if(index<entries.size()) {
                if(button==1){menu=entries.get(index);menuX=Math.clamp(x,4,204);menuY=Math.clamp(y,38,140);}
                else apps.launch(entries.get(index));
            }
        }
        else if (button==0 && x >= 320 && x < 360 && y >= 0 && y < 36) close();
    }
    @Override public void scroll(int x, int y, double amount) {
        menu=null;
        first = Math.clamp(first - (int) Math.signum(amount), 0, Math.max(0, apps.appEntries().size() - 3));
    }
    @Override public void render(WorldRenderContext context) {
        try (var surface = surface(context)) {
            if (surface == null || !surface.frontFacing()) return;
            var canvas=surface.canvas();
            canvas.rect(0, 0, 360, 180, 0, 0xFF18212D);
            canvas.rect(0, 0, 360, 36, 0.1f, 0xFF314D63);
            PixelIcon.APPS.draw(canvas,14,6,24,.2f,0xFF72ECF1);
            canvas.text("Apps",46,14,0xFFFFFFFF);
            canvas.rect(320,0,40,36,.2f,hoverColor(320,0,40,36,0xFF854551,0xFFB65B69));
            PixelIcon.CLOSE.draw(canvas,328,6,24,.3f,-1);
            var entries = apps.appEntries();
            first = Math.clamp(first, 0, Math.max(0, entries.size() - 3));
            for (int row = 0; row < 3 && first + row < entries.size(); row++) {
                var entry = entries.get(first + row); int y = 42 + row * 32;
                canvas.rect(18,y,324,30,.2f,hoverColor(18,y,324,32,0xFF294454,0xFF385A6C));
                apps.drawIcon(canvas, entry, 24, y + 3, 24);
                canvas.text(net.minecraft.client.Minecraft.getInstance().font.plainSubstrByWidth(entry.name(), 278), 56, y + 11, 0xFFFFFFFF);
            }
            canvas.text("Right-click: Stream | Scroll: more",18,154,0xFF9BAABD);
            if(menu!=null) {
                canvas.rect(menuX-1,menuY-1,154,34,.7f,0xFF657888);
                canvas.rect(menuX,menuY,152,32,.8f,hoverColor(menuX,menuY,152,32,0xFF314D63,0xFF466B83));
                canvas.text(menu.panel().streaming()?"Stop streaming":"Stream",menuX+12,menuY+12,-1,1,.9f);
            }
        }
    }
}
