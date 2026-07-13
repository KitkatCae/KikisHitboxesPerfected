package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.api.PartHitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Redirects vanilla's own entity hit-testing — both build-order items from Q2C-3's
 * "vanilla melee/arrows" — through {@link MahApi#raycastCombat}.
 *
 * <p>Both real redirect points live on the same class, {@code ProjectileUtil}, as
 * two genuinely different overloads of {@code getEntityHitResult}, confirmed via
 * decompile this session against the mapped Forge jar (not previously investigated
 * — {@code KHP_Phase2C_Design.md} §2/§6 flagged this as an open item):
 *
 * <ul>
 *   <li><b>Melee/crosshair picking</b> —
 *       {@code getEntityHitResult(Entity, Vec3, Vec3, AABB, Predicate, double)},
 *       called from {@code GameRenderer.pick()} (client-only) to decide what
 *       entity the crosshair is over — the result feeds directly into melee attack
 *       target selection. {@code pDistance} is a <b>squared</b> distance (confirmed
 *       from the method's own internal {@code distanceToSqr} comparisons) —
 *       {@code GameRenderer.pick()} independently re-validates the returned hit's
 *       distance against this same value <i>after</i> the call returns, so
 *       correctness here doesn't depend on this redirect enforcing that bound
 *       itself; it's still used to cap the MAH raycast's {@code maxDistance} as a
 *       sensible bound, not a correctness requirement.</li>
 *   <li><b>Arrow/projectile impact</b> —
 *       {@code getEntityHitResult(Level, Entity, Vec3, Vec3, AABB, Predicate,
 *       float)}, reached every tick an {@code AbstractArrow} is in flight via
 *       {@code getHitResultOnMoveVector} → the private {@code getHitResult}
 *       helper. This one runs on both sides — arrow damage is server-authoritative,
 *       so the server-side tick is the one that actually matters for gameplay.</li>
 * </ul>
 *
 * <h3>Remap convention — switched after a real crash elsewhere</h3>
 * Originally written with plain mapped (Parchment) names and default
 * {@code remap = true}, matching {@code ModelPartMixin}/
 * {@code LivingEntityRendererMixin}'s convention elsewhere in this codebase. A
 * separate mixin built the same way ({@code EntityHitboxOverlayMixin}, targeting a
 * private method) crashed at launch with
 * {@code InvalidMixinException: ... Using refmap khp.refmap.json} — the refmap
 * didn't have a usable entry for that target. Both methods here are {@code public},
 * so they may well have worked fine as originally written, but since real SRG names
 * are now available (Caelan's own {@code output.srg}, TSRG2, obf→SRG with real
 * class names) there's no reason to leave these on the convention that just failed
 * elsewhere — switched to {@code remap = false} + verified SRG names throughout,
 * matching this project's own stated critical mixin rule for vanilla targets.
 * Cross-matched by descriptor shape: melee overload
 * {@code (Lbfj;Leei;Leei;Leed;Ljava/util/function/Predicate;D)Leef;} →
 * {@code m_37287_}; arrow overload
 * {@code (Lcmm;Lbfj;Leei;Leei;Leed;Ljava/util/function/Predicate;F)Leef;} →
 * {@code m_150175_} (confirmed distinct from the 6-arg no-float delegate overload,
 * {@code m_37304_}, which is not the target here).
 */
@Mixin(value = ProjectileUtil.class, remap = false)
public abstract class ProjectileUtilMixin {

    @Inject(
            method = "m_37287_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)"
                    + "Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void khp$redirectMeleePick(Entity pShooter, Vec3 pStartVec, Vec3 pEndVec, AABB pBoundingBox,
                                                Predicate<Entity> pFilter, double pDistance,
                                                CallbackInfoReturnable<EntityHitResult> cir) {
        if (!MahConfig.enabled) return;

        Vec3 rayVec = pEndVec.subtract(pStartVec);
        double rayLength = rayVec.length();
        if (rayLength < 1.0e-6) return; // degenerate ray (pDistance == 0, "already touching"); let vanilla handle it

        // pDistance is squared (see class javadoc) — cap the raycast to it as a
        // sensible bound, not because correctness depends on it here.
        double maxDistance = Math.min(rayLength, Math.sqrt(Math.max(pDistance, 0.0)));
        if (maxDistance < 1.0e-6) return;

        Predicate<Entity> combatFilter = e -> e != pShooter && pFilter.test(e);
        List<PartHitResult> hits = MahApi.raycastCombat(pShooter.level(), pStartVec, rayVec, maxDistance, combatFilter);
        if (hits != null && !hits.isEmpty()) {
            PartHitResult nearest = hits.get(0);
            cir.setReturnValue(new EntityHitResult(nearest.entity(), nearest.worldEntryPoint()));
            cir.cancel();
            return;
        }

        // Watchlist-gated, no-op unless MahConfig.debugCombatMissWatchlist is
        // non-empty (Maze Mother investigation — design doc §8). Logged here
        // regardless of which branch below actually fires, so this catches both
        // "found as a candidate but the precise raycast missed" and "never found
        // as a candidate at all" — the latter is exactly what the old fixed-margin
        // search bug looked like, and nothing here proves Maze Mother isn't a second
        // instance of some other candidate-search gap.
        MahApi.logCombatMissDiagnostics(pShooter.level(), pStartVec, rayVec, maxDistance, combatFilter);

        if (MahApi.hasCombatCandidates(pShooter.level(), pStartVec, rayVec, maxDistance, combatFilter)) {
            // A MAH-trusted entity was in range and the precise raycast missed it —
            // a genuine miss. Cancel with no hit instead of falling through, or
            // vanilla's own (bigger, invisible) AABB test would still land a hit
            // here, exactly the "invisible but not disabled" bug.
            cir.setReturnValue(null);
            cir.cancel();
            return;
        }
        // nothing MAH covers nearby — let vanilla's own untouched loop run
    }

    @Inject(
            method = "m_150175_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
                    + "Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void khp$redirectArrowHit(Level pLevel, Entity pProjectile, Vec3 pStartVec, Vec3 pEndVec,
                                               AABB pBoundingBox, Predicate<Entity> pFilter, float pInflationAmount,
                                               CallbackInfoReturnable<EntityHitResult> cir) {
        if (!MahConfig.enabled) return;

        Vec3 rayVec = pEndVec.subtract(pStartVec);
        double maxDistance = rayVec.length();
        if (maxDistance < 1.0e-6) return;

        Predicate<Entity> combatFilter = e -> e != pProjectile && pFilter.test(e);
        List<PartHitResult> hits = MahApi.raycastCombat(pLevel, pStartVec, rayVec, maxDistance, combatFilter);
        if (hits != null && !hits.isEmpty()) {
            PartHitResult nearest = hits.get(0);
            cir.setReturnValue(new EntityHitResult(nearest.entity(), nearest.worldEntryPoint()));
            cir.cancel();
            return;
        }

        // Same diagnostic hook as the melee overload above — see its comment. This
        // overload matters most for the Maze Mother trace specifically: it's the
        // server-authoritative path, so it's the one that would also expose whether
        // Phase 3's blueprint sync (design doc §9) is relevant here at all.
        MahApi.logCombatMissDiagnostics(pLevel, pStartVec, rayVec, maxDistance, combatFilter);

        if (MahApi.hasCombatCandidates(pLevel, pStartVec, rayVec, maxDistance, combatFilter)) {
            // Same fix as the melee overload above — a genuine miss on a
            // MAH-trusted entity must not fall through to vanilla's bigger box.
            cir.setReturnValue(null);
            cir.cancel();
            return;
        }
        // nothing MAH covers nearby — let vanilla's own untouched loop run
    }
}
