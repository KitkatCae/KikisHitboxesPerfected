package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.collision.AabbResize;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/** AABB resize hook; {@code makeBoundingBox()} runs on every position update. See {@link AabbResize}. */
@Mixin(Entity.class)
public abstract class EntityMakeBoundingBoxMixin {

    @Inject(method = "makeBoundingBox", at = @At("RETURN"), cancellable = true)
    private void khp$resizeBoundingBox(CallbackInfoReturnable<AABB> cir) {
        Entity self = (Entity) (Object) this;
        @Nullable AABB resized = AabbResize.resizedBoxOrNull(self);
        if (resized != null) {
            cir.setReturnValue(resized);
        }
    }
}
