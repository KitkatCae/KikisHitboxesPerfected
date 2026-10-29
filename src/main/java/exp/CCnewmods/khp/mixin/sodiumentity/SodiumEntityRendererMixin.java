package exp.CCnewmods.khp.mixin.sodiumentity;

import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import exp.CCnewmods.khp.compat.embeddium.SodiumTreeRenderCapture;
import me.jellysquid.mods.sodium.client.render.immediate.model.EntityRenderer;
import me.jellysquid.mods.sodium.client.render.immediate.model.ModelCuboid;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Capture for Oculus + Embeddium, where Oculus cancels {@code ModelPart.render} and draws through Embeddium's
 * {@code EntityRenderer.render} tree walk instead. Never fires without Oculus.
 */
@Mixin(value = EntityRenderer.class, remap = false)
public abstract class SodiumEntityRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private static void khp$enterTreePart(PoseStack poseStack, VertexBufferWriter writer, ModelPart part,
                                          int light, int overlay, int color, CallbackInfo ci) {
        HitboxCaptureManager.enterSodiumTreePart(part);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private static void khp$exitTreePart(PoseStack poseStack, VertexBufferWriter writer, ModelPart part,
                                         int light, int overlay, int color, CallbackInfo ci) {
        HitboxCaptureManager.exitSodiumTreePart();
    }

    @Inject(method = "renderCuboids", at = @At("HEAD"), remap = false)
    private static void khp$captureCuboids(PoseStack.Pose pose, VertexBufferWriter writer, ModelCuboid[] cuboids,
                                           int light, int overlay, int color, CallbackInfo ci) {
        SodiumTreeRenderCapture.captureCuboids(pose, cuboids);
    }
}
