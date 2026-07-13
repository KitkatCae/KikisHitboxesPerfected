package exp.CCnewmods.khp.livingplatform;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One whitelisted entity type and every anchor point it offers — see
 * {@code KHP_Phase2E_Design.md} §4. A single-anchor mob is just a one-element
 * {@link #anchors} list; there is no separate single-bone schema variant.
 *
 * @param entityType the whitelisted {@code EntityType} registry id.
 * @param anchors    every buildable anchor point on this entity type, in file
 *                   order. Never empty — {@link LivingPlatformRegistry} skips
 *                   (and warns on) any file with zero anchors.
 */
public record LivingPlatformDefinition(ResourceLocation entityType, List<LivingPlatformAnchor> anchors) {

    public LivingPlatformDefinition {
        anchors = List.copyOf(anchors);
    }
}
