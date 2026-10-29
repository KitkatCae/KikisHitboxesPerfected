package exp.CCnewmods.khp;

import com.mojang.logging.LogUtils;
import exp.CCnewmods.khp.compat.Vs2Compat;
import exp.CCnewmods.khp.livingplatform.LivingPlatformBlocks;
import exp.CCnewmods.khp.livingplatform.LivingPlatformWiring;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** Kiki's Hitboxes Perfected: model-accurate entity hitboxes. */
@Mod(Mah.MODID)
public class Mah {

    public static final String MODID = "khp";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Mah() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MahConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MahConfig.CLIENT_SPEC);

        // Registered with or without VS2, so registry ids don't change.
        LivingPlatformBlocks.BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus());
        LivingPlatformBlocks.BLOCK_ENTITY_TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());

        if (Vs2Compat.isLoaded()) {
            LivingPlatformWiring.register(FMLJavaModLoadingContext.get().getModEventBus());
        } else {
            LOGGER.info("[KHP] Valkyrien Skies not installed — living platforms and ship collision disabled.");
        }

        LOGGER.info("KHP initialised — accurate hitbox capture layer active");
    }
}
