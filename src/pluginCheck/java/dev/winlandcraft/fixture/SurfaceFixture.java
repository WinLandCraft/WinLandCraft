package dev.winlandcraft.fixture;
import dev.winlandcraft.api.v2.*;
import java.nio.ByteBuffer;
public final class SurfaceFixture implements WinLandCraftPlugin {
    public void register(PluginRegistry registry){registry.register(AppDefinition.builder("surface_fixture:demo","Surface Demo",AppKind.SURFACE,Demo::new).size(1280,720).build());}
    private static final class Demo implements App {
        private FrameSurface frames;
        public void onOpen(WindowContext window){frames=window.frames();window.title("Frame producer");}
        public void onTick(){frames.submit(ByteBuffer.wrap(new byte[]{(byte)255,0,0,(byte)255}),1,1,4,PixelFormat.RGBA8);}
    }
}
