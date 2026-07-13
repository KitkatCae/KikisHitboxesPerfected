package exp.CCnewmods.khp.api;

import net.minecraft.world.entity.Entity;
import javax.annotation.Nullable;

/**
 * Extension point for mods that represent one creature as several separate,
 * individually-targetable {@code Entity} instances — a "part" proxy (often
 * invisible, no model of its own) that forwards damage/interaction to a "parent"
 * entity which owns the actual captured geometry.
 *
 * <p>Registered via {@link MultipartCompatRegistry#register}. MAH ships two
 * built-in adapters: {@code VanillaPartEntityAdapter} (covers
 * {@code net.minecraftforge.entity.PartEntity} — the vanilla Ender Dragon's own
 * mechanism, and any mod building on Forge's public extraction of it, e.g. Ender's
 * Cataclysm's {@code Cm_Part_Entity}) and an Ice-and-Fire-specific adapter (its
 * {@code EntityMutlipartPart} extends {@code Entity} directly, not Forge's
 * {@code PartEntity}, so it needs its own check). Third-party compat modules can
 * implement this for anything else following the same pattern.
 */
public interface MultipartEntityAdapter {

    /** True if {@code entity} is a part-proxy this adapter knows how to resolve. */
    boolean isPart(Entity entity);

    /**
     * The parent entity {@code part} represents a region of, or {@code null} if
     * unresolvable right now (parent despawned, not yet assigned, etc). Only called
     * after {@link #isPart} returns {@code true} for the same entity.
     */
    @Nullable
    Entity resolveParent(Entity part);
}
