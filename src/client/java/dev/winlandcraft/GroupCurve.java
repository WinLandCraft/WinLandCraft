package dev.winlandcraft;

import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Shared cylindrical surface, with flat coordinates retained for layout and browser input. */
final class GroupCurve {
    record Layout(float x,float y,float width,float height) {}
    final Map<WorldPanel,Layout> layout=new LinkedHashMap<>();
    Vec3 origin;
    Quaternionf rotation;
    final float width,height;
    float amount;
    int facing=1;

    GroupCurve(WorldPanel root) {
        var frame=WindowGroups.frame(root);
        origin=frame.position; rotation=new Quaternionf(frame.orientation);
        width=frame.worldWidth(); height=frame.worldHeight();
        for(var p:WindowGroups.members(root)) {
            var d=WindowGroups.local(frame,p.position);
            layout.put(p,new Layout(d.x,d.y,p.worldWidth(),p.worldHeight()));
        }
    }
    static boolean eligible(WorldPanel root) {
        return root.isOpen() && root.canResize() && root.hasWindowControls();
    }
    static GroupCurve get(WorldPanel root) {
        if(root.curve!=null) return root.curve;
        var curve=new GroupCurve(root);
        curve.layout.keySet().forEach(p -> p.curve=curve);
        return curve;
    }
    Vec3 point(float x,float y,float z) {
        var v=new org.joml.Vector3f(x,y,z).rotate(rotation);
        return origin.add(v.x,v.y,v.z);
    }
    float radius() { return width/(amount*(float)Math.toRadians(110)); }
    private float curvedX(float x) { return amount<.0001f?x:radius()*(float)Math.sin(x/radius()); }
    private float curvedZ(float x) { return amount<.0001f?0:facing*radius()*(1-(float)Math.cos(x/radius())); }
    void apply(float value) {
        amount=Math.clamp(value,0,1);
        for(var entry:layout.entrySet()) {
            var p=entry.getKey(); var l=entry.getValue();
            if(!p.isOpen()) continue;
            p.position=surface(l.x,l.y,0);
            p.orientation=new Quaternionf(rotation).rotateY(amount<.0001f?0:-facing*l.x/radius());
        }
    }
    Vec3 surface(float x,float y,float depth) {
        float angle=amount<.0001f?0:x/radius();
        return point(curvedX(x)-facing*(float)Math.sin(angle)*depth,y,curvedZ(x)+(float)Math.cos(angle)*depth);
    }
    Vec3 panelPoint(WorldPanel p,float x,float y,float depth) {
        var l=layout.get(p);return surface(l.x+x,l.y+y,depth);
    }
    org.joml.Vector3f unbend(Vec3 point) {
        var d=point.subtract(origin);
        var v=new org.joml.Vector3f((float)d.x,(float)d.y,(float)d.z).rotate(new Quaternionf(rotation).conjugate());
        if(amount>=.0001f) v.x=radius()*(float)Math.atan2(v.x,radius()-facing*v.z);
        return v;
    }
    org.joml.Vector3f local(WorldPanel p,Vec3 point) {
        var v=unbend(point);var l=layout.get(p);return v.sub(l.x,l.y,0);
    }
    double intersectSurface(Vec3 start,Vec3 ray) {
        var d=start.subtract(origin);
        var inverse=new Quaternionf(rotation).conjugate();
        var o=new org.joml.Vector3f((float)d.x,(float)d.y,(float)d.z).rotate(inverse);
        var v=new org.joml.Vector3f((float)ray.x,(float)ray.y,(float)ray.z).rotate(inverse);
        if(amount<.0001f) { double t=-o.z/v.z;return Double.isFinite(t)&&t>0?t:Double.POSITIVE_INFINITY; }
        double r=radius(), z=o.z-facing*r;
        double a=v.x*v.x+v.z*v.z,b=2*(o.x*v.x+z*v.z),c=o.x*o.x+z*z-r*r;
        double discriminant=b*b-4*a*c;
        if(a<1e-12||discriminant<0) return Double.POSITIVE_INFINITY;
        double root=Math.sqrt(discriminant);
        for(double t:new double[]{(-b-root)/(2*a),(-b+root)/(2*a)}) {
            if(t<=0) continue;
            double arc=r*Math.atan2(o.x+t*v.x,r-facing*(o.z+t*v.z));
            if(Math.abs(arc)<=width/2+.5) return t;
        }
        return Double.POSITIVE_INFINITY;
    }
    void flatten() {
        for(var entry:layout.entrySet()) {
            var p=entry.getKey(); var l=entry.getValue();
            p.curve=null;
            if(!p.isOpen()) continue;
            p.position=point(l.x,l.y,0); p.orientation=new Quaternionf(rotation); p.resize(l.width,l.height);
        }
    }
    static GroupCurve flatten(WorldPanel p) {
        var curve=p.curve;
        if(curve!=null) curve.flatten();
        return curve;
    }
    static void restore(WorldPanel p,GroupCurve previous) {
        if(previous==null || !eligible(p)) return;
        var curve=get(p); curve.facing=previous.facing; curve.apply(previous.amount);
    }
    void moved(Vec3 oldPosition,Vec3 newPosition,Quaternionf turn) {
        var d=origin.subtract(oldPosition);
        var v=new org.joml.Vector3f((float)d.x,(float)d.y,(float)d.z).rotate(turn);
        origin=newPosition.add(v.x,v.y,v.z); rotation=new Quaternionf(turn).mul(rotation);
    }
    int corner(WorldPanel p,Vec3 point) { return corner(p,point,null); }
    int corner(WorldPanel p,Vec3 point,Vec3 observer) {
        int corner=p.resizeCorner(point,observer);
        if(corner==0) return 0;
        var l=layout.get(p);
        float x=l.x+((corner&1)!=0?-l.width/2:l.width/2);
        float y=l.y+((corner&4)!=0?-l.height/2:l.height/2+p.titlebarHeight()*l.height/p.pixelHeight());
        return Math.abs(Math.abs(x)-width/2)<.02 && Math.abs(Math.abs(y)-height/2)<.02?corner:0;
    }
    WorldPanel control() {
        var control=new WorldPanel(.9f,.12f) {
            @Override public int pixelWidth(){return 360;}
            @Override public int pixelHeight(){return 48;}
            @Override public boolean canResize(){return false;}
        };
        control.position=point(0,-height/2-.13f,0);
        control.orientation=new Quaternionf(rotation);
        control.level=layout.keySet().iterator().next().level;
        return control;
    }
    boolean nearBottom(WorldPanel p,Vec3 point) {
        var at=WindowGroups.local(p,point);
        var l=layout.get(p);
        return Math.abs(l.y-l.height/2+height/2)<.03 && Math.abs(at.x)<p.worldWidth()/2+.12
                && Math.abs(at.y+p.worldHeight()/2)<.23;
    }
}
