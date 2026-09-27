package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Uploads sampled browser colors for the Solas shader-pack bridge. */
public final class ScreenLighting {
    static final int COLUMNS=3,ROWS=2,MAX_PANELS=2;
    private static final float REFERENCE_PANEL_DIAGONAL=(float)Math.hypot(3.2,1.8),MAX_RANGE_SCALE=4;
    private static final int BINDING=7,HEADER_BYTES=16,PANEL_BYTES=192;
    private static final boolean IRIS=FabricLoader.getInstance().isModLoaded("iris");
    private static final boolean IRLIGHTS=FabricLoader.getInstance().isModLoaded("irl-core");
    private static final ByteBuffer DATA=ByteBuffer.allocateDirect(HEADER_BYTES+MAX_PANELS*PANEL_BYTES).order(ByteOrder.nativeOrder());
    private static final ArrayList<WorldPanel> candidates=new ArrayList<>();
    private static List<WorldPanel> panels=List.of();
    private static int buffer;
    private static Boolean support;

    private ScreenLighting(){}

    static void install(AppWindows apps) {
        panels=apps.windows;
    }

    /** Called after vanilla updates this frame's camera, before Iris begins the world pipeline. */
    public static void uploadFrame(Camera camera){upload(panels,camera);}

    static boolean supported() {
        if(!IRIS||!RenderSystem.isOnRenderThread())return false;
        if(support!=null)return support;
        if(IRLIGHTS) {
            WinLandCraftClient.LOGGER.warn("Screen lighting disabled because IRLights owns the same Solas shader resources");
            return support=false;
        }
        var capabilities=GL.getCapabilities();
        boolean extension=capabilities.OpenGL43||capabilities.GL_ARB_shader_storage_buffer_object;
        support=extension&&GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)>BINDING;
        if(!support) {
            WinLandCraftClient.LOGGER.warn("Screen lighting disabled: this OpenGL driver has no shader-storage buffers");
        }
        return support;
    }

    static boolean samplingEnabled(){return ModSettings.screenLighting&&supported();}

    static float[] smooth(float[] previous,float[] sampled) {
        if(previous==null||previous.length!=sampled.length)return sampled;
        float[] result=new float[sampled.length];
        for(int i=0;i<result.length;i++)result[i]=previous[i]+(sampled[i]-previous[i])*.35f;
        return result;
    }

    /** Keeps the configured range as a floor while scaling extreme displays sublinearly. */
    static float effectiveRange(float width,float height,float configuredRange) {
        double diagonal=Math.hypot(width,height);
        double scale=Math.sqrt(Math.max(1,diagonal/REFERENCE_PANEL_DIAGONAL));
        return configuredRange*(float)Math.min(scale,MAX_RANGE_SCALE);
    }

    private static double distanceToBounds(WorldPanel panel,Vec3 point) {
        double radius=Math.hypot(panel.worldWidth()/2,panel.worldHeight()/2);
        return Math.max(0,panel.position.distanceTo(point)-radius);
    }

    private static void upload(List<WorldPanel> panels,Camera camera) {
        RenderSystem.assertOnRenderThread();
        if(!ModSettings.screenLighting)return;
        if(!supported())return;
        Minecraft minecraft=Minecraft.getInstance();
        Vec3 eye=camera.getPosition();
        candidates.clear();
        for(WorldPanel panel:panels)
            if(panel.isOpen()&&panel.level==minecraft.level&&panel.screenLightColors()!=null)
                candidates.add(panel);
        candidates.sort(Comparator.comparingDouble(panel->distanceToBounds(panel,eye)));

        DATA.clear();DATA.position(HEADER_BYTES);
        int count=0;
        for(WorldPanel panel:candidates) {
            if(count==MAX_PANELS)break;
            if(putPanel(panel,eye))count++;
        }
        DATA.putInt(0,count);DATA.putInt(4,0);DATA.putInt(8,0);DATA.putInt(12,0);
        DATA.limit(HEADER_BYTES+count*PANEL_BYTES);DATA.position(0);
        if(buffer==0)buffer=GL15.glGenBuffers();
        int previous=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,DATA,GL15.GL_STREAM_DRAW);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,BINDING,buffer);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,previous);
    }

    private static boolean putPanel(WorldPanel panel,Vec3 eye) {
        float[] colors=panel.screenLightColors();
        float halfWidth=panel.worldWidth()/2,halfHeight=panel.worldHeight()/2;
        float range=effectiveRange(panel.worldWidth(),panel.worldHeight(),ModSettings.screenLightRange);
        double maximumDistance=range+Math.hypot(halfWidth,halfHeight);
        if(panel.position.distanceToSqr(eye)>maximumDistance*maximumDistance)return false;
        Vec3 center=panel.position.subtract(eye);
        Vector3f right=new Vector3f(1,0,0).rotate(panel.orientation).normalize();
        Vector3f up=new Vector3f(0,1,0).rotate(panel.orientation).normalize();
        Vector3f normal=new Vector3f(0,0,1).rotate(panel.orientation).normalize();
        DATA.putFloat((float)center.x).putFloat((float)center.y).putFloat((float)center.z).putFloat(halfWidth);
        DATA.putFloat(right.x).putFloat(right.y).putFloat(right.z).putFloat(halfHeight);
        DATA.putFloat(up.x).putFloat(up.y).putFloat(up.z).putFloat(range);
        DATA.putFloat(normal.x).putFloat(normal.y).putFloat(normal.z).putFloat(ModSettings.screenLightIntensity);
        GroupCurve curve=panel.curve;
        boolean curved=curve!=null&&curve.amount>=.0001f&&curve.layout.containsKey(panel);
        DATA.putFloat(curved?curve.radius():0).putFloat(curved?curve.facing:0).putFloat(0).putFloat(0);
        float red=0,green=0,blue=0;
        for(int color=0;color<COLUMNS*ROWS;color++) {
            int offset=color*3;red+=colors[offset];green+=colors[offset+1];blue+=colors[offset+2];
        }
        DATA.putFloat(red/(COLUMNS*ROWS)).putFloat(green/(COLUMNS*ROWS)).putFloat(blue/(COLUMNS*ROWS)).putFloat(0);
        for(int color=0;color<COLUMNS*ROWS;color++) {
            int offset=color*3;
            DATA.putFloat(colors[offset]).putFloat(colors[offset+1]).putFloat(colors[offset+2]).putFloat(0);
        }
        return true;
    }

    static void shutdown() {
        panels=List.of();
        if(buffer==0)return;
        if(!RenderSystem.isOnRenderThread()){RenderSystem.recordRenderCall(ScreenLighting::shutdown);return;}
        GL15.glDeleteBuffers(buffer);buffer=0;
    }
}
