package exp.CCnewmods.khp.mixin.client;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.collision.PreciseWorldParts;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Per-part frustum culling: visible if any part is in view. Only ever culls more than vanilla's whole-box test;
 * entities without part data fall through to vanilla.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCullMixin<T extends Entity> {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void khp$preciseCull(T entity, Frustum frustum, double camX, double camY, double camZ,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (!MahConfig.enabled || !MahConfig.cullingEnabled) {
            return;
        }

        List<AABB> parts = PreciseWorldParts.resolveOrNull(entity, MahConfig.cullingMaxParts);
        if (parts == null) {
            return;
        }

        if (!entity.shouldRender(camX, camY, camZ)) {
            cir.setReturnValue(false);
            return;
        }
        if (entity.noCulling) {
            cir.setReturnValue(true);
            return;
        }

        boolean anyVisible = false;
        for (AABB part : parts) {
            AABB inflated = part.inflate(0.5);
            if (inflated.hasNaN() || inflated.getSize() == 0.0) {
                continue;
            }
            if (frustum.isVisible(inflated)) {
                anyVisible = true;
                break;
            }
        }
        cir.setReturnValue(anyVisible);
    }
}
