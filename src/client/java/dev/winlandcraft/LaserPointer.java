package dev.winlandcraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A panel interaction ray emitted in the same first-person pass as its item model. */
public final class LaserPointer {
    private static final long AIM_MAX_AGE_NANOS=500_000_000L;
    private static final Color[] COLORS = {
            new Color("color.winlandcraft.laser.red",0xFF4355),new Color("color.winlandcraft.laser.gold",0xFFC857),
            new Color("color.winlandcraft.laser.lime",0xFF6DE58A),new Color("color.winlandcraft.laser.cyan",0xFF51E6FF),
            new Color("color.winlandcraft.laser.violet",0xFFB87AFF)
    };
    private static ItemDisplayContext captureContext;
    private static Matrix4f handRoot;
    private static Aim preparedAim;
    private static long spinStarted,bounceStarted;
    private static float bounceDirection=1,sampledSpinDegrees,sampledBounceDegrees;

    private LaserPointer() { }

    static InteractionHand heldHand(Minecraft client) {
        if(client.player==null)return null;
        if(client.player.getMainHandItem().is(WinLandCraft.LASER_POINTER))return InteractionHand.MAIN_HAND;
        return client.player.getMainHandItem().isEmpty()&&client.player.getOffhandItem().is(WinLandCraft.LASER_POINTER)
                ?InteractionHand.OFF_HAND:null;
    }

    static boolean active(Minecraft client) {
        return ModSettings.laserPanelPointer()&&ModSettings.laserEnabled&&heldHand(client)!=null;
    }

    static int normalizeColor(int index) {
        return Math.floorMod(index,COLORS.length);
    }

    static void toggle(Minecraft client) {
        ModSettings.laserEnabled=!ModSettings.laserEnabled;
        ModSettings.save();
        bounceDirection=ModSettings.laserEnabled?1:-.55f;
        bounceStarted=System.nanoTime();
        powerSound(client);
        message(client,ModSettings.laserEnabled
                ?Component.translatable("message.winlandcraft.laser_on",color().label())
                :Component.translatable("message.winlandcraft.laser_off"));
    }

    static void cycleColor(Minecraft client) {
        ModSettings.laserColor=normalizeColor(ModSettings.laserColor+1);
        ModSettings.save();
        spinStarted=System.nanoTime();
        colorSound(client);
        message(client,Component.translatable("message.winlandcraft.laser_color",color().label()));
    }

    static void previewSpin(){spinStarted=System.nanoTime();}
    static void previewBounce(Minecraft client){bounceDirection=1;bounceStarted=System.nanoTime();powerSound(client);}

    public static void beginHands(PoseStack pose) {
        // Keep the world endpoint under view bob, but outside the item's independent hand motion.
        handRoot=new Matrix4f(pose.last().pose());
    }

    public static void beginHandRender(ItemDisplayContext context) {
        Minecraft client=Minecraft.getInstance();
        captureContext=context==selectedContext(client)?context:null;
        if(captureContext==null)return;
        float bounce=animation(bounceStarted,ModSettings.laserBounceMillis);
        sampledBounceDegrees=bounce<0?0:bounceDegrees(bounce,ModSettings.laserBounceDegrees*bounceDirection);
        float spin=animation(spinStarted,ModSettings.laserSpinMillis);
        sampledSpinDegrees=spin<0?0:spinDegrees(spin);
    }

    public static void captureModelTransform(PoseStack source,ItemTransform transform,MultiBufferSource buffers) {
        if(captureContext==null)return;
        PoseStack pose=new PoseStack();
        pose.mulPose(source.last().pose());
        transform.apply(captureContext==ItemDisplayContext.FIRST_PERSON_LEFT_HAND,pose);
        applyCalibration(pose);
        pose.translate(-.5f,-.5f,-.5f);
        Matrix4f matrix=pose.last().pose();
        Vector3f muzzle=matrix.transformPosition(new Vector3f(.5f+ModSettings.laserBeamX/16,
                .5f+ModSettings.laserBeamY/16,ModSettings.laserBeamInset/16));
        renderBeam(buffers,muzzle);
    }

    public static void applyCalibration(PoseStack pose) {
        if(captureContext==null)return;
        LaserTuning.applyModel(pose);
        if(sampledBounceDegrees!=0)pose.mulPose(new org.joml.Quaternionf().rotationX(
                (float)Math.toRadians(sampledBounceDegrees)));
        if(sampledSpinDegrees!=0)pose.mulPose(new org.joml.Quaternionf().rotationZ(
                (float)Math.toRadians(sampledSpinDegrees)));
    }

    public static void endHandRender() {
        captureContext=null;
    }

    public static void endHands() {
        handRoot=null;
    }

