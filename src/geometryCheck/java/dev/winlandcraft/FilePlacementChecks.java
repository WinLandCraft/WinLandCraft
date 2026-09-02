package dev.winlandcraft;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class FilePlacementChecks {
    static void run() {
        check(FileAppPlacement.supported(Path.of("notes.TXT")),"case insensitive association");
        check(FileAppPlacement.supported(Path.of("README")),"extensionless text association");
        check(!FileAppPlacement.supported(Path.of("photo.png")),"unsupported file");
        for(boolean curved:new boolean[]{false,true})for(int facing:new int[]{-1,1})for(var side:FileAppPlacement.Side.values()) {
            var source=new FileManagerPanel();source.position=new Vec3(12,5,-8);source.orientation=new Quaternionf().rotateXYZ(.2f,.6f,.1f);
            if(curved)GroupCurve.get(source).apply(.7f);
            var original=source.position;var rotation=new Quaternionf(source.orientation);
            var target=new NotepadPanel();
            var normal=new Vector3f(0,0,facing*5).rotate(rotation);
            FileAppPlacement.place(source,target,side,source.position.add(normal.x,normal.y,normal.z));
            int sx=side==FileAppPlacement.Side.LEFT?-1:side==FileAppPlacement.Side.RIGHT?1:0;
            int sy=side==FileAppPlacement.Side.BELOW?-1:side==FileAppPlacement.Side.ABOVE?1:0;
            Vec3 sourceEdge=curved?source.curve.panelPoint(source,sx*source.worldWidth()/2,sy*source.worldHeight()/2,0):WindowGroups.world(source.position,source.orientation,sx*source.worldWidth()/2,sy*source.worldHeight()/2);
            Vec3 targetEdge=WindowGroups.world(target.position,target.orientation,-sx*target.worldWidth()/2,-sy*target.worldHeight()/2);
            check(Math.abs(targetEdge.distanceTo(sourceEdge)-.08)<.0001,"edge gap on "+side);
            check(source.position.equals(original)&&rotation.equals(source.orientation),"source pose preserved");
            check(!target.grouped()&&target.curve==null,"independent new window");
            if(!curved)check(WindowGroups.local(source,target.position).z*facing>0,"fold toward viewer");
        }
        try {
            var source=new FileManagerPanel();var file=Path.of("notes.txt");
            var listing=FileManagerPanel.class.getDeclaredField("result");listing.setAccessible(true);
            listing.set(source,new FileDirectory.Listing(List.of(new FileDirectory.Entry(file,"notes.txt",false,false,0,0)),List.of(),false,""));
            source.mouseDown(300,120,0);check(file.equals(source.dragFileAt(300,120)),"first click still allows dragging");
            source.mouseDown(300,120,0);check(source.dragFileAt(300,120)==null,"double click blocks dragging");
            var modal=FileManagerPanel.class.getDeclaredField("placementFile");modal.setAccessible(true);
            check(file.equals(modal.get(source)),"double click opens placement");
            source.mouseDown(640,410,0);check(modal.get(source)==null,"cancel placement");
            check(source.dragFileAt(300,120)==null,"cancel does not start hidden file drag");
        } catch(ReflectiveOperationException error){throw new AssertionError(error);}
        System.out.println("File placement: associations, four edges, rotated/curved poses, viewer-facing folds, modal double-click and cancel passed.");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
