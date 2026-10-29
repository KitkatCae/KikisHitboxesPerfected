package exp.CCnewmods.khp.compat;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MahApi;
import net.minecraft.client.renderer.entity.layers.SlimeOuterLayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Registers the built-in anatomical render layers. */
@Mod.EventBusSubscriber(modid = Mah.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MahCompatBootstrap {

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        MahApi.registerAnatomicalRenderLayer(SlimeOuterLayer.class);

        // Ice and Fire and its Community Edition share a mod id but not packages.
        switch (exp.CCnewmods.khp.compat.iceandfire.IceAndFireVariant.current()) {
            case ORIGINAL -> {
                exp.CCnewmods.khp.compat.iceandfire.IceAndFireLayerRegistration.register();
                exp.CCnewmods.khp.compat.iceandfire.IceAndFireDynamicTextureResolver.register();
            }
            case COMMUNITY_EDITION ->
                    exp.CCnewmods.khp.compat.iceandfire.ce.IceAndFireCELayerRegistration.register();
            default -> {
            }
        }
        if (ModList.get().isLoaded("betterend")) {
            exp.CCnewmods.khp.compat.betterend.BetterEndLayerRegistration.register();
        }
    }

    private MahCompatBootstrap() {
    }
}
