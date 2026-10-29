package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import exp.CCnewmods.khp.compat.Vs2Compat;

@Mod.EventBusSubscriber(modid = Mah.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LivingPlatformClientBootstrap {

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // VS2 references live in LivingPlatformClientVs2Hooks.
        if (!Vs2Compat.isLoaded()) return;
        event.enqueueWork(LivingPlatformClientVs2Hooks::register);
    }

    private LivingPlatformClientBootstrap() {
    }
}
