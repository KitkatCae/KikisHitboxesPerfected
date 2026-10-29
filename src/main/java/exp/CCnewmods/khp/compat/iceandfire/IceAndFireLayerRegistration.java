package exp.CCnewmods.khp.compat.iceandfire;

import com.github.alexthe666.iceandfire.client.render.entity.layer.LayerHydraHead;
import exp.CCnewmods.khp.api.MahApi;

/** Hydra heads are drawn as a layer; register it as anatomy. Separate class so it only loads with Ice and Fire. */
public final class IceAndFireLayerRegistration {

    public static void register() {
        MahApi.registerAnatomicalRenderLayer(LayerHydraHead.class);
    }

    private IceAndFireLayerRegistration() {
    }
}
