package exp.CCnewmods.khp.blueprint;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge bus, so blueprints also load on dedicated servers. */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlueprintReloadRegistration {

    @SubscribeEvent
    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new BlueprintReloadListener());
    }

    private BlueprintReloadRegistration() {
    }
}
