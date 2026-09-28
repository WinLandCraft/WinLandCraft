package dev.winlandcraft;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Obstruction-aware spawn: scores camera-facing placements by their eye-to-panel view rays
 * (center plus four outer corners) and returns the first fully clear candidate. */
final class SpawnSpace {
    private static final double[] DISTANCES = {0, -0.7, 0.7, -1.3, 1.5};
    private static final double[] LATERAL = {0, -0.9, 0.9};
    private static final double[] VERTICAL = {0, 0.8, -0.8};
    record Placement(Vec3 position, boolean blocked) { }
    static Placement resolve(ClientLevel world, Camera camera, Vec3 fallback, Quaternionf orientation,
            float width, float top, float bottom) {
        var client = Minecraft.getInstance();
        if (client.player == null) return new Placement(fallback, false);
        Vec3 eye = camera.position();
        var look = new Vector3f(0, 0, -1).rotate(camera.rotation());
        var left = new Vector3f(camera.leftVector());
        var up = new Vector3f(camera.upVector());
        Vec3 best = fallback;
        int bestClear = clearRays(world, client.player, eye, fallback, orientation, width, top, bottom);
        if (bestClear == 5) return new Placement(fallback, false);
        double bestCost = 0;
        for (double distance : DISTANCES) for (double lateral : LATERAL) for (double vertical : VERTICAL) {
            if (distance == 0 && lateral == 0 && vertical == 0) continue;
            Vec3 candidate = fallback.add(look.x * distance + left.x * lateral + up.x * vertical,
                    look.y * distance + left.y * lateral + up.y * vertical,
                    look.z * distance + left.z * lateral + up.z * vertical);
            int clear = clearRays(world, client.player, eye, candidate, orientation, width, top, bottom);
            if (clear == 5) return new Placement(candidate, false);
            double cost = Math.abs(distance) + Math.abs(lateral) + Math.abs(vertical);
            if (clear > bestClear || clear == bestClear && cost < bestCost) {
                bestClear = clear;
                bestCost = cost;
                best = candidate;
            }
        }
        return new Placement(best, true);
    }
    /** True when any view ray to the placed window is cut by a block. */
    static boolean blocked(ClientLevel world, Player player, Vec3 eye, Vec3 position, Quaternionf orientation,
            float width, float top, float bottom) {
        return clearRays(world, player, eye, position, orientation, width, top, bottom) < 5;
    }
    private static int clearRays(ClientLevel world, Player player, Vec3 eye, Vec3 position, Quaternionf orientation,
            float width, float top, float bottom) {
        var right = new Vector3f(1, 0, 0).rotate(orientation);
        var up = new Vector3f(0, 1, 0).rotate(orientation);
        float half = width / 2;
        int clear = rayClear(world, player, eye, position) ? 1 : 0;
        double[][] corners = {{-half, top}, {half, top}, {-half, -bottom}, {half, -bottom}};
        for (var corner : corners) {
            Vec3 target = position.add(right.x * corner[0] + up.x * corner[1],
                    right.y * corner[0] + up.y * corner[1], right.z * corner[0] + up.z * corner[1]);
            if (rayClear(world, player, eye, target)) clear++;
        }
        return clear;
    }
    private static boolean rayClear(ClientLevel world, Player player, Vec3 eye, Vec3 target) {
        var hit = world.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return true;
        return eye.distanceTo(hit.getLocation()) + 0.05 >= eye.distanceTo(target);
    }
}
