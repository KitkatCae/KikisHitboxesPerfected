package exp.CCnewmods.khp.compat.betterend;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MahApi;

/**
 * Registers Better End's End Slime overlay layer as anatomy (it draws the translucent body and variant
 * decorations). The layer class is private, so it's looked up by name.
 */
public final class BetterEndLayerRegistration {

    private static final String LAYER_CLASS_NAME =
            "org.betterx.betterend.entity.render.RendererEntityEndSlime$OverlayFeatureRenderer";

    public static void register() {
        try {
            Class<?> layerClass = Class.forName(LAYER_CLASS_NAME);
            MahApi.registerAnatomicalRenderLayer(layerClass);
        } catch (ClassNotFoundException e) {
            Mah.LOGGER.warn("MAH: Better End's {} not found - installed version may have "
                    + "renamed or restructured this class; End Slime overlay geometry "
                    + "will stay unregistered until this is updated", LAYER_CLASS_NAME);
        }
    }

    private BetterEndLayerRegistration() {
    }
}
