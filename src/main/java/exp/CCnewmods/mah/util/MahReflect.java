package exp.CCnewmods.mah.util;

import java.lang.reflect.Field;

/**
 * Finds and caches a single declared field of a vanilla class by its erased type,
 * bypassing Mixin's {@code @Shadow} field resolution (and therefore its refmap)
 * entirely.
 *
 * <h3>Why this exists</h3>
 * In this project's Forge + MixinGradle + Parchment setup, {@code @Shadow}-ed
 * <b>methods</b> resolve reliably through the generated refmap, but {@code @Shadow}-ed
 * <b>fields</b> on vanilla classes have proven unreliable: the refmap entry for a
 * private generic-collection field (e.g. {@code PartDefinition.children},
 * {@code ModelPart.cubes}) can silently fail to generate, leaving the dev-mapped name
 * in the jar. That name doesn't exist in production's SRG-obfuscated classes, so the
 * mixin fails to apply at boot with "field X was not located in target class" —
 * even though the exact same source compiles cleanly in dev.
 *
 * <p>Every vanilla field MAH needs happens to be the <b>only</b> field of its erased
 * type on its class, so a type-based lookup is unambiguous and needs no SRG name at
 * all — it works identically in dev and production and is immune to mapping-channel
 * churn. Fields found this way fail loudly (at class-load time, via the caller's
 * static initializer) if the vanilla class shape ever changes, rather than silently
 * reading the wrong thing.
 */
public final class MahReflect {

    /**
     * @throws IllegalStateException if zero or more than one declared field of
     *                                {@code fieldType} exists on {@code owner} — the
     *                                lookup is only safe while it stays unambiguous.
     */
    public static Field findSoleFieldOfType(Class<?> owner, Class<?> fieldType) {
        Field found = null;
        for (Field f : owner.getDeclaredFields()) {
            if (f.getType() == fieldType) {
                if (found != null) {
                    throw new IllegalStateException("MAH: more than one field of type "
                            + fieldType.getName() + " on " + owner.getName()
                            + " — type-based reflective lookup is ambiguous; "
                            + "update the MahReflect call site to disambiguate.");
                }
                found = f;
            }
        }
        if (found == null) {
            throw new IllegalStateException("MAH: no field of type " + fieldType.getName()
                    + " found on " + owner.getName() + " — vanilla class shape changed, "
                    + "MAH needs an update for this Minecraft/mapping version.");
        }
        found.setAccessible(true);
        return found;
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Field field, Object instance) {
        try {
            return (T) field.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("MAH: reflective field read failed for " + field, e);
        }
    }

    /**
     * Build an identity map from every declared-field value assignable to
     * {@code boxType} on {@code owner}'s class hierarchy (up to and including
     * {@code Object}) to that field's name.
     *
     * <p>Used for third-party Tabula-style model classes (Citadel's and LionfishAPI's
     * {@code AdvancedModelBox}) whose part instances are plain fields on the concrete
     * model class ({@code this.left_leg = new AdvancedModelBox(...)}) rather than
     * named at construction time. This recovers the same names the mod's own source
     * uses, purely via reflection — no refmap involved, since these are third-party
     * classes with real (never obfuscated) names.
     */
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
                    // Skip unreadable fields rather than fail the whole scan.
                }
            }
        }
        return result;
    }

    private MahReflect() {
    }
}
