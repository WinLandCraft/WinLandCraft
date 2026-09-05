package dev.winlandcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import static dev.winlandcraft.LaserTuning.Parameter.*;

final class LaserTuningChecks {
    private static final float[] DEFAULTS={28.9f,-18f,123.3f,.064f,.158f,.003f,1.252f,-1.5f,0,3.45f,525,360,11,1};
    private static final float[] MINIMUMS={-90,-90,-180,-.5f,-.5f,-.5f,.5f,-4,-4,-1,150,150,-30,.25f};
    private static final float[] MAXIMUMS={90,90,180,.5f,.5f,.5f,1.75f,4,4,8,1200,1000,30,2};

    static void run() {
        var parameters=LaserTuning.Parameter.values();
        check(parameters.length==DEFAULTS.length,"all calibration parameters covered");
        for(int i=0;i<parameters.length;i++)near(DEFAULTS[i],parameters[i].value(),"initial calibration");
        try {
            for(int i=0;i<parameters.length;i++) {
                var parameter=parameters[i];
                LaserTuning.set(parameter,-Float.MAX_VALUE);near(MINIMUMS[i],parameter.value(),"lower limit");
                LaserTuning.set(parameter,Float.MAX_VALUE);near(MAXIMUMS[i],parameter.value(),"upper limit");
                for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
                    LaserTuning.set(parameter,invalid);near(DEFAULTS[i],parameter.value(),"nonfinite reset");
                }
                LaserTuning.setNormalized(parameter,.5f);
                near((MINIMUMS[i]+MAXIMUMS[i])/2,parameter.value(),"slider midpoint");
            }
            for(var section:LaserTuning.Section.values()) {
                for(var parameter:parameters)LaserTuning.set(parameter,Float.MAX_VALUE);
                LaserTuning.reset(section);
                Set<LaserTuning.Parameter> members=switch(section) {
                    case POSE->Set.of(PITCH,YAW,ROLL,X,Y,Z,SCALE);
                    case BEAM->Set.of(BEAM_X,BEAM_Y,BEAM_INSET);
                    case MOTION->Set.of(SPIN_MILLIS,BOUNCE_MILLIS,BOUNCE_DEGREES,SOUND_VOLUME);
                };
                for(int i=0;i<parameters.length;i++)near(members.contains(parameters[i])?DEFAULTS[i]:MAXIMUMS[i],parameters[i].value(),"section reset isolation");
            }
            persistence();
        } finally {for(var section:LaserTuning.Section.values())LaserTuning.reset(section);}
        System.out.println("Laser tuning: calibrated defaults, limits, sliders, resets, saved keys and settings round trips passed.");
    }

    private static void persistence() {
        var saved=JsonParser.parseString("""
            {"laserPosePitch":12.5,"laserPoseYaw":-22,"laserPoseRoll":60,
             "laserPoseX":0.1,"laserPoseY":-0.2,"laserPoseZ":0.3,"laserPoseScale":1.4,
             "laserBeamX":-2,"laserBeamY":3,"laserBeamInset":4,"laserSpinMillis":650,
             "laserBounceMillis":450,"laserBounceDegrees":-12,"laserSoundVolume":0.75}
            """).getAsJsonObject();
        float[] expected={12.5f,-22,60,.1f,-.2f,.3f,1.4f,-2,3,4,650,450,-12,.75f};
        var parameters=LaserTuning.Parameter.values();
        LaserTuning.reset();LaserTuning.load(saved);
        for(int i=0;i<parameters.length;i++)near(expected[i],parameters[i].value(),"existing saved value");
        var written=new JsonObject();LaserTuning.save(written);
        check(written.keySet().equals(saved.keySet()),"saved setting keys unchanged");
        for(String key:saved.keySet())near(saved.get(key).getAsFloat(),written.get(key).getAsFloat(),"saved value for "+key);
        LaserTuning.reset();LaserTuning.load(JsonParser.parseString(written.toString()).getAsJsonObject());
        for(int i=0;i<parameters.length;i++)near(expected[i],parameters[i].value(),"settings round trip");
        var partial=new JsonObject();partial.addProperty("laserPosePitch",-999);partial.addProperty("laserSoundVolume",999);
        partial.addProperty("laserBeamX",Float.NaN);partial.addProperty("laserBeamY",Float.POSITIVE_INFINITY);
        partial.addProperty("laserBeamInset",Float.NEGATIVE_INFINITY);partial.addProperty("futureSetting",123);
        LaserTuning.load(partial);
        near(-90,PITCH.value(),"saved lower limit");near(2,SOUND_VOLUME.value(),"saved upper limit");
        for(int i=1;i<parameters.length-1;i++)near(expected[i],parameters[i].value(),"missing or nonfinite value retained");
        written.addProperty("screenLighting",false);LaserTuning.save(written);
        check(!written.get("screenLighting").getAsBoolean(),"unrelated settings preserved");
    }
    private static void near(float expected,float actual,String message){check(Float.isFinite(actual)&&Math.abs(expected-actual)<.0001f,message+": expected "+expected+", got "+actual);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
