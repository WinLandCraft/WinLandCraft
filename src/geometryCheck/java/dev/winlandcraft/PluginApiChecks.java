package dev.winlandcraft;
import dev.winlandcraft.api.v1.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public final class PluginApiChecks {
    public static void main(String[] args)throws Exception {
        var classes=List.of(WinLandCraftPlugin.class,PluginRegistry.class,AppKind.class,App.class,AppDefinition.class,AppDefinition.Builder.class,Canvas.class,WindowContext.class,BrowserView.class);
        var signatures=new TreeSet<String>();var required=new TreeSet<String>();
        for(var type:classes){
            signatures.add("class "+type.getName());
            for(var method:type.getDeclaredMethods())if(Modifier.isPublic(method.getModifiers())&&!method.isSynthetic()){
                String s=method.toGenericString();signatures.add(s);
                if(type.isInterface()&&Modifier.isAbstract(method.getModifiers()))required.add(s);
            }
            for(var field:type.getDeclaredFields())if(Modifier.isPublic(field.getModifiers()))signatures.add(field.toGenericString());
            for(var ctor:type.getDeclaredConstructors())if(Modifier.isPublic(ctor.getModifiers()))signatures.add(ctor.toGenericString());
        }
        Path baseline=Path.of(args[0]);
        if(args.length>1&&args[1].equals("--record")){Files.write(baseline,signatures);return;}
        var old=new HashSet<>(Files.readAllLines(baseline));
        check(signatures.containsAll(old),"v1 public ABI removed or changed");
        check(old.containsAll(required),"new mandatory v1 interface callback; use default methods");
        var apps=new AppWindows();
        var plugin=(WinLandCraftPlugin)Class.forName("example.ExamplePlugin").getConstructor().newInstance();
        apps.plugins.register("wlc_example",plugin);
        check(apps.plugins.entries().size()==3,"external example registers all three kinds");
        check(apps.fileTargets(Path.of("TEST.WLCNOTES")).size()==1,"plugin file association");
        check(apps.fileTargets(Path.of("test.txt")).getFirst().id().equals("winlandcraft:notepad"),"core handler retained");
        try{apps.plugins.register("broken",r->{r.register(AppDefinition.builder("broken:valid","Valid",AppKind.NATIVE,()->new App(){}).build());throw new IllegalStateException();});throw new AssertionError("registration should fail");}catch(IllegalStateException expected){}
        check(apps.plugins.entries().size()==3,"failed registration rolls back");
        try{apps.plugins.register("other",r->r.register(AppDefinition.builder("wrong:id","Bad",AppKind.NATIVE,()->new App(){}).build()));throw new AssertionError("namespace");}catch(IllegalArgumentException expected){}
        try{AppDefinition.builder("other:../escape","Bad",AppKind.NATIVE,()->new App(){});throw new AssertionError("unsafe id");}catch(IllegalArgumentException expected){}
        var panel=(PluginPanel)apps.plugins.entries().getFirst().panel();
        panel.position=new Vec3(0,0,-3);panel.orientation=new Quaternionf();
        panel.resize(4,1.2f);check(panel.pixelWidth()==1600&&panel.pixelHeight()==480,"plugin resize changes viewport");
        panel.scaleTo(8,2.4f);check(panel.pixelWidth()==1600&&panel.pixelHeight()==480,"plugin scale preserves viewport");
        int[] events=new int[4];App receiver=new App(){public void onPointerDown(int x,int y,int b){events[0]++;}public void onPointerUp(int x,int y,int b){events[1]++;}public void onKey(int k,int s,int a,int m){events[2]++;}public void onCharacter(char c,int m){events[3]++;}};
        var field=PluginPanel.class.getDeclaredField("app");field.setAccessible(true);field.set(panel,receiver);
        panel.mouseDown(50,50,0);panel.mouseUp(-10,-10,0);panel.key(65,0,1,0);panel.character('a',0);
        check(Arrays.equals(events,new int[]{1,1,1,1}),"native local pointer and keyboard routing");
        check(!panel.browserEnabled()&&!panel.remoteControlAllowed(),"native plugin creates no CEF and no remote input");
        System.out.println("Plugin API: v1 ABI, external native/Chromium/hybrid compilation, registration rollback, file association, geometry and local input passed.");
    }
    private static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
}
