package exp.CCnewmods.khp.compat;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MultipartCompatRegistry;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Registers {@link exp.CCnewmods.khp.api.MultipartEntityAdapter}s on
 * {@link FMLCommonSetupEvent} — deliberately <b>not</b> {@code FMLClientSetupEvent}
 * (unlike {@link MahCompatBootstrap}, which is client-only and fine to be, since
 * render-layer registration only ever matters client-side). Arrow/melee damage
 * dealt through a part entity is server-authoritative — a dedicated server needs
 * these adapters registered too, or {@code MultipartCompatRegistry} would be
 * permanently empty there and every part-entity hit would silently fall back to
 * the part's own crude box on the side that actually deals the damage.
 *
 * <p>{@code FMLCommonSetupEvent} fires on both physical sides, well after
 * {@code ModList} is safe to query — same timing safety as {@code MahCompatBootstrap}
 * documents for {@code FMLClientSetupEvent}, just the common-bus equivalent.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MahCommonCompatBootstrap {

    @SubscribeEvent
    static void onCommonSetup(FMLCommonSetupEvent event) {
        // Forge's own PartEntity — always present, no mod-loaded gate needed.
        // Covers the vanilla Ender Dragon and Ender's Cataclysm's Cm_Part_Entity.
        MultipartCompatRegistry.register(new VanillaPartEntityAdapter());

        // Third-party — isolated so the reference is only resolved once we've
        // confirmed the mod is actually on the classpath.
        if (ModList.get().isLoaded("iceandfire")) {
            MultipartCompatRegistry.register(new exp.CCnewmods.khp.compat.iceandfire.IceAndFireMultipartAdapter());
        }
    }

    private MahCommonCompatBootstrap() {
    }
}
