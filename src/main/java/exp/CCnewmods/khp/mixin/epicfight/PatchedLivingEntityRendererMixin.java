package exp.CCnewmods.khp.mixin.epicfight;

import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.khp.compat.epicfight.EpicFightBoxCapture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer;

/**
 * Captures Epic Fight's skinned-mesh geometry — a totally separate rendering system
 * from everything else MAH hooks, confirmed via bytecode: Epic Fight cancels
 * vanilla's {@code RenderLivingEvent.Pre} and substitutes its own GPU-skinned mesh
 * draw for the duration an entity has "overridden render" active (a mob locked onto
 * a combat target, or a player holding a weapon) — none of MAH's existing hooks fire
 * during that window at all, the same "renderer bypasses the path we hook" class of
 * bug as Ice and Fire's Ghost, just via Forge's own event system instead of a
 * fully-overridden {@code render()} method.
 *
 * <p>{@code PatchedLivingEntityRenderer.render()} is a single shared method — verified
 * not overridden by any of the dozen-plus per-mob-type {@code P*Renderer} subclasses
 * (Creeper, Humanoid, IronGolem, Spider, etc. all checked), and verified that the
 * player-weapon-holding path (via {@code RenderEngine.renderEntityArmatureModel})
 * funnels through this exact same method too — so one mixin covers both mobs and
 * players. (Not covered: the ender dragon, which uses the simpler
 * {@code PatchedEntityRenderer} base directly and has its own separate
 * {@code PEnderDragonRenderer} — a known, low-priority gap, one boss entity.)
 *
 * <p>The draw call this redirects — {@code mesh.draw(poseStack, buffer, renderType,
 * packedLight, r, g, b, a, overlay, armature, armature.getPoseMatrices())} — is a
 * local variable's method call, not a field or parameter, so getting both
 * {@code armature} and the live pose matrices at one injection point needs
 * {@code @Redirect} rather than {@code @ModifyVariable} (which only exposes the one
 * variable being modified) — same reasoning as the Ferrouslime spike fix in Phase 1F.
 * The redirect handler captures, then calls the real {@code draw} through unchanged
 * so actual rendering is completely unaffected.
 *
 * <p>Third-party target: remap=false, literal names. Optional dependency — this
 * config is {@code "required": false} and simply won't find its target class if
 * Epic Fight isn't installed.
 */
@Mixin(value = PatchedLivingEntityRenderer.class, remap = false)
public abstract class PatchedLivingEntityRendererMixin {

    @Redirect(method = "render", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lyesman/epicfight/api/client/model/SkinnedMesh;"
                            + "draw(Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
                            + "Lnet/minecraft/client/renderer/RenderType;IFFFFI"
                            + "Lyesman/epicfight/api/model/Armature;"
                            + "[Lyesman/epicfight/api/utils/math/OpenMatrix4f;)V"))
    private void mah$captureAndDraw(SkinnedMesh mesh, PoseStack poseStack, MultiBufferSource buffer,
                                    RenderType renderType, int packedLight, float r, float g, float b, float a,
                                    int overlay, Armature armature, OpenMatrix4f[] poses) {
        EpicFightBoxCapture.capture(mesh, poseStack, armature, poses);
        mesh.draw(poseStack, buffer, renderType, packedLight, r, g, b, a, overlay, armature, poses);
    }
}
