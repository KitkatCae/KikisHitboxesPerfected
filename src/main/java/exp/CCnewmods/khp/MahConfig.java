package exp.CCnewmods.khp;

import exp.CCnewmods.khp.collision.AabbResize;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** KHP config. Hot paths read the baked static fields, never the ConfigValues. */
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
            .comment("A snapshot older than this many ticks (entity not rendered: frustum-culled or",
                    "behind the camera) is considered stale. Stale snapshots still move with the entity,",
                    "but blueprint geometry is preferred over them where available.")
            .defineInRange("stale_snapshot_ticks", 100, 5, 1200);

    private static final ForgeConfigSpec.DoubleValue OPACITY_THRESHOLD = COMMON_BUILDER
            .comment("A texture pixel counts as opaque (hittable) if its alpha, as a fraction of 255, is",
                    "strictly above this value. A face only lets raycasts through if every sampled pixel",
                    "across it is at or below this; any opaque pixel keeps the face solid.",
                    "Default 2/255: zero plus a little headroom for PNG compression noise.")
            .defineInRange("opacity_threshold", 2.0 / 255.0, 0.0, 1.0);

    private static final ForgeConfigSpec.IntValue COMBAT_STALE_SNAPSHOT_TICKS = COMMON_BUILDER
            .comment("Staleness window used by combat hit tests (vanilla melee and arrows, TACZ, Epic",
                    "Fight). Much tighter than stale_snapshot_ticks, since combat needs the pose at the",
                    "instant of the hit. Past this, only entity types in the #khp:combat_blueprint_trusted",
                    "entity type tag fall back to blueprint geometry; everything else uses its vanilla box.")
            .defineInRange("combat_stale_snapshot_ticks", 4, 0, 1200);

    private static final ForgeConfigSpec.DoubleValue MIN_PART_THICKNESS = COMMON_BUILDER
            .comment("Minimum thickness (blocks) of every captured part's box on every axis; thinner parts",
                    "are padded symmetrically. Flat wings, fins and membranes are often modelled with zero",
                    "depth and are nearly impossible to hit when aiming parallel to them without this.",
                    "Default 0.05: reliable to hit, visually imperceptible.")
            .defineInRange("min_part_thickness", 0.05, 0.0, 1.0);

    private static final ForgeConfigSpec.DoubleValue PART_BONE_MISMATCH_WARN_DISTANCE = COMMON_BUILDER
            .comment("Multipart entities (Ice and Fire parts, Ender Dragon / Ender's Cataclysm style",
                    "PartEntity) resolve a hit against the nearest bone of the parent's geometry. If that",
                    "bone is farther than this many blocks from the part, a (throttled) warning is logged.",
                    "The hit still resolves; it never falls back to the part's own vanilla box.")
            .defineInRange("part_bone_mismatch_warn_distance", 2.0, 0.0, 64.0);

    // ── Blueprint → HitboxStore sync ────────────────────────────────────────

    private static final ForgeConfigSpec.IntValue BLUEPRINT_SYNC_INTERVAL_TICKS = COMMON_BUILDER
            .comment("How often (ticks) the server fills in hitbox data from blueprints for entities no",
                    "client is currently rendering (so server-side hits like arrows still use accurate",
                    "geometry, e.g. on dedicated servers). Never replaces a fresh live snapshot. Staggered",
                    "per entity.")
            .defineInRange("blueprint_sync_interval_ticks", 10, 1, 1200);

    private static final ForgeConfigSpec.DoubleValue BLUEPRINT_SYNC_RADIUS = COMMON_BUILDER
            .comment("Entities farther than this from every player are skipped by the blueprint sync.",
                    "Separate from capture_radius so server cost can be tuned on its own.")
            .defineInRange("blueprint_sync_radius", 64.0d, 8.0d, 256.0d);

    // ── Bone/part exclusion (per-entity-type dead-weight geometry) ─────────

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BONE_EXCLUSIONS = COMMON_BUILDER
            .comment("Per-entity-type parts that are never captured, for parts that render but shouldn't",
                    "count as hitbox (e.g. the skeleton horse reuses the horse model, tail included).",
                    "Works for every model type KHP supports.",
                    "",
                    "Format: \"entity_type_id:pattern\", e.g. \"minecraft:skeleton_horse:tail\". The pattern",
                    "matches either the part's full name (vanilla models use a root/-prefixed path such as",
                    "\"root/tail\"; other model types use the bone/box name) or any single \"/\"-separated",
                    "segment of it, so \"tail\" matches both \"root/tail\" and \"root/body/tail\". Check the",
                    "actual part name for your model (debug_render helps).")
            .defineListAllowEmpty("bone_exclusions", List.of("minecraft:skeleton_horse:tail"), o -> {
                if (!(o instanceof String s)) return false;
                int first = s.indexOf(':');
                int second = first < 0 ? -1 : s.indexOf(':', first + 1);
                return second > first && second < s.length() - 1
                        && ResourceLocation.tryParse(s.substring(0, second)) != null;
            });

    // ── Solid mob collision ─────────────────────────────────────────────────

    private static final ForgeConfigSpec.BooleanValue SOLID_COLLISION_ENABLED = COMMON_BUILDER
            .comment("Make mobs solid: entities collide with mobs' precise per-part shapes instead of",
                    "walking through them. When off, mobs stay walk-through as in vanilla.")
            .define("solid_collision_enabled", false);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SOLID_COLLISION_BLACKLIST = COMMON_BUILDER
            .comment("Entity type ids that stay walk-through even when solid_collision_enabled is on.",
                    "Entities that are already solid in vanilla (boats, minecarts, etc.) are unaffected.")
            .defineListAllowEmpty("solid_collision_blacklist", List.of(), o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    private static final ForgeConfigSpec.IntValue SOLID_COLLISION_STALE_SNAPSHOT_TICKS = COMMON_BUILDER
            .comment("Staleness window for the live snapshot used to build a solid mob's collision",
                    "shape. Older than this, blueprint geometry is used instead.")
            .defineInRange("solid_collision_stale_snapshot_ticks", 4, 0, 1200);

    private static final ForgeConfigSpec.IntValue SOLID_COLLISION_MAX_PARTS = COMMON_BUILDER
            .comment("Entities with more captured parts than this use their vanilla box as the collision",
                    "shape instead (they stay solid). A performance limit for very detailed models.")
            .defineInRange("solid_collision_max_parts", 1024, 1, 4096);

    // ── Part displacement transfer ──────────────────────────────────────────

    private static final ForgeConfigSpec.BooleanValue DISPLACEMENT_TRANSFER_ENABLED = COMMON_BUILDER
            .comment("Entities resting against a solid mob are carried along when its animated parts",
                    "move, instead of being shoved out by vanilla collision (which causes stutter).")
            .define("displacement_transfer_enabled", true);

    private static final ForgeConfigSpec.DoubleValue DISPLACEMENT_TRANSFER_EPSILON = COMMON_BUILDER
            .comment("Minimum per-tick movement (blocks) a part must make before nearby entities are",
                    "checked for carrying. Keeps static and gently idling mobs essentially free. Should",
                    "be well above float noise (~1e-4) and well below a noticeable shove.")
            .defineInRange("displacement_transfer_epsilon", 0.002, 0.0, 1.0);

    // ── AABB resize ─────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.BooleanValue AABB_RESIZE_ENABLED = COMMON_BUILDER
            .comment("Resize each entity's vanilla collision box to cover its model's actual extent. When",
                    "off (default), entities keep their normal vanilla box.")
            .define("aabb_resize_enabled", false);

    private static final ForgeConfigSpec.EnumValue<AabbResize.Mode> AABB_RESIZE_MODE = COMMON_BUILDER
            .comment("STABLE: each entity's box only ever grows as more of its poses are seen, then just",
                    "moves with it; its size doesn't fluctuate. May be briefly small right after spawn.",
                    "",
                    "LIVE: rebuilt from the current pose every time; a tighter fit, but the box grows and",
                    "shrinks as the mob animates (e.g. an attack swing widens it).",
                    "",
                    "In both modes the box is the union of the model's parts, so it can be off-center.")
            .defineEnum("aabb_resize_mode", AabbResize.Mode.STABLE);

    private static final ForgeConfigSpec.IntValue AABB_RESIZE_MAX_PARTS = COMMON_BUILDER
            .comment("Entities with more captured parts than this keep their vanilla box for resizing.")
            .defineInRange("aabb_resize_max_parts", 64, 1, 4096);

    // ── Precise render culling ──────────────────────────────────────────────

    private static final ForgeConfigSpec.BooleanValue CULLING_ENABLED = COMMON_BUILDER
            .comment("Per-part frustum culling: an entity is only rendered if at least one of its parts is",
                    "in view, instead of testing one box around the whole entity. Can only cull more than",
                    "vanilla, never less (e.g. a coiled snake whose overall box is mostly empty space).",
                    "Entities without hitbox data use vanilla's test.")
            .define("culling_enabled", true);

    private static final ForgeConfigSpec.IntValue CULLING_MAX_PARTS = COMMON_BUILDER
            .comment("Entities with more captured parts than this use vanilla's whole-entity culling test.")
            .defineInRange("culling_max_parts", 256, 1, 4096);

    private static final ForgeConfigSpec.IntValue LIVING_PLATFORM_STALE_SNAPSHOT_TICKS = COMMON_BUILDER
            .comment("Staleness window for living platforms (buildable VS2 ships anchored to a mob's bone,",
                    "requires Valkyrien Skies 2). Tighter than combat's because a swaying platform shows a",
                    "stale pose very visibly.")
            .defineInRange("living_platform_stale_snapshot_ticks", 2, 0, 1200);

    private static final ForgeConfigSpec.BooleanValue LIVING_PLATFORM_EAGER_CREATION = COMMON_BUILDER
            .comment("If true, a living-platform mob gets a ship for every anchor as soon as it spawns or",
                    "loads. If false (default), each anchor's ship is created the first time a player",
                    "right-clicks near it.")
            .define("living_platform_eager_creation", false);

    public static final ForgeConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ── CLIENT ──────────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue DEBUG_RENDER = CLIENT_BUILDER
            .comment("Render captured hitbox wireframes in-world (green = live capture).")
            .define("debug_render", false);

    private static final ForgeConfigSpec.BooleanValue DEV_MODE_POSE_CAPTURE = CLIENT_BUILDER
            .comment("For modpack/addon authors. When on, captures any on-screen entity pose that doesn't",
                    "match an existing blueprint pose and appends it to config/khp/animstatecache_capture/,",
                    "to be copied into a data/khp/animstatecache/ blueprint file. Also toggled by keybind",
                    "(default F9).")
            .define("dev_mode_pose_capture", false);

    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ── Baked values ────────────────────────────────────────────────────────

    public static volatile boolean enabled = true;
    public static volatile double captureRadiusSqr = 64.0 * 64.0;
    public static volatile Set<ResourceLocation> blacklist = Set.of();
    public static volatile boolean captureLayers = false;
    public static volatile int staleSnapshotTicks = 100;
    public static volatile int combatStaleSnapshotTicks = 4;
    public static volatile boolean debugRender = false;
    public static volatile double opacityThreshold = 2.0 / 255.0;
    public static volatile double minPartThickness = 0.9;
    public static volatile double partBoneMismatchWarnDistance = 2.0;
    public static volatile boolean devModePoseCapture = false;

    public static volatile int blueprintSyncIntervalTicks = 10;
    public static volatile double blueprintSyncRadiusSqr = 64.0 * 64.0;

    public static volatile Map<ResourceLocation, Set<String>> boneExclusions = Map.of();

    public static volatile boolean solidCollisionEnabled = true;
    public static volatile Set<ResourceLocation> solidCollisionBlacklist = Set.of();
    public static volatile int solidCollisionStaleSnapshotTicks = 4;
    public static volatile int solidCollisionMaxParts = 1024;

    public static volatile boolean displacementTransferEnabled = true;
    public static volatile double displacementTransferEpsilon = 0.002;

    public static volatile boolean aabbResizeEnabled = false;
    public static volatile AabbResize.Mode aabbResizeMode = AabbResize.Mode.STABLE;
    public static volatile int aabbResizeMaxParts = 1024;

    public static volatile boolean cullingEnabled = true;
    public static volatile int cullingMaxParts = 256;

    public static volatile int livingPlatformStaleSnapshotTicks = 2;
    public static volatile boolean livingPlatformEagerCreation = false;

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
            combatStaleSnapshotTicks = COMBAT_STALE_SNAPSHOT_TICKS.get();
            opacityThreshold = OPACITY_THRESHOLD.get();
            minPartThickness = MIN_PART_THICKNESS.get();
            partBoneMismatchWarnDistance = PART_BONE_MISMATCH_WARN_DISTANCE.get();

            blueprintSyncIntervalTicks = BLUEPRINT_SYNC_INTERVAL_TICKS.get();
            double bsr = BLUEPRINT_SYNC_RADIUS.get();
            blueprintSyncRadiusSqr = bsr * bsr;

            Map<ResourceLocation, Set<String>> be = new HashMap<>();
            for (String s : BONE_EXCLUSIONS.get()) {
                int first = s.indexOf(':');
                int second = first < 0 ? -1 : s.indexOf(':', first + 1);
                if (second <= first || second >= s.length() - 1) continue;
                ResourceLocation entityType = ResourceLocation.tryParse(s.substring(0, second));
                String pattern = s.substring(second + 1);
                if (entityType == null || pattern.isEmpty()) continue;
                be.computeIfAbsent(entityType, k -> new HashSet<>()).add(pattern);
            }
            Map<ResourceLocation, Set<String>> beFinal = new HashMap<>();
            be.forEach((k, v) -> beFinal.put(k, Set.copyOf(v)));
            boneExclusions = Map.copyOf(beFinal);

            solidCollisionEnabled = SOLID_COLLISION_ENABLED.get();
            Set<ResourceLocation> scbl = new HashSet<>();
            for (String s : SOLID_COLLISION_BLACKLIST.get()) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) scbl.add(rl);
            }
            solidCollisionBlacklist = Set.copyOf(scbl);
            solidCollisionStaleSnapshotTicks = SOLID_COLLISION_STALE_SNAPSHOT_TICKS.get();
            solidCollisionMaxParts = SOLID_COLLISION_MAX_PARTS.get();

            displacementTransferEnabled = DISPLACEMENT_TRANSFER_ENABLED.get();
            displacementTransferEpsilon = DISPLACEMENT_TRANSFER_EPSILON.get();

            aabbResizeEnabled = AABB_RESIZE_ENABLED.get();
            aabbResizeMode = AABB_RESIZE_MODE.get();
            aabbResizeMaxParts = AABB_RESIZE_MAX_PARTS.get();

            cullingEnabled = CULLING_ENABLED.get();
            cullingMaxParts = CULLING_MAX_PARTS.get();

            livingPlatformStaleSnapshotTicks = LIVING_PLATFORM_STALE_SNAPSHOT_TICKS.get();
            livingPlatformEagerCreation = LIVING_PLATFORM_EAGER_CREATION.get();
        } else if (event.getConfig().getSpec() == CLIENT_SPEC) {
            debugRender = DEBUG_RENDER.get();
            devModePoseCapture = DEV_MODE_POSE_CAPTURE.get();
        }
    }

    /** Also writes the change back to the config file. */
    public static void setDevModePoseCapture(boolean value) {
        devModePoseCapture = value;
        DEV_MODE_POSE_CAPTURE.set(value);
    }

    private MahConfig() {
    }
}
