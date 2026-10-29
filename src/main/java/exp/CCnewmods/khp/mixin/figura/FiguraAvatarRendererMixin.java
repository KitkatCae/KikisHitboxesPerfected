package exp.CCnewmods.khp.mixin.figura;

import exp.CCnewmods.khp.compat.figura.FiguraCapture;
import org.figuramc.figura.model.FiguraModelPart;
import org.figuramc.figura.model.PartCustomization;
import org.figuramc.figura.model.rendering.ImmediateAvatarRenderer;
import org.figuramc.figura.model.rendering.Vertex;
import org.figuramc.figura.model.rendering.texture.FiguraTextureSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Captures Figura avatars. {@code pushFaces} runs once per part with its local vertices while the part's
 * customization is on top of the stack; {@code renderPart} brackets give the part path.
 */
@Mixin(value = ImmediateAvatarRenderer.class, remap = false)
public abstract class FiguraAvatarRendererMixin {

    @Shadow(remap = false)
    @Final
    protected PartCustomization.PartCustomizationStack customizationStack;

    @Inject(method = "commonRender", at = @At("HEAD"))
    private void khp$reset(double vertexOffset, CallbackInfoReturnable<Integer> cir) {
        FiguraCapture.reset();
    }

    @Inject(method = "renderPart", at = @At("HEAD"))
    private void khp$enterPart(FiguraModelPart part, int[] remainingComplexity, boolean prevPredicate,
                               CallbackInfoReturnable<Boolean> cir) {
        FiguraCapture.enterPart(part);
    }

    @Inject(method = "renderPart", at = @At("RETURN"))
    private void khp$exitPart(FiguraModelPart part, int[] remainingComplexity, boolean prevPredicate,
                              CallbackInfoReturnable<Boolean> cir) {
        FiguraCapture.exitPart();
    }

    @Inject(method = "pushFaces", at = @At("HEAD"))
    private void khp$capture(int faceCount, int[] remainingComplexity, FiguraTextureSet textureSet,
                             List<Vertex> vertices, CallbackInfo ci) {
        FiguraCapture.capture(customizationStack.peek(), vertices);
    }
}
