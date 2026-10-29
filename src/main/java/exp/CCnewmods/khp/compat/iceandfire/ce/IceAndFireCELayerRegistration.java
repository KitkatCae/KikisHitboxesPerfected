package exp.CCnewmods.khp.compat.iceandfire.ce;

import com.iafenvoy.iceandfire.render.entity.layer.LayerHydraHead;
import exp.CCnewmods.khp.api.MahApi;

/** Community Edition hydra heads as anatomy. CE dragon textures are real files, so no texture resolver. */
public final class IceAndFireCELayerRegistration {

    public static void register() {
        MahApi.registerAnatomicalRenderLayer(LayerHydraHead.class);
    }

    private IceAndFireCELayerRegistration() {
    }
}
