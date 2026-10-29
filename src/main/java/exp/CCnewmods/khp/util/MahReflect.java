package exp.CCnewmods.khp.util;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Finds vanilla fields by type instead of {@code @Shadow}, whose refmap entries for some private fields fail to
 * generate and break in production. Each field KHP needs is the only one of its type on its class.
 */
public final class MahReflect {

    /** @throws IllegalStateException unless exactly one field of {@code fieldType} exists. */
    public static Field findSoleFieldOfType(Class<?> owner, Class<?> fieldType) {
        return findSoleFieldOfType(owner, fieldType, (Class<?>) null);
    }

    /** Also matches the generic argument, since mods like Embeddium add same-typed fields to model classes. */
    public static Field findSoleFieldOfType(Class<?> owner, Class<?> fieldType, Class<?> genericArg) {
        Field found = null;
        StringBuilder candidateNames = new StringBuilder();
        for (Field f : owner.getDeclaredFields()) {
            if (f.getType() != fieldType) continue;
            if (genericArg != null && !genericTypeArgMatches(f, genericArg)) continue;

            if (found != null) {
                throw new IllegalStateException("MAH: more than one field of type "
                        + fieldType.getName()
                        + (genericArg != null ? "<" + genericArg.getName() + ">" : "")
                        + " on " + owner.getName()
                        + " — type-based reflective lookup is ambiguous"
                        + (genericArg == null ? "; supply a genericArg to disambiguate, or " : "; ")
                        + "update the MahReflect call site to disambiguate. Candidates so far: "
                        + candidateNames + ", " + f.getName());
            }
            found = f;
            candidateNames.append(f.getName());
        }
        if (found == null) {
            throw new IllegalStateException("MAH: no field of type " + fieldType.getName()
                    + (genericArg != null ? "<" + genericArg.getName() + ">" : "")
                    + " found on " + owner.getName() + " — vanilla class shape changed, "
                    + "MAH needs an update for this Minecraft/mapping version.");
        }
        found.setAccessible(true);
        return found;
    }

    private static boolean genericTypeArgMatches(Field f, Class<?> expectedArg) {
        Type generic = f.getGenericType();
        if (!(generic instanceof ParameterizedType pt)) return false;
        for (Type arg : pt.getActualTypeArguments()) {
            if (arg instanceof Class<?> && arg == expectedArg) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Field field, Object instance) {
        try {
            return (T) field.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("MAH: reflective field read failed for " + field, e);
        }
    }

    /** Box instance → field name, for Tabula-style models whose boxes are plain fields. */
    public static java.util.Map<Object, String> mapFieldNamesByIdentity(Object owner, Class<?> boxType) {
        java.util.IdentityHashMap<Object, String> result = new java.util.IdentityHashMap<>();
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!boxType.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    Object value = f.get(owner);
                    if (value != null) {
                        result.putIfAbsent(value, f.getName());
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return result;
    }

    private MahReflect() {
    }
}
