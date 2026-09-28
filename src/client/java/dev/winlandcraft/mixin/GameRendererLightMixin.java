package dev.winlandcraft.mixin;

import dev.winlandcraft.ScreenLighting;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uploads screen lights before the level renders. */
@Mixin(GameRenderer.class)
public final class GameRendererLightMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void winlandcraft$uploadScreenLights(CallbackInfo ci) {
        ScreenLighting.uploadFrame(((GameRenderer) (Object) this).mainCamera());
    }
}
