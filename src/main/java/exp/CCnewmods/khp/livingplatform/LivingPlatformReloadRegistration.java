package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Registered by LivingPlatformWiring only when VS2 is present.
public final class LivingPlatformReloadRegistration {

    @SubscribeEvent
    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new LivingPlatformReloadListener());
    }

    private LivingPlatformReloadRegistration() {
    }
}
