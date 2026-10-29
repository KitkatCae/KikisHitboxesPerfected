package exp.CCnewmods.khp.api;

import net.minecraft.world.entity.Entity;
import javax.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registered {@link MultipartEntityAdapter}s. */
public final class MultipartCompatRegistry {

    private static final List<MultipartEntityAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    public static void register(MultipartEntityAdapter adapter) {
        ADAPTERS.add(adapter);
    }

    /** {@code null} if not a part, or the parent can't be resolved right now. */
    @Nullable
    public static Entity resolveParent(Entity entity) {
        for (MultipartEntityAdapter adapter : ADAPTERS) {
            if (adapter.isPart(entity)) {
                Entity parent = adapter.resolveParent(entity);
                if (parent != null) return parent;
            }
        }
        return null;
    }

    private MultipartCompatRegistry() {
    }
}
