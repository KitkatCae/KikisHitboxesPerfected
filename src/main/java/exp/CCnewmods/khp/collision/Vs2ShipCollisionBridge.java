package exp.CCnewmods.khp.collision;

import exp.CCnewmods.khp.Mah;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every VS2 type reference used by {@link exp.CCnewmods.khp.mixin.EntityCollideEntityShapesMixin}'s
 * ship-collision composition lives here, and only here — see that mixin's own
 * javadoc for why. The JVM resolves referenced class types at class-verification
 * time, not lazily per-branch, so a VS2 type reference sitting directly inside a
 * core, always-applied mixin would fail that mixin's class load entirely without
 * VS2 present, even inside a guarded {@code if}. Isolating those references into
 * this separate class means it's only ever loaded (and its imports resolved) the
 * first time {@link #adjustForShipCollisions} is actually called — which the
 * mixin only does after confirming {@code ModList.get().isLoaded("valkyrienskies")}
 * — same established pattern already used by
 * {@code exp.CCnewmods.khp.compat.MahCompatBootstrap} for other optional mods.
 *
 * <p>{@code adjustEntityMovementForShipCollisions} — bytecode-confirmed this
 * session as the actual entry point VS2's own {@code MixinEntity.collideWithShips}
 * calls (a {@code @WrapOperation} on the same {@code Entity.collide(Vec3)} call
 * site {@code EntityCollideEntityShapesMixin} already {@code @Redirect}s, which is
 * why VS2's own hook was confirmed via diagnostic logging to never fire in this
 * modpack — see {@code KHP_Phase2E_Design.md} §16/§17). Calling it explicitly from
 * here, from within the mixin that's confirmed to actually control movement
 * resolution, restores the behavior VS2's bypassed hook would have provided.
 */
public final class Vs2ShipCollisionBridge {

    /**
     * Mirrors what VS2's own (bypassed) {@code collideWithShips} does with this
     * same call — clips {@code movement} against any ship the entity's bounding
     * box (expanded by the movement) intersects. Does <b>not</b> replicate VS2's
     * own sub-step subdivision for fast-moving entities (its
     * {@code collideWithShips} splits movement into ≤0.5-block steps) — a known
     * simplification, not yet needed for the mob-speed cases this was built for,
     * flagged in case a fast-moving entity ever tunnels through a platform.
     */
    // TEMP DIAGNOSTIC — confirms VS2's own method call completed without throwing,
    // independent of whether any mixin-based diagnostic elsewhere fires (rules out
    // a silently-swallowed exception as the explanation if this class's own call
    // site diagnostics fire but VS2's internal mixin diagnostics don't). Remove
    // once resolved.
    private static final AtomicBoolean khp$completedConfirmed = new AtomicBoolean(false);

    public static Vec3 adjustForShipCollisions(Entity entity, Vec3 movement, AABB entityBoundingBox) {
        Vec3 result = EntityShipCollisionUtils.INSTANCE.adjustEntityMovementForShipCollisions(
                entity, movement, entityBoundingBox, entity.level());
        if (khp$completedConfirmed.compareAndSet(false, true)) {
            Mah.LOGGER.info("[KHP DIAG] Vs2ShipCollisionBridge: VS2's adjustEntityMovementForShipCollisions "
                    + "returned without throwing (first call, input={}, output={})", movement, result);
        }
        return result;
    }

    private Vs2ShipCollisionBridge() {
    }
}
