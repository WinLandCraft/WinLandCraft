package dev.winlandcraft;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ModSettingsScreen extends Screen {
    private final Screen parent;
    private final Runnable changed;
    private boolean saveFailed;
    private boolean invalidRange;
    private boolean rangeChanged;
    private boolean patching;
    private String patchStatus="";
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
    private Component pointerLabel(){return Component.literal("Panel pointer: "+(ModSettings.laserPanelPointer()?"Laser":"Gaze hover"));}
    private Component lightingLabel(){return Component.literal("Lighting "+(ModSettings.screenLighting?"ON":"OFF"));}
    private Component smoothingLabel(){return Component.literal("Panel smoothing: "+(ModSettings.panelSmoothing?"ON":"OFF"));}
    private Component lightPowerLabel(){return Component.literal(String.format(java.util.Locale.ROOT,"Power %.1fx",ModSettings.screenLightIntensity));}
    private Component lightRangeLabel(){return Component.literal("Range "+Math.round(ModSettings.screenLightRange)+"+");}
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
                .bounds(width / 2 - 145, 184, 130, 20).build());
        addRenderableWidget(Button.builder(smoothingLabel(),button->{
            ModSettings.panelSmoothing=!ModSettings.panelSmoothing;
            saveFailed=!ModSettings.save();button.setMessage(smoothingLabel());
        }).bounds(width/2-10,184,155,20)
                .tooltip(Tooltip.create(Component.literal("OFF removes distance blur. Pixels stay sharp but may shimmer at a distance. Applies immediately."))).build());
        addRenderableWidget(Button.builder(pointerLabel(),button->{
            ModSettings.panelPointerMode=ModSettings.laserPanelPointer()?ModSettings.POINTER_GAZE:ModSettings.POINTER_LASER;
            changed.run();saveFailed=!ModSettings.save();button.setMessage(pointerLabel());
        }).bounds(width/2-125,216,250,20)
                .tooltip(Tooltip.create(Component.literal("Gaze hover uses the screen cursor. Laser requires a powered laser pointer in either hand and uses its beam dot."))).build());
        addRenderableWidget(Button.builder(lightingLabel(),button->{
            ModSettings.screenLighting=!ModSettings.screenLighting;saveFailed=!ModSettings.save();button.setMessage(lightingLabel());
        }).bounds(width/2-125,252,96,20).build());
        addRenderableWidget(Button.builder(lightPowerLabel(),button->{
            ModSettings.screenLightIntensity=ModSettings.nextScreenLightIntensity(ModSettings.screenLightIntensity);
            saveFailed=!ModSettings.save();button.setMessage(lightPowerLabel());
        }).bounds(width/2-24,252,72,20).build());
        addRenderableWidget(Button.builder(lightRangeLabel(),button->{
            ModSettings.screenLightRange=ModSettings.nextScreenLightRange(ModSettings.screenLightRange);
            saveFailed=!ModSettings.save();button.setMessage(lightRangeLabel());
        }).bounds(width/2+53,252,72,20).build());
        addRenderableWidget(Button.builder(Component.literal("Create Solas lighting-compatible copy"),button->patchSolas())
                .bounds(width/2-125,288,250,20).build());
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
        graphics.drawCenteredString(font,"Choose exactly one pointer source; Laser mode requires the powered item.",width/2,240,0xFFB8CBDE);
        graphics.drawCenteredString(font,"Power is linear; range is a minimum that grows with panel size.",width/2,276,0xFFB8CBDE);
        if(!patchStatus.isEmpty())graphics.drawCenteredString(font,font.plainSubstrByWidth(patchStatus,width-20),width/2,314,
                patching?0xFFB8CBDE:patchStatus.startsWith("Created")?0xFF80E6AE:0xFFFF8888);
        if (saveFailed) graphics.drawCenteredString(font, "Could not save settings. See latest.log.", width / 2, 30, 0xFFFF8888);
    }
    @Override public void removed() {
        if (rangeChanged) { saveFailed = !ModSettings.save(); rangeChanged = false; }
        super.removed();
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    private void patchSolas() {
        if(patching)return;
        patching=true;patchStatus="Creating shader-pack copy...";
        Thread.ofVirtual().name("WinLandCraft Solas patcher").start(()->{
            var result=SolasShaderPatcher.install();
            minecraft.execute(()->{patching=false;patchStatus=result.message();});
        });
    }
}
