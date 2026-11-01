package exp.CCnewmods.khp.blueprint.sweep;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Pose-id fragments for one entity's animation array. Name if the animation has one, else {@code id<getID()>};
 * any fragment shared by more than one animation gets {@code _<array index>} appended. Many mods create every
 * animation with the default id 0, which otherwise gives every animation the same pose ids.
 */
final class AnimFragmentNames {

    /**
     * @param count animation array length (null slots included, so indices match the array).
     * @param name  name of animation {@code i}, or {@code null} if unnamed or the slot is null.
     * @param id    {@code getID()} of animation {@code i}; only called for unnamed non-null slots.
     * @param isNull whether slot {@code i} is null; null slots get a {@code null} fragment.
     */
    static String[] assign(int count, IntFunction<String> name, IntUnaryOperator id, IntFunction<Boolean> isNull) {
        String[] base = new String[count];
        Map<String, Integer> uses = new HashMap<>();
        for (int i = 0; i < count; i++) {
            if (isNull.apply(i)) continue;
            String n = name.apply(i);
            base[i] = n != null ? n : "id" + id.applyAsInt(i);
            uses.merge(base[i], 1, Integer::sum);
        }
        for (int i = 0; i < count; i++) {
            if (base[i] != null && uses.get(base[i]) > 1) base[i] = base[i] + "_" + i;
        }
        return base;
    }

    private AnimFragmentNames() {
    }
}
