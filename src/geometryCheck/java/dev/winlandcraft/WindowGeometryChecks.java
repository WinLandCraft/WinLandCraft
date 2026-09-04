package dev.winlandcraft;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Known geometric cases, run by Gradle's verifyWindowGeometry task. */
public final class WindowGeometryChecks {
    public static void main(String[] args) {
        near(4.5, ModSettings.interactionRange(4.5), "default vanilla interaction range");
        ModSettings.extendInteractionRange = true;
        near(16, ModSettings.interactionRange(4.5), "extended default range");
        ModSettings.interactionRange = 200;
        near(200, ModSettings.interactionRange(4.5), "configured interaction range");
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 4097})
            if (ModSettings.validInteractionRange(invalid)) throw new AssertionError("invalid interaction range accepted");
        ModSettings.extendInteractionRange = false;
        ModSettings.interactionRange = 16;
        TestPanel panel = new TestPanel();
        panel.position = new Vec3(0, 0, -3);
        panel.orientation = new Quaternionf();
        pixel(panel, new Vec3(0, 0, -3), 200, 24, "center pixel");
        pixel(panel, new Vec3(-1.19, 0.14, -3), 1, 0, "top-left pixel");
        pixel(panel, new Vec3(1.19, -0.14, -3), 398, 47, "bottom-right pixel");
        pixel(panel, new Vec3(2.4, 0, -3), 600, 24, "captured drag outside panel");
        near(3, panel.planeDistance(new Vec3(2.4, 0, 0), new Vec3(0, 0, -1)), "captured ray outside bounds");
        near(3, panel.intersect(Vec3.ZERO, new Vec3(0, 0, -1)), "front hit");
        near(3, panel.intersect(new Vec3(0, 0, -6), new Vec3(0, 0, 1)), "back hit");
        miss(panel.intersect(new Vec3(1.3, 0, 0), new Vec3(0, 0, -1)), "outside width");
        miss(panel.intersect(new Vec3(0, 0.2, 0), new Vec3(0, 0, -1)), "outside height");
        miss(panel.intersect(Vec3.ZERO, new Vec3(1, 0, 0)), "parallel ray");
        miss(panel.intersect(Vec3.ZERO, new Vec3(0, 0, 1)), "behind camera");
        panel.orientation = new Quaternionf().rotateY((float) (Math.PI / 2));
        near(3, panel.intersect(new Vec3(3, 0, -3), new Vec3(-1, 0, 0)), "rotated hit");
        panel.position = new Vec3(20000000, 70, 20000000);
        panel.orientation = new Quaternionf();
        near(3, panel.intersect(new Vec3(20000000.5, 70, 20000003), new Vec3(0, 0, -1)), "far-world hit");
        panel.close();
        miss(panel.intersect(Vec3.ZERO, new Vec3(0, 0, -1)), "closed panel");
        System.out.println("Window geometry: 14 checks passed.");
        startMenu();
        rotationLock();
        resizing();
        scaling();
        NativeResizeChecks.run();
        FilePlacementChecks.run();
        titlebars();
        browserCloseThread();
        laserPointer();
        GroupChecks.run();
        CurveChecks.run();
    }

    private static void laserPointer() {
        ModSettings.panelPointerMode=ModSettings.POINTER_GAZE;
        if(ModSettings.laserPanelPointer())throw new AssertionError("gaze pointer must be the default");
        if(!WindowControls.panelPointerAvailable(false,false)||!WindowControls.panelPointerAvailable(false,true))
            throw new AssertionError("gaze mode must route panel input without a laser");
        ModSettings.panelPointerMode=ModSettings.POINTER_LASER;
        if(!ModSettings.laserPanelPointer())throw new AssertionError("laser pointer mode selection");
        if(WindowControls.panelPointerAvailable(true,false)||!WindowControls.panelPointerAvailable(true,true))
            throw new AssertionError("laser mode must route panel input only through an active laser");
        ModSettings.panelPointerMode=ModSettings.POINTER_GAZE;
        if(LaserPointer.normalizeColor(-1)!=4||LaserPointer.normalizeColor(5)!=0)
            throw new AssertionError("laser color wrapping");
        near(-1.5,ModSettings.DEFAULT_LASER_BEAM_X,"laser calibrated beam horizontal");
        near(0,ModSettings.DEFAULT_LASER_BEAM_Y,"laser calibrated beam vertical");
        near(3.45,ModSettings.DEFAULT_LASER_BEAM_INSET,"laser calibrated beam inset");
        float near=LaserPointer.beamWidth(.34),far=LaserPointer.beamWidth(16);
        if(!(near>0&&far>near&&LaserPointer.beamWidth(1000)<=.04f))
            throw new AssertionError("laser beam distance scaling");
        if(!(LaserPointer.targetRadius(1)>0&&LaserPointer.targetRadius(1000)<=.11f))
            throw new AssertionError("laser target distance scaling");
        near(0,LaserPointer.spinDegrees(0),"laser spin starts home");
        near(0,LaserPointer.spinDegrees(1),"laser spin ends home");
        float last=0;
        for(int step=1;step<100;step++) {
            float next=LaserPointer.spinDegrees(step/100f);
            if(next<last)throw new AssertionError("laser spin easing reversed");
            last=next;
        }
        near(0,LaserPointer.bounceDegrees(0,12),"laser bounce starts home");
        near(0,LaserPointer.bounceDegrees(1,12),"laser bounce ends home");
        if(LaserPointer.bounceDegrees(.38f,12)<11.9f||LaserPointer.bounceDegrees(.76f,12)>0)
            throw new AssertionError("laser bounce keyframes");
        System.out.println("Laser pointer: calibrated origin, distance scaling, spin easing, and power bounce passed.");
    }

    private static void scaling() {
        for (int sx : new int[]{-1,1}) for (int sy : new int[]{-1,1}) {
            var app = new BrowserPanel();
            app.position = new Vec3(0,0,-3); app.orientation = new Quaternionf().rotateXYZ(.2f,.4f,.1f);
            float w=app.worldWidth(), h=app.worldHeight(), outer=h+app.titlebarHeight()*h/app.pixelHeight();
            Vec3 anchor=localPoint(app,-sx*w/2,sy>0?-h/2:outer-h/2);
            Vec3 start=localPoint(app,sx*w/2,sy<0?-h/2:outer-h/2);
            var resize=new PanelResize(app,(sx<0?1:2)|(sy<0?4:8),start,true);
            var delta=new org.joml.Vector3f(sx*w*.25f,sy*outer*.25f,0).rotate(app.orientation);
            resize.move(start.add(delta.x,delta.y,delta.z));
            near(w*1.25,app.worldWidth(),"scale physical width");
            near(h*1.25,app.worldHeight(),"scale preserves aspect");
            near(1280,app.pixelWidth(),"scale preserves browser width");
            near(720,app.pixelHeight(),"scale preserves browser height");
            Vec3 after=localPoint(app,-sx*app.worldWidth()/2,sy>0?-app.worldHeight()/2:WindowGroups.top(app));
            near(0,anchor.distanceTo(after),"scale opposite outer corner fixed");
            app.resize(app.worldWidth()*1.1f,app.worldHeight());
            near(1408,app.pixelWidth(),"normal resize still changes layout after scaling");
        }
        var a=new BrowserPanel();a.position=new Vec3(0,0,-3);a.orientation=new Quaternionf();
        var b=new GroupChecks.App(3,0);WindowGroups.join(a,b);
        float aw=a.worldWidth(),ah=a.worldHeight(),bw=b.worldWidth(),bh=b.worldHeight();
        var curve=GroupCurve.get(a);curve.apply(.6f);
        GroupCurve.flatten(a);
        var frame=WindowGroups.frame(a);float fw=frame.worldWidth(),fh=frame.worldHeight();
        Vec3 start=frame.position.add(fw/2,fh/2,0);
        GroupCurve.restore(a,curve);
        var resize=new PanelResize(a,10,start,true);
        resize.move(start.add(fw*.2,fh*.2,0));
        near(aw*1.2,a.worldWidth(),"curved group browser scale");near(ah*1.2,a.worldHeight(),"curved group browser aspect");
        near(bw*1.2,b.worldWidth(),"curved group native scale");near(bh*1.2,b.worldHeight(),"curved group native aspect");
        near(1280,a.pixelWidth(),"curved group keeps browser layout");near(.6,a.curve.amount,"scale preserves curve");
        GroupCurve.flatten(a);
        near((a.worldWidth()+b.worldWidth())/2,b.position.x-a.position.x,"scaled group seam");
        System.out.println("Scaling: rotated anchors, aspect ratios, unchanged browser resolution, subsequent resize, and curved mixed groups passed.");
    }

    private static void resizing() {
        var distancePanel=new BrowserPanel();distancePanel.position=new Vec3(0,0,0);distancePanel.orientation=new Quaternionf();
        float nearHandle=distancePanel.resizeHandleSize(new Vec3(0,0,3)),farHandle=distancePanel.resizeHandleSize(new Vec3(0,0,50));
        if(farHandle<=nearHandle||farHandle>.45f)throw new AssertionError("resize handle distance scaling");
        for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) {
            TestPanel panel = new TestPanel();
            panel.position = new Vec3(20000000, 80, 20000000);
            panel.orientation = new Quaternionf().rotateXYZ(0.3f, 0.9f, -0.4f);
            Vec3 hit = localPoint(panel, sx * 1.21f, sy * 0.154f);
            int corner = (sx < 0 ? 1 : 2) | (sy < 0 ? 4 : 8);
            if (panel.resizeCorner(hit) != corner) throw new AssertionError("exterior corner detection");
            if (panel.resizeCorner(localPoint(panel, sx * 1.199f, sy * 0.143f)) != 0)
                throw new AssertionError("resize must not steal interior controls");
            var normal = new org.joml.Vector3f(0, 0, 1).rotate(panel.orientation);
            Vec3 ray = new Vec3(normal.x, normal.y, normal.z);
            near(3, panel.pointerDistance(hit.add(ray.scale(3)), ray.scale(-1)), "exterior handle raycast");
            Vec3 fixed = localPoint(panel, -sx * 1.2f, -sy * 0.144f);
            var resize = new PanelResize(panel, corner, hit);
            Vec3 destination = localPoint(panel, sx * 1.81f, sy * 0.554f);
            resize.move(destination);
            near(3, panel.worldWidth(), "resize width"); near(0.688, panel.worldHeight(), "resize height");
            near(0, fixed.distanceTo(localPoint(panel, -sx * panel.worldWidth() / 2, -sy * panel.worldHeight() / 2)), "opposite corner fixed");
            pixel(panel, panel.position, 200, 24, "resized center pixel mapping");
            resize.move(destination);
            near(3, panel.worldWidth(), "repeated move does not accumulate");
            resize.move(hit.add(ray.cross(new Vec3(0, 1, 0)).scale(1000)));
            if (panel.worldWidth() < 0.6f || panel.worldWidth() > 6.4f || panel.worldHeight() < 0.15f || panel.worldHeight() > 3.6f)
                throw new AssertionError("resize bounds");
        }
        var browser = new BrowserPanel(); browser.resize(100, 100);
        if (browser.pixelWidth() != 2560 || browser.pixelHeight() != 1440) throw new AssertionError("maximum viewport");
        browser.resize(0, 0);
        if (browser.pixelWidth() != 520 || browser.pixelHeight() != 320) throw new AssertionError("sidebar minimum viewport");
        var app = new BrowserPanel("https://example.com"); app.resize(0, 0);
        if (app.pixelWidth() != 320 || app.pixelHeight() != 240) throw new AssertionError("webapp minimum viewport");
        var apps = new AppWindows(); apps.tasks.position = Vec3.ZERO; apps.tasks.orientation = new Quaternionf();
        apps.toggleStartMenu(); apps.tasks.resize(3.2f, 0.5f); apps.syncAttachments();
        near(-0.3, apps.launcher.position.x, "fixed taskbar keeps menu left alignment");
        near(0.594, apps.launcher.position.y, "fixed taskbar keeps menu top alignment");
        if (apps.launcher.canResize()) throw new AssertionError("attached menu keeps taskbar attachment");
        System.out.println("Resizing: all four rotated corners, content priority, fixed anchor, pixel mapping, limits, and menu attachment passed.");
    }
    private static void titlebars() {
        var apps = new AppWindows();
        apps.tasks.resize(5, 2); apps.launcher.resize(4, 2);
        near(2.4, apps.tasks.worldWidth(), "taskbar fixed width"); near(0.288, apps.tasks.worldHeight(), "taskbar fixed height");
        near(1.8, apps.launcher.worldWidth(), "launcher fixed width"); near(0.9, apps.launcher.worldHeight(), "launcher fixed height");
        if (apps.tasks.canResize() || apps.launcher.canResize()) throw new AssertionError("shell panels cannot resize");
        if (apps.tasks.titlebarHeight() != 0 || apps.launcher.titlebarHeight() != 0) throw new AssertionError("shell panels have no titlebar");
        var panel = new BrowserPanel("https://example.com"); panel.setAppName("Example app");
        panel.position = new Vec3(20, 80, 30); panel.orientation = new Quaternionf().rotateXYZ(0.2f, 0.8f, 0.1f);
        if (!panel.windowTitle().equals("Example app")) throw new AssertionError("app title fallback");
        if(panel.titlebarHeight()!=0||!panel.floatingControls())throw new AssertionError("app content has no fixed titlebar");
        if(panel.titlebarAction(100,-16)!=0||panel.titlebarAction(1250,-16)!=0)throw new AssertionError("old titlebar hit targets removed");
        if(panel.resizeCorner(localPoint(panel,1.61f,.91f))!=10)throw new AssertionError("resize corners follow content bounds");
        if(panel.pixelWidth()!=1280||panel.pixelHeight()!=720)throw new AssertionError("content resolution preserved");
        EdgeControlsChecks.run();
        near(2.75, WindowControls.moveDistance(2.5, 1), "scroll up moves farther");
        near(2.25, WindowControls.moveDistance(2.5, -1), "scroll down moves closer");
        near(32.25, WindowControls.moveDistance(32, 1), "moving beyond old distance cap");
        near(4096, WindowControls.moveDistance(4096, 1), "extended distance cap");
        near(951.6, WindowControls.moveDistance(1000, -1), "distant windows move closer without snapping");
        near(0.5, WindowControls.moveDistance(0.5, -1), "near distance clamp");
        System.out.println("Titlebars: fixed shell panels, rotated picking, close/drag regions, preserved content, resize corners, and scroll direction passed.");
    }
    private static Vec3 localPoint(WorldPanel panel, float x, float y) {
        var offset = new org.joml.Vector3f(x, y, 0).rotate(panel.orientation);
        return panel.position.add(offset.x, offset.y, offset.z);
    }

    private static void browserCloseThread() {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        var browser = new BrowserPanel();
        browser.position = new Vec3(0, 0, -3);
        browser.orientation = new Quaternionf();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread network = new Thread(() -> {
            try { browser.close(); } catch (Throwable error) { failure.set(error); }
        }, "disconnect-regression-network-thread");
        network.start();
        try { network.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
        if (failure.get() != null) throw new AssertionError("off-thread close failed", failure.get());
        if (!browser.isOpen()) throw new AssertionError("network thread changed browser state");
        com.mojang.blaze3d.systems.RenderSystem.replayQueue();
        if (browser.isOpen()) throw new AssertionError("render thread did not close browser");
        browser.close();
        System.out.println("Browser cleanup: off-thread deferral, render-thread execution, and repeated close passed (without native CEF).");
    }

    private static void rotationLock() {
        if (ModSettings.freePanelRotation) throw new AssertionError("rotation must default off");
        for (float yaw : new float[]{-3f, -1f, 0f, 1.5f, 3f}) {
            for (float pitch : new float[]{-1.5707964f, -0.8f, 0f, 0.8f, 1.5707964f}) {
                var camera = new Quaternionf().rotationY(yaw).rotateX(pitch);
                var upright = PanelRotation.allowed(camera);
                var up = new org.joml.Vector3f(0, 1, 0).rotate(upright);
                near(0, up.x, "upright x"); near(1, up.y, "upright y"); near(0, up.z, "upright z");
                var right = new org.joml.Vector3f(1, 0, 0).rotate(upright);
                near(Math.cos(yaw), right.x, "heading at extreme pitch");
                near(-Math.sin(yaw), right.z, "heading at extreme pitch z");
            }
        }
        var tilted = new Quaternionf().rotateXYZ(0.8f, 0.5f, 1.2f);
        var up = new org.joml.Vector3f(0, 1, 0).rotate(PanelRotation.allowed(tilted));
        near(1, up.y, "existing tilted panel levels");
        ModSettings.freePanelRotation = true;
        if (!PanelRotation.allowed(tilted).equals(tilted)) throw new AssertionError("free rotation preserves tilt");
        ModSettings.freePanelRotation = false;
        System.out.println("Panel rotation: default lock, 25 heading/pitch combinations, existing tilt, and opt-in freedom passed.");
    }

    private static void startMenu() {
        var apps = new AppWindows();
        apps.tasks.position = new Vec3(0, 0, -3);
        apps.tasks.orientation = new Quaternionf();
        apps.toggleStartMenu();
        near(-0.3, apps.launcher.position.x, "start menu left alignment");
        near(0.594, apps.launcher.position.y, "start menu bottom meets taskbar top");
        near(-3, apps.launcher.position.z, "start menu coplanar");
        if (apps.launcher.dragTarget() != apps.tasks) throw new AssertionError("attached drag target");
        apps.tasks.orientation = new Quaternionf().rotateXYZ(0.5f, 1.2f, -0.3f);
        apps.tasks.position = new Vec3(20000000, 70, 20000000);
        apps.syncAttachments();
        var local = new org.joml.Vector3f(apps.launcher.position.subtract(apps.tasks.position).toVector3f())
                .rotate(new Quaternionf(apps.tasks.orientation).conjugate());
        near(-0.3, local.x, "rotated menu left alignment");
        near(0.594, local.y, "rotated menu top alignment");
        near(0, local.z, "rotated menu plane");
        if (!apps.tasks.orientation.equals(apps.launcher.orientation)) throw new AssertionError("menu orientation");
        apps.toggleStartMenu();
        if (apps.launcher.isOpen() || apps.launcher.isAttached()) throw new AssertionError("menu toggle closes");
        apps.toggleStartMenu();
        apps.tasks.close();
        if (apps.launcher.isOpen()) throw new AssertionError("taskbar closes attached menu");
        apps.launcher.position = new Vec3(1, 1, 1);
        apps.launcher.orientation = new Quaternionf();
        apps.tasks.close();
        apps.syncAttachments();
        if (!apps.launcher.isOpen() || apps.launcher.dragTarget() != apps.launcher)
            throw new AssertionError("independent launcher remains independent");
        System.out.println("Start menu: alignment, rotated movement, shared dragging, toggle, and close behavior passed.");
    }

    private static void near(double expected, double actual, String name) {
        if (Math.abs(expected - actual) > 0.0001 || !Double.isFinite(actual)) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }

    private static void pixel(WorldPanel panel, Vec3 point, int x, int y, String name) {
        int[] actual = panel.pixelAt(point);
        if (actual[0] != x || actual[1] != y) throw new AssertionError(name + ": got " + actual[0] + "," + actual[1]);
    }

    private static void miss(double actual, String name) {
        if (actual != Double.POSITIVE_INFINITY) {
            throw new AssertionError(name + ": expected miss, got " + actual);
        }
    }

    private static final class TestPanel extends WorldPanel {
        private TestPanel() {
            super(2.4f, 0.288f);
        }
    }
}
