package dev.winlandcraft;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ModSettingsScreen extends Screen {
    private final Screen parent;
    private final Runnable changed;
    private boolean saveFailed;
    private boolean invalidRange;
    private boolean rangeChanged;
    private EditBox rangeField;
    ModSettingsScreen(Screen parent, Runnable changed) {
        super(Component.literal("WinLandCraft Settings"));
        this.parent = parent; this.changed = changed;
    }
    private Component rotationLabel() {
        return Component.literal("Free panel rotation: " + (ModSettings.freePanelRotation ? "ON" : "OFF"));
    }
    private Component sizingLabel(){return Component.literal("Remove sizing limitations: "+(ModSettings.removeSizingLimitations?"ON":"OFF"));}
    private Component rangeLabel(){return Component.literal("Extend interaction range: "+(ModSettings.extendInteractionRange?"ON":"OFF"));}
    @Override protected void init() {
        addRenderableWidget(Button.builder(rotationLabel(), button -> {
            ModSettings.freePanelRotation = !ModSettings.freePanelRotation;
            changed.run();
            saveFailed = !ModSettings.save();
            button.setMessage(rotationLabel());
        }).bounds(width / 2 - 125, 42, 250, 20).build());
        addRenderableWidget(Button.builder(sizingLabel(),button->{
            ModSettings.removeSizingLimitations=!ModSettings.removeSizingLimitations;
            changed.run();saveFailed=!ModSettings.save();button.setMessage(sizingLabel());
        }).bounds(width/2-125,94,250,20).build());
        rangeField = new EditBox(font, width/2+85, 142, 55, 20, Component.literal("Interaction range in blocks"));
        rangeField.setMaxLength(12);
        rangeField.setValue(java.math.BigDecimal.valueOf(ModSettings.interactionRange).stripTrailingZeros().toPlainString());
        rangeField.active = ModSettings.extendInteractionRange;
        invalidRange = false;
        rangeField.setResponder(text -> {
            try {
                double value = Double.parseDouble(text);
                invalidRange = !ModSettings.validInteractionRange(value);
                if (!invalidRange) { ModSettings.interactionRange = value; rangeChanged = true; }
            } catch (NumberFormatException error) { invalidRange = true; }
            rangeField.setTextColor(invalidRange ? 0xFF5555 : 0xFFFFFF);
        });
        addRenderableWidget(rangeField);
        addRenderableWidget(Button.builder(rangeLabel(),button->{
            ModSettings.extendInteractionRange=!ModSettings.extendInteractionRange;
            rangeField.active=ModSettings.extendInteractionRange;
            saveFailed=!ModSettings.save();button.setMessage(rangeLabel());
        }).bounds(width/2-140,142,220,20).build());
        addRenderableWidget(Button.builder(Component.literal("Manage webapps..."), button ->
                minecraft.setScreen(new WebAppsScreen(this, changed)))
                .bounds(width / 2 - 125, 184, 250, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 - 100, height - 30, 200, 20).build());
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xF018212D);
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.drawCenteredString(font, "OFF: panels stay upright, with level edges.", width / 2, 68, 0xFFB8CBDE);
        graphics.drawCenteredString(font, "ON: dragging can tilt panels freely.", width / 2, 80, 0xFFB8CBDE);
        graphics.drawCenteredString(font, "Unstable: extreme sizes may lag or crash the game.", width / 2, 122, 0xFFFF5555);
        graphics.drawCenteredString(font, invalidRange && ModSettings.extendInteractionRange
                ? "Enter a distance above 0, up to 4096 blocks."
                : "Blocks (max 4096). OFF uses normal Minecraft reach.", width / 2, 168,
                invalidRange && ModSettings.extendInteractionRange ? 0xFFFF5555 : 0xFF9BAABD);
        if (saveFailed) graphics.drawCenteredString(font, "Could not save settings. See latest.log.", width / 2, 30, 0xFFFF8888);
    }
    @Override public void removed() {
        if (rangeChanged) { saveFailed = !ModSettings.save(); rangeChanged = false; }
        super.removed();
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
