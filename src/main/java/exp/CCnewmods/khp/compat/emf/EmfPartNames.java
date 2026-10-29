package exp.CCnewmods.khp.compat.emf;

import exp.CCnewmods.khp.duck.MahNamedPart;
import exp.CCnewmods.khp.util.MahReflect;
import net.minecraft.client.model.geom.ModelPart;

import java.lang.reflect.Field;
import java.util.List;

/**
 * EMF part names and cubes, read reflectively so nothing here references EMF types. EMF builds its parts outside
 * the LayerDefinition bake, so they never get KHP's bake-time names; they're stamped from EMF's own
 * {@code name} (vanilla parts) or {@code id} (custom parts) the first time they render.
 */
public final class EmfPartNames {

    private static final Field CUBES = MahReflect.findSoleFieldOfType(ModelPart.class, List.class, ModelPart.Cube.class);

    private static final ClassValue<Field> NAME_FIELD = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            Field f = find(type, "name");
            return f != null ? f : find(type, "id");
        }
    };

    public static void stamp(ModelPart part) {
        MahNamedPart named = (MahNamedPart) (Object) part;
        if (!"part".equals(named.mah$getName())) return;
        Field field = NAME_FIELD.get(part.getClass());
        if (field == null) return;
        try {
            Object value = field.get(part);
            if (value instanceof String s && !s.isEmpty()) named.mah$setName(s);
        } catch (IllegalAccessException ignored) {
        }
    }

    public static List<ModelPart.Cube> cubes(ModelPart part) {
        return MahReflect.get(CUBES, part);
    }

    private static Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != ModelPart.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private EmfPartNames() {
    }
}
