package exp.CCnewmods.khp.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/** Overrides an entity's key (which selects its BoneTable and blueprint). Return null to defer. */
@FunctionalInterface
public interface EntityKeyProvider {

    @Nullable
    ResourceLocation keyFor(Entity entity);
}
