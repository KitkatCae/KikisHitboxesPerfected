package exp.CCnewmods.mah.mixin.client;

import exp.CCnewmods.mah.duck.MahNamedPart;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Names the outermost baked part "root", giving paths like {@code root/body/head}. */
@Mixin(LayerDefinition.class)
public abstract class LayerDefinitionMixin {

    @Inject(method = "bakeRoot", at = @At("RETURN"))
    private void mah$stampRoot(CallbackInfoReturnable<ModelPart> cir) {
        ((MahNamedPart) (Object) cir.getReturnValue()).mah$setName("root");
    }
}
