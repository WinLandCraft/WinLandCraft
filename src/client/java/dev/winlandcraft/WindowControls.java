package dev.winlandcraft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** WaylandCraft-style hover routing, implicit mouse grabs, and separate window grabs.
 * Implemented for Minecraft 1.21.4 and CEF rather than the Wayland protocol. */
public final class WindowControls {
    static final double MOVE_RANGE=4096;
    private final AppWindows apps;
    private final KeyMapping typingKey;
    private WorldPanel dragging, pointer, focused, hovered;
    private final Set<Integer> buttons = new HashSet<>();
    private final Map<Integer, Integer> keys = new HashMap<>();
    private int pointerX, pointerY;
    private InteractionHand dragHand;
    private double distance;
    private Vector3f grabOffset;
    private Quaternionf relativeRotation;
    private boolean usedThisPress, typing;
    private int activationKey = -1;
    private PanelResize resizing;
    private int hoveredCorner;
    private WorldPanel hintPanel;
    private int hintCorner;
    private long hintSince;
    private WindowGroups.Suggestion suggestion;
    private WorldPanel curveUi,curveOwner;
    private boolean curving;
    private long curveUntil;
    private java.nio.file.Path draggedFile;
    private boolean fileDragging;
    private int fileStartX,fileStartY;
    private Vec3 fileStartRay;

