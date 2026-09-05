package dev.winlandcraft;

import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Edge graph: removing a bridge naturally leaves independent connected components. */
final class WindowGroups {
    static List<WorldPanel> members(WorldPanel root) { return List.copyOf(component(root)); }
    private static Set<WorldPanel> component(WorldPanel root) {
        var found = new LinkedHashSet<WorldPanel>(); found.add(root);
        var queue = new ArrayDeque<WorldPanel>(); queue.add(root);
        while (!queue.isEmpty()) {
            var p = queue.remove();
            for (var n : p.glued) if (n.isOpen() && found.add(n)) queue.add(n);
        }
        return found;
    }
    static void detach(WorldPanel panel) {
        var previous=GroupCurve.flatten(panel);
        var neighbors=List.copyOf(panel.glued);
        for (var other : neighbors) other.glued.remove(panel);
        panel.glued.clear();
        if(previous!=null) for(var other:neighbors) if(other.curve==null) GroupCurve.restore(other,previous);
    }
    static Vector3f local(WorldPanel panel, Vec3 point) {
        if(panel.curve!=null) return panel.curve.local(panel,point);
        Vec3 d = point.subtract(panel.position);
        return new Vector3f((float)d.x, (float)d.y, (float)d.z).rotate(new Quaternionf(panel.orientation).conjugate());
    }
    static Vec3 world(Vec3 origin, Quaternionf rotation, float x, float y) {
        var v = new Vector3f(x, y, 0).rotate(rotation);
        return origin.add(v.x, v.y, v.z);
    }
    static float top(WorldPanel p) { return p.worldHeight()/2 + p.titlebarHeight()*p.worldHeight()/p.pixelHeight(); }
    static void moved(WorldPanel root, Vec3 oldPosition, Quaternionf oldRotation) {
        if (oldPosition == null || oldRotation == null) return;
        var turn = new Quaternionf(root.orientation).mul(new Quaternionf(oldRotation).conjugate());
        if(root.curve!=null) root.curve.moved(oldPosition,root.position,turn);
        for (var p : members(root)) if (p != root) {
            Vec3 d = p.position.subtract(oldPosition);
            var offset = new Vector3f((float)d.x,(float)d.y,(float)d.z).rotate(turn);
            p.position = root.position.add(offset.x,offset.y,offset.z);
            p.orientation = new Quaternionf(turn).mul(p.orientation);
        }
    }
    record Suggestion(WorldPanel a, WorldPanel b, int x, int y, int edge) {
        boolean contains(int px, int py) { return px >= x && px <= x+88 && py >= y && py <= y+28; }
    }
    static Suggestion suggest(WorldPanel a, Vec3 point, List<WorldPanel> windows) {
        if (!a.isOpen() || !a.canGroup() || !a.hasWindowControls()) return null;
        int edge=nearestEdge(a,point);
        Vec3 seam=edgePoint(a,point,edge);
        if (seam.distanceTo(point)>.25) return null;
        WorldPanel closest=null; double best=.4;
        var group=component(a);
        for (var b : windows) {
            if (b == a || !b.isOpen() || !b.canGroup() || b.level != a.level || !b.hasWindowControls() || group.contains(b)) continue;
            double distance=edgePoint(b,seam,nearestEdge(b,seam)).distanceTo(seam);
            if(distance<best) { best=distance; closest=b; }
        }
        if(closest==null) return null;
        int[] pixel=a.pixelAt(seam);
        int x=edge<2 ? (edge==1?a.pixelWidth()-92:4) : Math.clamp(pixel[0]-44,4,a.pixelWidth()-92);
        int y=edge<2 ? Math.clamp(pixel[1]-14,0,a.pixelHeight()-28) : edge==3?0:a.pixelHeight()-28;
        return new Suggestion(a,closest,x,y,edge);
    }
    /** Actual world-space edge distances work for angled neighbors and gaps. */
    private static Vec3 edgePoint(WorldPanel p,Vec3 point,int edge) {
        var d=local(p,point);
        float x=Math.clamp(d.x,-p.worldWidth()/2,p.worldWidth()/2);
        float y=Math.clamp(d.y,-p.worldHeight()/2,top(p));
        if(edge<2) x=(edge==0?-1:1)*p.worldWidth()/2;
        else y=edge==2?-p.worldHeight()/2:top(p);
        return p.curve!=null?p.curve.panelPoint(p,x,y,0):world(p.position,p.orientation,x,y);
    }
    private static int nearestEdge(WorldPanel p,Vec3 point) {
        int edge=0; double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<4;i++) { double d=edgePoint(p,point,i).distanceTo(point); if(d<best){best=d;edge=i;} }
        return edge;
    }
    static void join(WorldPanel a, WorldPanel b) {
        join(a,b,nearestEdge(a,b.position));
    }
    static void join(WorldPanel a, WorldPanel b,int edge) {
        if (!a.isOpen() || !b.isOpen() || !a.canGroup() || !b.canGroup() || !a.hasWindowControls() || !b.hasWindowControls() || component(a).contains(b)) return;
        var previous=GroupCurve.flatten(a);
        var incoming=GroupCurve.flatten(b);
        if (edge<2) matchHeight(a,b);
        var oldPosition=b.position; var oldRotation=new Quaternionf(b.orientation);
        var d=local(a,b.position);
        if(edge<2) {
            d.x=(edge==0?-1:1)*(a.worldWidth()+b.worldWidth())/2;
            // Align the complete outer frame, including differently scaled native titlebars.
            d.y=(b.worldHeight()-a.worldHeight())/2;
        }
        else d.y=edge==3 ? top(a)+b.worldHeight()/2 : -a.worldHeight()/2-top(b);
        b.orientation=new Quaternionf(a.orientation);
        b.position=world(a.position,a.orientation,d.x,d.y);
        moved(b,oldPosition,oldRotation);
        a.glued.add(b); b.glued.add(a);
        GroupCurve.restore(a,previous!=null?previous:incoming);
    }
    private static void matchHeight(WorldPanel anchor,WorldPanel joining) {
        float target=anchor.worldHeight()/2+top(anchor);
        float outer=joining.worldHeight()/2+top(joining);
        if(joining.grouped()) {
            // Scale the incoming component vertically so existing seams stay connected.
            var bounds=frame(joining);
            var hit=world(bounds.position,bounds.orientation,bounds.worldWidth()/2,bounds.worldHeight()/2);
            new PanelResize(joining,10,hit).move(world(hit,bounds.orientation,0,bounds.worldHeight()*(target/outer-1)));
        } else {
            float height=joining instanceof BrowserPanel ? target-(outer-joining.worldHeight()) : target*joining.worldHeight()/outer;
            joining.resize(joining.worldWidth(),height);
        }
    }

    /** Invisible bounding rectangle provides only the four exterior group handles. */
    static WorldPanel frame(WorldPanel root) {
        float left=Float.POSITIVE_INFINITY,right=-left,bottom=left,top=-left;
        for(var p:members(root)) {
            var d=local(root,p.position);
            left=Math.min(left,d.x-p.worldWidth()/2); right=Math.max(right,d.x+p.worldWidth()/2);
            bottom=Math.min(bottom,d.y-p.worldHeight()/2); top=Math.max(top,d.y+top(p));
        }
        var frame=new WorldPanel(right-left,top-bottom) {};
        frame.position=world(root.position,root.orientation,(left+right)/2,(bottom+top)/2);
        frame.orientation=new Quaternionf(root.orientation); frame.level=root.level;
        return frame;
    }
    static int corner(WorldPanel p, Vec3 point) { return corner(p,point,null); }
    static int corner(WorldPanel p,Vec3 point,Vec3 observer) {
        return p.curve!=null?p.curve.corner(p,point,observer):p.glued.isEmpty()?p.resizeCorner(point,observer):frame(p).resizeCorner(point,observer);
    }
}
