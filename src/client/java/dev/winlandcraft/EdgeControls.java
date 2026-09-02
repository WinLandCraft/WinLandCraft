package dev.winlandcraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Local-only chrome with deliberate activation and frame-rate independent edge following. */
final class EdgeControls extends WorldPanel {
    static final int TOP=0,RIGHT=1,BOTTOM=2,LEFT=3;
    final WorldPanel owner;
    final int edge;
    private float anchorX,anchorY;
    private final float minimumUnit;
    private float unit;
    private Vec3 observer;
    private boolean sizeLocked;
    private float opacity,frameSeconds;
    private long lastFrame;
    private boolean expanded;
    private long until,hoverUntil;
    EdgeControls(WorldPanel owner,Vec3 point,long now) {
        super(.6f,.1f);this.owner=owner;
        var local=WindowGroups.local(owner,point);
        edge=nearestEdge(local.x,local.y,owner.worldWidth(),owner.worldHeight());
        anchorX=edge==LEFT?-owner.worldWidth()/2:edge==RIGHT?owner.worldWidth()/2:Math.clamp(local.x,-owner.worldWidth()/2,owner.worldWidth()/2);
        anchorY=edge==TOP?owner.worldHeight()/2:edge==BOTTOM?-owner.worldHeight()/2:Math.clamp(local.y,-owner.worldHeight()/2,owner.worldHeight()/2);
        minimumUnit=Math.clamp(owner.worldHeight()/owner.pixelHeight(),.0015f,.004f);unit=minimumUnit;
        lastFrame=now;touch(now);sync();
    }
    static int nearestEdge(float x,float y,float width,float height) {
        float[] d={Math.abs(y-height/2),Math.abs(x-width/2),Math.abs(y+height/2),Math.abs(x+width/2)};
        int best=0;for(int i=1;i<4;i++)if(d[i]<d[best])best=i;return best;
    }
    static boolean near(WorldPanel p,Vec3 point) {
        return near(p,point,null);
    }
    static boolean near(WorldPanel p,Vec3 point,Vec3 observer) {
        if(!p.floatingControls()||!p.canMove()||!p.canInteract())return false;
        var v=WindowGroups.local(p,point);float w=p.worldWidth()/2,h=p.worldHeight()/2;
        float margin=Math.clamp(p.worldHeight()/p.pixelHeight()*48,.08f,.18f);
        if(observer!=null)margin=Math.max(margin,(float)Math.min(observer.distanceTo(point)*.006,Math.min(w,h)*.5));
        return Math.abs(v.x)<=w+margin&&Math.abs(v.y)<=h+margin
                &&(Math.abs(Math.abs(v.x)-w)<margin||Math.abs(Math.abs(v.y)-h)<margin);
    }
    void touch(long now){until=now+650_000_000L;}
    void dismiss(long now){until=Math.min(until,now);}
    boolean expired(long now){return now>until&&opacity<=0;}
    boolean pickable(){return opacity>=.08f;}
    float opacity(){return opacity;}
    void advance(long now,boolean held) {
        frameSeconds=(float)Math.clamp((now-lastFrame)/1e9,0,.1);lastFrame=now;
        if(held)touch(now);
        opacity=Math.clamp(opacity+(now<=until?1:-1)*frameSeconds/.2f,0,1);
    }
    void follow(Vec3 point,long now) {
        nearTouch(now);
        var local=WindowGroups.local(owner,point);
        float blend=(float)(1-Math.exp(-frameSeconds/.12));
        if(edge==TOP||edge==BOTTOM)anchorX+=(Math.clamp(local.x,-owner.worldWidth()/2,owner.worldWidth()/2)-anchorX)*blend;
        else anchorY+=(Math.clamp(local.y,-owner.worldHeight()/2,owner.worldHeight()/2)-anchorY)*blend;
        sync();
    }
    static final class Dwell {
        private WorldPanel panel;
        private int edge;
        private long since;
        boolean ready(WorldPanel panel,int edge,long now) {
            if(this.panel!=panel||this.edge!=edge){this.panel=panel;this.edge=edge;since=now;}
            return now-since>=500_000_000L;
        }
        void clear(){panel=null;}
    }
    void expand(long now){touch(now);hoverUntil=now+650_000_000L;if(!expanded){expanded=true;sync();}}
    void nearTouch(long now){touch(now);if(expanded&&now>hoverUntil){expanded=false;sync();}}
    boolean expanded(){return expanded;}
    int headerX(){return expanded&&edge!=RIGHT?120:0;}
    int headerY(){return expanded&&edge==TOP?72:0;}
    @Override public int pixelWidth(){return expanded?360:240;}
    @Override public int pixelHeight(){return expanded?112:40;}
    @Override public boolean canResize(){return false;}
    @Override public boolean canGroup(){return false;}
    @Override public WorldPanel dragTarget(){return owner;}
    void view(Vec3 observer,boolean locked){this.observer=observer;sizeLocked=locked;sync();}
    void sync() {
        if(!owner.isOpen()){position=null;return;}
        Vec3 anchor;
        if(owner.curve!=null) {
            var c=owner.curve;anchor=c.panelPoint(owner,anchorX,anchorY,0);
            float x=c.layout.get(owner).x()+anchorX;
            orientation=new Quaternionf(c.rotation).rotateY(c.amount<.0001f?0:-c.facing*x/c.radius());
        }else {
            var v=new Vector3f(anchorX,anchorY,0).rotate(owner.orientation);
            anchor=owner.position.add(v.x,v.y,v.z);orientation=new Quaternionf(owner.orientation);
        }
        // Keep a roughly constant angular pixel size at distance; never shrink the nearby UI.
        // Freeze size through a grab so its world-space grab point/slider mapping stays stable.
        if(observer!=null&&!sizeLocked)unit=Math.max(minimumUnit,(float)Math.min(observer.distanceTo(anchor),WindowControls.MOVE_RANGE)*.001f);
        // Expansion grows away from the panel; the compact header and close button don't move.
        float left=edge==LEFT?-252:edge==RIGHT?12:-120,top=edge==TOP?-52:edge==BOTTOM?12:-20;
        left-=headerX();top-=headerY();
        var offset=new Vector3f((left+pixelWidth()/2f)*unit,-(top+pixelHeight()/2f)*unit,.002f).rotate(orientation);
        position=anchor.add(offset.x,offset.y,offset.z);level=owner.level;
        scaleTo(pixelWidth()*unit,pixelHeight()*unit);
    }
    /** 1 drag, 2 close, 3 ungroup, 4 curve; body gaps consume input without moving. */
    int action(int x,int y) {
        if(x<0||y<0||x>=pixelWidth()||y>=pixelHeight())return 0;
        int hx=headerX(),hy=headerY();
        if(y>=hy&&y<hy+40&&x<hx+240)return x>=hx+200?2:1;
        if(expanded&&owner.grouped()&&x>=12&&x<112&&y>=ungroupY()&&y<ungroupY()+28)return 3;
        if(expanded&&GroupCurve.eligible(owner)&&y>=sliderY()-14&&y<sliderY()+18)return 4;
        return 0;
    }
    private int sliderY(){return edge==TOP?48:84;}
    private int ungroupY(){return edge==TOP?4:42;}
    float curveValue(int x){return Math.clamp((x-92)/214f,0,1);}
    @Override public void render(WorldRenderContext context) {
        // Minecraft's font treats near-zero alpha as unspecified/opaque.
        if(!owner.isOpen()||opacity<.02f)return;
        try(var surface=surface(context)) {
            if(surface==null||!surface.frontFacing())return;
            surface.foreground(opacity);
            var c=surface.canvas();
            int hx=headerX(),hy=headerY();
            c.rect(-1,-1,pixelWidth()+2,pixelHeight()+2,.2f,0xFF657888);
            c.rect(0,0,pixelWidth(),pixelHeight(),.3f,0xF020303D);
            c.rect(0,hy,hx+200,40,.4f,hoverColor(0,hy,hx+200,40,0xFF314D63,0xFF466B83));
            String title=Minecraft.getInstance().font.plainSubstrByWidth(owner.windowTitle(),(int)((hx+180)/1.5f));
            c.text(title,10,hy+14,-1,1.5f);
            c.rect(hx+200,hy,40,40,.4f,hoverColor(hx+200,hy,40,40,0xFF854551,0xFFB65B69));
            PixelIcon.CLOSE.draw(c,hx+208,hy+8,24,.5f,-1);
            if(expanded) {
                if(owner.grouped()) {
                    int y=ungroupY();c.rect(12,y,100,28,.4f,hoverColor(12,y,100,28,0xFF386776,0xFF4C8493));
                    PixelIcon.UNLINK.draw(c,16,y+2,24,.5f,-1);c.text("Ungroup",44,y+10,-1);
                }
                if(GroupCurve.eligible(owner)) {
                    float amount=owner.curve==null?0:owner.curve.amount;int y=sliderY();
                    c.text("Curve",12,y-5,0xFFE1F2FA,1.5f);
                    c.rect(92,y,214,4,.4f,0xFF69808A);c.rect(92,y,214*amount,4,.45f,0xFF51CFDF);
                    c.rect(88+214*amount,y-8,8,20,.5f,0xFFAAEDF5);
                    c.text(Math.round(amount*100)+"%",316,y-3,-1);
                }
            }
        }
    }
}