    public WindowControls(AppWindows apps, KeyMapping typingKey) { this.apps = apps; this.typingKey = typingKey; }
    private boolean active(Minecraft c) {
        return c.player != null && c.level != null && c.player.isAlive() && !c.player.isSpectator()
                && c.screen == null && c.isWindowActive();
    }
    public void tick(Minecraft c) {
        if (!c.options.keyUse.isDown()) { usedThisPress = false; if (dragHand != null) dragging = null; }
        if (!active(c)) { cancel(); return; }
        if(curveOwner!=null && (!GroupCurve.eligible(curveOwner) || curveOwner.curve==null)) clearCurve();
        if (resizing != null && (!resizing.panel.isOpen() || resizing.panel.level != c.level)) resizing = null;
        if (dragging != null && (!dragging.isOpen() || dragging.level != c.level
                || dragHand != null && !c.player.getItemInHand(dragHand).is(WinLandCraft.WINDOW_DRAG))) dragging = null;
        if (pointer != null && (!pointer.isOpen() || pointer.level != c.level || !pointer.canInteract())) releasePointer();
        if (focused != null && (!focused.isOpen() || focused.level != c.level || !focused.acceptsKeyboard())) stopTyping();
    }
    public void cancel() {
        dragging = null;
        resizing = null; hoveredCorner = 0;
        releasePointer();
        stopTyping();
        if (hovered != null && hovered.isOpen()) hovered.hover(-1, -1);
        hovered = null;
        suggestion = null;
        clearCurve();
    }
    private void clearCurve(){curveUi=null;curveOwner=null;curving=false;}
    private void releasePointer() {
        if (pointer != null) for (int button : buttons) pointer.mouseUp(pointerX, pointerY, button);
        buttons.clear(); pointer = null;
        draggedFile=null;fileDragging=false;
    }
    private void stopTyping() {
        if (focused != null) keys.forEach((key, scan) -> focused.key(key, scan, GLFW.GLFW_RELEASE, 0));
        if (focused != null) focused.keyboardStopped();
        keys.clear(); typing = false; activationKey = -1;
    }
    public void use(Minecraft c, InteractionHand hand) {
        if (usedThisPress || !active(c)) return;
        usedThisPress = true;
        dragging = null;
        var item = c.player.getItemInHand(hand);
        if (item.is(WinLandCraft.TASKS)) {
            if (c.player.isShiftKeyDown()) apps.tasks.close(); else apps.tasks.open(c);
            return;
        }
        if (item.is(WinLandCraft.APPS)) {
            if (c.player.isShiftKeyDown()) apps.launcher.close(); else apps.openApps();
            return;
        }
        if (!item.is(WinLandCraft.WINDOW_DRAG)) return;
        Hit hit = pick(c,MOVE_RANGE);
        if (hit == null) { message("Point at a window to drag it."); return; }
        if (!hit.panel.canMove()) { message("This shared window is positioned by its owner."); return; }
        beginMove(hit, c.gameRenderer.getMainCamera(), hand);
    }
    private void beginMove(Hit hit, Camera camera, InteractionHand hand) {
        stopTyping();
        dragging = hit.panel.dragTarget();
        dragHand = hand;
        distance = hit.distance;
        Quaternionf oldRotation=new Quaternionf(dragging.orientation);
        dragging.orientation = PanelRotation.allowed(dragging.orientation);
        WindowGroups.moved(dragging,dragging.position,oldRotation);
        Vec3 offset = hit.point.subtract(dragging.position);
        grabOffset = new Vector3f((float) offset.x, (float) offset.y, (float) offset.z)
                .rotate(new Quaternionf(dragging.orientation).conjugate());
        relativeRotation = PanelRotation.allowed(camera.rotation()).conjugate().mul(dragging.orientation);
    }
    public boolean mouseButton(int button, int action) {
        Minecraft c = Minecraft.getInstance();
        if (action == GLFW.GLFW_RELEASE && buttons.remove(button)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if(fileDragging&&draggedFile!=null&&active(c)) {
                    Hit target=pick(c);
                    if(target!=null&&target.panel.acceptsFileDrop()) {target.panel.dropFile(draggedFile);message("Opening "+draggedFile.getFileName()+" in Notepad.");}
                    else message("File drop canceled. Drop onto an open Notepad panel.");
                }
                draggedFile=null;fileDragging=false;
                curving=false;
                resizing = null;
                if (dragHand == null) dragging = null;
            }
            if (pointer != null) pointer.mouseUp(pointerX, pointerY, button);
            if (buttons.isEmpty()) pointer = null;
            return true;
        }
        if (!active(c)) return false;
        if (action != GLFW.GLFW_PRESS) return false;
        if (resizing != null || dragging != null || curving) { buttons.add(button); return true; }
        // Control items keep their right-click actions, including Window Drag.
        if (pointer == null && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && (WinLandCraft.isControl(c.player.getMainHandItem())
                || c.player.getMainHandItem().isEmpty() && WinLandCraft.isControl(c.player.getOffhandItem()))) return false;
        if (pointer == null && c.player.isUsingItem()) return false;
        Hit hit = pick(c);
        if(pointer==null && hit!=null && hit.panel==curveUi) {
            buttons.add(button);
            if(button==GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                stopTyping(); curving=true;
                var relative=WindowGroups.local(curveUi,c.gameRenderer.getMainCamera().getPosition());
                curveOwner.curve.facing=relative.z>=0?1:-1;
                setCurve(hit.point);
            }
            return true;
        }
        if (pointer == null && hit != null && corner(hit.panel,hit.point) != 0 && button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            buttons.add(button); return true;
        }
        if (pointer == null && hit != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int[] at = hit.panel.pixelAt(hit.point);
            if (suggestion != null && suggestion.a()==hit.panel && suggestion.contains(at[0],at[1])) {
                WindowGroups.join(suggestion.a(),suggestion.b(),suggestion.edge()); suggestion=null; buttons.add(button); return true;
            }
            int corner = corner(hit.panel,hit.point);
            if (corner != 0) {
                stopTyping();
                resizing = new PanelResize(hit.panel,corner,hit.point,scalingHeld());
                buttons.add(button);
                return true;
            }
            int[] pixel = hit.panel.pixelAt(hit.point);
            int titlebar = hit.panel.titlebarAction(pixel[0], pixel[1]);
            if (titlebar != 0) {
                buttons.add(button);
                if (titlebar == 2) { stopTyping(); hit.panel.close(); }
                else if (titlebar == 3) WindowGroups.detach(hit.panel);
                else beginMove(hit, c.gameRenderer.getMainCamera(), null);
                return true;
            }
        }
        if (pointer == null && hit != null) {
            pointer = hit.panel;
            int[] pixel = pointer.pixelAt(hit.point); pointerX = pixel[0]; pointerY = pixel[1];
            if (focused != pointer) { stopTyping(); focused = pointer; }
        }
        if (pointer != null) {
            if (buttons.add(button)) {
                pointer.mouseDown(pointerX, pointerY, button);
                if(button==GLFW.GLFW_MOUSE_BUTTON_LEFT){draggedFile=pointer.dragFileAt(pointerX,pointerY);fileStartX=pointerX;fileStartY=pointerY;fileStartRay=direction(c.gameRenderer.getMainCamera());}
            }
            if (pointer.wantsKeyboard() && !typing) {
                focused = pointer;
                KeyMapping.releaseAll(); typing = true;focused.keyboardStarted();
                message("Typing in window — Esc returns to Minecraft.");
            }
            return true;
        }
        return false;
    }
    public boolean scroll(double amount) {
        var c = Minecraft.getInstance();
        if (!active(c)) return false;
        if(fileDragging)return true;
        if(curving) return true;
        if (dragging != null) { distance = moveDistance(distance, amount); return true; }
        if (resizing != null) return true;

        Hit hit = pick(c);
        if (hit != null) {
            if(hit.panel==curveUi) return true;
            if (corner(hit.panel,hit.point) != 0) return true;
            int[] pixel = hit.panel.pixelAt(hit.point);
            hit.panel.scroll(pixel[0], pixel[1], amount);
            return true;
        }
        return pointer != null || typing;
    }
    public boolean key(int key, int scan, int action, int modifiers) {
        var c = Minecraft.getInstance();
        if (!active(c)) return false;
        if(draggedFile!=null&&key==GLFW.GLFW_KEY_ESCAPE){releasePointer();stopTyping();message("File drop canceled.");return true;}
        if (key == activationKey) {
            if (action == GLFW.GLFW_RELEASE) activationKey = -1;
            return true;
        }
        if (!typing) {
            if (action == GLFW.GLFW_PRESS && typingKey.matches(key, scan)) {
                Hit hit = pick(c);
                if (hit != null && hit.panel.acceptsKeyboard()) focused = hit.panel;
                if (focused != null && focused.isOpen() && focused.acceptsKeyboard()) {
                    KeyMapping.releaseAll(); typing = true; activationKey = key;focused.keyboardStarted();
                    message("Typing in window — Esc returns to Minecraft.");
                } else message("Click a browser window before enabling typing.");
                return true;
            }
            return false;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) { stopTyping(); message("Minecraft controls restored."); return true; }
        if (action == GLFW.GLFW_PRESS) {
            keys.put(key, scan); focused.key(key, scan, action, modifiers);
        } else if (action == GLFW.GLFW_REPEAT && keys.containsKey(key)) focused.key(key, scan, action, modifiers);
        else if (action == GLFW.GLFW_RELEASE && keys.remove(key) != null) focused.key(key, scan, action, modifiers);
        return true;
    }
    public boolean character(int codepoint, int modifiers) {
        if (!typing || !active(Minecraft.getInstance())) return false;
        if (activationKey != -1) return true;
        for (char character : Character.toChars(codepoint)) focused.character(character, modifiers);
        return true;
    }
    public boolean isTyping() { return typing; }
    static double moveDistance(double current, double wheel) { return Math.clamp(current + wheel * Math.max(.25,(current-32)*.05), 0.5, MOVE_RANGE); }
    public boolean blocksWorldActions() {
        var client = Minecraft.getInstance();
        return active(client) && (pointer != null || resizing != null || dragging != null || curving || pick(client) != null);
    }
    public int cursor() {
        var client = Minecraft.getInstance();
        if (!active(client) || client.options.hideGui) return 0;
        int corner = resizing != null ? resizing.corner : hoveredCorner;
        if (corner != 0) {
            int diagonal=((corner&1)!=0)==((corner&4)!=0)?3:2;
            return (resizing!=null?resizing.scaling():scalingHeld())?diagonal+2:diagonal;
        }
        return hovered != null || pointer != null || dragging != null || curving ? 1 : 0;
    }
    public void renderResizeHint(net.minecraft.client.gui.GuiGraphics graphics) {
        var client = Minecraft.getInstance();
        WorldPanel target = active(client) && !client.options.hideGui && resizing == null && dragging == null
                && pointer == null && !curving && hoveredCorner != 0 ? hovered : null;
        long now = System.nanoTime();
        if (target == null || target != hintPanel || hintCorner != hoveredCorner) {
            hintPanel = target; hintCorner = hoveredCorner; hintSince = now;
            return;
        }
        if (now - hintSince < 2_000_000_000L) return;
        String text = "Hold CTRL to scale instead of resize";
        int x = (graphics.guiWidth() - client.font.width(text)) / 2, y = graphics.guiHeight() / 2 + 20;
        graphics.fill(x-5,y-4,x+client.font.width(text)+5,y+13,0xE018212D);
        graphics.drawString(client.font,text,x,y,0xFFE1F2FA,true);
    }
    public void renderHandles(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context) {
        if(fileDragging&&draggedFile!=null) {
            var client=Minecraft.getInstance();Hit target=pick(client);boolean accepted=target!=null&&target.panel.acceptsFileDrop();
            var camera=context.camera();
            Vec3 position=camera.getPosition().add(direction(camera).scale(.65));
            try(var badge=new PanelCanvas(context,position,camera.rotation(),.0015f,.0015f,260,32)) {
                badge.rect(12,18,260,32,.4f,accepted?0xEE25684B:0xEE334958);
                badge.text(client.font.plainSubstrByWidth((accepted?"Open: ":"File: ")+draggedFile.getFileName(),244),20,29,-1);
            }
            return;
        }
        var panel = resizing != null ? resizing.panel : hovered;
        if(panel!=null && panel!=curveUi) {
            boolean scaling=resizing!=null?resizing.scaling():scalingHeld();
            if(panel.curve!=null) for(var member:WindowGroups.members(panel)) member.renderResizeHandles(context,scaling);
            else (panel.grouped()?WindowGroups.frame(panel):panel).renderResizeHandles(context,scaling);
        }
        if(curveUi!=null && curveOwner!=null && curveOwner.curve!=null) try(var canvas=curveUi.canvas(context)) {
            if(canvas!=null) {
                float amount=curveOwner.curve.amount;
                canvas.rect(0,0,360,48,.3f,0xEE243C4A);
                canvas.text("Curve",12,17,0xFFE1F2FA,1.5f);
                canvas.rect(100,22,210,4,.4f,0xFF69808A);
                canvas.rect(100,22,210*amount,4,.45f,0xFF51CFDF);
                canvas.rect(96+210*amount,14,8,20,.5f,0xFFAAEDF5);
                canvas.text(Integer.toString(Math.round(amount*100)),320,18,0xFFE1F2FA);
            }
        }
        if (suggestion != null) try(var canvas=suggestion.a().canvas(context)) {
            if(canvas!=null) {
                canvas.rect(suggestion.x(),suggestion.y(),88,28,0.5f,0xEE386776);
                canvas.text("Group",suggestion.x()+17,suggestion.y()+7,0xFFFFFFFF,1.5f);
            }
        }
    }
    public void update(Camera camera) {
        var c = Minecraft.getInstance(); tick(c);
        if (!active(c)) return;
        if(draggedFile!=null&&pointer!=null) {
            Hit target=pick(c);
            if(target==null||target.panel!=pointer||fileStartRay.dot(direction(camera))<.99995
                    ||Math.abs(pointerX-fileStartX)>8||Math.abs(pointerY-fileStartY)>8)fileDragging=true;
            if(fileDragging){suggestion=null;clearCurve();hoveredCorner=0;return;}
        }
        if(!curving && System.nanoTime()>curveUntil) clearCurve();
        if(curving && curveUi!=null) {
            double t=curveUi.planeDistance(camera.getPosition(),direction(camera));
            if(Double.isFinite(t) && t<Math.max(64,ModSettings.interactionRange(c.player.blockInteractionRange()))) setCurve(camera.getPosition().add(direction(camera).scale(t)));
            hoveredCorner=0; return;
        }
        if (suggestion != null && (!suggestion.a().isOpen() || !suggestion.b().isOpen() || WindowGroups.members(suggestion.a()).contains(suggestion.b()))) suggestion=null;
        if (dragging != null) {
            Vec3 oldPosition=dragging.position; Quaternionf oldRotation=new Quaternionf(dragging.orientation);
            dragging.orientation = PanelRotation.allowed(camera.rotation()).mul(relativeRotation);
            Vector3f offset = new Vector3f(grabOffset).rotate(dragging.orientation);
            dragging.position = camera.getPosition().add(direction(camera).scale(distance)).subtract(offset.x, offset.y, offset.z);
            WindowGroups.moved(dragging,oldPosition,oldRotation);
        }
        if (resizing != null) {
            double t = resizing.planeDistance(camera.getPosition(), direction(camera));
            if (Double.isFinite(t) && t < Math.max(64,ModSettings.interactionRange(c.player.blockInteractionRange()))) resizing.move(camera.getPosition().add(direction(camera).scale(t)));
        }
        apps.syncAttachments();
        hoveredCorner = 0;
        if (dragging != null || resizing != null || pointer != null) suggestion=null;
        if(dragging!=null || resizing!=null) clearCurve();
        WorldPanel nextHover = null;
        if (resizing != null) {
            nextHover = resizing.panel;
        } else if (pointer != null) {
            double t = pointer.planeDistance(camera.getPosition(), direction(camera));
            if (Double.isFinite(t)) {
                int[] pixel = pointer.pixelAt(camera.getPosition().add(direction(camera).scale(t)));
                pointerX = pixel[0]; pointerY = pixel[1];
                pointer.hover(pointerX, pointerY);
            }
            nextHover = pointer;
        } else {
            Hit hit = pick(c);
            if (hit != null) {
                if(hit.panel==curveUi) curveUntil=System.nanoTime()+1_500_000_000L;
                else if(dragging==null && GroupCurve.eligible(hit.panel)) {
                    var curve=GroupCurve.get(hit.panel);
                    if(curve.nearBottom(hit.panel,hit.point)) {
                        curveOwner=hit.panel; curveUi=curve.control(); curveUntil=System.nanoTime()+1_500_000_000L;
                    }
                }
                nextHover = hit.panel;
                hoveredCorner = corner(hit.panel,hit.point);
                if (dragging==null) {
                    int[] at=hit.panel.pixelAt(hit.point);
                    if (suggestion==null || suggestion.a()!=hit.panel || !suggestion.contains(at[0],at[1]))
                        suggestion=WindowGroups.suggest(hit.panel,hit.point,apps.windows);
                }
                int[] pixel = hit.panel.pixelAt(hit.point); hit.panel.hover(pixel[0], pixel[1]);
            }
            else suggestion=null;
        }
        if (hovered != null && hovered != nextHover && hovered.isOpen()) hovered.hover(-1, -1);
        hovered = nextHover;
    }
    private Hit pick(Minecraft c) {
        return pick(c,ModSettings.interactionRange(c.player.blockInteractionRange()));
    }
    private Hit pick(Minecraft c,double range) {
        if (c.options.hideGui) return null;
        apps.syncAttachments();
        Camera camera = c.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition(), ray = direction(camera);
        double nearest = range;
        WorldPanel target = null;
        if(curveUi!=null) {
            double hit=curveUi.intersect(origin,ray);
            if(hit<nearest) {nearest=hit;target=curveUi;}
        }
        for (WorldPanel window : apps.windows) {
            if (window.level != c.level || !window.canInteract()) continue;
            double hit = window.pointerDistance(origin, ray);
            if(window.grouped() && window.curve==null) {
                // Suppress internal member handles, and add the group's exterior handles.
                hit=window.intersect(origin,ray);
                var frame=WindowGroups.frame(window);
                double t=frame.planeDistance(origin,ray);
                if(Double.isFinite(t) && frame.resizeCorner(origin.add(ray.scale(t)),origin)!=0) hit=Math.min(hit,t);
            }
            if(window.curve!=null) {
                hit=window.intersect(origin,ray);
                double t=window.planeDistance(origin,ray);
                if(t<nearest && WindowGroups.corner(window,origin.add(ray.scale(t)),origin)!=0) hit=Math.min(hit,t);
            }
            // Aiming through a narrow seam must still reveal the nearby Group button.
            if(!Double.isFinite(hit) && window.isOpen() && (curveUi==null || target!=curveUi)) {
                double t=window.planeDistance(origin,ray);
                if(t<nearest) {
                    Vec3 point=origin.add(ray.scale(t));
                    if(WindowGroups.suggest(window,point,apps.windows)!=null || GroupCurve.eligible(window) && GroupCurve.get(window).nearBottom(window,point)) hit=t;
                }
            }
            if (hit < nearest) { nearest = hit; target = window; }
        }
        if (target == null) return null;
        Vec3 point = origin.add(ray.scale(nearest));
        var block = c.level.clip(new ClipContext(origin, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, c.player));
        if (block.getType() != HitResult.Type.MISS && origin.distanceTo(block.getLocation()) + 0.01 < nearest) return null;
        if (c.hitResult != null && c.hitResult.getType() == HitResult.Type.ENTITY
                && origin.distanceTo(c.hitResult.getLocation()) < nearest) return null;
        return new Hit(target, point, nearest);
    }
    private int corner(WorldPanel panel,Vec3 point) {
        return WindowGroups.corner(panel,point,Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }
    private boolean scalingHeld() {
        long window=Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetKey(window,GLFW.GLFW_KEY_LEFT_CONTROL)==GLFW.GLFW_PRESS
                ||GLFW.glfwGetKey(window,GLFW.GLFW_KEY_RIGHT_CONTROL)==GLFW.GLFW_PRESS;
    }
    private void setCurve(Vec3 point) {
        if(curveUi==null || curveOwner==null || curveOwner.curve==null) return;
        int[] at=curveUi.pixelAt(point);
        curveOwner.curve.apply((at[0]-100)/210f);
        curveUntil=System.nanoTime()+1_500_000_000L;
    }
    private static Vec3 direction(Camera camera) {
        Vector3f vector = new Vector3f(0, 0, -1).rotate(camera.rotation());
        return new Vec3(vector.x, vector.y, vector.z);
    }
    private static void message(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(text), true);
    }
    private record Hit(WorldPanel panel, Vec3 point, double distance) { }
}
