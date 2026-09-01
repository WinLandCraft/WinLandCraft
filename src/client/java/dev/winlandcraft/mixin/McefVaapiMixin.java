package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds the Linux VA-API switches required by MCEF's Chromium 116 build. */
@Mixin(targets = "com.cinemamod.mcef.CefUtil", remap = false)
public abstract class McefVaapiMixin {
    private static final AtomicBoolean WINLANDCRAFT$LOGGED = new AtomicBoolean();

    // JCEF processes these arguments during startup and again during CefApp
    // construction. Modify both calls so browser and helper processes agree.
    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lorg/cef/CefApp;startup([Ljava/lang/String;)Z", remap = false),
            index = 0, remap = false)
    private static String[] winlandcraft$startupVaapi(String[] arguments) {
        return winlandcraft$withVaapi(arguments);
    }

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lorg/cef/CefApp;getInstance([Ljava/lang/String;Lorg/cef/CefSettings;)Lorg/cef/CefApp;", remap = false),
            index = 0, remap = false)
    private static String[] winlandcraft$instanceVaapi(String[] arguments) {
        return winlandcraft$withVaapi(arguments);
    }

    private static String[] winlandcraft$withVaapi(String[] arguments) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return arguments;

        var result = new ArrayList<String>(arguments.length + 3);
        var features = new LinkedHashSet<String>();
        boolean ignoresBlocklist = false, selectsGl = false;
        for (String argument : arguments) {
            if (argument.startsWith("--enable-features=")) {
                for (String feature : argument.substring("--enable-features=".length()).split(","))
                    if (!feature.isBlank()) features.add(feature);
            } else {
                result.add(argument);
                ignoresBlocklist |= argument.equals("--ignore-gpu-blocklist");
                selectsGl |= argument.startsWith("--use-gl=");
            }
        }
        features.add("VaapiVideoDecoder");
        features.add("VaapiVideoEncoder");
        result.add("--enable-features=" + String.join(",", features));
        if (!ignoresBlocklist) result.add("--ignore-gpu-blocklist");
        if (!selectsGl) result.add("--use-gl=egl");

        if (WINLANDCRAFT$LOGGED.compareAndSet(false, true))
            WinLandCraftClient.LOGGER.info("Enabled Chromium Linux VA-API video acceleration: features={}, ignoreGpuBlocklist={}, GL={}",
                    String.join(",", features), true, selectsGl ? "preconfigured" : "egl");
        return result.toArray(String[]::new);
    }
}
