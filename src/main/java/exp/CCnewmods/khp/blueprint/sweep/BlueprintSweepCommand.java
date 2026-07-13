package exp.CCnewmods.khp.blueprint.sweep;

import com.mojang.brigadier.CommandDispatcher;
import exp.CCnewmods.khp.Mah;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /khpsweep start|stop|status} — dev-only, client-side. Drives
 * {@link BlueprintCaptureSweep}, which does the actual per-tick work; this class is
 * just the Brigadier wiring.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BlueprintSweepCommand {

    @SubscribeEvent
    static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("khpsweep")
                .then(Commands.literal("start").executes(ctx -> {
                    BlueprintCaptureSweep.start();
                    ctx.getSource().sendSuccess(() -> Component.literal("[KHP] Blueprint sweep started."), false);
                    return 1;
                }))
                .then(Commands.literal("stop").executes(ctx -> {
                    BlueprintCaptureSweep.stop();
                    ctx.getSource().sendSuccess(() -> Component.literal("[KHP] Blueprint sweep stopped."), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("[KHP] " + BlueprintCaptureSweep.status()), false);
                    return 1;
                })));
    }

    private BlueprintSweepCommand() {
    }
}
