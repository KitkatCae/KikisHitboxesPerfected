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

/**
 * {@code /khprefresh} — dev-only, client-side. Forces {@link TextureAlphaSampler} to
 * drop every cached {@code NativeImage} (and every failed-read/resolved-dynamic-layer
 * entry alongside them) and re-read from disk on next sample.
 *
 * <p>This is the manual refresh command referenced by name ("Q5") in {@link
 * TextureAlphaSampler#invalidateAll()}'s own javadoc since the very first opacity
 * design doc — flagged there and in every phase handoff since as "not done", and
 * never actually built until now. Neither this command nor a real resource-reload
 * listener existed before this session ({@code invalidateAll()} had zero callers at
 * all) — {@code TextureCacheReloadListener} (same package) now covers the automatic
 * case (a real resource-pack reload); this command covers what that listener can't:
 * an EMF-style client-side model/texture swap, or any other case where the texture
 * changed underneath MAH without a resource reload firing.
 */
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
