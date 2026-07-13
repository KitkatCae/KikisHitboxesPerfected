package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.collision.AabbResize;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * AABB resize (replaces Phase 2D Part A3) — see {@link AabbResize}'s own javadoc
 * for the full design and reasoning.
 *
 * <p>Targets {@code Entity#makeBoundingBox()} (confirmed against the mapped jar:
 * {@code protected AABB makeBoundingBox() { return this.dimensions.makeBoundingBox(this.position); }}),
 * not {@code setBoundingBox} or {@code setPos} directly — this is the single
 * chokepoint {@code Entity#setPos(double,double,double)} (bytecode-confirmed:
 * {@code this.setPosRaw(...); this.setBoundingBox(this.makeBoundingBox());})
 * already calls on every position update, vanilla or modded, so overriding it
 * here covers movement, teleports, and spawning uniformly without needing to
 * chase every call site that moves an entity.
 *
 * <p>{@code @Inject(RETURN, cancellable)} rather than {@code @Overwrite}/
 * {@code @Redirect}: when {@link AabbResize#resizedBoxOrNull} returns
 * {@code null} (feature off, no snapshot, over the part cap), this does nothing
 * and vanilla's own computed return value passes through untouched.
 */
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
