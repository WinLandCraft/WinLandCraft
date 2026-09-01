package dev.winlandcraft;

import org.joml.Quaternionf;
import org.joml.Vector3f;

final class PanelRotation {
    /** Keep yaw, remove pitch/roll. The right axis is stable even when looking straight up. */
    static Quaternionf upright(Quaternionf rotation) {
        Vector3f right = new Vector3f(1, 0, 0).rotate(rotation);
        if (right.x * right.x + right.z * right.z < 0.000001f) {
            Vector3f normal = new Vector3f(0, 0, 1).rotate(rotation);
            return new Quaternionf().rotationY((float) Math.atan2(normal.x, normal.z));
        }
        return new Quaternionf().rotationY((float) Math.atan2(-right.z, right.x));
    }
    static Quaternionf allowed(Quaternionf rotation) {
        return ModSettings.freePanelRotation ? new Quaternionf(rotation) : upright(rotation);
    }
}
