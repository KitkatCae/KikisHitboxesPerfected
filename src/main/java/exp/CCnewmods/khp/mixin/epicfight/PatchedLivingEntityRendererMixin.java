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
 * Captures Epic Fight's skinned meshes, which replace normal rendering while its animations are active (mobs in
 * combat, players with weapons). Covers mobs and players; not the ender dragon.
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
