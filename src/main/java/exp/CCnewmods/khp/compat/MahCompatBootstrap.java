package exp.CCnewmods.khp.compat;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MahApi;
import net.minecraft.client.renderer.entity.layers.SlimeOuterLayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Registers MAH's built-in anatomical RenderLayer allowlist entries (see
 * {@link MahApi#registerAnatomicalRenderLayer}) once, on {@link FMLClientSetupEvent}.
 *
 * <p>This runs on the <b>mod bus</b> during client setup — well after FML has
 * constructed every mod and populated {@code ModList} — deliberately not from a
 * mixin plugin. The Phase 1B hotfix hit exactly this class of bug: a mixin config
 * plugin calling {@code ModList.get()} far too early in bootstrap, before ModList
 * exists, throwing an NPE that silently disabled an entire "required: false" config.
 * Registration here has no such timing risk; it fires long after ModList is safe to
 * query.
 *
 * <p>Flagged per the project's verify-before-assuming rule: {@code ModList} and
 * {@code FMLClientSetupEvent} weren't available to verify against actual bytecode
 * this session (not present in the mapped-jar slice used for the rest of MAH) — both
 * are long-stable, well-documented Forge platform APIs, but this rests on
 * established knowledge of their firing order rather than a bytecode check.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MahCompatBootstrap {

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Vanilla — always present, safe to reference directly.
        MahApi.registerAnatomicalRenderLayer(SlimeOuterLayer.class);

        // Third-party — isolated into their own classes so the reference is only
        // resolved once we've confirmed the mod is actually on the classpath.
        if (ModList.get().isLoaded("iceandfire")) {
            exp.CCnewmods.khp.compat.iceandfire.IceAndFireLayerRegistration.register();
            exp.CCnewmods.khp.compat.iceandfire.IceAndFireDynamicTextureResolver.register();
        }
        if (ModList.get().isLoaded("betterend")) {
            exp.CCnewmods.khp.compat.betterend.BetterEndLayerRegistration.register();
        }
    }

    private MahCompatBootstrap() {
    }
}
