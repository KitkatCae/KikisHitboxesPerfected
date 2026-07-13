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

/**
 * Clears {@link TextureAlphaSampler}'s cache on a real resource-pack reload
 * (F3+T, or a pack list change) — the resource-reload half of the "texture cache
 * invalidation isn't wired up" gap flagged since Phase 2A and left unbuilt through
 * every phase since. See {@code MahDebugCommand} ({@code /khprefresh}) for the other
 * half — the manual-trigger case (EMF-style swaps, anything that doesn't fire a real
 * reload) this listener can't catch on its own.
 *
 * <p><b>Not bytecode-verified this session</b> — no Forge/Minecraft jar was available
 * to confirm {@code SimplePreparableReloadListener}'s exact abstract method shape
 * against this project's pinned Parchment 2023.09.03 / Forge 47.4.20 mappings, unlike
 * this project's usual practice for every other API call. The class and its two
 * abstract methods (`prepare`/`apply`) have been stable vanilla API across many MC
 * versions, so this is a low-risk gap, but it should be the first thing double-checked
 * against the real mapped jar (or just a real {@code ./gradlew compileJava}) before
 * trusting this file the way the rest of the codebase's API usage is trusted.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TextureCacheReloadListener extends SimplePreparableReloadListener<Object> {

    private static final Object NO_STATE = new Object();

    @SubscribeEvent
    static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new TextureCacheReloadListener());
    }

    @Override
    protected Object prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        // Nothing to prepare off-thread — invalidateAll() itself is cheap (just
        // dropping cached NativeImages, no re-reading here); the real re-read happens
        // lazily, on the next sampleOpacity() call that misses the now-empty cache.
        return NO_STATE;
    }

    @Override
    protected void apply(Object state, ResourceManager resourceManager, ProfilerFiller profiler) {
        TextureAlphaSampler.invalidateAll();
    }
}
