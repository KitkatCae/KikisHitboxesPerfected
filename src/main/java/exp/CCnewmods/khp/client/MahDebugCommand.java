package exp.CCnewmods.khp.client;

import com.mojang.brigadier.CommandDispatcher;
import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.capture.TextureAlphaSampler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** {@code /khprefresh}: re-reads textures, for swaps that don't trigger a resource reload (e.g. EMF). */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class MahDebugCommand {

    @SubscribeEvent
    static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("khprefresh").executes(ctx -> {
            TextureAlphaSampler.invalidateAll();
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[KHP] Texture opacity cache cleared — next sample re-reads from disk."),
                    false);
            return 1;
        }));
    }

    private MahDebugCommand() {
    }
}
