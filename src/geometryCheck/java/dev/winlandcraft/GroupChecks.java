package dev.winlandcraft;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import java.util.List;

final class GroupChecks {
    static final class App extends WorldPanel {
        App(float x,float y) { super(2,1); position=new Vec3(x,y,-3); orientation=new Quaternionf(); }
        @Override public int pixelWidth(){return 800;}
        @Override public int pixelHeight(){return 400;}
        @Override public int titlebarHeight(){return 32;}
    }
    static void run() {
        var a=new App(0,0); var b=new App(2.1f,0); var c=new App(4.1f,0); var d=new App(6.1f,0); var e=new App(8.1f,0);
        check(WindowGroups.suggest(a,new Vec3(.99,0,-3),List.of(a,b))!=null,"side suggestion");
        check(WindowGroups.suggest(a,new Vec3(0,0,-3),List.of(a,b))==null,"no content hint");
        check(WindowGroups.suggest(a,new Vec3(1.05,0,-3),List.of(a,b))!=null,"hint through gap");
        b.orientation.rotateY(.8f);
        check(WindowGroups.suggest(a,new Vec3(.99,0,-3),List.of(a,b))==null,"unrelated angle");
        b.position=new Vec3(1.05+Math.cos(.8),0,-3-Math.sin(.8));
        var hint=WindowGroups.suggest(a,new Vec3(1.02,0,-3),List.of(a,b));
        check(hint!=null,"angled neighbors meeting at edge");
        var anchor=a.position; var anchorRotation=new Quaternionf(a.orientation);
        WindowGroups.join(a,b,hint.edge());
        near(0,a.position.distanceTo(anchor),"anchor position unchanged");
        near(1,Math.abs(a.orientation.dot(anchorRotation)),"anchor angle unchanged");
        near(1,Math.abs(a.orientation.dot(b.orientation)),"joining angle enforced");
        near(0,WindowGroups.local(a,b.position).z,"joining plane enforced");
        near(2,WindowGroups.local(a,b.position).x,"joining edge snapped");
        WindowGroups.detach(b); b.position=new Vec3(2.1,0,-3);
        b.orientation.identity();
        WindowGroups.join(a,b); WindowGroups.join(b,c); WindowGroups.join(c,d); WindowGroups.join(d,e);
        check(WindowGroups.members(a).size()==5,"chain merge");
        check(a.titlebarAction(680,-10)==3,"ungroup target");
        c.close();
        check(WindowGroups.members(a).size()==2 && WindowGroups.members(d).size()==2,"bridge close splits chain");
        WindowGroups.detach(b);
        check(!a.grouped()&&!b.grouped(),"singletons ungrouped");
        var old=d.position; var rotation=new Quaternionf(d.orientation);
        var oldDelta=e.position.subtract(d.position);
        d.position=d.position.add(3,2,1); d.orientation.rotateY(.5f); WindowGroups.moved(d,old,rotation);
        near(oldDelta.length(),e.position.distanceTo(d.position),"rigid move");
        near(1,Math.abs(d.orientation.dot(e.orientation)),"shared rotation");
        for(int sx:new int[]{-1,1}) for(int sy:new int[]{-1,1}) {
            a=new App(0,0); b=new App(2,0); WindowGroups.join(a,b);
            rotation=new Quaternionf(a.orientation); a.orientation.rotateXYZ(.3f,.5f,.2f);
            WindowGroups.moved(a,a.position,rotation);
            var frame=WindowGroups.frame(a);
            anchor=WindowGroups.world(frame.position,frame.orientation,-sx*frame.worldWidth()/2,-sy*frame.worldHeight()/2);
            var hit=WindowGroups.world(frame.position,frame.orientation,sx*(frame.worldWidth()/2+.01f),sy*(frame.worldHeight()/2+.01f));
            int corner=(sx<0?1:2)|(sy<0?4:8);
            check(WindowGroups.corner(a,hit)==corner,"group exterior corner");
            check(WindowGroups.corner(a,WindowGroups.world(a.position,a.orientation,1,.59f))==0,"internal corner disabled");
            var resize=new PanelResize(a,corner,hit);
            resize.move(WindowGroups.world(hit,a.orientation,sx*1f,sy*.54f));
            near(2.5,a.worldWidth(),"proportional first width"); near(2.5,b.worldWidth(),"proportional second width");
            near(1.5,a.worldHeight(),"proportional height");
            frame=WindowGroups.frame(a);
            var after=WindowGroups.world(frame.position,frame.orientation,-sx*frame.worldWidth()/2,-sy*frame.worldHeight()/2);
            near(0,anchor.distanceTo(after),"opposite group corner fixed");
            near(a.worldWidth(),b.position.distanceTo(a.position),"seam retained");
        }
        a=new App(0,0); b=new App(0,1.1f);
        check(WindowGroups.suggest(a,new Vec3(0,.57,-3),List.of(a,b))!=null,"stacked suggestion");
        WindowGroups.join(a,b); near(1.08,b.position.y-a.position.y,"stacked titlebar seam");
        a=new App(0,0); a.resize(2,2); b=new App(2,.4f); c=new App(4,.4f);
        WindowGroups.join(b,c); WindowGroups.join(a,b,1);
        near(2,b.worldHeight(),"incoming height matched"); near(2,c.worldHeight(),"incoming group height matched");
        near(0,b.position.y-a.position.y,"incoming height aligned");
        near(0,c.position.y-a.position.y,"incoming group aligned");
        near(2,b.worldWidth(),"matching height preserves width");
        var browser=new BrowserPanel(); browser.position=new Vec3(0,0,-3); browser.orientation=new Quaternionf();
        a=new App(3,0); WindowGroups.join(browser,a);
        near(browser.position.y+WindowGroups.top(browser),a.position.y+WindowGroups.top(a),"mixed titlebar top aligned");
        near(browser.position.y-browser.worldHeight()/2,a.position.y-a.worldHeight()/2,"mixed titlebar bottom aligned");
        var frame=WindowGroups.frame(browser);
        var hit=WindowGroups.world(frame.position,frame.orientation,frame.worldWidth()/2+.01f,frame.worldHeight()/2+.01f);
        var resize=new PanelResize(browser,10,hit);
        resize.move(hit.add(100,100,0));
        near(6.4,browser.worldWidth(),"group maximum width"); near(3.6,browser.worldHeight(),"browser maximum height");
        near(4,a.worldWidth(),"mixed group width ratio");
        resize.move(hit.add(-100,-100,0));
        near(1.3,browser.worldWidth(),"browser minimum width"); near(.8,browser.worldHeight(),"browser minimum height");
        check(a.worldWidth()>=a.minimumWidth()&&a.worldHeight()>=a.minimumHeight(),"all members respect minimums");
        System.out.println("Window groups: edge hints, chain splits, singleton cleanup, shared transforms, all resize corners and anchors passed.");
    }
    static void check(boolean result,String name){if(!result)throw new AssertionError(name);}
    static void near(double expected,double actual,String name){if(Math.abs(expected-actual)>.001)throw new AssertionError(name+": "+actual+" != "+expected);}
}
