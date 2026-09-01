package dev.winlandcraft;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Warps panel pixels after their local text/icon transforms but before world rendering. */
final class CurvedVertices implements VertexConsumer {
    private final VertexConsumer target;
    private final WorldPanel panel;
    private final Matrix4f inverse,world;
    private final Vec3 camera;
    CurvedVertices(VertexConsumer target,WorldPanel panel,Matrix4f inverse,Matrix4f world,Vec3 camera) {
        this.target=target;this.panel=panel;this.inverse=inverse;this.world=world;this.camera=camera;
    }
    @Override public VertexConsumer addVertex(float x,float y,float z) {
        var pixel=inverse.transformPosition(new Vector3f(x,y,z));
        var point=panel.curve.panelPoint(panel,(pixel.x/panel.pixelWidth()-.5f)*panel.worldWidth(),
                (.5f-pixel.y/panel.pixelHeight())*panel.worldHeight(),pixel.z*panel.worldWidth()/panel.pixelWidth()).subtract(camera);
        var vertex=world.transformPosition(new Vector3f((float)point.x,(float)point.y,(float)point.z));
        target.addVertex(vertex.x,vertex.y,vertex.z);return this;
    }
    @Override public VertexConsumer setColor(int r,int g,int b,int a){target.setColor(r,g,b,a);return this;}
    @Override public VertexConsumer setUv(float u,float v){target.setUv(u,v);return this;}
    @Override public VertexConsumer setUv1(int u,int v){target.setUv1(u,v);return this;}
    @Override public VertexConsumer setUv2(int u,int v){target.setUv2(u,v);return this;}
    @Override public VertexConsumer setNormal(float x,float y,float z){target.setNormal(x,y,z);return this;}
}
