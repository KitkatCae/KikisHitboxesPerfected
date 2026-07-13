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
 * Phase 2D, Part B — precise per-part frustum culling.
 *
 * <p>Targets {@code EntityRenderer.shouldRender}, not
 * {@code EntityRenderDispatcher.shouldRender} — the dispatcher method (bytecode-
 * confirmed) just delegates straight to this one, which is where the real test
 * lives:
 * <pre>
 * if (!entity.shouldRender(camX, camY, camZ)) return false;
 * if (entity.noCulling) return true;
 * AABB aabb = entity.getBoundingBoxForCulling().inflate(0.5);
 * // NaN/zero-size guard, then:
 * return frustum.isVisible(aabb);
 * </pre>
 * (both {@code Entity.shouldRender(double,double,double)} and {@code Entity.noCulling}
 * are public, confirmed against the mapped jar).
 *
 * <p>This injects at HEAD, cancellable. When no precise part geometry is
 * resolvable for the entity (disabled, no snapshot, over the part cap), it does
 * nothing and lets vanilla's method body run unmodified — so the two gate checks
 * above are only ever duplicated here on the path where this mixin is actually
 * about to supply its own answer, not on every call.
 *
 * <p>When parts ARE available, {@code frustum.isVisible(AABB)} (bytecode-confirmed
 * the only public visibility test {@link Frustum} exposes) is tested per part
 * instead of once against the union box; the entity is visible if ANY part is.
 * Since the union of all parts always contains every individual part, this can
 * only cull entities the union-box test would have kept — never the reverse — so
 * it is strictly a tightening, not a behavior change for anything currently
 * rendering correctly.
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
