package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers {@link LivingPlatformReloadListener} on {@code AddReloadListenerEvent}
 * (Forge event bus — same confirmed-by-javap bus choice as
 * {@code BlueprintReloadRegistration}, tied to {@code ReloadableServerResources} so
 * it fires on dedicated servers too, not just client resource-pack reloads).
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LivingPlatformReloadRegistration {

    @SubscribeEvent
    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new LivingPlatformReloadListener());
    }

    private LivingPlatformReloadRegistration() {
    }
}
