package exp.CCnewmods.khp.compat.betterend;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MahApi;

/**
 * Registers Better End's End Slime overlay layer as anatomical (see
 * {@link MahApi#registerAnatomicalRenderLayer}).
 *
 * <p>Confirmed via bytecode: {@code RendererEntityEndSlime} is a normal
 * {@code MobRenderer} using the standard {@code addLayer} mechanism — structurally
 * the same shape as vanilla Slime, not Alex's Caves' Ferrouslime (see the Phase 1E
 * changelog for why that one is a different, currently-unsolvable problem). Its
 * layer does two things: renders genuinely extra decorative sub-geometry for
 * lake/amber/chorus variants ({@code renderFlower}/{@code renderCrop}, unambiguously
 * extra anatomy), and renders the slime's main translucent body through a second
 * full {@code EndSlimeEntityModel} instance (mirroring vanilla Slime's own
 * primary-core / translucent-outer-layer split).
 *
 * <p>One thing this doesn't fully rule out the way the vanilla Slime and Ice and
 * Fire Hydra cases did: I haven't directly compared the baked size of the "overlay"
 * model against the primary model to confirm it's larger/different rather than an
 * exact duplicate (the Drowned case). The strong structural and thematic resemblance
 * to vanilla Slime — plus the confirmed extra flower/crop geometry in the same
 * layer — makes registering this the reasonable call, but flagging the inference
 * honestly rather than presenting it as fully bytecode-confirmed the way the other
 * two were.
 *
 * <p>The layer class ({@code RendererEntityEndSlime.OverlayFeatureRenderer}) is
 * declared {@code private} on its enclosing renderer — not accessible as a normal
 * compile-time type reference from here, so this resolves it via
 * {@code Class.forName} instead (obtaining a {@code Class} object doesn't require
 * access permission the way instantiating or invoking members would; only
 * {@code getClass()} equality is ever needed for the allowlist check).
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