    static void prepare(Camera camera,Vec3 target) {
        Minecraft client=Minecraft.getInstance();
        if(!active(client)||client.options.hideGui||client.level==null) {
            preparedAim=null;
            return;
        }
        Vec3 eye=camera.getPosition();
        if(target==null) {
            HitResult crosshair=client.hitResult;
            if(crosshair!=null&&crosshair.getType()!=HitResult.Type.MISS)target=crosshair.getLocation();
            else {
                Vector3f forward=camera.getLookVector();
                Vec3 reach=eye.add(forward.x*64,forward.y*64,forward.z*64);
                HitResult block=client.level.clip(new ClipContext(eye,reach,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,client.player));
                target=block.getType()==HitResult.Type.MISS?reach:block.getLocation();
            }
        }
        preparedAim=new Aim(target,System.nanoTime());
    }

    private static void renderBeam(MultiBufferSource buffers,Vector3f from) {
        Minecraft client=Minecraft.getInstance();
        Aim aim=preparedAim;
        if(!active(client)||client.options.hideGui||aim==null||client.level==null
                ||handRoot==null||System.nanoTime()-aim.preparedAt>AIM_MAX_AGE_NANOS)return;
        Camera camera=client.gameRenderer.getMainCamera();
        Vec3 eye=camera.getPosition();
        Matrix4f worldToHand=new Matrix4f(handRoot).mul(new Matrix4f().rotation(
                new Quaternionf(camera.rotation()).conjugate()));
        Vector3f to=worldToHand.transformPosition(relative(aim.target,eye));
        Vector3f viewer=worldToHand.transformPosition(new Vector3f());
        double distance=new Vector3f(from).sub(to).length();
        if(distance<.05)return;

        Vector3f up=worldToHand.transformDirection(new Vector3f(camera.getUpVector())).normalize();
        Vector3f left=worldToHand.transformDirection(new Vector3f(camera.getLeftVector())).normalize();
        Vector3f axis=new Vector3f(to).sub(from).normalize();
        Vector3f view=new Vector3f(from).add(to).mul(.5f).negate().add(viewer);
        Vector3f side=new Vector3f(axis).cross(view);
        if(side.lengthSquared()<1.0e-8f)side.set(axis).cross(up);
        if(side.lengthSquared()<1.0e-8f)side.set(left);
        side.normalize();
        if(new Vector3f(axis).cross(side).dot(view)<0)side.negate();

        float startWidth=beamWidth(.34),endWidth=beamWidth(distance);
        int rgb=color().rgb;
        VertexConsumer vertices=buffers.getBuffer(RenderType.lightning());
        Matrix4f matrix=new Matrix4f();
        ribbon(vertices,matrix,from,to,side,startWidth*3.4f,endWidth*3.4f,withAlpha(rgb,42));
        ribbon(vertices,matrix,from,to,side,startWidth,endWidth,withAlpha(brighten(rgb),230));
        target(vertices,matrix,to,viewer,left,up,targetRadius(distance),rgb);
    }

    static float beamWidth(double distance) {
        return Math.clamp((float)distance*.0012f,.0004f,.04f);
    }

    static float targetRadius(double distance) {
        return Math.clamp((float)distance*.0035f,.014f,.11f);
    }

    static float spinDegrees(float progress) {
        return progress>=1?0:360*cubicBezier(Math.clamp(progress,0,1),.65f,0,.35f,1);
    }

    static float bounceDegrees(float progress,float peak) {
        progress=Math.clamp(progress,0,1);
        if(progress<.38f)return peak*cubicBezier(progress/.38f,.2f,.8f,.3f,1);
        if(progress<.76f)return lerp(peak,-peak*.12f,cubicBezier((progress-.38f)/.38f,.45f,0,.55f,1));
        return lerp(-peak*.12f,0,cubicBezier((progress-.76f)/.24f,.2f,0,.2f,1));
    }

    private static float animation(long started,float durationMillis) {
        if(started==0||durationMillis<=0)return -1;
        float progress=(System.nanoTime()-started)/(durationMillis*1_000_000f);
        return progress>=1?-1:Math.max(0,progress);
    }

    private static float cubicBezier(float x,float x1,float y1,float x2,float y2) {
        float low=0,high=1,t=x;
        for(int iteration=0;iteration<12;iteration++) {
            float estimate=bezier(t,x1,x2);
            if(estimate<x)low=t;else high=t;
            t=(low+high)*.5f;
        }
        return bezier(t,y1,y2);
    }

    private static float bezier(float t,float a,float b) {
        float inverse=1-t;
        return 3*inverse*inverse*t*a+3*inverse*t*t*b+t*t*t;
    }

