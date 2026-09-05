package dev.winlandcraft;

import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.world.phys.Vec3;

final class FileWindowChecks {
    static void run() {
        var apps=new AppWindows();var pool=new ArrayList<WorldPanel>();
        int original=apps.windows.size();
        for(int i=0;i<16;i++) {
            var panel=apps.fileWindow(pool,()->new WorldPanel(2,1){});
            check(panel!=null,"file window allocated below limit");panel.position=Vec3.ZERO;
        }
        check(apps.windows.size()==original+16,"new windows registered exactly once");
        check(apps.fileWindow(pool,()->{throw new AssertionError("over-limit allocation");})==null,"open window limit");
        var closed=pool.get(3);closed.position=null;
        check(apps.fileWindow(pool,()->{throw new AssertionError("closed window replaced");})==closed,"closed window reused at capacity");
        check(apps.windows.size()==original+16&&pool.size()==16,"reuse does not duplicate registration");
        var otherPool=new ArrayList<WorldPanel>();
        check(apps.fileWindow(otherPool,()->new WorldPanel(2,1){})!=null,"per-app limits remain independent");
        var source=new FileManagerPanel();
        for(String id:new String[]{"winlandcraft:notepad","winlandcraft:image_viewer","winlandcraft:video_player"})
            check(!apps.openFile(id,source,Path.of("test.txt"),FileAppPlacement.Side.RIGHT),"closed source rejected without allocating");
        source.position=Vec3.ZERO;
        check(!apps.openFile("winlandcraft:image_viewer",source,Path.of("test.txt"),FileAppPlacement.Side.RIGHT),"unsupported file rejected");
        check(!apps.openFile("unknown:app",source,Path.of("test.txt"),FileAppPlacement.Side.RIGHT),"unknown plugin handler rejected");
        System.out.println("File windows: per-app bounds, closed-window reuse, registration and rejected requests passed.");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
