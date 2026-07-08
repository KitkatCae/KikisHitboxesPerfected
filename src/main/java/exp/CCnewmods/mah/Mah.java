package exp.CCnewmods.mah;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Misanthrope Accurate Hitboxes.
 *
 * <p>Phase 1: model-accurate hitbox <b>capture</b>. Every rendered entity's geometry
 * (vanilla {@code ModelPart} models, GeckoLib bones, and — later — Figura avatars) is
 * captured during rendering as entity-local oriented bounding boxes with stable bone
 * identity, published to {@link exp.CCnewmods.mah.capture.HitboxStore} once per frame,
 * and visualised by the debug wireframe renderer.
 *
 * <p>Later phases add: transparency face-culling, the raycast API + combat interception
 * (projectiles / crosshair / TACZ / Epic Fight), the headless server-side GeckoLib
 * animation resolver + shipped blueprints for dedicated servers, and VS2-delegated
 * per-part solid collision. See MAH_Design_v1.md.
 */
@Mod(Mah.MODID)
public class Mah {

    public static final String MODID = "mah";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Mah() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MahConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MahConfig.CLIENT_SPEC);
        LOGGER.info("MAH initialised — accurate hitbox capture layer active");
    }
}
