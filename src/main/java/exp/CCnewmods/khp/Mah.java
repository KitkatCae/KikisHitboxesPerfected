package exp.CCnewmods.khp;

import com.mojang.logging.LogUtils;
import exp.CCnewmods.khp.livingplatform.LivingPlatformBlocks;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Misanthrope Accurate Hitboxes.
 *
 * <p>Phase 1: model-accurate hitbox <b>capture</b>. Every rendered entity's geometry
 * (vanilla {@code ModelPart} models, GeckoLib bones, and — later — Figura avatars) is
 * captured during rendering as entity-local oriented bounding boxes with stable bone
 * identity, published to {@link exp.CCnewmods.khp.capture.HitboxStore} once per frame,
 * and visualised by the debug wireframe renderer.
 *
 * <p>Later phases add: transparency face-culling, the raycast API + combat interception
 * (projectiles / crosshair / TACZ / Epic Fight), the headless server-side GeckoLib
 * animation resolver + shipped blueprints for dedicated servers, and VS2-delegated
 * per-part solid collision. See MAH_Design_v1.md.
 *
 * <p>{@code FMLJavaModLoadingContext}/{@code getModEventBus()} — standard, unchanging
 * Forge 1.20.1 mod-bus access, same package family as the {@code ModLoadingContext}
 * already used just below it — not bytecode-verified this session (the fmlcore/
 * javafmllanguage jars where it actually lives weren't part of this session's uploaded
 * jar set, only the forge-universal jar was), flagged per this project's own
 * verify-before-assume rule rather than silently treated as equally certain as
 * everything else in this file.
 */
@Mod(Mah.MODID)
public class Mah {

    public static final String MODID = "khp";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Mah() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MahConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MahConfig.CLIENT_SPEC);

        LivingPlatformBlocks.BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus());

        LOGGER.info("KHP initialised — accurate hitbox capture layer active");
    }
}
