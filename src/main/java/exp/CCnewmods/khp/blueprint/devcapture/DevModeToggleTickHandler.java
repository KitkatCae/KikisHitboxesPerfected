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
