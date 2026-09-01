package dev.winlandcraft;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Base64;
import java.util.UUID;

final class WebAppEditScreen extends Screen {
    private final WebAppsScreen parent;
    private final Runnable changed;
    private final WebApps.App original;
    private EditBox name, url;
    private Button save;
    private WebAppIconProbe probe;
    private String status = "";
    WebAppEditScreen(WebAppsScreen parent, Runnable changed, WebApps.App original) {
        super(Component.literal(original == null ? "Add Webapp" : "Edit Webapp"));
        this.parent = parent; this.changed = changed; this.original = original;
    }
    @Override protected void init() {
        String oldName = name != null ? name.getValue() : original == null ? "" : original.name();
        String oldUrl = url != null ? url.getValue() : original == null ? "" : original.url();
        name = new EditBox(font, width / 2 - 145, 48, 290, 20, Component.literal("App name"));
        name.setMaxLength(48); name.setValue(oldName); addRenderableWidget(name);
        url = new EditBox(font, width / 2 - 145, 90, 290, 20, Component.literal("Website URL"));
        url.setMaxLength(8192); url.setValue(oldUrl); addRenderableWidget(url);
        save = addRenderableWidget(Button.builder(Component.literal("Save and get icon"), b -> save())
                .bounds(width / 2 - 145, height - 30, 175, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width / 2 + 35, height - 30, 110, 20).build());
        name.setEditable(probe == null); url.setEditable(probe == null); save.active = probe == null;
        setInitialFocus(name);
    }
    private void save() {
        String appName = name.getValue().strip(); String appUrl = WebApps.normalizeUrl(url.getValue());
        if (appName.isEmpty()) { status = "Enter a name."; return; }
        if (appUrl == null) { status = "Enter a valid HTTP or HTTPS website URL."; return; }
        name.setEditable(false); url.setEditable(false); save.active = false;
        status = "Loading website to find its icon...";
        probe = new WebAppIconProbe(appUrl, png -> {
            probe = null;
            String icon = png == null ? original != null && original.url().equals(appUrl) ? original.iconPng() : ""
                    : Base64.getEncoder().encodeToString(png);
            var app = new WebApps.App(original == null ? UUID.randomUUID().toString() : original.id(), appName, appUrl, icon);
            if (WebApps.put(app)) {
                changed.run();
                parent.status = png == null ? "Saved. No icon found; using a fallback or previous icon." : "Saved " + appName;
                minecraft.setScreen(parent);
            } else {
                status = "Could not save. See latest.log.";
                name.setEditable(true); url.setEditable(true); save.active = true;
            }
        });
    }
    @Override public void tick() { if (probe != null) probe.tick(); }
    @Override public void removed() { if (probe != null) { probe.cancel(); probe = null; } }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void render(GuiGraphics g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xF018212D); super.render(g, x, y, delta);
        g.drawCenteredString(font, title, width / 2, 16, -1);
        g.drawString(font, "Name", width / 2 - 145, 36, 0xFFB8CBDE);
        g.drawString(font, "Website URL", width / 2 - 145, 78, 0xFFB8CBDE);
        g.drawCenteredString(font, font.plainSubstrByWidth(status, width - 20), width / 2, 128, 0xFFB8CBDE);
        g.drawCenteredString(font, "Opens in its own full 1280 x 720 panel.", width / 2, 150, 0xFF9BAABD);
    }
}
