package dev.winlandcraft;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

final class CurveChecks {
    static void run() {
        var a=new GroupChecks.App(0,0); var b=new GroupChecks.App(2,0); var c=new GroupChecks.App(4,0);
        WindowGroups.join(a,b); WindowGroups.join(b,c);
        GroupChecks.check(GroupCurve.eligible(a),"wide group curve available");
        var curve=GroupCurve.get(a);
        GroupChecks.check(curve.nearBottom(b,new Vec3(2,-.6,-3)),"curve trigger below border");
        GroupChecks.check(!curve.nearBottom(b,new Vec3(2,0,-3)),"curve trigger excludes content");
        curve.apply(1);
        GroupChecks.check(a.position.z>b.position.z && c.position.z>b.position.z,"outer panels toward viewer");
        seam(a,b); seam(b,c);
        smoothSurface(a,b,c);
        var normal=new org.joml.Vector3f(0,0,1).rotate(a.orientation);
        GroupChecks.check(normal.x>0,"left panel faces inward");
        var before=a.position;
        curve.apply(1); GroupChecks.near(0,before.distanceTo(a.position),"curve does not accumulate");
        curve.apply(0);
        GroupChecks.near(0,a.position.x,"flat layout restored"); GroupChecks.near(-3,c.position.z,"flat depth restored");
        GroupChecks.near(2,a.worldWidth(),"flat width restored");
        curve.facing=-1;curve.apply(.7f);
        GroupChecks.check(a.position.z<b.position.z,"reverse viewing side"); seam(a,b);
        var old=b.position;var oldRotation=new Quaternionf(b.orientation);
        b.position=b.position.add(2,3,4);b.orientation.rotateY(.2f);
        WindowGroups.moved(b,old,oldRotation);
        before=a.position;curve.apply(.7f);
        GroupChecks.near(0,before.distanceTo(a.position),"curve follows group movement");
        GroupChecks.check(curve.control().isOpen(),"curve control exists");
        WindowGroups.detach(b);
        GroupChecks.check(!a.grouped()&&!c.grouped()&&a.curve!=null&&c.curve!=null,"single windows retain curve after splitting");
        a=new GroupChecks.App(0,0);b=new GroupChecks.App(2,0);WindowGroups.join(a,b);
        curve=GroupCurve.get(a);curve.apply(.5f);
        Vec3 hit=curve.panelPoint(b,b.worldWidth()/2+.01f,WindowGroups.top(b)+.01f,0);
        var resize=new PanelResize(b,10,hit);
        GroupChecks.check(b.curve!=null,"starting resize preserves curve");
        resize.move(hit.add(1,.2,0));
        GroupChecks.near(.5,b.curve.amount,"resize preserves curvature");seam(a,b);
        b.curve.apply(0);GroupChecks.check(Double.isFinite(a.position.x),"flatten after resizing");
        var small=new GroupChecks.App(0,0); small.resize(.8f,1);
        var other=new GroupChecks.App(1,0);other.resize(.8f,1);WindowGroups.join(small,other);
        GroupChecks.check(GroupCurve.eligible(small),"narrow groups allow curve");
        var solo=new GroupChecks.App(0,0);
        GroupChecks.check(GroupCurve.eligible(solo),"single window allows curve");
        var soloCurve=GroupCurve.get(solo);soloCurve.apply(.5f);
        var soloHit=soloCurve.panelPoint(solo,solo.worldWidth()/2+.01f,WindowGroups.top(solo)+.01f,0);
        new PanelResize(solo,10,soloHit).move(soloHit.add(.3,.2,0));
        GroupChecks.check(solo.curve!=null,"single resize retains curve");GroupChecks.near(.5,solo.curve.amount,"single curve amount retained");
        try {
            ModSettings.removeSizingLimitations=true;
            solo.curve.flatten();solo.resize(30,20);GroupChecks.near(30,solo.worldWidth(),"unlimited large width");GroupChecks.near(20,solo.worldHeight(),"unlimited large height");
            solo.resize(.02f,.01f);GroupChecks.near(.02,solo.worldWidth(),"unlimited small width");GroupChecks.near(.01,solo.worldHeight(),"unlimited small height");
            var browser=new BrowserPanel();browser.resize(.0001f,.0001f);GroupChecks.check(browser.pixelWidth()>260&&browser.pixelHeight()>0,"tiny browser retains valid viewport");
            var left=new GroupChecks.App(0,0);var right=new GroupChecks.App(2,0);WindowGroups.join(left,right);var bounds=WindowGroups.frame(left);
            var edge=WindowGroups.world(bounds.position,bounds.orientation,bounds.worldWidth()/2,bounds.worldHeight()/2);
            new PanelResize(left,10,edge).move(edge.add(30,20,0));GroupChecks.check(left.worldWidth()>6.4&&right.worldHeight()>3.6,"group resize removes limits");
        } finally {ModSettings.removeSizingLimitations=false;}
        solo.resize(30,20);GroupChecks.near(6.4,solo.worldWidth(),"default width cap restored");GroupChecks.near(3.6,solo.worldHeight(),"default height cap restored");
        System.out.println("Group curve: threshold, bottom trigger, seam continuity, inward angles, flat reset, movement, resize and split cleanup passed.");
    }
    private static void seam(WorldPanel a,WorldPanel b) {
        var right=a.curve.panelPoint(a,a.worldWidth()/2,0,0);
        var left=b.curve.panelPoint(b,-b.worldWidth()/2,0,0);
        GroupChecks.near(0,right.distanceTo(left),"curved seam continuity");
    }
    private static void smoothSurface(WorldPanel a,WorldPanel b,WorldPanel c) {
        var curve=a.curve;
        var left=curve.panelPoint(b,-b.worldWidth()/2,0,0);
        var right=curve.panelPoint(b,b.worldWidth()/2,0,0);
        var middle=curve.panelPoint(b,0,0,0);
        GroupChecks.check(middle.distanceTo(left.add(right).scale(.5))>.05,"panel interior bends rather than forming a chord");
        GroupChecks.near(2,b.worldWidth(),"curving preserves viewport width");
        for(int facing:new int[]{1,-1}) for(float strength:new float[]{0,.001f,.4f,1}) {
            curve.facing=facing;curve.apply(strength);
            for(var p:new WorldPanel[]{a,b,c}) for(float x:new float[]{-.85f,-.2f,.3f,.85f}) {
                var point=curve.panelPoint(p,x,.123f,0);
                var flat=curve.local(p,point);
                GroupChecks.near(x,flat.x,"surface inverse horizontal coordinate");
                GroupChecks.near(.123,flat.y,"surface inverse vertical coordinate");
                var start=curve.panelPoint(p,x,.123f,1);
                var ray=point.subtract(start).normalize();
                GroupChecks.near(1,p.intersect(start,ray),"curved ray intersection");
                int[] pixel=p.pixelAt(point);
                int expected=(int)((x/p.worldWidth()+.5)*p.pixelWidth());
                GroupChecks.check(Math.abs(pixel[0]-expected)<=1,"curved browser pixel targeting");
            }
        }
        curve.facing=1;curve.apply(1);
        var sink=new Capture();
        var vertices=new CurvedVertices(sink,b,new org.joml.Matrix4f(),new org.joml.Matrix4f(),Vec3.ZERO);
        vertices.addVertex(b.pixelWidth()*.25f,b.pixelHeight()*.5f,0).setUv(.25f,.5f);
        var expected=curve.panelPoint(b,-b.worldWidth()*.25f,0,0);
        GroupChecks.near(0,expected.distanceTo(sink.point),"renderer vertices follow surface");
        GroupChecks.near(.25,sink.u,"texture UV preserved");
    }
    private static final class Capture implements com.mojang.blaze3d.vertex.VertexConsumer {
        Vec3 point;float u;
        public Capture addVertex(float x,float y,float z){point=new Vec3(x,y,z);return this;}
        public Capture setColor(int r,int g,int b,int a){return this;}
        public Capture setUv(float u,float v){this.u=u;return this;}
        public Capture setUv1(int u,int v){return this;}
        public Capture setUv2(int u,int v){return this;}
        public Capture setNormal(float x,float y,float z){return this;}
    }
}
