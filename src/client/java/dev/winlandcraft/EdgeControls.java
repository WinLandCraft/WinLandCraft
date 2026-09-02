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
    private WindowGroups.Suggestion groupCandidate;
    private long previewSince;
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
    int headerY(){return expanded&&edge==TOP?72+streamHeight():0;}
    @Override public int pixelWidth(){return expanded?360:240;}
    @Override public int pixelHeight(){return expanded?112+streamHeight():40;}
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
    /** 1 drag, 2 close, 3 ungroup, 4 curve, 5 stream, 6 group. */
    int action(int x,int y) {
        if(x<0||y<0||x>=pixelWidth()||y>=pixelHeight())return 0;
        int hx=headerX(),hy=headerY();
        if(y>=hy&&y<hy+40&&x<hx+240)return x>=hx+200?2:1;
        if(expanded&&groupCandidate!=null&&x>=124&&x<232&&y>=ungroupY()&&y<ungroupY()+28)return 6;
        if(expanded&&owner.grouped()&&x>=12&&x<112&&y>=ungroupY()&&y<ungroupY()+28)return 3;
        if(expanded&&GroupCurve.eligible(owner)&&y>=sliderY()-14&&y<sliderY()+18)return 4;
        if(expanded&&owner.streaming()&&y>=streamY()&&y<streamY()+streamHeight())return 5;
        return 0;
    }
    void updateGroup(java.util.List<WorldPanel> windows) {
        WindowGroups.Suggestion next=null;
        if(owner.isOpen()&&owner.canGroup()) {
            Vec3 anchor=owner.curve==null?WindowGroups.world(owner.position,owner.orientation,anchorX,anchorY)
                    :owner.curve.panelPoint(owner,anchorX,anchorY,0);
            next=WindowGroups.suggest(owner,anchor,windows);
        }
        if(groupCandidate==null||next==null||groupCandidate.b()!=next.b()||groupCandidate.edge()!=next.edge())previewSince=0;
        groupCandidate=next;
    }
    void joinGroup() {
        if(groupCandidate==null)return;
        WindowGroups.join(owner,groupCandidate.b(),groupCandidate.edge());
        groupCandidate=null;previewSince=0;sync();
    }
    boolean previewsGroup(){return expanded&&groupCandidate!=null&&hovered(124,ungroupY(),108,28);}
    static float previewOpacity(double seconds){return (float)(.85*(1-Math.cos(Math.max(0,seconds)*Math.PI/1.2))/2);}
    void renderGroupPreview(WorldRenderContext context,boolean active) {
        if(!active||!previewsGroup()){previewSince=0;return;}
        long now=System.nanoTime();if(previewSince==0)previewSince=now;
        int alpha=Math.round(255*opacity*previewOpacity((now-previewSince)/1e9));
        if(alpha<2)return;
        var panels=new java.util.LinkedHashSet<>(WindowGroups.members(owner));
        panels.addAll(WindowGroups.members(groupCandidate.b()));
        for(var panel:panels)try(var c=panel.canvas(context)) {
            if(c==null)continue;
            c.foreground();
            float width=panel.pixelWidth(),height=panel.pixelHeight()+panel.titlebarHeight(),top=-panel.titlebarHeight();
            float size=(float)Math.max(.004,context.camera().getPosition().distanceTo(panel.position)*.001);
            float dx=Math.clamp(size*width/panel.worldWidth(),1,Math.max(1,width/30));
            float dy=Math.clamp(size*panel.pixelHeight()/panel.worldHeight(),1,Math.max(1,height/30));
            int color=alpha<<24|0x49DC75;
            // Bound dash count even for oversized windows; the canvas follows curved surfaces.
            float stepX=Math.max(dx*3,width/160),stepY=Math.max(dy*3,height/90);
            for(float x=0;x<width;x+=stepX){float dash=Math.min(stepX*.5f,width-x);c.rect(x,top-dy,dash,dy,.75f,color);c.rect(x,panel.pixelHeight(),dash,dy,.75f,color);}
            for(float y=0;y<height;y+=stepY){float dash=Math.min(stepY*.5f,height-y);c.rect(-dx,top+y,dx,dash,.75f,color);c.rect(width,top+y,dx,dash,.75f,color);}
        }
    }
    private int sliderY(){return edge==TOP?48+streamHeight():84;}
    private int ungroupY(){return edge==TOP?4+streamHeight():42;}
    private int streamHeight(){return owner.streaming()?216:0;}
    int streamY(){return edge==TOP?0:112;}
    void streamClick(int x,int y) {
        if(!expanded||!owner.streaming())return;
        int local=y-streamY();
        if(local>=184&&local<212&&x>=12&&x<348){owner.streamClient.stop(owner);sync();return;}
        if(local>=144&&local<168&&owner instanceof BrowserPanel browser&&x>=292&&x<348) {
            ModSettings.streamRemoteControl=!ModSettings.streamRemoteControl;
            if(!ModSettings.streamRemoteControl)browser.clearRemoteControls();
        } else {
            if(local<24||local>=144||x<292||x>=348||(local-24)%24>=22)return;
            int row=(local-24)/24,step=x<320?-1:1;
            switch(row) {
                case 0->ModSettings.streamFps=StreamQuality.step(ModSettings.streamFps,StreamQuality.FPS,step);
                case 1->ModSettings.streamKbps=StreamQuality.step(ModSettings.streamKbps,StreamQuality.BITRATES,step);
                case 2->ModSettings.streamHeight=StreamQuality.step(ModSettings.streamHeight,StreamQuality.HEIGHTS,step);
                case 3->ModSettings.streamAudio=!ModSettings.streamAudio;
                case 4->ModSettings.streamAudioKbps=StreamQuality.step(ModSettings.streamAudioKbps,StreamQuality.AUDIO,step);
            }
        }
        if(!ModSettings.save())owner.streamStatus="Could not save quality settings";
    }
    private void drawStream(PanelCanvas c) {
        int top=streamY();
        c.text("Stream quality",12,top+6,0xFFD4ACFF,1.5f);
        String[] labels={"FPS: "+ModSettings.streamFps,"Video: "+ModSettings.streamKbps+" kbps","Size: "+ModSettings.streamHeight+"p","Audio: "+(ModSettings.streamAudio?"ON":"OFF"),"Audio: "+ModSettings.streamAudioKbps+" kbps"};
        for(int i=0;i<labels.length;i++) {
            int y=top+24+i*24;c.rect(12,y,336,22,.3f,0xFF30263F);c.text(labels[i],18,y+7,-1,1.2f);
            for(int j=0;j<2;j++){int x=292+j*28;c.rect(x,y,28,22,.4f,hoverColor(x,y,28,22,0xFF624389,0xFF8059AE));(j==0?PixelIcon.MINUS:PixelIcon.PLUS).draw(c,x+6,y+3,16,.5f,-1);}
        }
        int y=top+144;
        if(owner instanceof BrowserPanel) {
            c.text("Allow remote control",18,y+7,-1,1.2f);
            c.rect(292,y,56,22,.4f,hoverColor(292,y,56,22,0xFF624389,0xFF8059AE));
            c.text(ModSettings.streamRemoteControl?"ON":"OFF",306,y+7,-1);
        }else c.text("View only | This app has no audio",18,y+7,0xFF9BAABD);
        c.text(Minecraft.getInstance().font.plainSubstrByWidth(owner.streamStatus,326),18,top+172,0xFFD4ACFF);
        c.rect(12,top+184,336,28,.4f,hoverColor(12,top+184,336,28,0xFF854551,0xFFB65B69));
        c.text("Stop streaming",18,top+194,-1,1.2f);
    }
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
                if(owner.streaming())drawStream(c);
                if(owner.canGroup()) {
                    int y=ungroupY();boolean enabled=groupCandidate!=null;
                    c.rect(124,y,108,28,.4f,enabled?hoverColor(124,y,108,28,0xFF326D4A,0xFF458F62):0xFF293B42);
                    c.text("Group",140,y+10,enabled?-1:0xFF7E9199);
                }
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
