package exp.CCnewmods.khp.livingplatform;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record LivingPlatformDefinition(ResourceLocation entityType, List<LivingPlatformAnchor> anchors) {

    public LivingPlatformDefinition {
        anchors = List.copyOf(anchors);
    }
}
