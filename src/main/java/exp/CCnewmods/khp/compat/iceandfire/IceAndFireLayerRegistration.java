package exp.CCnewmods.khp.compat.iceandfire;

import com.github.alexthe666.iceandfire.client.render.entity.layer.LayerHydraHead;
import exp.CCnewmods.khp.api.MahApi;

/**
 * Registers Ice and Fire's Hydra-head layer as anatomical (see
 * {@link MahApi#registerAnatomicalRenderLayer}). Confirmed via bytecode: Hydra's
 * body+tail ({@code ModelHydraBody}, primary) and heads ({@code ModelHydraHead},
 * rendered through this layer since head count varies) are genuinely separate
 * anatomy, not a cosmetic duplicate of the primary model — unlike, say, vanilla's
 * {@code DrownedOuterLayer}, which re-renders a scaled copy of the SAME body shape
 * with a different texture and should stay excluded.
 *
 * <p>Isolated in its own class (rather than referenced directly from
 * {@code MahCompatBootstrap}) so the {@code LayerHydraHead} import is only resolved
 * when this class is actually loaded — which only happens after
 * {@code MahCompatBootstrap} has confirmed Ice and Fire is present via
 * {@code ModList.isLoaded}.
 */
public final class IceAndFireLayerRegistration {

    public static void register() {
        MahApi.registerAnatomicalRenderLayer(LayerHydraHead.class);
    }

    private IceAndFireLayerRegistration() {
    }
}
