package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Registers living-platform event handlers, only when VS2 is present. Done by hand rather than with
 * {@code @Mod.EventBusSubscriber}, since Forge's scan would load VS2-referencing classes without VS2.
 */
public final class LivingPlatformWiring {

    public static void register(IEventBus modBus) {
        modBus.register(LivingPlatformCommonBootstrap.class);

        MinecraftForge.EVENT_BUS.register(LivingPlatformEvents.class);
        MinecraftForge.EVENT_BUS.register(LivingPlatformDeathHandler.class);
        MinecraftForge.EVENT_BUS.register(LivingPlatformReloadRegistration.class);

        Mah.LOGGER.info("[KHP] Valkyrien Skies detected — living platform subsystem enabled.");
    }

    private LivingPlatformWiring() {
    }
}
