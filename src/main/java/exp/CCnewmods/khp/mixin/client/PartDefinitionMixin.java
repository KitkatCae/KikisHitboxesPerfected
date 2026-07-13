package exp.CCnewmods.khp.mixin.client;

import exp.CCnewmods.khp.duck.MahNamedPart;
import exp.CCnewmods.khp.util.MahReflect;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Stamps every baked child part with its definition-map name. bake() creates one
 * ModelPart per child definition, so getChild never throws here. The subtree root of
 * each bake call is stamped by <i>its</i> parent's pass; the true root is handled by
 * LayerDefinitionMixin.
 *
 * <p>{@code children} is read via {@link MahReflect} rather than {@code @Shadow} — see
 * that class's javadoc: field-refmap generation for this exact field silently failed
 * (production crash, "field children was not located in target class"), while
 * PartDefinition has exactly one {@code Map}-typed field, making the type-based lookup
 * unambiguous and refmap-free.
 */
@Mixin(PartDefinition.class)
public abstract class PartDefinitionMixin {

    @Unique
    private static final Field MAH$CHILDREN_FIELD =
            MahReflect.findSoleFieldOfType(PartDefinition.class, Map.class, PartDefinition.class);

    @Inject(method = "bake", at = @At("RETURN"))
    private void mah$stampChildNames(int texWidth, int texHeight,
                                     CallbackInfoReturnable<ModelPart> cir) {
        ModelPart baked = cir.getReturnValue();
        Map<String, PartDefinition> children = MahReflect.get(MAH$CHILDREN_FIELD, this);
        for (String name : children.keySet()) {
            ((MahNamedPart) (Object) baked.getChild(name)).mah$setName(name);
        }
    }
}
