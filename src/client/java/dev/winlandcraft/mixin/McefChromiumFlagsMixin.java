package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Applies CEF compatibility and hardware-acceleration command-line flags. */
@Mixin(targets = "com.cinemamod.mcef.CefUtil", remap = false)
public abstract class McefChromiumFlagsMixin {
    private static final AtomicBoolean WINLANDCRAFT$LOGGED = new AtomicBoolean();

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lorg/cef/CefApp;startup([Ljava/lang/String;)Z", remap = false),
            index = 0, remap = false)
    private static String[] winlandcraft$startupFlags(String[] arguments) {
        return winlandcraft$withChromiumFlags(arguments);
    }

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lorg/cef/CefApp;getInstance([Ljava/lang/String;Lorg/cef/CefSettings;)Lorg/cef/CefApp;", remap = false),
            index = 0, remap = false)
    private static String[] winlandcraft$instanceFlags(String[] arguments) {
        return winlandcraft$withChromiumFlags(arguments);
    }

    private static String[] winlandcraft$withChromiumFlags(String[] arguments) {
        boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
        var result = new ArrayList<String>(arguments.length + 7);
        var features = new LinkedHashSet<String>();
        var disabledFeatures = new LinkedHashSet<String>();
        boolean ignoresBlocklist = false, selectsGl = false, selectsAngle = false, disablesVulkan = false;
        for (String argument : arguments) {
            if (argument.startsWith("--enable-features=")) {
                for (String feature : argument.substring("--enable-features=".length()).split(","))
                    if (!feature.isBlank()) features.add(feature);
            } else if (argument.startsWith("--disable-features=")) {
                for (String feature : argument.substring("--disable-features=".length()).split(","))
                    if (!feature.isBlank()) disabledFeatures.add(feature);
            } else {
                result.add(argument);
                ignoresBlocklist |= argument.equals("--ignore-gpu-blocklist");
                selectsGl |= argument.startsWith("--use-gl=");
                selectsAngle |= argument.startsWith("--use-angle=");
                disablesVulkan |= argument.equals("--disable-vulkan");
            }
        }

        // Chromium 151's Alloy runtime crashes when this Chrome-only observer
        // handles a soft navigation (CEF #4234). Neither feature is exposed by MCEF.
        disabledFeatures.add("ImmersiveReadAnything");
        disabledFeatures.add("SoftNavigationDetection");

        if (linux) {
            features.add("VaapiVideoDecoder");
            features.add("VaapiVideoEncoder");
            if (!ignoresBlocklist) result.add("--ignore-gpu-blocklist");
            if (!selectsGl) {
                result.add("--use-gl=angle");
                if (!selectsAngle) result.add("--use-angle=gl");
            }
            if (!System.getenv().getOrDefault("WAYLAND_DISPLAY", "").isBlank()) {
                disabledFeatures.add("Vulkan");
                if (!disablesVulkan) result.add("--disable-vulkan");
            }
        }

        if (!features.isEmpty()) result.add("--enable-features=" + String.join(",", features));
        result.add("--disable-features=" + String.join(",", disabledFeatures));

        if (WINLANDCRAFT$LOGGED.compareAndSet(false, true)) {
            WinLandCraftClient.LOGGER.info(
                    "Applied Chromium CEF compatibility flags: disabled unsafe Alloy soft-navigation observer{}",
                    linux ? "; enabled Linux VA-API video acceleration" : "");
        }
        return result.toArray(String[]::new);
    }
}
