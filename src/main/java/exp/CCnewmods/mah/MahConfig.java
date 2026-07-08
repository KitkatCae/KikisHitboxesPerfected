package exp.CCnewmods.mah;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MAH configuration.
 *
 * <p>COMMON holds capture behaviour that gameplay logic (and, in later phases, the
 * server) must agree on. CLIENT holds purely visual toggles (debug wireframes).
 *
 * <p>Baked static fields are what hot paths read — never the ConfigValue objects.
 */
@Mod.EventBusSubscriber(modid = Mah.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MahConfig {

    // ── COMMON ──────────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue ENABLED = COMMON_BUILDER
            .comment("Master switch for accurate hitbox capture.")
            .define("enabled", true);

    private static final ForgeConfigSpec.DoubleValue CAPTURE_RADIUS = COMMON_BUILDER
            .comment("Entities farther than this from the local player are not captured",
                    "and keep vanilla hitbox behaviour.")
            .defineInRange("capture_radius", 64.0d, 8.0d, 256.0d);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST = COMMON_BUILDER
            .comment("Entity type ids (e.g. \"minecraft:bat\") excluded from accurate hitboxes.",
                    "Items, XP orbs, projectiles and area effect clouds are always excluded.")
            .defineListAllowEmpty("blacklist", List.of(), o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    private static final ForgeConfigSpec.BooleanValue CAPTURE_LAYERS = COMMON_BUILDER
            .comment("Capture geometry rendered by RenderLayers (armor, saddles, glow layers, ...)",
                    "in addition to the entity's primary model. Default off: hitboxes represent",
                    "the creature's body, not its equipment.")
            .define("capture_layers", false);

    private static final ForgeConfigSpec.IntValue STALE_SNAPSHOT_TICKS = COMMON_BUILDER
            .comment("A snapshot older than this many ticks (entity unrendered — frustum-culled or",
                    "behind the camera) is considered stale. Stale snapshots still translate with the",
                    "entity but are flagged; later phases drop to blueprint/headless sources instead.")
            .defineInRange("stale_snapshot_ticks", 100, 5, 1200);

    public static final ForgeConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ── CLIENT ──────────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue DEBUG_RENDER = CLIENT_BUILDER
            .comment("Render captured hitbox wireframes in-world (green = live capture).",
                    "Phase 1 acceptance tool — verify boxes are glued to animated models.")
            .define("debug_render", false);

    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ── Baked values (read by hot paths) ────────────────────────────────────

    public static volatile boolean enabled = true;
    public static volatile double captureRadiusSqr = 64.0 * 64.0;
    public static volatile Set<ResourceLocation> blacklist = Set.of();
    public static volatile boolean captureLayers = false;
    public static volatile int staleSnapshotTicks = 100;
    public static volatile boolean debugRender = false;

    @SubscribeEvent
    static void onConfigLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == COMMON_SPEC) {
            enabled = ENABLED.get();
            double r = CAPTURE_RADIUS.get();
            captureRadiusSqr = r * r;
            Set<ResourceLocation> bl = new HashSet<>();
            for (String s : BLACKLIST.get()) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) bl.add(rl);
            }
            blacklist = Set.copyOf(bl);
            captureLayers = CAPTURE_LAYERS.get();
            staleSnapshotTicks = STALE_SNAPSHOT_TICKS.get();
        } else if (event.getConfig().getSpec() == CLIENT_SPEC) {
            debugRender = DEBUG_RENDER.get();
        }
    }

    private MahConfig() {
    }
}
