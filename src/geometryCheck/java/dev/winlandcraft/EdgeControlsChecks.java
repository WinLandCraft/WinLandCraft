package dev.winlandcraft;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

final class EdgeControlsChecks {
    static void run() {
        var p=new BrowserPanel();p.position=new Vec3(20,80,30);p.orientation=new Quaternionf().rotateXYZ(.2f,.8f,.1f);
        float[][] edges={{0,.9f},{1.6f,0},{0,-.9f},{-1.6f,0}};
        for(int edge=0;edge<4;edge++) {
            Vec3 at=WindowGroups.world(p.position,p.orientation,edges[edge][0],edges[edge][1]);
            check(EdgeControls.near(p,at),"all edges activate");
            var ui=new EdgeControls(p,at,100);
            check(ui.edge==edge,"nearest edge on rotated panel");
            check(ui.action(20,20)==1&&ui.action(220,20)==2,"compact move/close");
            Vec3 close=pixel(ui,220,20);
            ui.expand(200);
            check(close.distanceTo(pixel(ui,ui.headerX()+220,ui.headerY()+20))<.00001,"expansion preserves close world position");
            check(ui.action(ui.headerX()+220,ui.headerY()+20)==2,"expanded close target");
            check(ui.action(ui.headerX()+20,ui.headerY()+20)==1,"expanded move target");
            check(ui.action(10,ui.headerY()+20)==1,"expanded title is draggable from its left edge on every side");
            int sliderY=edge==EdgeControls.TOP?48:84;
            check(ui.action(180,sliderY)==4,"curve target");
            check(ui.curveValue(-50)==0&&ui.curveValue(500)==1,"slider clamps");
            check(!ui.expired(600_000_200L)&&ui.expired(700_000_200L),"leave grace period");
            check(ui.dragTarget()==p&&!ui.canResize()&&!ui.canGroup(),"chrome delegates movement only");
            var normal=new org.joml.Vector3f(0,0,1).rotate(ui.orientation);
            var ray=new Vec3(normal.x,normal.y,normal.z);
            check(Math.abs(ui.intersect(close.add(ray.scale(2)),ray.scale(-1))-2)<.00001,"expanded rotated picking");
        }
        check(!EdgeControls.near(p,p.position),"center does not activate");
        var edgePoint=WindowGroups.world(p.position,p.orientation,0,.9f);
        var normalVector=new org.joml.Vector3f(0,0,1).rotate(p.orientation);
        var direction=new Vec3(normalVector.x,normalVector.y,normalVector.z);
        var sized=new EdgeControls(p,edgePoint,0);
        sized.view(edgePoint.add(direction.scale(4)),false);float nearWidth=sized.worldWidth();
        sized.view(edgePoint.add(direction.scale(16)),false);float farWidth=sized.worldWidth();
        check(Math.abs(farWidth/nearWidth-4)<.0001,"titlebar maintains angular size at distance");
        check(p.pixelWidth()==1280&&p.pixelHeight()==720,"distance sizing preserves app resolution");
        sized.view(edgePoint.add(direction.scale(64)),true);
        check(sized.worldWidth()==farWidth,"gesture locks titlebar scale");
        sized.view(edgePoint.add(direction.scale(64)),false);
        check(Math.abs(sized.worldWidth()/farWidth-4)<.0001,"scale catches up after gesture");
        Vec3 closeAt=pixel(sized,220,20);
        check(Math.abs(sized.intersect(closeAt.add(direction.scale(64)),direction.scale(-1))-64)<.0001,"distant enlarged close is pickable");
        int[] closePixel=sized.pixelAt(closeAt);
        check(sized.action(closePixel[0],closePixel[1])==2,"enlarged close uses unchanged logical hit area");
        var dwell=new EdgeControls.Dwell();
        check(!dwell.ready(p,0,0)&&!dwell.ready(p,0,499_000_000L)&&dwell.ready(p,0,500_000_000L),"half-second dwell threshold");
        check(!dwell.ready(p,1,510_000_000L),"changing sides restarts dwell");
        dwell.clear();check(!dwell.ready(p,1,2_000_000_000L),"leaving restarts dwell");
        var start=WindowGroups.world(p.position,p.orientation,0,.9f);
        var target=WindowGroups.world(p.position,p.orientation,1,.9f);
        var slow=new EdgeControls(p,start,0);var fast=new EdgeControls(p,start,0);
        check(slow.opacity()==0&&!slow.pickable(),"invisible controls cannot intercept clicks");
        slow.advance(20_000_000L,false);slow.follow(target,20_000_000L);
        double firstMove=pixel(slow,120,20).distanceTo(pixel(fast,120,20));
        check(firstMove>0&&firstMove<.5,"edge tracking eases rather than jumping");
        for(int i=2;i<=20;i++){slow.advance(i*20_000_000L,false);slow.follow(target,i*20_000_000L);}
        for(int i=1;i<=40;i++){fast.advance(i*10_000_000L,false);fast.follow(target,i*10_000_000L);}
        check(slow.position.distanceTo(fast.position)<.00001,"tracking independent of frame rate");
        check(slow.opacity()==1&&slow.pickable(),"fade reaches visible state");
        slow.dismiss(400_000_000L);slow.advance(450_000_000L,false);
        check(slow.opacity()>0&&slow.opacity()<1&&!slow.expired(450_000_000L),"fade-out retains partially visible handle");
        float faded=slow.opacity();slow.touch(450_000_000L);slow.advance(470_000_000L,false);
        check(slow.opacity()>faded&&slow.opacity()<1,"returning reverses fade without a pop");
        slow.dismiss(470_000_000L);
        for(int i=1;i<=6;i++)slow.advance(470_000_000L+i*50_000_000L,false);
        check(slow.expired(800_000_000L),"faded control is removed");
        check(!new AppWindows().tasks.floatingControls()&&!new AppWindows().launcher.floatingControls(),"shell panels excluded");
        var curve=GroupCurve.get(p);curve.apply(.7f);
        var at=curve.panelPoint(p,p.worldWidth()/2,0,0);
        var ui=new EdgeControls(p,at,0);ui.expand(1);
        check(ui.edge==EdgeControls.RIGHT,"curved edge selection");
        check(Double.isFinite(ui.position.x)&&Float.isFinite(ui.orientation.x),"curved tangent placement");
        System.out.println("Floating controls: all edges, rotated picking, fixed close on expansion, dwell, eased following, fade reversal, curve slider, grace period and shell exclusion passed.");
    }
    private static Vec3 pixel(WorldPanel p,float x,float y){return WindowGroups.world(p.position,p.orientation,(x/p.pixelWidth()-.5f)*p.worldWidth(),(.5f-y/p.pixelHeight())*p.worldHeight());}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
