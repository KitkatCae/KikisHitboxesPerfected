package exp.CCnewmods.khp.client;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.capture.TextureAlphaSampler;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Clears {@link TextureAlphaSampler}'s cache on resource reload. */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TextureCacheReloadListener extends SimplePreparableReloadListener<Object> {

    private static final Object NO_STATE = new Object();

    @SubscribeEvent
    static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new TextureCacheReloadListener());
    }

    @Override
    protected Object prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return NO_STATE;
    }

    @Override
    protected void apply(Object state, ResourceManager resourceManager, ProfilerFiller profiler) {
        TextureAlphaSampler.invalidateAll();
    }
}
