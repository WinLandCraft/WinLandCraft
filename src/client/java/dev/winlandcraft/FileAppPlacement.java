package dev.winlandcraft;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class FileAppPlacement {
    enum Side { LEFT, RIGHT, ABOVE, BELOW }
    private static final Set<String> TEXT=Set.of("txt","md","markdown","log","json","jsonc","xml","yaml","yml","toml","ini","cfg","conf","properties","csv","tsv","java","kt","py","js","ts","jsx","tsx","html","htm","css","scss","sql","sh","bat","cmd","ps1","rs","c","h","cpp","hpp","cs","go","lua","svg");
    static boolean supported(Path path) {
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot=name.lastIndexOf('.');
        return TEXT.contains(name.substring(dot+1))||Set.of("readme","license","licence","makefile","dockerfile",".gitignore",".gitattributes",".editorconfig").contains(name);
    }
    static void place(WorldPanel source,WorldPanel target,Side side,Vec3 observer) {
        int sx=side==Side.LEFT?-1:side==Side.RIGHT?1:0,sy=side==Side.BELOW?-1:side==Side.ABOVE?1:0;
        float x=sx*source.worldWidth()/2,y=sy*source.worldHeight()/2;
        Quaternionf basis=new Quaternionf(source.orientation);
        Vec3 edge=WindowGroups.world(source.position,basis,x,y);
        if(source.curve!=null) {
            var curve=source.curve;edge=curve.panelPoint(source,x,y,0);
            float arcX=curve.layout.get(source).x()+x;
            basis=new Quaternionf(curve.rotation).rotateY(curve.amount<.0001f?0:-curve.facing*arcX/curve.radius());
        }
        var normal=new Vector3f(0,0,1).rotate(basis);
        float facing=observer.subtract(edge).dot(new Vec3(normal.x,normal.y,normal.z))<0?-1:1;
        float angle=(float)Math.toRadians(28)*facing;
        target.orientation=new Quaternionf(basis).rotateY(-sx*angle).rotateX(sy*angle);
        var gap=new Vector3f(sx*.08f,sy*.08f,0).rotate(basis);
        var offset=new Vector3f(sx*target.worldWidth()/2,sy*target.worldHeight()/2,0).rotate(target.orientation);
        target.position=edge.add(gap.x+offset.x,gap.y+offset.y,gap.z+offset.z);
        target.level=source.level;
    }
}
