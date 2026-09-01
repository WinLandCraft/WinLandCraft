package dev.winlandcraft;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Captured resize in the initial panel plane; the opposite corner stays fixed. */
final class PanelResize {
    final WorldPanel panel;
    final int corner;
    private final Vec3 initialPosition, initialHit;
    private final Quaternionf rotation;
    private final float width, height;
    private final java.util.List<State> group;
    private final GroupCurve curve;
    private final boolean scaling;
    private record State(WorldPanel panel, Vec3 position, float width, float height, float outerHeight) {}
    PanelResize(WorldPanel panel, int corner, Vec3 hit) {
        this(panel, corner, hit, false);
    }
    PanelResize(WorldPanel panel, int corner, Vec3 hit, boolean scaling) {
        this.scaling = scaling;
        curve=GroupCurve.flatten(panel);
        this.panel = panel; this.corner = corner; initialPosition = panel.position; initialHit = hit;
        rotation = new Quaternionf(panel.orientation); width = panel.worldWidth(); height = panel.worldHeight();
        group = panel.grouped() || scaling ? WindowGroups.members(panel).stream().map(p -> new State(p,p.position,p.worldWidth(),p.worldHeight(),p.worldHeight()+p.titlebarHeight()*p.worldHeight()/p.pixelHeight())).toList() : java.util.List.of();
        GroupCurve.restore(panel,curve);
    }
    void move(Vec3 hit) {
        GroupCurve.flatten(panel);
        Vec3 delta = hit.subtract(initialHit);
        Vector3f local = new Vector3f((float) delta.x, (float) delta.y, (float) delta.z)
                .rotate(new Quaternionf(rotation).conjugate());
        int sx = (corner & 1) != 0 ? -1 : 1, sy = (corner & 4) != 0 ? -1 : 1;
        if (!group.isEmpty()) { resizeGroup(local,sx,sy); GroupCurve.restore(panel,curve); return; }
        panel.resize(width + sx * local.x, height + sy * local.y);
        Vector3f offset = new Vector3f(sx * (panel.worldWidth() - width) / 2,
                sy * (panel.worldHeight() - height) / 2, 0).rotate(rotation);
        panel.position = initialPosition.add(offset.x, offset.y, offset.z);
        GroupCurve.restore(panel,curve);
    }
    double planeDistance(Vec3 origin,Vec3 direction) {
        var normal=new org.joml.Vector3f(0,0,1).rotate(rotation);
        var n=new Vec3(normal.x,normal.y,normal.z);
        double denominator=direction.dot(n);
        if(Math.abs(denominator)<.00001) return Double.POSITIVE_INFINITY;
        double t=initialHit.subtract(origin).dot(n)/denominator;
        return t>0?t:Double.POSITIVE_INFINITY;
    }
    private void resizeGroup(Vector3f delta,int sx,int sy) {
        float left=Float.POSITIVE_INFINITY,right=-left,bottom=left,top=-left;
        float minX=0,maxX=Float.POSITIVE_INFINITY,minY=0,maxY=Float.POSITIVE_INFINITY;
        for(var s:group) {
            var d=relative(s.position);
            left=Math.min(left,d.x-s.width/2); right=Math.max(right,d.x+s.width/2);
            bottom=Math.min(bottom,d.y-s.height/2); top=Math.max(top,d.y-s.height/2+s.outerHeight);
            minX=Math.max(minX,s.panel.resizeMinimumWidth()/s.width); maxX=Math.min(maxX,s.panel.resizeMaximumWidth()/s.width);
            minY=Math.max(minY,scaling?s.panel.resizeMinimumHeight()/s.height:outer(s,s.panel.resizeMinimumHeight())/s.outerHeight); maxY=Math.min(maxY,scaling?s.panel.resizeMaximumHeight()/s.height:outer(s,s.panel.resizeMaximumHeight())/s.outerHeight);
        }
        float scaleX=Math.clamp(1+sx*delta.x/(right-left),minX,maxX);
        float scaleY=Math.clamp(1+sy*delta.y/(top-bottom),minY,maxY);
        if (scaling) {
            float w=right-left,h=top-bottom;
            float factor=1+(sx*delta.x*w+sy*delta.y*h)/(w*w+h*h);
            scaleX=scaleY=Math.clamp(factor,Math.min(Math.max(minX,minY),1),Math.max(Math.min(maxX,maxY),1));
        }
        float anchorX=sx>0?left:right,anchorY=sy>0?bottom:top;
        for(var s:group) {
            if (!s.panel.isOpen()) continue;
            var d=relative(s.position);
            float targetOuter=s.outerHeight*scaleY;
            float h=s.panel instanceof BrowserPanel ? targetOuter-(s.outerHeight-s.height) : targetOuter*s.height/s.outerHeight;
            if (scaling) s.panel.scaleTo(s.width*scaleX,s.height*scaleY);
            else s.panel.resize(s.width*scaleX,h);
            float x=anchorX+(d.x-anchorX)*scaleX;
            float y=anchorY+(d.y-s.height/2-anchorY)*scaleY+s.panel.worldHeight()/2;
            s.panel.position=WindowGroups.world(initialPosition,rotation,x,y);
        }
    }
    private float outer(State s,float contentHeight) { return s.panel instanceof BrowserPanel ? contentHeight+s.outerHeight-s.height : contentHeight*s.outerHeight/s.height; }
    private Vector3f relative(Vec3 p) {
        var d=p.subtract(initialPosition);
        return new Vector3f((float)d.x,(float)d.y,(float)d.z).rotate(new Quaternionf(rotation).conjugate());
    }
}
