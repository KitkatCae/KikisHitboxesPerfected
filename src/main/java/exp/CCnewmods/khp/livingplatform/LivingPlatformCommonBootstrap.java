package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/** Registers the sync channel and the VS2 attachment type (so attachments are saved). */
// Registered by LivingPlatformWiring only when VS2 is present.
public final class LivingPlatformCommonBootstrap {

    @SubscribeEvent
    static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            LivingPlatformNetworking.register();

            VSGameUtilsKt.getVsCore().registerAttachment(LivingPlatformAttachment.class, builder -> {
                builder.useJacksonSerializer();
                return kotlin.Unit.INSTANCE;
            });

            Mah.LOGGER.info("[KHP] Living platform networking channel and VS2 attachment registered.");
        });
    }

    private LivingPlatformCommonBootstrap() {
    }
}
