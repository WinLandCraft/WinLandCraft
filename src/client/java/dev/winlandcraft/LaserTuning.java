package dev.winlandcraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.google.gson.JsonObject;
import java.util.Locale;
import org.joml.Quaternionf;

/** Persisted first-person alignment, beam origin, and motion tuning. */
final class LaserTuning {
    enum Section { POSE, BEAM, MOTION }

    enum Parameter {
        PITCH("laserPosePitch","Pitch",28.9f,-90,90,.1f,"deg"),
        YAW("laserPoseYaw","Yaw",-18f,-90,90,.1f,"deg"),
        ROLL("laserPoseRoll","Roll",123.3f,-180,180,.1f,"deg"),
        X("laserPoseX","Horizontal",.064f,-.5f,.5f,.002f,""),
        Y("laserPoseY","Vertical",.158f,-.5f,.5f,.002f,""),
        Z("laserPoseZ","Depth",.003f,-.5f,.5f,.002f,""),
        SCALE("laserPoseScale","Scale",1.252f,.5f,1.75f,.01f,"x"),
        BEAM_X("laserBeamX","Beam horizontal",-1.5f,-4,4,.1f,"u"),
        BEAM_Y("laserBeamY","Beam vertical",0,-4,4,.1f,"u"),
        BEAM_INSET("laserBeamInset","Beam inset",3.45f,-1,8,.1f,"u"),
        SPIN_MILLIS("laserSpinMillis","Color spin time",525,150,1200,25,"ms"),
        BOUNCE_MILLIS("laserBounceMillis","Power bounce time",360,150,1000,25,"ms"),
        BOUNCE_DEGREES("laserBounceDegrees","Power bounce angle",11,-30,30,.5f,"deg"),
        SOUND_VOLUME("laserSoundVolume","Power sound volume",1,.25f,2,.05f,"x");

        final String key,label,unit;
        final float defaultValue,minimum,maximum,step;
        private float value;
        Parameter(String key,String label,float defaultValue,float minimum,float maximum,float step,String unit) {
            this.key=key;this.label=label;this.defaultValue=defaultValue;this.value=defaultValue;
            this.minimum=minimum;this.maximum=maximum;this.step=step;this.unit=unit;
        }
        float value(){return value;}
    }

    private static final Parameter[] POSE={Parameter.PITCH,Parameter.YAW,Parameter.ROLL,Parameter.X,
            Parameter.Y,Parameter.Z,Parameter.SCALE};
    private static final Parameter[] BEAM={Parameter.BEAM_X,Parameter.BEAM_Y,Parameter.BEAM_INSET};
    private static final Parameter[] MOTION={Parameter.SPIN_MILLIS,Parameter.BOUNCE_MILLIS,
            Parameter.BOUNCE_DEGREES,Parameter.SOUND_VOLUME};

    private LaserTuning() { }

    static Parameter[] parameters(Section section) {
        return switch(section) {case POSE -> POSE;case BEAM -> BEAM;case MOTION -> MOTION;};
    }

    static void applyModel(PoseStack pose) {
        pose.translate(Parameter.X.value(),Parameter.Y.value(),Parameter.Z.value());
        pose.mulPose(new Quaternionf().rotationXYZ(radians(Parameter.PITCH.value()),
                radians(Parameter.YAW.value()),radians(Parameter.ROLL.value())));
        pose.scale(Parameter.SCALE.value(),Parameter.SCALE.value(),Parameter.SCALE.value());
    }

    static void set(Parameter parameter,float value) {
        parameter.value=Math.clamp(Float.isFinite(value)?value:parameter.defaultValue,parameter.minimum,parameter.maximum);
    }

    static void setNormalized(Parameter parameter,float value) {
        set(parameter,parameter.minimum+Math.clamp(value,0,1)*(parameter.maximum-parameter.minimum));
    }

    static void adjust(Parameter parameter,float direction) {
        set(parameter,parameter.value+Math.signum(direction)*parameter.step);
    }

    static float normalized(Parameter parameter) {
        return (parameter.value-parameter.minimum)/(parameter.maximum-parameter.minimum);
    }

    static String display(Parameter parameter) {
        float value=parameter.value;
        if(parameter.unit.equals("ms"))return String.format(Locale.ROOT,"%.0f ms",value);
        if(parameter.unit.equals("deg"))return String.format(Locale.ROOT,"%+.1f deg",value);
        if(parameter.unit.equals("x"))return String.format(Locale.ROOT,"%.2fx",value);
        if(parameter.unit.equals("u"))return String.format(Locale.ROOT,"%+.2f u",value);
        return String.format(Locale.ROOT,"%+.3f",value);
    }

    static String summary() {
        return String.format(Locale.ROOT,
                "pitch=%.1f, yaw=%.1f, roll=%.1f, x=%.3f, y=%.3f, z=%.3f, scale=%.3f, "
                        +"beamX=%.2f, beamY=%.2f, beamInset=%.2f, spinMs=%.0f, bounceMs=%.0f, "
                        +"bounceDeg=%.1f, sound=%.2f",
                Parameter.PITCH.value(),Parameter.YAW.value(),Parameter.ROLL.value(),
                Parameter.X.value(),Parameter.Y.value(),Parameter.Z.value(),Parameter.SCALE.value(),
                Parameter.BEAM_X.value(),Parameter.BEAM_Y.value(),Parameter.BEAM_INSET.value(),
                Parameter.SPIN_MILLIS.value(),Parameter.BOUNCE_MILLIS.value(),Parameter.BOUNCE_DEGREES.value(),
                Parameter.SOUND_VOLUME.value());
    }

    static void reset(Section section) {
        for(Parameter parameter:parameters(section))set(parameter,parameter.defaultValue);
    }

    static void reset(){for(var section:Section.values())reset(section);}

    static void load(JsonObject json) {
        for(var parameter:Parameter.values())if(json.has(parameter.key)) {
            float value=json.get(parameter.key).getAsFloat();
            if(Float.isFinite(value))set(parameter,value);
        }
    }

    static void save(JsonObject json){for(var parameter:Parameter.values())json.addProperty(parameter.key,parameter.value);}

    private static float radians(float degrees) {
        return (float)Math.toRadians(degrees);
    }
}