    private static float lerp(float from,float to,float amount){return from+(to-from)*amount;}

    private static void ribbon(VertexConsumer out,Matrix4f matrix,Vector3f from,Vector3f to,Vector3f side,
                               float startWidth,float endWidth,int color) {
        Vector3f near=new Vector3f(side).mul(startWidth),far=new Vector3f(side).mul(endWidth);
        vertex(out,matrix,new Vector3f(from).sub(near),color);
        vertex(out,matrix,new Vector3f(to).sub(far),color);
        vertex(out,matrix,new Vector3f(to).add(far),color);
        vertex(out,matrix,new Vector3f(from).add(near),color);
    }

    private static void target(VertexConsumer out,Matrix4f matrix,Vector3f center,Vector3f viewer,
                               Vector3f left,Vector3f up,float radius,int rgb) {
        float offset=Math.clamp(center.distance(viewer)*.0001f,.002f,.02f);
        Vector3f towardCamera=new Vector3f(viewer).sub(center).normalize(offset);
        Vector3f at=new Vector3f(center).add(towardCamera);
        quad(out,matrix,at,left,up,radius*1.9f,withAlpha(rgb,54));
        quad(out,matrix,at,left,up,radius,withAlpha(brighten(rgb),245));
    }

    private static void quad(VertexConsumer out,Matrix4f matrix,Vector3f center,Vector3f left,Vector3f up,
                             float radius,int color) {
        Vector3f horizontal=new Vector3f(left).normalize(radius),vertical=new Vector3f(up).normalize(radius);
        vertex(out,matrix,new Vector3f(center).sub(horizontal),color);
        vertex(out,matrix,new Vector3f(center).add(vertical),color);
        vertex(out,matrix,new Vector3f(center).add(horizontal),color);
        vertex(out,matrix,new Vector3f(center).sub(vertical),color);
    }

    private static Vector3f relative(Vec3 point,Vec3 eye) {
        Vec3 relative=point.subtract(eye);
        return new Vector3f((float)relative.x,(float)relative.y,(float)relative.z);
    }

    private static ItemDisplayContext selectedContext(Minecraft client) {
        InteractionHand hand=heldHand(client);
        if(hand==null||client.player==null)return null;
        HumanoidArm arm=hand==InteractionHand.MAIN_HAND?client.player.getMainArm():client.player.getMainArm().getOpposite();
        return arm==HumanoidArm.LEFT?ItemDisplayContext.FIRST_PERSON_LEFT_HAND:ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
    }

    private static void vertex(VertexConsumer out,Matrix4f matrix,Vector3f point,int color) {
        out.addVertex(matrix,point.x,point.y,point.z).setColor(color);
    }

    private static int withAlpha(int rgb,int alpha) {
        return alpha<<24|rgb&0xFFFFFF;
    }

    private static int brighten(int rgb) {
        int r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;
        return (r+(255-r)*2/5)<<16|(g+(255-g)*2/5)<<8|b+(255-b)*2/5;
    }

    private static Color color() {
        ModSettings.laserColor=normalizeColor(ModSettings.laserColor);
        return COLORS[ModSettings.laserColor];
    }

    private static void powerSound(Minecraft client) {
        float volume=ModSettings.laserSoundVolume;
        float pitch=ModSettings.laserEnabled?1.55f:1.15f;
        sound(client,ModSettings.laserEnabled?SoundEvents.COPPER_BULB_TURN_ON:SoundEvents.COPPER_BULB_TURN_OFF,pitch,1.6f*volume);
        sound(client,SoundEvents.UI_BUTTON_CLICK.value(),ModSettings.laserEnabled?1.45f:1.05f,.7f*volume);
        sound(client,SoundEvents.AMETHYST_BLOCK_CHIME,ModSettings.laserEnabled?1.8f:1.25f,.5f*volume);
    }

    private static void colorSound(Minecraft client) {
        sound(client,SoundEvents.UI_BUTTON_CLICK.value(),1.2f,.34f);
        sound(client,SoundEvents.AMETHYST_BLOCK_CHIME,1.05f+ModSettings.laserColor*.14f,.18f);
    }

    private static void sound(Minecraft client,net.minecraft.sounds.SoundEvent event,float pitch,float volume) {
        client.getSoundManager().play(SimpleSoundInstance.forUI(event,pitch,volume));
    }

    private static void message(Minecraft client,Component text) {
        if(client.player!=null)client.player.displayClientMessage(text,true);
    }

    private record Color(String translationKey,int rgb) {
        Component label() {
            return Component.translatable(translationKey).withStyle(style->style.withColor(TextColor.fromRgb(rgb)).withBold(true));
        }
    }

    private record Aim(Vec3 target,long preparedAt) { }
}
