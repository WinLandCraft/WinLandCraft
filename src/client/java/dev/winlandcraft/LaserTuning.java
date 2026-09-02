package dev.winlandcraft;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Locale;
import org.joml.Quaternionf;

/** Persisted first-person alignment, beam origin, and motion tuning. */
final class LaserTuning {
    enum Section { POSE, BEAM, MOTION }

    enum Parameter {
        PITCH("Pitch",-90,90,.1f,"deg"),YAW("Yaw",-90,90,.1f,"deg"),
        ROLL("Roll",-180,180,.1f,"deg"),X("Horizontal",-.5f,.5f,.002f,""),
        Y("Vertical",-.5f,.5f,.002f,""),Z("Depth",-.5f,.5f,.002f,""),
        SCALE("Scale",.5f,1.75f,.01f,"x"),
        BEAM_X("Beam horizontal",-4,4,.1f,"u"),BEAM_Y("Beam vertical",-4,4,.1f,"u"),
        BEAM_INSET("Beam inset",-1,8,.1f,"u"),SPIN_MILLIS("Color spin time",150,1200,25,"ms"),
        BOUNCE_MILLIS("Power bounce time",150,1000,25,"ms"),
        BOUNCE_DEGREES("Power bounce angle",-30,30,.5f,"deg"),
        SOUND_VOLUME("Power sound volume",.25f,2,.05f,"x");

        final String label,unit;
        final float minimum,maximum,step;
        Parameter(String label,float minimum,float maximum,float step,String unit) {
            this.label=label;this.minimum=minimum;this.maximum=maximum;this.step=step;this.unit=unit;
        }
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
        pose.translate(ModSettings.laserPoseX,ModSettings.laserPoseY,ModSettings.laserPoseZ);
        pose.mulPose(new Quaternionf().rotationXYZ(radians(ModSettings.laserPosePitch),
                radians(ModSettings.laserPoseYaw),radians(ModSettings.laserPoseRoll)));
        pose.scale(ModSettings.laserPoseScale,ModSettings.laserPoseScale,ModSettings.laserPoseScale);
    }

    static float value(Parameter parameter) {
        return switch(parameter) {
            case PITCH -> ModSettings.laserPosePitch;
            case YAW -> ModSettings.laserPoseYaw;
            case ROLL -> ModSettings.laserPoseRoll;
            case X -> ModSettings.laserPoseX;
            case Y -> ModSettings.laserPoseY;
            case Z -> ModSettings.laserPoseZ;
            case SCALE -> ModSettings.laserPoseScale;
            case BEAM_X -> ModSettings.laserBeamX;
            case BEAM_Y -> ModSettings.laserBeamY;
            case BEAM_INSET -> ModSettings.laserBeamInset;
            case SPIN_MILLIS -> ModSettings.laserSpinMillis;
            case BOUNCE_MILLIS -> ModSettings.laserBounceMillis;
            case BOUNCE_DEGREES -> ModSettings.laserBounceDegrees;
            case SOUND_VOLUME -> ModSettings.laserSoundVolume;
        };
    }

    static void set(Parameter parameter,float value) {
        value=Math.clamp(Float.isFinite(value)?value:defaultValue(parameter),parameter.minimum,parameter.maximum);
        switch(parameter) {
            case PITCH -> ModSettings.laserPosePitch=value;
            case YAW -> ModSettings.laserPoseYaw=value;
            case ROLL -> ModSettings.laserPoseRoll=value;
            case X -> ModSettings.laserPoseX=value;
            case Y -> ModSettings.laserPoseY=value;
            case Z -> ModSettings.laserPoseZ=value;
            case SCALE -> ModSettings.laserPoseScale=value;
            case BEAM_X -> ModSettings.laserBeamX=value;
            case BEAM_Y -> ModSettings.laserBeamY=value;
            case BEAM_INSET -> ModSettings.laserBeamInset=value;
            case SPIN_MILLIS -> ModSettings.laserSpinMillis=value;
            case BOUNCE_MILLIS -> ModSettings.laserBounceMillis=value;
            case BOUNCE_DEGREES -> ModSettings.laserBounceDegrees=value;
            case SOUND_VOLUME -> ModSettings.laserSoundVolume=value;
        }
    }

    static void setNormalized(Parameter parameter,float value) {
        set(parameter,parameter.minimum+Math.clamp(value,0,1)*(parameter.maximum-parameter.minimum));
    }

    static void adjust(Parameter parameter,float direction) {
        set(parameter,value(parameter)+Math.signum(direction)*parameter.step);
    }

    static float normalized(Parameter parameter) {
        return (value(parameter)-parameter.minimum)/(parameter.maximum-parameter.minimum);
    }

    static String display(Parameter parameter) {
        float value=value(parameter);
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
                ModSettings.laserPosePitch,ModSettings.laserPoseYaw,ModSettings.laserPoseRoll,
                ModSettings.laserPoseX,ModSettings.laserPoseY,ModSettings.laserPoseZ,ModSettings.laserPoseScale,
                ModSettings.laserBeamX,ModSettings.laserBeamY,ModSettings.laserBeamInset,
                ModSettings.laserSpinMillis,ModSettings.laserBounceMillis,ModSettings.laserBounceDegrees,
                ModSettings.laserSoundVolume);
    }

    static void reset(Section section) {
        for(Parameter parameter:parameters(section))set(parameter,defaultValue(parameter));
    }

    private static float defaultValue(Parameter parameter) {
        return switch(parameter) {
            case PITCH -> ModSettings.DEFAULT_LASER_PITCH;
            case YAW -> ModSettings.DEFAULT_LASER_YAW;
            case ROLL -> ModSettings.DEFAULT_LASER_ROLL;
            case X -> ModSettings.DEFAULT_LASER_X;
            case Y -> ModSettings.DEFAULT_LASER_Y;
            case Z -> ModSettings.DEFAULT_LASER_Z;
            case SCALE -> ModSettings.DEFAULT_LASER_SCALE;
            case BEAM_X -> ModSettings.DEFAULT_LASER_BEAM_X;
            case BEAM_Y -> ModSettings.DEFAULT_LASER_BEAM_Y;
            case BEAM_INSET -> ModSettings.DEFAULT_LASER_BEAM_INSET;
            case SPIN_MILLIS -> ModSettings.DEFAULT_LASER_SPIN_MILLIS;
            case BOUNCE_MILLIS -> ModSettings.DEFAULT_LASER_BOUNCE_MILLIS;
            case BOUNCE_DEGREES -> ModSettings.DEFAULT_LASER_BOUNCE_DEGREES;
            case SOUND_VOLUME -> ModSettings.DEFAULT_LASER_SOUND_VOLUME;
        };
    }

    private static float radians(float degrees) {
        return (float)Math.toRadians(degrees);
    }
}
