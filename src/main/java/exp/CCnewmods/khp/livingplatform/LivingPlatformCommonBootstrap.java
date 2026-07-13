package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Both-sides setup for KHP Phase 2E: registers the {@link LivingPlatformNetworking}
 * channel, and registers {@link LivingPlatformAttachment} with VS2's own attachment
 * system via {@code VsCoreApi.registerAttachment} + {@code useJacksonSerializer()}
 * so the server-side attachment set in
 * {@link LivingPlatformShipManager#createShip} actually persists to disk (survives
 * a ship reload/server restart, per {@code KHP_Phase2E_Design.md} §6.3) — separate
 * concern from {@link LivingPlatformNetworking}, which only handles getting the
 * same identity to connected clients, not disk persistence.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
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
