package dev.winlandcraft;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class WebAppsScreen extends Screen {
    private final Screen parent;
    private final Runnable changed;
    private int page;
    String status = "";
    WebAppsScreen(Screen parent, Runnable changed) {
        super(Component.literal("Your Webapps")); this.parent = parent; this.changed = changed;
    }
    @Override protected void init() {
        int rows = Math.max(1, (height - 112) / 26);
        page = Math.clamp(page, 0, Math.max(0, (WebApps.all().size() - 1) / rows));
        for (int row = 0; row < rows && page * rows + row < WebApps.all().size(); row++) {
            var app = WebApps.all().get(page * rows + row); int y = 40 + row * 26;
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(app.name(), 166)), b ->
                    minecraft.setScreen(new WebAppEditScreen(this, changed, app)))
                    .bounds(width / 2 - 145, y, 225, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                if (WebApps.remove(app.id())) { changed.run(); status = "Deleted " + app.name(); }
                else status = "Could not save. See latest.log.";
                rebuildWidgets();
            }).bounds(width / 2 + 85, y, 60, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
                .bounds(width / 2 - 145, height - 66, 30, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("Add webapp"), b -> minecraft.setScreen(new WebAppEditScreen(this, changed, null)))
                .bounds(width / 2 - 100, height - 66, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
                .bounds(width / 2 + 115, height - 66, 30, 20).build()).active = (page + 1) * rows < WebApps.all().size();
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 100, height - 26, 200, 20).build());
    }
    @Override public void render(GuiGraphics g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xF018212D); super.render(g, x, y, delta);
        g.drawCenteredString(font, title, width / 2, 16, -1);
        if (WebApps.all().isEmpty()) g.drawCenteredString(font, "Add a website to open it as its own app.", width / 2, 48, 0xFFB8CBDE);
        g.drawCenteredString(font, font.plainSubstrByWidth(status, width - 20), width / 2, height - 42, 0xFFB8CBDE);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
