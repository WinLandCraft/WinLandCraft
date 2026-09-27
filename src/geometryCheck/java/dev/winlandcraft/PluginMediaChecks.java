package dev.winlandcraft;
import dev.winlandcraft.api.v2.*;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

public final class PluginMediaChecks {
    public static void main(String[] args)throws Exception {
        var signatures=new TreeSet<String>();var required=new TreeSet<String>();
        for(String name:List.of("WinLandCraftPlugin","PluginRegistry","AppKind","App","AppDefinition","AppDefinition$Builder","Canvas","WindowContext","BrowserView","FrameSurface","PixelFormat","AudioOutput")){
            var type=Class.forName("dev.winlandcraft.api.v2."+name);signatures.add("class "+type.getName());
            for(var m:type.getDeclaredMethods())if(Modifier.isPublic(m.getModifiers())&&!m.isSynthetic()){signatures.add(m.toGenericString());if(type.isInterface()&&Modifier.isAbstract(m.getModifiers()))required.add(m.toGenericString());}
            for(var f:type.getDeclaredFields())if(Modifier.isPublic(f.getModifiers()))signatures.add(f.toGenericString());
            for(var c:type.getDeclaredConstructors())if(Modifier.isPublic(c.getModifiers()))signatures.add(c.toGenericString());
        }
        var old=new HashSet<>(Files.readAllLines(Path.of(args[0])));check(signatures.containsAll(old),"v2 ABI changed");check(old.containsAll(required),"mandatory v2 method added");
        FrameSurface legacy=new FrameSurface(){public boolean submit(ByteBuffer b,int w,int h,int s,PixelFormat f){return true;}public void clear(){}};
        check(!legacy.supportsGpu(),"legacy default capability");
        try{legacy.gpuSource(null);throw new AssertionError("legacy GPU unsupported");}catch(UnsupportedOperationException expected){}
        int[] calls=new int[3];var gpuFrame=new GpuFrame(1,1280,720,false);
        GpuSource gpu=new GpuSource(){public GpuFrame acquire(){calls[0]++;return gpuFrame;}public void release(GpuFrame f){check(f==gpuFrame,"same borrowed frame released");calls[1]++;}public void close(){}};
        PluginGpuTransfer.copy(gpu,f->calls[2]++);check(Arrays.equals(calls,new int[]{1,1,1}),"GPU acquire copy release");
        try{PluginGpuTransfer.copy(gpu,f->{throw new IllegalStateException("copy failed");});throw new AssertionError("copy failure lost");}catch(IllegalStateException expected){}
        check(calls[1]==2,"release after failed copy");
        PluginGpuTransfer.copy(new GpuSource(){public GpuFrame acquire(){return null;}public void release(GpuFrame f){throw new AssertionError("null release");}public void close(){}},f->{throw new AssertionError("null copy");});
        try{new GpuFrame(0,1,1,true);throw new AssertionError("invalid texture accepted");}catch(IllegalArgumentException expected){}
        var mailbox=new PluginFrameMailbox();byte[] raw=new byte[27];for(int i=0;i<raw.length;i++)raw[i]=(byte)i;
        var source=ByteBuffer.wrap(raw);source.position(3);
        check(mailbox.submit(source,2,2,12,PixelFormat.BGRA8),"frame accepted");check(source.position()==3,"input position preserved");
        raw[3]=99;var frame=mailbox.take().frame();check(frame.pixels().length==16&&frame.pixels()[0]==3&&frame.pixels()[8]==15,"copied rows without padding");
        check(frame.format()==PixelFormat.BGRA8,"format preserved");
        mailbox.submit(ByteBuffer.wrap(new byte[4]),1,1,4,PixelFormat.RGBA8);
        mailbox.submit(ByteBuffer.wrap(new byte[]{8,7,6,5}),1,1,4,PixelFormat.RGBA8);
        check(mailbox.take().frame().pixels()[0]==8&&mailbox.take().frame()==null,"one latest frame");
        mailbox.submit(ByteBuffer.wrap(new byte[4]),1,1,4,PixelFormat.RGBA8);mailbox.clear();var cleared=mailbox.take();check(cleared.clear()&&cleared.frame()==null,"clear discards pending");
        for(int[] dimensions:new int[][]{{0,1,4},{4097,1,16388},{2,1,4},{2,2,Integer.MAX_VALUE}}){try{mailbox.submit(ByteBuffer.allocate(16),dimensions[0],dimensions[1],dimensions[2],PixelFormat.RGBA8);throw new AssertionError("invalid dimensions");}catch(IllegalArgumentException expected){}}
        mailbox.close();check(!mailbox.submit(null,0,0,0,null),"closed producer rejected");
        float[] stereo={2,-2,Float.NaN,.5f};byte[] pcm=PluginAudio.pcm(stereo);stereo[0]=0;
        var floats=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();check(floats.get(0)==1&&floats.get(1)==0&&floats.get(2)==-1&&floats.get(3)==.5f,"owned sanitized planar stereo");
        try{PluginAudio.pcm(new float[3]);throw new AssertionError("odd PCM");}catch(IllegalArgumentException expected){}
        var apps=new AppWindows();var plugin=(WinLandCraftPlugin)Class.forName("dev.winlandcraft.fixture.SurfaceFixture").getConstructor().newInstance();
        apps.plugins.register("surface_fixture",r->plugin.register(d->r.register(PluginV2.adapt(d))));
        var panel=(PluginPanel)apps.plugins.entries().getFirst().panel();check(!panel.browserEnabled()&&panel.pixelWidth()==1280,"surface app without CEF");
        var nativeApp=new PluginAudio(panel);nativeApp.localPlayback(false);check(nativeApp.submit(new float[960]),"silent local audio accepted");nativeApp.close();check(!nativeApp.submit(new float[960]),"closed audio rejected");
        System.out.println("Plugin v2: frozen ABI, independent compilation, stride/copy/latest-frame ownership, limits, close, PCM and surface registration passed.");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
