package exp.CCnewmods.khp.mixin.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.EntityUtil;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.api.PartHitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Locale;

/**
 * Redirects TACZ's per-candidate hit test through {@link MahApi}'s combat fallback
 * chain (see {@code KHP_Phase2C_Design.md} §3) instead of its own single-AABB
 * {@code AABB.clip} + eye-height-band headshot heuristic.
 *
 * <p>Third-party mixin target — literal method name from the real jar, per this
 * project's mixin-mapping rules: {@code remap = false} on the {@code @Mixin}
 * annotation itself, not just the injector. Target signature confirmed via
 * decompile this session against {@code tacz-1_20_1-1_1_8-hotfix.jar}:
 * {@code protected static EntityKineticBullet.EntityResult getHitResult(Projectile
 * bulletEntity, Entity entity, Vec3 startVec, Vec3 endVec)} — see
 * {@code KHP_Phase2C_Design.md} §2 for the full verification note.
 *
 * <h3>Fallback contract — matters here specifically, not just a style choice</h3>
 * Two genuinely different "no MAH answer" cases have to be told apart, or a
 * MAH-precise miss (e.g. a shot skimming through a real gap in the model) would get
 * a second, unearned chance to hit via TACZ's own larger fixed AABB:
 * <ul>
 *   <li>{@link MahApi#getCombatSnapshot} returns {@code null} — no geometry MAH
 *       trusts for this entity right now (no fresh live snapshot and either no
 *       blueprint at all or its tier isn't combat-allowlisted). Injector does
 *       <b>not</b> cancel — TACZ's own original body runs entirely untouched.</li>
 *   <li>{@code getCombatSnapshot} returns non-{@code null} but the actual raycast
 *       finds no hit — a real, MAH-accurate miss. Injector cancels with a
 *       {@code null} return value (the same "no hit" contract the original method
 *       already uses) — vanilla's fixed-AABB path never runs for this candidate.</li>
 * </ul>
 */
@Mixin(value = EntityUtil.class, remap = false)
public abstract class TaczEntityUtilMixin {

    @Inject(method = "getHitResult", at = @At("HEAD"), cancellable = true, remap = false)
    private static void khp$redirectToMahCombat(Projectile bulletEntity, Entity entity, Vec3 startVec, Vec3 endVec,
                                                  CallbackInfoReturnable<EntityKineticBullet.EntityResult> cir) {
        if (!MahConfig.enabled) return;

        // getCombatSnapshot decides trust (fresh live, or allowlisted blueprint tier)
        // independently of whether the ray actually goes on to hit anything — see
        // class javadoc for why this two-step check matters here.
        if (MahApi.getCombatSnapshot(entity) == null) {
            return; // no MAH geometry trusted for this entity right now; let TACZ's own logic run
        }

        Vec3 rayVec = endVec.subtract(startVec);
        double maxDistance = rayVec.length();
        if (maxDistance < 1.0e-6) {
            return; // degenerate zero-length ray; not our problem to solve, let vanilla handle it
        }

        List<PartHitResult> hits = MahApi.raycastEntityCombat(entity, startVec, rayVec, maxDistance);
        if (hits == null || hits.isEmpty()) {
            // MAH trusts geometry for this entity and the shot genuinely missed it —
            // a real miss, not "MAH has nothing to say." Do not let TACZ's own
            // fixed-AABB path re-test and potentially register a hit anyway.
            cir.setReturnValue(null);
            cir.cancel();
            return;
        }

        PartHitResult nearest = hits.get(0); // raycastEntityCombat returns nearest-first, never empty here
        boolean headshot = nearest.boneName() != null
                && nearest.boneName().toLowerCase(Locale.ROOT).contains("head");
        cir.setReturnValue(new EntityKineticBullet.EntityResult(entity, nearest.worldEntryPoint(), headshot));
        cir.cancel();
    }
}
