package dev.winlandcraft.fixture;
import dev.winlandcraft.api.v2.*;
import java.nio.ByteBuffer;
public final class SurfaceFixture implements WinLandCraftPlugin {
    // API-only compile coverage: a real producer supplies a synchronized GL texture here.
    public static GpuSource textureSource(int id,int width,int height){return new GpuSource(){
        public GpuFrame acquire(){return new GpuFrame(id,width,height,true);}
        public void release(GpuFrame frame){}
        public void close(){}
    };}
    public void register(PluginRegistry registry){registry.register(AppDefinition.builder("surface_fixture:demo","Surface Demo",AppKind.SURFACE,Demo::new).size(1280,720).build());}
    private static final class Demo implements App {
        private FrameSurface frames;
        public void onOpen(WindowContext window){frames=window.frames();window.title("Frame producer");}
        public void onTick(){frames.submit(ByteBuffer.wrap(new byte[]{(byte)255,0,0,(byte)255}),1,1,4,PixelFormat.RGBA8);}
    }
}
