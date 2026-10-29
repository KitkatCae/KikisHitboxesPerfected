package exp.CCnewmods.khp.api;

import net.minecraft.world.entity.Entity;
import javax.annotation.Nullable;

/**
 * For mods that split a creature into separate "part" entities that forward to a parent. Register via
 * {@link MultipartCompatRegistry#register}.
 */
public interface MultipartEntityAdapter {

    boolean isPart(Entity entity);

    /** {@code null} if unresolvable right now. Only called after {@link #isPart}. */
    @Nullable
    Entity resolveParent(Entity part);
}
