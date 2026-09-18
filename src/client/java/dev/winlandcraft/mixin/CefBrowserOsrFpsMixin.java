package dev.winlandcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the user's chosen Chromium FPS to every new OSR browser.
 * CEF's default windowless_frame_rate is 30; we override it at browser creation time
 * so the native browser is created with the right BeginFrame rate and users can pick
 * 20..120 based on how much GPU/bandwidth they want to burn. Stays in core mod, no fork.
 * Uses reflection to avoid a compile-time dep on jcef.jar (provided by MCEF at runtime).
 * The injection is optional (require=0) so a JCEF build that lacks the target still boots at 30.
 */
@Pseudo
@Mixin(targets = "org.cef.browser.CefBrowserOsr", remap = false)
public abstract class CefBrowserOsrFpsMixin {
    @Inject(method = "<init>(Lorg/cef/CefClient;Ljava/lang/String;ZLorg/cef/browser/CefRequestContext;Lorg/cef/CefBrowserSettings;)V",
            at = @At("HEAD"), remap = false, require = 0)
    private void winlandcraft$frameRateInit(Object client, String url, boolean transparent,
                                            Object context, Object settings) {
        if (settings != null) {
            try {
                var field = settings.getClass().getField("windowless_frame_rate");
                Class<?> modSettings = Class.forName("dev.winlandcraft.ModSettings");
                int fps = modSettings.getDeclaredField("chromiumFps").getInt(null);
                field.setInt(settings, fps);
            } catch (ReflectiveOperationException ignored) { }
        }
    }

    // Fallback for JCEF builds that validate via a static helper (different build of jcef.jar).
    @Inject(method = "copyAndValidateSettings(Lorg/cef/CefBrowserSettings;ZZ)Lorg/cef/CefBrowserSettings;",
            at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void winlandcraft$frameRateValidated(Object settings, boolean b1, boolean b2,
                                                        CallbackInfoReturnable<Object> cir) {
        Object validated = cir.getReturnValue();
        if (validated != null) {
            try {
                var field = validated.getClass().getField("windowless_frame_rate");
                Class<?> modSettings = Class.forName("dev.winlandcraft.ModSettings");
                int fps = modSettings.getDeclaredField("chromiumFps").getInt(null);
                field.setInt(validated, fps);
                cir.setReturnValue(validated);
            } catch (ReflectiveOperationException ignored) { }
        }
    }
}
