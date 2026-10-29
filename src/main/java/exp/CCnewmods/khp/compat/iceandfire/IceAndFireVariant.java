package exp.CCnewmods.khp.compat.iceandfire;

import net.minecraftforge.fml.ModList;

/**
 * Original Ice and Fire vs Community Edition: same mod id, incompatible packages, multipart class, hydra layer,
 * dragon texturing and model library (Citadel vs Uranus). Detected via class resources without loading classes.
 */
public enum IceAndFireVariant {
    NONE,
    ORIGINAL,
    COMMUNITY_EDITION;

    private static final String MOD_ID = "iceandfire";
    private static final String CE_MARKER = "com/iafenvoy/iceandfire/entity/EntityMultipartPart.class";
    private static final String ORIGINAL_MARKER = "com/github/alexthe666/iceandfire/entity/EntityMutlipartPart.class";

    private static volatile IceAndFireVariant detected;

    /** Only after mod loading (setup events). */
    public static IceAndFireVariant current() {
        IceAndFireVariant v = detected;
        if (v == null) {
            v = detect();
            detected = v;
        }
        return v;
    }

    private static IceAndFireVariant detect() {
        if (!ModList.get().isLoaded(MOD_ID)) return NONE;
        ClassLoader loader = IceAndFireVariant.class.getClassLoader();
        if (loader.getResource(CE_MARKER) != null) return COMMUNITY_EDITION;
        if (loader.getResource(ORIGINAL_MARKER) != null) return ORIGINAL;
        return NONE;
    }
}
