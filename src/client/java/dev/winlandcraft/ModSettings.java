package dev.winlandcraft;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;

final class ModSettings {
    static boolean freePanelRotation = false;
    static boolean removeSizingLimitations = false;
    static boolean extendInteractionRange = false;
    static double interactionRange = 16;
    static int streamFps=30,streamKbps=2000,streamHeight=720,streamAudioKbps=96;
    static boolean streamAudio=true;
    static boolean validInteractionRange(double value) { return Double.isFinite(value) && value > 0 && value <= 4096; }
    static double interactionRange(double normalRange) { return extendInteractionRange ? interactionRange : normalRange; }
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("winlandcraft.json"); }
    static void load() {
        freePanelRotation = false;
        removeSizingLimitations = false;
        extendInteractionRange = false;
        interactionRange = 16;
        try {
            if (Files.exists(path())) {
                var json = JsonParser.parseString(Files.readString(path())).getAsJsonObject();
                if(json.has("streamFps"))streamFps=StreamQuality.nearest(json.get("streamFps").getAsInt(),StreamQuality.FPS);
                if(json.has("streamKbps"))streamKbps=StreamQuality.nearest(json.get("streamKbps").getAsInt(),StreamQuality.BITRATES);
                if(json.has("streamHeight"))streamHeight=StreamQuality.nearest(json.get("streamHeight").getAsInt(),StreamQuality.HEIGHTS);
                if(json.has("streamAudioKbps"))streamAudioKbps=StreamQuality.nearest(json.get("streamAudioKbps").getAsInt(),StreamQuality.AUDIO);
                if(json.has("streamAudio"))streamAudio=json.get("streamAudio").getAsBoolean();
                if (json.has("freePanelRotation")) freePanelRotation = json.get("freePanelRotation").getAsBoolean();
                if (json.has("removeSizingLimitations")) removeSizingLimitations = json.get("removeSizingLimitations").getAsBoolean();
                if (json.has("extendInteractionRange")) extendInteractionRange = json.get("extendInteractionRange").getAsBoolean();
                if (json.has("interactionRange")) {
                    double value = json.get("interactionRange").getAsDouble();
                    if (validInteractionRange(value)) interactionRange = value;
                }
            }
        } catch (Exception error) { WinLandCraftClient.LOGGER.warn("Could not read WinLandCraft settings; using defaults", error); }
    }
    static boolean save() {
        try {
            var json = new JsonObject(); json.addProperty("freePanelRotation", freePanelRotation);
            json.addProperty("removeSizingLimitations", removeSizingLimitations);
            json.addProperty("extendInteractionRange", extendInteractionRange);
            json.addProperty("interactionRange", interactionRange);
            json.addProperty("streamFps",streamFps);json.addProperty("streamKbps",streamKbps);json.addProperty("streamHeight",streamHeight);
            json.addProperty("streamAudio",streamAudio);json.addProperty("streamAudioKbps",streamAudioKbps);
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
            return true;
        } catch (Exception error) {
            WinLandCraftClient.LOGGER.error("Could not save WinLandCraft settings", error);
            return false;
        }
    }
}
