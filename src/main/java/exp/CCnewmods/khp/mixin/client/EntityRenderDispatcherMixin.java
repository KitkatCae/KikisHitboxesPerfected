package exp.CCnewmods.khp.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens and closes the capture bracket around every entity render. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private <E extends Entity> void mah$enterBracket(E entity, double x, double y, double z,
                                                     float rotationYaw, float partialTick,
                                                     PoseStack poseStack, MultiBufferSource buffer,
                                                     int packedLight, CallbackInfo ci) {
        EntityRenderDispatcher self = (EntityRenderDispatcher) (Object) this;
        EntityRenderer<? super E> renderer = self.getRenderer(entity);
        HitboxCaptureManager.enterBracket(entity, renderer, x, y, z, partialTick, poseStack);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private <E extends Entity> void mah$exitBracket(E entity, double x, double y, double z,
                                                    float rotationYaw, float partialTick,
                                                    PoseStack poseStack, MultiBufferSource buffer,
                                                    int packedLight, CallbackInfo ci) {
        HitboxCaptureManager.exitBracket();
    }
}
