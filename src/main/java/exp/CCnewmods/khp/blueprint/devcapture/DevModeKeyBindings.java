package exp.CCnewmods.khp.blueprint.devcapture;

import exp.CCnewmods.khp.Mah;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Dev-mode pose capture toggle, default F9. */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DevModeKeyBindings {

    public static final KeyMapping TOGGLE_DEV_MODE = new KeyMapping(
            "key.khp.toggle_dev_mode_pose_capture", GLFW.GLFW_KEY_F9, "key.categories.khp");

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_DEV_MODE);
    }

    private DevModeKeyBindings() {
    }
}
