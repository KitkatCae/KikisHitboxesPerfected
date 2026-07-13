package exp.CCnewmods.khp.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Piggybacks on vanilla's own F3+B hitbox toggle (per Caelan's answer, not a
 * separate keybind). Redirects the single call site in
 * {@code EntityRenderDispatcher.render()} (SRG {@code m_114384_}) to vanilla's
 * private {@code renderHitbox(...)} (SRG {@code m_114441_}): when
 * {@link MahApi#getCombatSnapshot} has an answer for this entity, vanilla's own
 * white box is fully suppressed (not drawn alongside ours — per Caelan's answer,
 * MAH-covered entities route entirely to our box) and replaced with a color-coded
 * per-part wireframe; otherwise vanilla's original call runs completely untouched.
 *
 * <h3>remap=false + SRG names — not the first attempt</h3>
 * This class originally used plain mapped names with default {@code remap=true},
 * matching {@code ModelPartMixin}/{@code LivingEntityRendererMixin}'s convention
 * elsewhere in this codebase. That crashed at launch:
 * {@code InvalidMixinException: @Shadow method renderHitbox ... was not located in
 * the target class ... Using refmap khp.refmap.json} — the refmap didn't have a
 * usable entry for this specific private method. Real SRG names were pulled from
 * Caelan's own {@code output.srg} (TSRG2, obf→SRG with real class names) and
 * cross-matched by descriptor shape + the {@code static} marker: {@code render}'s
 * descriptor {@code (Lbfj;DDDFFLeij;Lfjx;I)V} → {@code m_114384_}; the private
 * static {@code renderHitbox}'s descriptor {@code (Leij;Lein;Lbfj;F)V}, explicitly
 * marked {@code static} in the file → {@code m_114441_}. This now matches this
 * project's own stated critical mixin rule for vanilla targets (SRG names,
 * {@code remap=false}), which this crash empirically confirms is the one that
 * actually works for a private target — not a stylistic preference.
 *
 * <h3>Color code — what's actually being used for combat right now</h3>
 * <ul>
 *   <li><b>Green</b> — live snapshot, fresh within {@code combat_stale_snapshot_ticks}.</li>
 *   <li><b>Orange</b> — blueprint-resolved, accepted only because this entity's tier
 *       is combat-trusted (see {@code MahConfig.combatBlueprintAllowlist}).</li>
 *   <li><i>(vanilla's own white box)</i> — {@code getCombatSnapshot} returned
 *       {@code null}; this entity is genuinely on vanilla AABBs for combat right now.</li>
 * </ul>
 * This deliberately reflects {@link MahApi#getCombatSnapshot} specifically, not the
 * more permissive {@link MahApi#getSnapshot} — the point of this overlay is to show
 * what combat redirects actually see, not MAH's general-purpose geometry
 * availability. An entity can have a perfectly good blueprint and still show
 * vanilla's white box here if its tier isn't combat-trusted — that's the intended,
 * accurate reading, not a bug.
 *
 * <p>Per-part drawing (translate to the tracked live-position delta, multiply by the
 * part's own transform, draw its local box) mirrors {@code MahClientEvents}'s
 * existing {@code drawDebugWireframes} loop exactly — same proven approach, applied
 * here per-entity inside vanilla's own hitbox call site instead of as a separate
 * always-on overlay pass.
 *
 * <p>Not yet handled: a text label per entity (source name, not just color) — color
 * alone was the agreed first pass; flagging it as a natural follow-up if color-only
 * turns out to be hard to read at a glance in practice.
 */
@Mixin(value = EntityRenderDispatcher.class, remap = false)
public abstract class EntityHitboxOverlayMixin {

    @Shadow(remap = false)
    private static void m_114441_(PoseStack pPoseStack, VertexConsumer pBuffer, Entity pEntity, float pPartialTicks) {
        throw new AssertionError("shadowed"); // replaced by Mixin; body never actually runs
    }

    @Redirect(
            method = "m_114384_(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                            + "m_114441_(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "Lnet/minecraft/world/entity/Entity;F)V",
                    remap = false),
            remap = false)
    private void khp$redirectRenderHitbox(PoseStack poseStack, VertexConsumer buffer, Entity entity, float partialTicks) {
        if (!MahConfig.enabled) {
            m_114441_(poseStack, buffer, entity, partialTicks);
            return;
        }

        EntityHitboxSnapshot combatSnap = MahApi.getCombatSnapshot(entity);
        if (combatSnap == null) {
            m_114441_(poseStack, buffer, entity, partialTicks); // untouched — genuinely vanilla for combat right now
            return;
        }

        boolean live = MahApi.hasFreshSnapshot(entity, MahConfig.combatStaleSnapshotTicks);
        float r = live ? 0.2f : 1.0f;
        float g = live ? 1.0f : 0.65f;
        float b = 0.3f;

        // PoseStack here is already translated by vanilla's own render() to this
        // entity's camera-relative INTERPOLATED render position (the standard
        // pX/pY/pZ convention every entity render call uses — lerp(partialTick,
        // xOld, x) etc), not to anchorPos, and not to raw entity.position() either.
        // The correct world-space part transform per PartOBB's own doc is
        // translate(anchorPos + (entity.position() - capturedEntityPos)) * transform.
        // Since both that target and the ambient PoseStack state are expressible in
        // WORLD coordinates, the camera term cancels out algebraically — computing
        // just the WORLD-space difference between the two and adding it as one more
        // translate on top of the ambient state lands exactly on the right spot
        // without needing this handler to know the camera position at all.
        Vec3 delta = entity.position().subtract(combatSnap.capturedEntityPos());
        Vec3 targetWorldPos = combatSnap.anchorPos().add(delta);
        Vec3 interpolatedRenderPos = new Vec3(
                Mth.lerp((double) partialTicks, (double) entity.xOld, entity.getX()),
                Mth.lerp((double) partialTicks, (double) entity.yOld, entity.getY()),
                Mth.lerp((double) partialTicks, (double) entity.zOld, entity.getZ()));
        Vec3 correction = targetWorldPos.subtract(interpolatedRenderPos);

        for (PartOBB part : combatSnap.parts()) {
            poseStack.pushPose();
            poseStack.translate(correction.x, correction.y, correction.z);
            poseStack.mulPoseMatrix(part.transform());
            LevelRenderer.renderLineBox(poseStack, buffer, part.localBox(), r, g, b, 1.0f);
            poseStack.popPose();
        }
    }
}
