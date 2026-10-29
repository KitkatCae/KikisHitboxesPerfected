package exp.CCnewmods.khp.compat;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MultipartCompatRegistry;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** Registers multipart adapters on both sides; part hits are server-authoritative. */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MahCommonCompatBootstrap {

    @SubscribeEvent
    static void onCommonSetup(FMLCommonSetupEvent event) {
        // Ender Dragon, Ender's Cataclysm, and anything else on Forge's PartEntity.
        MultipartCompatRegistry.register(new VanillaPartEntityAdapter());

        switch (exp.CCnewmods.khp.compat.iceandfire.IceAndFireVariant.current()) {
            case ORIGINAL -> MultipartCompatRegistry.register(
                    new exp.CCnewmods.khp.compat.iceandfire.IceAndFireMultipartAdapter());
            case COMMUNITY_EDITION -> MultipartCompatRegistry.register(
                    new exp.CCnewmods.khp.compat.iceandfire.ce.IceAndFireCEMultipartAdapter());
            default -> {
            }
        }
    }

    private MahCommonCompatBootstrap() {
    }
}
