package exp.CCnewmods.khp.mixin.epicfight;

import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.CastShape;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.api.PartHitResult;
import exp.CCnewmods.khp.duck.MahColliderAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.collider.OBBCollider;
import yesman.epicfight.api.utils.math.Vec3f;

import java.util.List;

/**
 * Redirects Epic Fight's {@code OBBCollider.isCollide(Entity)} — a target-side
 * synthetic-box-vs-attacker-OBB SAT test (confirmed via decompile,
 * {@code KHP_Phase2C_Design.md} §2) — through {@link MahApi#shapecastEntityCombat}
 * so a weapon's swept collider tests against the target's real captured geometry
 * instead of a single box built from {@code entity.getBoundingBox()}.
 *
 * <h3>Coordinate mirroring — the one genuinely risky part of this mixin</h3>
 * Epic Fight's collider transform pipeline ({@code Collider.transform},
 * {@code OpenMatrix4f.transform}) operates in a coordinate space that mirrors true
 * Minecraft world space across X and Z (Y untouched). This isn't a guess — it's
 * confirmed from <b>two independent</b> real touchpoints where Epic Fight's own code
 * converts collider-space back to genuine world coordinates, both decompiled this
 * session:
 * <ul>
 *   <li>{@code OBBCollider.getHitboxAABB()}: {@code ....move(-worldCenter.x,
 *       worldCenter.y, -worldCenter.z)} — the broad-phase AABB Epic Fight's own
 *       {@code Level.getEntities} call actually uses, so this must already be
 *       correct or EF's own hit detection would be broken.</li>
 *   <li>{@code Collider.updateAndSelectCollideEntity}'s {@code toWorldCoord}:
 *       {@code createTranslation(-entity.getX(), entity.getY(), -entity.getZ())}.</li>
 * </ul>
 * Both negate X and Z, leave Y alone — same pattern, two unrelated call sites. Every
 * position/direction pulled out of this collider for use in a real {@code Vec3}
 * below goes through {@link #khp$mirror}, matching that convention exactly.
 * <b>Still worth an explicit in-game sanity check</b> (e.g. compare against
 * {@code debug_render}'s wireframe, or log both AABBs side by side) before trusting
 * this beyond "probably right" — unlike the TACZ redirect, this one required
 * reconstructing geometry from internal state rather than a direct 1:1 API mapping,
 * and hasn't been visually confirmed in a running game this session.
 *
 * <h3>Two structurally different {@code OBBCollider} shapes — only one handled</h3>
 * {@code OBBCollider} has two constructors with genuinely different internal
 * representations: the simple {@code (vertexX, vertexY, vertexZ, centerX, centerY,
 * centerZ)} form (4 {@code modelVertices}, 3 {@code modelNormals} — a true 3D box,
 * confirmed the common case for a swept weapon collider) and a second
 * {@code (pos1, pos2, norm1, norm2, center)} form (2 vertices, 2 normals — some other
 * point/normal-pair shape, not investigated this session). Only the first (3-normal)
 * form is redirected here — the field-length check below detects the second form and
 * falls through to Epic Fight's own unmodified logic for it rather than
 * misinterpreting fields that mean something different in that shape.
 */
@Mixin(value = OBBCollider.class, remap = false)
public abstract class EpicFightOBBColliderMixin {

    // worldCenter is declared on the Collider superclass, not OBBCollider itself —
    // @Shadow cannot reach it directly here (confirmed the hard way: this originally
    // shadowed it in-place and threw InvalidMixinException at mixin-apply time in
    // real testing). Retrieved instead via MahColliderAccess, implemented by the
    // separate EpicFightColliderMixin targeting Collider directly — see that
    // mixin's javadoc.
    @Shadow(remap = false) protected Vec3[] modelVertices;
    @Shadow(remap = false) protected Vec3[] rotatedVertices;
    @Shadow(remap = false) protected Vec3[] rotatedNormals;
    @Shadow(remap = false) protected Vec3f scale;

    @Inject(method = "isCollide(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void khp$redirectToMahCombat(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!MahConfig.enabled) return;

        // Only the 4-vertex/3-normal "true box" constructor variant is safely
        // reinterpretable this way — see class javadoc.
        if (rotatedVertices == null || rotatedVertices.length != 4
                || rotatedNormals == null || rotatedNormals.length != 3
                || modelVertices == null || modelVertices.length != 4
                || scale == null) {
            return; // let Epic Fight's own logic run for this collider shape
        }

        if (MahApi.getCombatSnapshot(entity) == null) {
            return; // no MAH geometry trusted for this entity right now
        }

        Vec3 center = khp$mirror(((MahColliderAccess) (Object) this).mah$getWorldCenter());
        Vec3 axisX = khp$mirror(rotatedNormals[0]);
        Vec3 axisY = khp$mirror(rotatedNormals[1]);
        Vec3 axisZ = khp$mirror(rotatedNormals[2]);

        // modelVertices[1] == (vertexX, vertexY, vertexZ) for this constructor
        // variant (confirmed via decompile — see OBBCollider's simple constructor).
        // Sign doesn't matter for a half-extent magnitude, so no mirroring needed.
        Vec3 v = modelVertices[1];
        Vec3 halfExtents = new Vec3(
                Math.abs(v.x) * scale.x, Math.abs(v.y) * scale.y, Math.abs(v.z) * scale.z);

        CastShape.BoxShape box = new CastShape.BoxShape(center, halfExtents, axisX, axisY, axisZ);

        List<PartHitResult> hits = MahApi.shapecastEntityCombat(entity, box);
        cir.setReturnValue(hits != null && !hits.isEmpty());
        cir.cancel();
    }

    /** Epic Fight's own collider-space-to-world-space convention: negate X and Z, leave Y. */
    private static Vec3 khp$mirror(Vec3 v) {
        return new Vec3(-v.x, v.y, -v.z);
    }
}
