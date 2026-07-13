package exp.CCnewmods.khp.blueprint.devcapture;

import exp.CCnewmods.khp.Mah;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the dev-mode-pose-capture toggle keybind (design doc §5.1 — config
 * <b>and</b> a keybind, so this can be flipped mid-session without a restart) and
 * consumes it each client tick, same standard Forge keybind pattern used everywhere
 * else. Default F9 — unbound in vanilla, rebindable in the normal controls menu like
 * any other keybind regardless of this default.
 */
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
