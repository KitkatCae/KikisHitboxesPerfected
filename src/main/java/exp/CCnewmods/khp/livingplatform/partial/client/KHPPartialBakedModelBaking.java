package exp.CCnewmods.khp.livingplatform.partial.client;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.livingplatform.LivingPlatformBlocks;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Wraps {@code khp_partial}'s placeholder model (parented on air) in a {@link KHPPartialBakedModel}. */
@Mod.EventBusSubscriber(modid = Mah.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KHPPartialBakedModelBaking {

    @SubscribeEvent
    static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        event.getModels().computeIfPresent(
                BlockModelShaper.stateToModelLocation(LivingPlatformBlocks.KHP_PARTIAL.get().defaultBlockState()),
                (location, originalModel) -> new KHPPartialBakedModel(originalModel));
    }

    private KHPPartialBakedModelBaking() {
    }
}
