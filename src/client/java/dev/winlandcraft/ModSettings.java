package dev.winlandcraft;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;

final class ModSettings {
    static final int POINTER_GAZE=0,POINTER_LASER=1;
    static final float DEFAULT_SCREEN_LIGHT_INTENSITY=3.5f,MIN_SCREEN_LIGHT_RANGE=24.0f;
    private static final float[] SCREEN_LIGHT_INTENSITIES={.5f,1f,1.5f,2f,2.5f,3f,3.5f,4f,5f,6f,8f};
    private static final float[] SCREEN_LIGHT_RANGES={24f,32f,48f,64f};
    static boolean freePanelRotation = false;
    static boolean removeSizingLimitations = false;
    static boolean extendInteractionRange = false;
    static double interactionRange = 16;
    static int panelPointerMode=POINTER_GAZE;
    static int streamCodecMode=0;
    static int streamFps=30,streamKbps=2000,streamHeight=720,streamAudioKbps=96;
    static boolean streamAudio=true,streamRemoteControl=false;
    static boolean screenLighting=true;
    static boolean panelSmoothing=true;
    static float screenLightIntensity=DEFAULT_SCREEN_LIGHT_INTENSITY,screenLightRange=MIN_SCREEN_LIGHT_RANGE;
    static boolean laserEnabled=true;
    static int laserColor;
    static boolean validInteractionRange(double value) { return Double.isFinite(value) && value > 0 && value <= 4096; }
    static double interactionRange(double normalRange) { return extendInteractionRange ? interactionRange : normalRange; }
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("winlandcraft.json"); }
    static void load() {
        freePanelRotation = false;
        removeSizingLimitations = false;
        extendInteractionRange = false;
        interactionRange = 16;
        panelPointerMode = POINTER_GAZE;
        streamRemoteControl = false;
        screenLighting = true;
        panelSmoothing = true;
        screenLightIntensity = DEFAULT_SCREEN_LIGHT_INTENSITY;
        screenLightRange = MIN_SCREEN_LIGHT_RANGE;
        laserEnabled = true;
        laserColor = 0;
        LaserTuning.reset();
        try {
            if (Files.exists(path())) {
                var json = JsonParser.parseString(Files.readString(path())).getAsJsonObject();
                if(json.has("streamCodecMode"))streamCodecMode=Math.clamp(json.get("streamCodecMode").getAsInt(),0,StreamQuality.CODECS.length-1);
                if(json.has("panelPointerMode"))panelPointerMode=Math.clamp(json.get("panelPointerMode").getAsInt(),POINTER_GAZE,POINTER_LASER);
                if(json.has("streamFps"))streamFps=StreamQuality.nearest(json.get("streamFps").getAsInt(),StreamQuality.FPS);
                if(json.has("streamKbps"))streamKbps=StreamQuality.nearest(json.get("streamKbps").getAsInt(),StreamQuality.BITRATES);
                if(json.has("streamHeight"))streamHeight=StreamQuality.nearest(json.get("streamHeight").getAsInt(),StreamQuality.HEIGHTS);
                if(json.has("streamAudioKbps"))streamAudioKbps=StreamQuality.nearest(json.get("streamAudioKbps").getAsInt(),StreamQuality.AUDIO);
                if(json.has("streamAudio"))streamAudio=json.get("streamAudio").getAsBoolean();
                if(json.has("streamRemoteControl"))streamRemoteControl=json.get("streamRemoteControl").getAsBoolean();
                if(json.has("screenLighting"))screenLighting=json.get("screenLighting").getAsBoolean();
                if(json.has("panelSmoothing"))panelSmoothing=json.get("panelSmoothing").getAsBoolean();
                if(json.has("screenLightIntensity"))screenLightIntensity=nearest(json.get("screenLightIntensity").getAsFloat(),SCREEN_LIGHT_INTENSITIES);
                if(json.has("screenLightRange"))screenLightRange=nearest(json.get("screenLightRange").getAsFloat(),SCREEN_LIGHT_RANGES);
                if(json.has("laserEnabled"))laserEnabled=json.get("laserEnabled").getAsBoolean();
                if(json.has("laserColor"))laserColor=LaserPointer.normalizeColor(json.get("laserColor").getAsInt());
                LaserTuning.load(json);
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
    static float nextScreenLightIntensity(float current){return next(current,SCREEN_LIGHT_INTENSITIES);}
    static float nextScreenLightRange(float current){return next(current,SCREEN_LIGHT_RANGES);}
    private static float next(float current,float[] values) {
        if(!Float.isFinite(current))return values[0];
        for(float value:values)if(value>current+.01f)return value;
        return values[0];
    }
    private static float nearest(float current,float[] values) {
        if(!Float.isFinite(current))return values[0];
        float nearest=values[0];
        for(float value:values)if(Math.abs(value-current)<Math.abs(nearest-current))nearest=value;
        return nearest;
    }
    static boolean laserPanelPointer(){return panelPointerMode==POINTER_LASER;}
    static boolean save() {
        try {
            var json = new JsonObject(); json.addProperty("freePanelRotation", freePanelRotation);
            json.addProperty("removeSizingLimitations", removeSizingLimitations);
            json.addProperty("extendInteractionRange", extendInteractionRange);
            json.addProperty("interactionRange", interactionRange);
            json.addProperty("panelPointerMode",panelPointerMode);
            json.addProperty("streamCodecMode",streamCodecMode);
            json.addProperty("streamFps",streamFps);json.addProperty("streamKbps",streamKbps);json.addProperty("streamHeight",streamHeight);
            json.addProperty("streamAudio",streamAudio);json.addProperty("streamAudioKbps",streamAudioKbps);
            json.addProperty("streamRemoteControl",streamRemoteControl);
            json.addProperty("screenLighting",screenLighting);
            json.addProperty("panelSmoothing",panelSmoothing);
            json.addProperty("screenLightIntensity",screenLightIntensity);
            json.addProperty("screenLightRange",screenLightRange);
            json.addProperty("laserEnabled",laserEnabled);
            json.addProperty("laserColor",LaserPointer.normalizeColor(laserColor));
            LaserTuning.save(json);
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
            return true;
        } catch (Exception error) {
            WinLandCraftClient.LOGGER.error("Could not save WinLandCraft settings", error);
            return false;
        }
    }
}
