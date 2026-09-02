package dev.winlandcraft;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;

final class ModSettings {
    static final float DEFAULT_SCREEN_LIGHT_INTENSITY=3.5f,MIN_SCREEN_LIGHT_RANGE=24.0f;
    static final float DEFAULT_LASER_PITCH=28.9f,DEFAULT_LASER_YAW=-18f,DEFAULT_LASER_ROLL=123.3f;
    static final float DEFAULT_LASER_X=.064f,DEFAULT_LASER_Y=.158f,DEFAULT_LASER_Z=.003f,DEFAULT_LASER_SCALE=1.252f;
    static final float DEFAULT_LASER_BEAM_X=-1.5f,DEFAULT_LASER_BEAM_Y=0,DEFAULT_LASER_BEAM_INSET=3.45f;
    static final float DEFAULT_LASER_SPIN_MILLIS=525f;
    static final float DEFAULT_LASER_BOUNCE_MILLIS=360f,DEFAULT_LASER_BOUNCE_DEGREES=11f,DEFAULT_LASER_SOUND_VOLUME=1f;
    private static final float[] SCREEN_LIGHT_INTENSITIES={.5f,1f,1.5f,2f,2.5f,3f,3.5f,4f,5f,6f,8f};
    private static final float[] SCREEN_LIGHT_RANGES={24f,32f,48f,64f};
    static boolean freePanelRotation = false;
    static boolean removeSizingLimitations = false;
    static boolean extendInteractionRange = false;
    static double interactionRange = 16;
    static int streamFps=30,streamKbps=2000,streamHeight=720,streamAudioKbps=96;
    static boolean streamAudio=true,streamRemoteControl=false;
    static boolean screenLighting=true;
    static boolean panelSmoothing=true;
    static float screenLightIntensity=DEFAULT_SCREEN_LIGHT_INTENSITY,screenLightRange=MIN_SCREEN_LIGHT_RANGE;
    static boolean laserEnabled=true;
    static int laserColor;
    static float laserPosePitch=DEFAULT_LASER_PITCH,laserPoseYaw=DEFAULT_LASER_YAW,laserPoseRoll=DEFAULT_LASER_ROLL;
    static float laserPoseX=DEFAULT_LASER_X,laserPoseY=DEFAULT_LASER_Y,laserPoseZ=DEFAULT_LASER_Z,laserPoseScale=DEFAULT_LASER_SCALE;
    static float laserBeamX=DEFAULT_LASER_BEAM_X,laserBeamY=DEFAULT_LASER_BEAM_Y,
            laserBeamInset=DEFAULT_LASER_BEAM_INSET;
    static float laserSpinMillis=DEFAULT_LASER_SPIN_MILLIS,laserBounceMillis=DEFAULT_LASER_BOUNCE_MILLIS;
    static float laserBounceDegrees=DEFAULT_LASER_BOUNCE_DEGREES,laserSoundVolume=DEFAULT_LASER_SOUND_VOLUME;
    static boolean validInteractionRange(double value) { return Double.isFinite(value) && value > 0 && value <= 4096; }
    static double interactionRange(double normalRange) { return extendInteractionRange ? interactionRange : normalRange; }
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("winlandcraft.json"); }
    static void load() {
        freePanelRotation = false;
        removeSizingLimitations = false;
        extendInteractionRange = false;
        interactionRange = 16;
        streamRemoteControl = false;
        screenLighting = true;
        panelSmoothing = true;
        screenLightIntensity = DEFAULT_SCREEN_LIGHT_INTENSITY;
        screenLightRange = MIN_SCREEN_LIGHT_RANGE;
        laserEnabled = true;
        laserColor = 0;
        laserPosePitch=DEFAULT_LASER_PITCH;laserPoseYaw=DEFAULT_LASER_YAW;laserPoseRoll=DEFAULT_LASER_ROLL;
        laserPoseX=DEFAULT_LASER_X;laserPoseY=DEFAULT_LASER_Y;laserPoseZ=DEFAULT_LASER_Z;laserPoseScale=DEFAULT_LASER_SCALE;
        laserBeamX=DEFAULT_LASER_BEAM_X;laserBeamY=DEFAULT_LASER_BEAM_Y;laserBeamInset=DEFAULT_LASER_BEAM_INSET;
        laserSpinMillis=DEFAULT_LASER_SPIN_MILLIS;laserBounceMillis=DEFAULT_LASER_BOUNCE_MILLIS;
        laserBounceDegrees=DEFAULT_LASER_BOUNCE_DEGREES;laserSoundVolume=DEFAULT_LASER_SOUND_VOLUME;
        try {
            if (Files.exists(path())) {
                var json = JsonParser.parseString(Files.readString(path())).getAsJsonObject();
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
                laserPosePitch=number(json,"laserPosePitch",laserPosePitch,-90,90);
                laserPoseYaw=number(json,"laserPoseYaw",laserPoseYaw,-90,90);
                laserPoseRoll=number(json,"laserPoseRoll",laserPoseRoll,-180,180);
                laserPoseX=number(json,"laserPoseX",laserPoseX,-.5f,.5f);
                laserPoseY=number(json,"laserPoseY",laserPoseY,-.5f,.5f);
                laserPoseZ=number(json,"laserPoseZ",laserPoseZ,-.5f,.5f);
                laserPoseScale=number(json,"laserPoseScale",laserPoseScale,.5f,1.75f);
                laserBeamX=number(json,"laserBeamX",laserBeamX,-4,4);
                laserBeamY=number(json,"laserBeamY",laserBeamY,-4,4);
                laserBeamInset=number(json,"laserBeamInset",laserBeamInset,-1,8);
                laserSpinMillis=number(json,"laserSpinMillis",laserSpinMillis,150,1200);
                laserBounceMillis=number(json,"laserBounceMillis",laserBounceMillis,150,1000);
                laserBounceDegrees=number(json,"laserBounceDegrees",laserBounceDegrees,-30,30);
                laserSoundVolume=number(json,"laserSoundVolume",laserSoundVolume,.25f,2);
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
    private static float number(JsonObject json,String name,float fallback,float minimum,float maximum) {
        if(!json.has(name))return fallback;
        float value=json.get(name).getAsFloat();
        return Float.isFinite(value)?Math.clamp(value,minimum,maximum):fallback;
    }
    static boolean save() {
        try {
            var json = new JsonObject(); json.addProperty("freePanelRotation", freePanelRotation);
            json.addProperty("removeSizingLimitations", removeSizingLimitations);
            json.addProperty("extendInteractionRange", extendInteractionRange);
            json.addProperty("interactionRange", interactionRange);
            json.addProperty("streamFps",streamFps);json.addProperty("streamKbps",streamKbps);json.addProperty("streamHeight",streamHeight);
            json.addProperty("streamAudio",streamAudio);json.addProperty("streamAudioKbps",streamAudioKbps);
            json.addProperty("streamRemoteControl",streamRemoteControl);
            json.addProperty("screenLighting",screenLighting);
            json.addProperty("panelSmoothing",panelSmoothing);
            json.addProperty("screenLightIntensity",screenLightIntensity);
            json.addProperty("screenLightRange",screenLightRange);
            json.addProperty("laserEnabled",laserEnabled);
            json.addProperty("laserColor",LaserPointer.normalizeColor(laserColor));
            json.addProperty("laserPosePitch",laserPosePitch);json.addProperty("laserPoseYaw",laserPoseYaw);
            json.addProperty("laserPoseRoll",laserPoseRoll);json.addProperty("laserPoseX",laserPoseX);
            json.addProperty("laserPoseY",laserPoseY);json.addProperty("laserPoseZ",laserPoseZ);
            json.addProperty("laserPoseScale",laserPoseScale);
            json.addProperty("laserBeamX",laserBeamX);json.addProperty("laserBeamY",laserBeamY);
            json.addProperty("laserBeamInset",laserBeamInset);json.addProperty("laserSpinMillis",laserSpinMillis);
            json.addProperty("laserBounceMillis",laserBounceMillis);json.addProperty("laserBounceDegrees",laserBounceDegrees);
            json.addProperty("laserSoundVolume",laserSoundVolume);
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
            return true;
        } catch (Exception error) {
            WinLandCraftClient.LOGGER.error("Could not save WinLandCraft settings", error);
            return false;
        }
    }
}
