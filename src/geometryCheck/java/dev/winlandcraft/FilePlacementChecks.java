package dev.winlandcraft;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class FilePlacementChecks {
    static void run() {
        listRows();
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
        System.out.println("File placement: row bounds after scroll/resize, associations, four edges, rotated/curved poses, viewer-facing folds, modal double-click and cancel passed.");
    }
    private static void listRows() {
        try {
            var panel=new FileManagerPanel();var entries=new java.util.ArrayList<FileDirectory.Entry>();
            for(int i=0;i<40;i++)entries.add(new FileDirectory.Entry(Path.of("file-"+i+".txt"),"File "+i,false,false,0,0));
            var result=FileManagerPanel.class.getDeclaredField("result");result.setAccessible(true);
            result.set(panel,new FileDirectory.Listing(entries,List.of(),false,""));
            check(panel.visibleRows()==16&&panel.listBottom()==656,"default list viewport");
            check(panel.dragFileAt(239,112)==null&&panel.dragFileAt(240,111)==null,"left and top list bounds");
            check(panel.dragFileAt(1260,112)==null&&panel.dragFileAt(240,656)==null,"right and bottom list bounds");
            check(entries.getFirst().path().equals(panel.dragFileAt(240,112)),"first row starts at list origin");
            check(entries.getFirst().path().equals(panel.dragFileAt(1259,145)),"row gap remains part of its hit area");
            check(entries.get(1).path().equals(panel.dragFileAt(240,146)),"next row boundary");
            check(entries.get(15).path().equals(panel.dragFileAt(240,655)),"last visible row");
            panel.scroll(300,112,-1);
            check(entries.get(3).path().equals(panel.dragFileAt(240,112)),"scroll offset used by file picking");
            panel.resize(4,2.25f);
            check(panel.visibleRows()==21&&panel.listBottom()==826,"resized list viewport");
            check(entries.get(23).path().equals(panel.dragFileAt(1579,825)),"resized last row and right edge");
            check(panel.dragFileAt(1580,825)==null&&panel.dragFileAt(240,826)==null,"resized exterior bounds");
            var hover=FileManagerPanel.class.getDeclaredField("hoverRow");hover.setAccessible(true);
            var selected=FileManagerPanel.class.getDeclaredField("selected");selected.setAccessible(true);
            panel.pointerMoved(1579,825);panel.mouseDown(1579,825,0);
            check(hover.getInt(panel)==23&&selected.getInt(panel)==23,"hover, click and drag agree after scroll/resize");
        } catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
