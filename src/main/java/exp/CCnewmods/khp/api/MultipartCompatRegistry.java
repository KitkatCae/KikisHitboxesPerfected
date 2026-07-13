package exp.CCnewmods.khp.api;

import net.minecraft.world.entity.Entity;
import javax.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of {@link MultipartEntityAdapter}s. Queried by
 * {@link MahApi#getCombatSnapshot} to resolve a part-proxy entity to its parent's
 * captured geometry — see that method's javadoc for the full resolution chain.
 *
 * <p>{@code CopyOnWriteArrayList} since registration happens once at startup
 * (mod/common setup) and resolution happens on hot combat-query paths — optimizes
 * for read frequency, not write frequency.
 */
public final class MultipartCompatRegistry {

    private static final List<MultipartEntityAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    public static void register(MultipartEntityAdapter adapter) {
        ADAPTERS.add(adapter);
    }

    /**
     * The parent entity {@code entity} is a known part-proxy of, or {@code null} if
     * {@code entity} isn't recognized as a part by any registered adapter (or is a
     * part but its parent can't be resolved right now).
     */
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
