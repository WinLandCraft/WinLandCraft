package dev.winlandcraft;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared placement and picking for taskbar and future app windows. */
public abstract class WorldPanel {
    final java.util.Set<WorldPanel> glued = new java.util.HashSet<>();
    GroupCurve curve;
    java.util.UUID broadcastSession;
    volatile StreamEncoder encoder;
    volatile String streamStatus="Starting codecs...";
    StreamClient streamClient;
    final boolean streaming(){return broadcastSession!=null;}
    void drawSurface(PanelCanvas canvas) { }

    private int hoverX=Integer.MIN_VALUE,hoverY=Integer.MIN_VALUE;
    public boolean grouped() { return !glued.isEmpty(); }
    protected void renderUngroup(PanelCanvas canvas) {
        if (!grouped()) return;
        int x=pixelWidth()-140,y=-titlebarHeight();
        canvas.rect(x,y,100,titlebarHeight(),0.45f,hoverColor(x,y,100,titlebarHeight(),0xFF386776,0xFF4C8493));
        PixelIcon.UNLINK.draw(canvas,x+6,y+4,24,.55f,-1);
        canvas.text("Ungroup",x+34,-22,0xFFFFFFFF,1.5f);
    }
    protected final void renderClose(PanelCanvas canvas) {
        int x=pixelWidth()-40,y=-titlebarHeight();
        canvas.rect(x,y,40,titlebarHeight(),.45f,hoverColor(x,y,40,titlebarHeight(),0xFF854551,0xFFB65B69));
        PixelIcon.CLOSE.draw(canvas,x+8,y+4,24,.55f,-1);
    }
    final void pointerMoved(int x,int y){hoverX=x;hoverY=y;hover(x,y);}
    protected final boolean hovered(int x,int y,int width,int height){return hoverX>=x&&hoverX<x+width&&hoverY>=y&&hoverY<y+height;}
    protected final int hoverColor(int x,int y,int width,int height,int normal,int hot){return hovered(x,y,width,height)?hot:normal;}
    public void open(net.minecraft.client.Minecraft client) {
        if (client.level != null) bringToView(client.level, client.gameRenderer.mainCamera(), 0.6f);
    }
    public int pixelWidth() { return 400; }
    public int pixelHeight() { return 48; }
    final RemoteAppInput remoteInput=new RemoteAppInput();
    boolean remoteControlAllowed(){return true;}
    void remoteControl(StreamProtocol.Control input){remoteInput.accept(this,input);}
    void clearRemoteControls(){remoteInput.clear(this);}
    public void mouseDown(int x, int y, int button) { }
    public void mouseUp(int x, int y, int button) { }
    public java.nio.file.Path dragFileAt(int x,int y){return null;}
    public boolean acceptsFileDrop(){return false;}
    public void dropFile(java.nio.file.Path path) { }
    public void hover(int x, int y) { }
    public void scroll(int x, int y, double amount) { }
    public boolean acceptsKeyboard() { return false; }
    public boolean wantsKeyboard() { return false; }
    public void keyboardStarted() { }
    public void keyboardStopped() { }
    public void key(int key, int scan, int action, int modifiers) { }
    public void character(char character, int modifiers) { }
    public void render(WorldPass pass) { }
    public void tick(net.minecraft.client.Minecraft client) {
        if (level != client.level || client.player == null || !client.player.isAlive()) close();
    }
    public boolean isOpen() { return position != null; }
    public WorldPanel dragTarget() { return this; }
    public int titlebarHeight() { return 0; }
    public boolean floatingControls(){return false;}
    public String windowTitle(){return "App";}
    final boolean hasWindowControls(){return floatingControls()||titlebarHeight()>0;}
    /** 0 = content/outside, 1 = drag area, 2 = close button, 3 = ungroup. */
    public int titlebarAction(int x, int y) {
        if (titlebarHeight() == 0 || y >= 0 || y < -titlebarHeight() || x < 0 || x >= pixelWidth()) return 0;
        return x >= pixelWidth() - 40 ? 2 : grouped() && x >= pixelWidth()-140 ? 3 : 1;
    }
    private float topEdge() { return halfHeight + titlebarHeight() * worldHeight() / pixelHeight(); }
    protected ClientLevel level;
    protected Vec3 position;
    protected Quaternionf orientation;
    private long beaconUntil;
    /** Marks a spawn that landed inside blocks; the beacon outline fades over ten seconds. */
    void markBeacon() { beaconUntil = System.nanoTime() + Beacon.DURATION_NANOS; }
    float beaconAlpha() {
        if (position == null) return 0;
        return Math.clamp((beaconUntil - System.nanoTime()) / (float) Beacon.DURATION_NANOS, 0, 1);
    }
    private float halfWidth;
    private float halfHeight;
    private float[] screenLightColors;
    public float worldWidth() { return halfWidth * 2; }
    public float worldHeight() { return halfHeight * 2; }
    protected float minimumWidth() { return 0.6f; }
    protected float minimumHeight() { return 0.15f; }
    public boolean canResize() { return true; }
    public boolean canMove() { return true; }
    public boolean canInteract() { return true; }
    public boolean canGroup() { return !streaming(); }
    protected boolean projectsLight(){return false;}
    final float[] screenLightColors(){return screenLightColors;}
    final void screenLightColors(float[] colors){screenLightColors=ScreenLighting.smooth(screenLightColors,colors);}
    float resizeMinimumWidth(){return ModSettings.removeSizingLimitations?.0001f:minimumWidth();}
    float resizeMinimumHeight(){return ModSettings.removeSizingLimitations?.0001f:minimumHeight();}
    float resizeMaximumWidth(){return ModSettings.removeSizingLimitations?Float.MAX_VALUE:6.4f;}
    float resizeMaximumHeight(){return ModSettings.removeSizingLimitations?Float.MAX_VALUE:3.6f;}
    public void resize(float width, float height) {
        if (!canResize()) return;
        if(!Float.isFinite(width)||!Float.isFinite(height))return;
        halfWidth = Math.clamp(width, resizeMinimumWidth(), resizeMaximumWidth()) / 2;
        halfHeight = Math.clamp(height, resizeMinimumHeight(), resizeMaximumHeight()) / 2;
    }
    /** Physical scaling bypasses app resize callbacks and preserves its pixel layout. */
    final void scaleTo(float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return;
        halfWidth = width / 2;
        halfHeight = height / 2;
    }
    float resizeHandleSize(Vec3 observer) {
        float size=observer==null?0.045f:(float)(observer.distanceTo(position)*0.006);
        return Math.min(Math.clamp(size,0.045f,0.45f),Math.max(0.045f,Math.min(worldWidth(),halfHeight+topEdge())*.3f));
    }
    /** Exterior corner handles leave all content pixels available to the app. */
    public int resizeCorner(Vec3 point) { return resizeCorner(point,null); }
    int resizeCorner(Vec3 point,Vec3 observer) {
        if (!canResize() || !isOpen()) return 0;
        Vector3f local = WindowGroups.local(this,point);
        float x = Math.abs(local.x), edge = local.y < 0 ? halfHeight : topEdge(), y = Math.abs(local.y), margin = resizeHandleSize(observer);
        if (x < halfWidth && y < edge) return 0;
        if (Math.abs(x - halfWidth) > margin || Math.abs(y - edge) > margin) return 0;
        return (local.x < 0 ? 1 : 2) | (local.y < 0 ? 4 : 8);
    }
    public double pointerDistance(Vec3 origin, Vec3 direction) {
        double hit = intersect(origin, direction);
        if (Double.isFinite(hit)) return hit;
        double plane = planeDistance(origin, direction);
        return Double.isFinite(plane) && resizeCorner(origin.add(direction.scale(plane)),origin) != 0 ? plane : Double.POSITIVE_INFINITY;
    }
    public void renderResizeHandles(WorldPass pass,boolean scaling) {
        if (!canResize()) return;
        try (var canvas = canvas(pass)) {
            if (canvas == null) return;
            float size=resizeHandleSize(pass.camera().pos);
            float hx = size * pixelWidth() / worldWidth(), hy = size * pixelHeight() / worldHeight();
            for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) {
                if(curve!=null) {
                    var cornerPoint=curve.panelPoint(this,sx*(worldWidth()/2+.001f),sy<0?-worldHeight()/2-.001f:WindowGroups.top(this)+.001f,0);
                    if(curve.corner(this,cornerPoint,pass.camera().pos)==0) continue;
                }
                float x = sx < 0 ? -hx : pixelWidth(), y = sy < 0 ? -titlebarHeight() - hy : pixelHeight();
                canvas.rect(x, y, hx, hy, 0.7f, scaling?0xFFFFC857:0xFF51CFDF);
            }
        }
    }

    protected WorldPanel(float width, float height) {
        halfWidth = width / 2;
        halfHeight = height / 2;
    }

    /** Coplanar placement: this panel's bottom-left meets the anchor's top-left. */
    protected void alignAboveLeft(WorldPanel anchor) {
        orientation = new Quaternionf(anchor.orientation);
        Vector3f offset = new Vector3f(halfWidth - anchor.halfWidth, halfHeight + anchor.halfHeight, 0)
                .rotate(orientation);
        position = anchor.position.add(offset.x, offset.y, offset.z);
        level = anchor.level;
    }

    public int[] pixelAt(Vec3 point) {
        Vector3f local = WindowGroups.local(this,point);
        // Do not clamp: an active mouse gesture must still receive positions outside the window.
        return new int[]{(int) Math.floor((local.x / (2 * halfWidth) + 0.5) * pixelWidth()),
                (int) Math.floor((0.5 - local.y / (2 * halfHeight)) * pixelHeight())};
    }

    public double planeDistance(Vec3 origin, Vec3 direction) {
        if (position == null) return Double.POSITIVE_INFINITY;
        if(curve!=null) return curve.intersectSurface(origin,direction);
        Vector3f normal = new Vector3f(0, 0, 1).rotate(orientation);
        Vec3 n = new Vec3(normal.x, normal.y, normal.z);
        double denominator = direction.dot(n);
        if (Math.abs(denominator) < 0.00001) return Double.POSITIVE_INFINITY;
        double t = position.subtract(origin).dot(n) / denominator;
        return t > 0 ? t : Double.POSITIVE_INFINITY;
    }

    final boolean frontFacing(Vec3 observer) {
        Vector3f normal=new Vector3f(0,0,1).rotate(orientation);
        Vec3 relative=observer.subtract(position);
        return relative.x*normal.x+relative.y*normal.y+relative.z*normal.z>=0;
    }

    private boolean canRender(WorldPass pass) {
        return position != null && level == net.minecraft.client.Minecraft.getInstance().level;
    }

    protected PanelCanvas canvas(WorldPass pass) {
        return canRender(pass) ? new PanelCanvas(pass, this) : null;
    }

    public void bringToView(ClientLevel world, Camera camera) {
        bringToView(world, camera, -0.45f);
    }

    public void bringToView(ClientLevel world, Camera camera, float verticalOffset) {
        Vec3 oldPosition = position; Quaternionf oldRotation = orientation;
        orientation = PanelRotation.allowed(camera.rotation());
        Vector3f offset = new Vector3f(0, verticalOffset, -2.5f).rotate(camera.rotation());
        Vec3 fallback = camera.position().add(offset.x, offset.y, offset.z);
        float title = titlebarHeight() * worldHeight() / Math.max(1, pixelHeight());
        var placement = SpawnSpace.resolve(world, camera, fallback, orientation,
                worldWidth(), worldHeight() / 2 + title, worldHeight() / 2);
        position = placement.position();
        level = world;
        WindowGroups.moved(this, oldPosition, oldRotation);
        if (placement.blocked()) Beacon.mark(this);
    }

    public void close() {
        if(streamClient!=null)streamClient.stop(this);
        WindowGroups.detach(this);
        hoverX=hoverY=Integer.MIN_VALUE;
        screenLightColors=null;
        position = null;
        orientation = null;
        level = null;
    }

    /** Two-sided ray/rectangle intersection, in world units. */
    public double intersect(Vec3 origin, Vec3 direction) {
        if (position == null) {
            return Double.POSITIVE_INFINITY;
        }
        if(curve!=null) {
            double t=curve.intersectSurface(origin,direction);
            if(!Double.isFinite(t)) return t;
            var local=curve.local(this,origin.add(direction.scale(t)));
            return Math.abs(local.x)<=halfWidth && local.y>=-halfHeight && local.y<=topEdge()?t:Double.POSITIVE_INFINITY;
        }
        Quaternionf inverse = new Quaternionf(orientation).conjugate();
        Vec3 relative = origin.subtract(position);
        Vector3f localOrigin = new Vector3f((float) relative.x, (float) relative.y, (float) relative.z).rotate(inverse);
        Vector3f localDirection = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z).rotate(inverse);
        if (Math.abs(localDirection.z) < 0.00001f) {
            return Double.POSITIVE_INFINITY;
        }
        double distance = -localOrigin.z / localDirection.z;
        float y = (float) (localOrigin.y + distance * localDirection.y);
        if (distance <= 0 || Math.abs(localOrigin.x + distance * localDirection.x) > halfWidth
                || y < -halfHeight || y > topEdge()) {
            return Double.POSITIVE_INFINITY;
        }
        return distance;
    }
}
