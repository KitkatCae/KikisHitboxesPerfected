package exp.CCnewmods.mah.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.mah.capture.HitboxCaptureManager;
import exp.CCnewmods.mah.duck.MahNamedPart;
import exp.CCnewmods.mah.util.MahReflect;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Three jobs:
 * <ol>
 *   <li>Carry the part name stamped at bake time ({@link MahNamedPart} duck).</li>
 *   <li>Maintain the render-walk path stack — HEAD/RETURN of the 8-arg render are
 *       always balanced (every entry has exactly one exit), including the early
 *       invisible/empty returns.</li>
 *   <li>Capture cubes at {@code compile} HEAD, where the pose includes this part's
 *       translateAndRotate — the exact matrix the vertices are transformed by.
 *       skipDraw parts never reach compile and are correctly not captured.</li>
 * </ol>
 *
 * <p>{@code cubes} is read via {@link MahReflect} rather than {@code @Shadow} — see
 * that class's javadoc. ModelPart has exactly one {@code List}-typed field, so the
 * lookup is unambiguous and needs no SRG name.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin implements MahNamedPart {

    @Unique
    private static final Field MAH$CUBES_FIELD =
            MahReflect.findSoleFieldOfType(ModelPart.class, List.class);

    @Unique
    private String mah$name = "part";

    @Override
    public String mah$getName() {
        return this.mah$name;
    }

    @Override
    public void mah$setName(String name) {
        this.mah$name = name;
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("HEAD"))
    private void mah$pushName(PoseStack poseStack, VertexConsumer buffer, int packedLight,
                              int packedOverlay, float red, float green, float blue, float alpha,
                              CallbackInfo ci) {
        HitboxCaptureManager.pushPartName((ModelPart) (Object) this);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("RETURN"))
    private void mah$popName(PoseStack poseStack, VertexConsumer buffer, int packedLight,
                             int packedOverlay, float red, float green, float blue, float alpha,
                             CallbackInfo ci) {
        HitboxCaptureManager.popPartName();
    }

    @Inject(method = "compile", at = @At("HEAD"))
    private void mah$captureCubes(PoseStack.Pose pose, VertexConsumer buffer, int packedLight,
                                  int packedOverlay, float red, float green, float blue, float alpha,
                                  CallbackInfo ci) {
        if (HitboxCaptureManager.shouldCaptureModelGeometry()) {
            List<ModelPart.Cube> cubes = MahReflect.get(MAH$CUBES_FIELD, this);
            HitboxCaptureManager.captureVanillaCubes(cubes, pose);
        }
    }
}
