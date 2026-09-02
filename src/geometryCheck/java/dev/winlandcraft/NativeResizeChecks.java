package dev.winlandcraft;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

final class NativeResizeChecks {
    static void run() {
        for(var panel:new NativePanel[]{new FileManagerPanel(),new NotepadPanel(),new TaskManagerPanel(null),new LaserCalibrationPanel()}) {
            float dx=panel.pixelWidth()/panel.worldWidth(),dy=panel.pixelHeight()/panel.worldHeight();
            panel.resize(panel.worldWidth()*1.25f,panel.worldHeight()*1.1f);
            density(panel,dx,dy);
            int w=panel.pixelWidth(),h=panel.pixelHeight();
            panel.scaleTo(panel.worldWidth()*1.2f,panel.worldHeight()*1.2f);
            check(panel.pixelWidth()==w&&panel.pixelHeight()==h,"scaling preserves native viewport");
            dx=panel.pixelWidth()/panel.worldWidth();dy=panel.pixelHeight()/panel.worldHeight();
            panel.resize(panel.worldWidth()*.8f,panel.worldHeight()*.9f);
            density(panel,dx,dy);
        }
        var files=new FileManagerPanel();var notes=new NotepadPanel();
        int rows=files.visibleRows(),noteRows=notes.visibleRows(),tabs=notes.visibleTabs();
        files.resize(4.8f,2.7f);notes.resize(4.8f,2.7f);
        check(files.visibleRows()>rows&&notes.visibleRows()>noteRows&&notes.visibleTabs()>tabs,"native layout uses added space");
        var nativeApp=new TaskManagerPanel(null);var browser=new BrowserPanel();
        nativeApp.position=new Vec3(0,0,-3);nativeApp.orientation=new Quaternionf();
        browser.position=new Vec3(3.2,0,-3);browser.orientation=new Quaternionf();
        WindowGroups.join(nativeApp,browser);
        var frame=WindowGroups.frame(nativeApp);
        var corner=WindowGroups.world(frame.position,frame.orientation,frame.worldWidth()/2,frame.worldHeight()/2);
        new PanelResize(nativeApp,10,corner).move(corner.add(.5,.4,0));
        density(nativeApp,400,400);
        check(nativeApp.pixelHeight()>720&&browser.pixelHeight()>720,"group resizing updates both viewports");
        System.out.println("Native resizing: viewport density, Ctrl scaling, responsive layout, and mixed groups passed.");
    }
    private static void density(NativePanel panel,float x,float y) {
        check(Math.abs(panel.pixelWidth()-panel.worldWidth()*x)<=1&&Math.abs(panel.pixelHeight()-panel.worldHeight()*y)<=1,"native content keeps pixel density: "+panel.getClass().getSimpleName());
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
