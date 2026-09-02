package dev.winlandcraft;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

final class EdgeControlsChecks {
    private static void grouping() {
        for(int edge=0;edge<4;edge++) {
            var a=new BrowserPanel();var b=new BrowserPanel();
            a.position=new Vec3(0,80,0);a.orientation=new Quaternionf().rotateXYZ(.2f,.7f,.1f);
            b.orientation=new Quaternionf(a.orientation);
            float x=edge==EdgeControls.LEFT?-3.3f:edge==EdgeControls.RIGHT?3.3f:0;
            float y=edge==EdgeControls.TOP?1.9f:edge==EdgeControls.BOTTOM?-1.9f:0;
            b.position=WindowGroups.world(a.position,a.orientation,x,y);
            var at=WindowGroups.world(a.position,a.orientation,Math.signum(x)*1.6f,Math.signum(y)*.9f);
            var ui=new EdgeControls(a,at,0);ui.expand(1);ui.updateGroup(java.util.List.of(a,b));
            int row=edge==EdgeControls.TOP?4:42;
            check(ui.action(150,row+14)==6,"group action available on every edge");
            ui.pointerMoved(150,row+14);check(ui.previewsGroup(),"hover previews joining windows");
            ui.pointerMoved(20,ui.headerY()+20);check(!ui.previewsGroup(),"title hover does not preview grouping");
            b.position=WindowGroups.world(a.position,a.orientation,x*10,y*10);ui.updateGroup(java.util.List.of(a,b));
            check(ui.action(150,row+14)==0,"distant candidate cannot be grouped");
            b.position=WindowGroups.world(a.position,a.orientation,x,y);ui.updateGroup(java.util.List.of(a,b));ui.joinGroup();
            check(a.grouped()&&b.grouped(),"pill joins the selected pair");
            ui.updateGroup(java.util.List.of(a,b));check(ui.action(150,row+14)==0,"already grouped pair is not suggested again");
        }
        check(EdgeControls.previewOpacity(0)==0&&Math.abs(EdgeControls.previewOpacity(1.2)-.85)<.0001
                &&EdgeControls.previewOpacity(2.4)<.0001,"outline fades in and out over 2.4 seconds");
    }
    static void run() {
        grouping();
        var p=new BrowserPanel();p.position=new Vec3(20,80,30);p.orientation=new Quaternionf().rotateXYZ(.2f,.8f,.1f);
        float[][] edges={{0,.9f},{1.6f,0},{0,-.9f},{-1.6f,0}};
        for(int edge=0;edge<4;edge++) {
            Vec3 at=WindowGroups.world(p.position,p.orientation,edges[edge][0],edges[edge][1]);
            check(EdgeControls.near(p,at),"all edges activate");
            var ui=new EdgeControls(p,at,100);
            check(ui.edge==edge,"nearest edge on rotated panel");
            check(ui.action(270,20)!=7,"start stream hidden while compact");
            check(ui.action(20,20)==1&&ui.action(220,20)==2,"compact move/close");
            Vec3 close=pixel(ui,220,20);
            ui.expand(200);
            check(ui.action(270,(edge==EdgeControls.TOP?4:42)+14)==7,"start stream available on every edge");
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
        p.broadcastSession=java.util.UUID.randomUUID();
        for(int edge=0;edge<4;edge++) {
            Vec3 at=WindowGroups.world(p.position,p.orientation,edges[edge][0],edges[edge][1]);
            var ui=new EdgeControls(p,at,0);Vec3 close=pixel(ui,220,20);ui.expand(1);
            check(ui.pixelHeight()==352,"stream controls expand the pill");
            check(ui.action(270,(edge==EdgeControls.TOP?244:42)+14)!=7,"active stream hides start action");
            check(close.distanceTo(pixel(ui,ui.headerX()+220,ui.headerY()+20))<.00001,"stream expansion keeps close anchored");
            check(ui.action(306,ui.streamY()+152)==5,"codec selector routes to stream controls");
            check(ui.action(306,ui.streamY()+32)==5,"quality button routes to stream controls");
            check(ui.action(30,ui.streamY()+218)==5,"stop stream routes to stream controls");
            check(ui.action(180,edge==EdgeControls.TOP?288:84)==4,"stream controls do not overlap curve slider");
        }
        p.broadcastSession=null;
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
