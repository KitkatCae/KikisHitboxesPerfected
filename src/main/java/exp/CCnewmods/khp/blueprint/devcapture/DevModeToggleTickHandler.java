package exp.CCnewmods.khp.blueprint.devcapture;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.MahConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Consumes {@link DevModeKeyBindings#TOGGLE_DEV_MODE} once per client tick — kept as
 * its own top-level class rather than nested inside {@link DevModeKeyBindings}, since
 * Forge's {@code @Mod.EventBusSubscriber} annotation scanning against nested classes
 * isn't something worth gambling a silently-never-registered tick handler on; every
 * other event-bus-subscribed class in this mod is already a small dedicated
 * top-level class for the same reason.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DevModeToggleTickHandler {

    @SubscribeEvent
    static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        while (DevModeKeyBindings.TOGGLE_DEV_MODE.consumeClick()) {
            boolean newValue = !MahConfig.devModePoseCapture;
            MahConfig.setDevModePoseCapture(newValue);
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        "[KHP] Dev-mode pose capture: " + (newValue ? "ON" : "OFF")), false);
            }
        }
    }

    private DevModeToggleTickHandler() {
    }
}
