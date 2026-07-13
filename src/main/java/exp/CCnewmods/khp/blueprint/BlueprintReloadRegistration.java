package exp.CCnewmods.khp.blueprint;

import exp.CCnewmods.khp.Mah;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers {@link BlueprintReloadListener} on {@code AddReloadListenerEvent}.
 *
 * <p><b>Forge event bus, not mod bus</b> — confirmed via {@code javap}:
 * {@code AddReloadListenerEvent} extends {@code net.minecraftforge.eventbus.api.Event}
 * directly, the same shape as {@code RenderLevelStageEvent} and the other Forge-bus
 * events this project has already been burned by registering on the wrong bus before
 * (Phase 1 handoff §5). This event is tied to {@code ReloadableServerResources}, which
 * exists on <b>both</b> integrated and dedicated servers — unlike a client resource-pack
 * reload listener, this is exactly the hook a real dedicated server needs to actually
 * load blueprint data, which is the entire point of this system.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlueprintReloadRegistration {

    @SubscribeEvent
    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new BlueprintReloadListener());
    }

    private BlueprintReloadRegistration() {
    }
}
