package dev.winlandcraft;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Per-frame world submission scope. Replaces the fabric WorldRenderContext. */
public record WorldPass(SubmitNodeCollector collector, PoseStack pose, CameraRenderState camera) {
}
