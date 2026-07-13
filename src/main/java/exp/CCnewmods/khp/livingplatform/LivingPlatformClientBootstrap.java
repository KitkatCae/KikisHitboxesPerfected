package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Registers the VS2 client ship load/unload listeners once, on
 * {@link FMLClientSetupEvent} — same firing-order reasoning as
 * {@code MahCompatBootstrap} (well after every mod, including VS2 itself, has
 * finished construction; {@code mods.toml}'s {@code ordering = "AFTER"} for
 * {@code valkyrienskies} additionally guarantees VS2's own setup has already run
 * by the time this fires).
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LivingPlatformClientBootstrap {

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            VSGameUtilsKt.getVsCore().getShipLoadEventClient().on(e ->
                    ClientLivingPlatformRegistry.onShipLoaded(e.getShip()));
            VSGameUtilsKt.getVsCore().getShipUnloadEventClient().on(e ->
                    ClientLivingPlatformRegistry.onShipUnloaded(e.getShip()));
        });
    }

    private LivingPlatformClientBootstrap() {
    }
}
