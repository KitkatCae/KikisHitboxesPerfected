package exp.CCnewmods.khp.mixin.vs2;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.livingplatform.LivingPlatformShipManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KHP Phase 2E, §16 — the real fix for the living-platform collision-feedback
 * loop (§13's original {@code Block.getCollisionShape} approach never worked:
 * confirmed by decompiling VS2's actual collision pipeline this session —
 * ship-vs-entity collision goes through a pre-extracted polygon list fed into
 * {@code VsiEntityPolygonCollider}, never touching per-block/per-entity
 * {@code CollisionContext} at all).
 *
 * <p>Targets {@link EntityShipCollisionUtils#getShipPolygonsCollidingWithEntity}
 * (bytecode-confirmed method body this session) — specifically its call to the
 * static {@code mayShipIntersectLocalAabb(Ship, AABBdc)}, which is what decides
 * (via a {@code continue}) whether a given intersecting ship's polygons get
 * added to the result list at all for this collision query. Redirecting that
 * one call site lets a ship be skipped entirely for one specific entity — the
 * ship's own {@code LivingPlatformAttachment} owner — while every other
 * ship/entity combination goes through the exact same vanilla-VS2 logic
 * unmodified. Confirmed via decompile that this call site occurs exactly once
 * in the target method, so no ordinal disambiguation is needed.
 *
 * <p>{@code remap = false} — VS2 is a third-party mod jar, not part of the
 * SRG-mapped Forge/vanilla surface, matching this project's established
 * convention for third-party mixin targets (see {@code MahConfig}/mixin
 * conventions noted elsewhere in this codebase).
 *
 * <h3>Not fully verifiable without a live compile + launch</h3>
 * Static bytecode inspection can confirm the target method's body, the exact
 * call site, and that it's unique — it cannot confirm a Mixin transformer
 * actually applies cleanly at runtime (correct {@code @At} target descriptor,
 * no ordering conflict with another mod's mixin on the same call site — see
 * {@code EntityCollideEntityShapesMixin}'s own javadoc for a real precedent of
 * exactly that happening elsewhere in this codebase). If this doesn't fire,
 * {@code -Dmixin.debug.export=true} (already established project practice) is
 * the next step, same as any other mixin that doesn't apply as expected.
 */
@Mixin(value = EntityShipCollisionUtils.class, remap = false)
public abstract class EntityShipCollisionUtilsMixin {

    // TEMP DIAGNOSTIC — proves whether this @Redirect actually applies at runtime,
    // same pattern as EntityCollideEntityShapesMixin's own diagnostic (which exists
    // precisely because a redirect/inject can silently never fire in this modpack —
    // see that class's javadoc for the documented precedent). If this line never
    // appears after right-clicking a whitelisted mob, the redirect isn't installed
    // or isn't being reached, and everything downstream (the exemption logic itself)
    // is irrelevant until that's fixed first. Remove once resolved.
    private static final AtomicBoolean khp$redirectConfirmed = new AtomicBoolean(false);

    @Redirect(method = "getShipPolygonsCollidingWithEntity", at = @At(value = "INVOKE",
            target = "Lorg/valkyrienskies/mod/common/util/EntityShipCollisionUtils;mayShipIntersectLocalAabb(Lorg/valkyrienskies/core/api/ships/Ship;Lorg/joml/primitives/AABBdc;)Z"))
    private boolean khp$excludeOwningEntity(Ship ship, AABBdc aabb,
                                             Entity entity, Vec3 movement, AABB entityBoundingBox, Level world) {
        if (khp$redirectConfirmed.compareAndSet(false, true)) {
            Mah.LOGGER.info("[KHP DIAG] EntityShipCollisionUtilsMixin redirect is firing "
                    + "(first call, entity={}, level={})", entity != null ? entity.getType() : "null",
                    world.isClientSide() ? "client" : "server");
        }

        boolean vanillaResult = EntityShipCollisionUtils.mayShipIntersectLocalAabb(ship, aabb);
        if (!vanillaResult) return false;
        if (entity == null) return true;

        UUID owningEntityUuid = LivingPlatformShipManager.resolveOwningEntityUuid(ship);
        if (owningEntityUuid != null && owningEntityUuid.equals(entity.getUUID())) {
            Mah.LOGGER.info("[KHP DIAG] EntityShipCollisionUtilsMixin excluded ship {} from colliding with its "
                    + "own owning entity {} ({}).", ship.getId(), entity.getUUID(), entity.getType());
            return false; // treat as "doesn't intersect" — this ship's polygons never get gathered for this entity
        }
        return true;
    }
}
