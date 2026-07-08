package exp.CCnewmods.mah.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * Overrides which MAH entity key an entity resolves to. Keys select the
 * {@link exp.CCnewmods.mah.capture.BoneTable} and, in later phases, the shipped
 * blueprint and transparency masks.
 *
 * <p>Return null to defer to other providers / the default EntityType id.
 */
@FunctionalInterface
public interface EntityKeyProvider {

    @Nullable
    ResourceLocation keyFor(Entity entity);
}
