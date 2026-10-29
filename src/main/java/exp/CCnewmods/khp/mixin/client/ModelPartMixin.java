package exp.CCnewmods.khp.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import exp.CCnewmods.khp.duck.MahNamedPart;
import exp.CCnewmods.khp.util.MahReflect;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Stores part names, keeps the render-path stack balanced, and captures cubes at {@code compile} HEAD.
 * {@code cubes} is found via {@link MahReflect}, pinned to {@code List<ModelPart.Cube>} since Embeddium adds
 * other {@code List} fields.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin implements MahNamedPart {

    @Unique
    private static final Field MAH$CUBES_FIELD =
            MahReflect.findSoleFieldOfType(ModelPart.class, List.class, ModelPart.Cube.class);

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
