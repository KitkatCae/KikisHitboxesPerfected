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

    private static final ForgeConfigSpec.DoubleValue OPACITY_THRESHOLD = COMMON_BUILDER
            .comment("A texture pixel counts as opaque (face stays hittable) if its alpha, as a",
                    "fraction of 255, is strictly above this value. A face is culled from raycasts",
                    "(faceMask bit set) only if every sampled pixel across its whole UV footprint",
                    "falls at or below this threshold — any opaque pixel anywhere on the face keeps",
                    "it solid. Default 2/255: strict zero plus a little headroom for PNG compression",
                    "noise on otherwise-transparent regions. Single global value — not per-material;",
                    "materials that need different behaviour are a resistance_factor-style follow-up",
                    "if it ever comes up, not a day-one field.")
            .defineInRange("opacity_threshold", 2.0 / 255.0, 0.0, 1.0);

    private static final ForgeConfigSpec.IntValue COMBAT_STALE_SNAPSHOT_TICKS = COMMON_BUILDER
            .comment("Separate, much tighter staleness window than stale_snapshot_ticks, used only",
                    "by combat hit-test redirects (TACZ/Epic Fight/vanilla melee, Phase 2C). A live",
                    "snapshot older than this many ticks is treated as \"not fresh\" for combat",
                    "purposes even though it would still count as fresh for debug-render — combat",
                    "cares about pose accuracy at the instant of the hit far more than a wireframe",
                    "overlay does. See KHP_Phase2C_Design.md §3.")
            .defineInRange("combat_stale_snapshot_ticks", 4, 0, 1200);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> COMBAT_BLUEPRINT_ALLOWLIST = COMMON_BUILDER
            .comment("Entity type ids allowed to fall back to blueprint-resolved (non-live) geometry",
                    "during a combat hit-test when no live snapshot is fresh within",
                    "combat_stale_snapshot_ticks. Entities NOT on this list fall straight to vanilla",
                    "AABB instead of a possibly-wrong-pose blueprint hit.",
                    "Empty (the default) means \"auto\": membership is decided per-entity at query",
                    "time by resolving the entity's blueprint the same baby-first/adult-fallback way",
                    "BlueprintResolver always does, and checking whether that blueprint's rendererKind",
                    "is VANILLA_LIVING — the only tier where walk/attack pose-matching actually works",
                    "(see BlueprintResolver's class javadoc). List specific modded entity ids here to",
                    "extend combat trust to a non-VANILLA_LIVING blueprint once you've verified its",
                    "pose accuracy is good enough. See KHP_Phase2C_Design.md §3.")
            .defineListAllowEmpty("combat_blueprint_allowlist", List.of(), o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    private static final ForgeConfigSpec.DoubleValue MIN_PART_THICKNESS = COMMON_BUILDER
            .comment("Every captured part's box is padded (symmetrically about its own center, every",
                    "axis) so its extent is never below this many blocks — applied unconditionally in",
                    "PartOBB's own constructor, after Option A/B's opaque-region tightening has",
                    "already run against the true, unpadded dimensions (this only ever changes what",
                    "the FINAL stored box looks like, not whether tightening was attempted).",
                    "",
                    "A genuinely zero-thickness box (dead flat quad — very common for wings/fins/",
                    "membranes authored as a single 0-depth cube) is close to unhittable by",
                    "HitboxRaycaster's slab test specifically whenever the ray's LOCAL-space direction",
                    "on that thin axis is itself near zero — i.e. whenever the player is aiming",
                    "roughly parallel to the flat plane, which for a horizontal fin/wing during normal",
                    "roughly-level melee aim is the common case, not an edge case. The slab test's own",
                    "near-parallel branch requires the ray origin's local coordinate on that axis to",
                    "land exactly on a single float value when the box has zero width there — never",
                    "true in practice. Padding to a small real thickness gives that axis a genuine,",
                    "non-degenerate valid range instead, fixing the near-parallel rejection and the",
                    "exact-pinned-t precision issue at the same time, without touching the raycast",
                    "math itself.",
                    "",
                    "Default 0.05 blocks (~0.8 voxels) — comfortably real for hit-testing tolerance,",
                    "imperceptible visually (under a texel on a typical mob-sized cube).")
            .defineInRange("min_part_thickness", 0.05, 0.0, 0.5);

    private static final ForgeConfigSpec.DoubleValue PART_BONE_MISMATCH_WARN_DISTANCE = COMMON_BUILDER
            .comment("For multipart-proxy entities (Ice and Fire's EntityMutlipartPart, Ender's",
                    "Cataclysm/vanilla-Ender-Dragon-style PartEntity), MAH resolves a hit against the",
                    "nearest bone in the PARENT's captured geometry to the part's current position —",
                    "always resolves to SOME bone, never blocks. If the nearest match is farther than",
                    "this many blocks away, it's very likely the wrong bone (a genuinely mismatched",
                    "part position, or missing/incomplete capture) — logged as a warning (throttled",
                    "per part) so it can be investigated, but the resolution still proceeds; it does",
                    "NOT fall back to the part's own vanilla box. Per Caelan: never block, just flag.")
            .defineInRange("part_bone_mismatch_warn_distance", 2.0, 0.0, 64.0);

    // ── Blueprint → HitboxStore sync (Phase 3) ──────────────────────────────

    private static final ForgeConfigSpec.IntValue BLUEPRINT_SYNC_INTERVAL_TICKS = COMMON_BUILDER
            .comment("How often (in ticks) each eligible living entity is considered for the",
                    "blueprint-to-HitboxStore sync job — server-side, populates HitboxStore from",
                    "an entity's blueprint whenever no fresher LIVE_CLIENT snapshot already covers",
                    "it, so combat/candidate-search geometry exists even where no client has",
                    "rendered the entity this session (a real dedicated server's arrow hits, mainly).",
                    "Staggered per-entity by entity id so entities don't all sync on the same tick.",
                    "Never downgrades a fresh live snapshot — see KHP_Snapshot_Extent_Candidate_",
                    "Search_Design.md §9.2.")
            .defineInRange("blueprint_sync_interval_ticks", 10, 1, 1200);

    private static final ForgeConfigSpec.DoubleValue BLUEPRINT_SYNC_RADIUS = COMMON_BUILDER
            .comment("Entities farther than this from every player are skipped by the blueprint",
                    "sync job — mirrors capture_radius's own gate, own value so it can be tuned",
                    "independently (server-side cost profile differs from client-side capture).")
            .defineInRange("blueprint_sync_radius", 64.0d, 8.0d, 256.0d);

    // ── Bone/part exclusion (per-entity-type dead-weight geometry) ─────────

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BONE_EXCLUSIONS = COMMON_BUILDER
            .comment("Entity-type-scoped bone/part exclusions — geometry from a matching named part is",
                    "never captured at all (no PartOBB, no faceMask/flatFaceUV, nothing), for parts that",
                    "still render but are structural dead weight for a given entity (e.g. skeleton_horse",
                    "reusing the regular horse model wholesale, including a tail bone the skeleton horse",
                    "never meaningfully draws). Checked at every capture entry point — vanilla ModelPart,",
                    "GeckoLib bones, Citadel/LLibrary AdvancedModelBox, EpicFight skinned-mesh joints, and",
                    "raw-vertex capture (Alex's Caves and similar) — so it works regardless of which",
                    "rendering framework a given entity's model uses. This is a coarser tool than",
                    "opacity_threshold — that denies individual transparent pixels on a",
                    "captured cube; this skips capturing the part in the first place, for the whole bracket.",
                    "",
                    "Format: \"entity_type_id:pattern\", e.g. \"minecraft:skeleton_horse:tail\". entity_type_id",
                    "is the usual namespace:path registry id. pattern matches either the full identifier the",
                    "capture path uses for that part (vanilla's root/-prefixed path, e.g. \"root/tail\"; a",
                    "flat bone/box name everywhere else) or any single \"/\"-separated segment within it (so",
                    "\"tail\" alone matches both a top-level root/tail vanilla bone and a nested",
                    "root/body/tail one) — verify the exact name/segment for your target model before",
                    "relying on this, part names aren't guaranteed by this comment.")
            .defineListAllowEmpty("bone_exclusions", List.of("minecraft:skeleton_horse:tail"), o -> {
                if (!(o instanceof String s)) return false;
                int first = s.indexOf(':');
                int second = first < 0 ? -1 : s.indexOf(':', first + 1);
                return second > first && second < s.length() - 1
                        && ResourceLocation.tryParse(s.substring(0, second)) != null;
            });

    // ── Diagnostics ──────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> DEBUG_COMBAT_MISS_WATCHLIST = COMMON_BUILDER
            .comment("Entity type ids to log detailed combat-miss diagnostics for — entityId,",
                    "hasCombatCandidates, snapshot presence/freshness/source, and part count — every",
                    "time ProjectileUtilMixin's melee redirect resolves to a genuine miss while one of",
                    "these types is within the search bounds. Empty by default (no overhead, no log",
                    "spam). Intended as a short-lived, per-investigation tool — add an entity id,",
                    "reproduce the miss in-game, read the log, remove it again — not a permanent",
                    "always-on watch. Currently used for the open Maze Mother investigation",
                    "(KHP_Snapshot_Extent_Candidate_Search_Design.md §8): its symptom doesn't fit",
                    "the search-margin explanation the rest of that design fixes, so this exists to",
                    "get a real trace instead of another guess.")
            .defineListAllowEmpty("debug_combat_miss_watchlist", List.of(), o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> DEBUG_OPACITY_WATCHLIST = COMMON_BUILDER
            .comment("Entity key ids (matched against EntityHitboxSnapshot.entityKey(), same key",
                    "MAH's own capture/raycast pipeline uses — usually the EntityType id, but not",
                    "always: corpses key by mob type via a registered EntityKeyProvider) to log a",
                    "detailed transparency-raycast trace for. Every time HitboxRaycaster tests a ray",
                    "against a watchlisted entity's flat parts, this logs the entered face, the",
                    "faceMask pre-filter result, and — if it reaches Option B — the sampled UV,",
                    "opacity, threshold, and final hit/miss verdict, under the MAH/raycast-debug",
                    "logger. Empty by default (one Set.isEmpty() check, no overhead, no log spam) —",
                    "same short-lived, per-investigation shape as debug_combat_miss_watchlist: add an",
                    "id, reproduce the miss/hit in-game, read the log, remove it again. Not a",
                    "permanent always-on trace, and NOT the same thing as debug_render (MahConfig",
                    "CLIENT) — debug_render draws Option A/B's capture-time result for every tracked",
                    "entity every frame (visually useful, but expensive with many entities nearby and",
                    "says nothing about what a specific attack's raycast actually decided); this",
                    "watchlist is the raycast-time counterpart, cheap enough to leave server-side",
                    "config alone between sessions.")
            .defineListAllowEmpty("debug_opacity_watchlist", List.of(), o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    // ── Solid mob collision (Phase 2D, Part A) ──────────────────────────────

    private static final ForgeConfigSpec.BooleanValue SOLID_COLLISION_ENABLED = COMMON_BUILDER
            .comment("Master switch for Phase 2D solid-mob collision. When off, canBeCollidedWith()",
                    "is left at vanilla's default (false) for every entity — mobs remain walk-through,",
                    "same as unmodified vanilla. KHP builds this itself rather than depending on",
                    "solid_mobs (reference-only for this project, not a runtime dependency).")
            .define("solid_collision_enabled", false);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SOLID_COLLISION_BLACKLIST = COMMON_BUILDER
            .comment("Entity type ids excluded from solid collision even when solid_collision_enabled",
                    "is true — mirrors solid_mobs' own blacklist-not-allowlist design (mobs are solid",
                    "by default; list specific exceptions here). Boats, minecarts, and anything already",
                    "handled by vanilla's own canBeCollidedWith overrides are unaffected either way —",
                    "this mixin only changes entities where vanilla's default (false) would otherwise",
                    "apply, i.e. ordinary living mobs.")
            .defineListAllowEmpty("solid_collision_blacklist", List.of(), o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    private static final ForgeConfigSpec.IntValue SOLID_COLLISION_STALE_SNAPSHOT_TICKS = COMMON_BUILDER
            .comment("Staleness window for the live snapshot used to build a solid entity's precise",
                    "per-part collision shape (Phase 2D Part A2). Independent of both",
                    "stale_snapshot_ticks (debug-render, 100) and combat_stale_snapshot_ticks (4) —",
                    "collision runs every tick for every nearby pair, not once per attack, so this",
                    "threshold is free to be tuned separately if it ever needs to differ from combat's.",
                    "Default matches combat's 4 as a reasonable starting point, not because the two",
                    "are the same system.")
            .defineInRange("solid_collision_stale_snapshot_ticks", 4, 0, 1200);

    private static final ForgeConfigSpec.IntValue SOLID_COLLISION_MAX_PARTS = COMMON_BUILDER
            .comment("Hard cap on how many PartOBB boxes are unioned into a single entity's precise",
                    "collision shape. Entities whose snapshot has more parts than this fall back to",
                    "vanilla's plain whole-body AABB for collision purposes (shape accuracy is skipped,",
                    "solidity itself is not affected) — a cost-control valve, not a correctness one.",
                    "Original default (48) was an unprofiled placeholder and confirmed in testing to be",
                    "too low for several real GeckoLib models in this pack (one entity alone had 74",
                    "captured parts and was silently falling back to the vanilla box). Raised to 256 on",
                    "that evidence — still not a profiled number, just no longer obviously wrong; revisit",
                    "with real dedicated-server numbers if performance ever becomes a concern.")
            .defineInRange("solid_collision_max_parts", 1024, 1, 4096);

    // ── AABB resize (replaces Phase 2D Part A3) ──────────────────────────────
    // Part A3 (per-part precise terrain sweep) was removed after testing showed
    // the most-restrictive-across-parts combine zeroed out movement for most mob
    // types whenever any single part's captured geometry drifted slightly into a
    // block — see EntityCollideEntityShapesMixin's javadoc. This replaces it with
    // a far simpler approach: resize the entity's own single vanilla collision
    // box to cover its model's actual extent, then let vanilla's completely
    // unmodified single-box collision handle everything else. Off by default —
    // opt in per-pack once you've checked it against your own mob roster.

    private static final ForgeConfigSpec.BooleanValue AABB_RESIZE_ENABLED = COMMON_BUILDER
            .comment("Master switch. When off (default), entities keep vanilla's registered",
                    "EntityDimensions-based box exactly as normal — nothing in this section runs.")
            .define("aabb_resize_enabled", false);

    private static final ForgeConfigSpec.EnumValue<AabbResize.Mode> AABB_RESIZE_MODE = COMMON_BUILDER
            .comment("STABLE: the resized box is built once per entity (from an envelope that only ever",
                    "grows, never shrinks, as more of that entity's animated poses are observed — see",
                    "AabbResize's own javadoc) and then just translates with the entity afterward. Box",
                    "size/shape never fluctuates tick to tick.",
                    "",
                    "LIVE: the box is rebuilt every call from whatever the current animated pose's",
                    "captured parts look like right now — tighter fit, but the box grows and shrinks",
                    "continuously as the mob animates (e.g. an attack swing temporarily widens it).",
                    "",
                    "Both are asymmetric/off-center rectangular boxes (the actual union of the model's",
                    "part extents, not a symmetric width/height box like vanilla's) in either mode — the",
                    "only difference is whether that shape is captured once and reused or rebuilt live.")
            .defineEnum("aabb_resize_mode", AabbResize.Mode.STABLE);

    private static final ForgeConfigSpec.IntValue AABB_RESIZE_MAX_PARTS = COMMON_BUILDER
            .comment("Hard cap on how many PartOBB boxes are unioned to build the resized box. Entities",
                    "over this cap keep their vanilla box untouched for this feature (rendering/culling",
                    "are unaffected, they have their own separate caps).")
            .defineInRange("aabb_resize_max_parts", 64, 1, 4096);

    // ── Precise render culling (Phase 2D, Part B) ────────────────────────────

    private static final ForgeConfigSpec.BooleanValue CULLING_ENABLED = COMMON_BUILDER
            .comment("Master switch for per-part frustum culling. When off, EntityRenderer.shouldRender",
                    "keeps vanilla's behavior of testing one inflated union AABB against the frustum.",
                    "When on, entities with a resolvable snapshot are tested part-by-part instead (visible",
                    "if ANY part intersects the frustum) — strictly tighter than the union box, so it can",
                    "only cull MORE aggressively than vanilla, never less; a long/bent entity (e.g. a snake",
                    "coiled into an L-shape) whose union box spans mostly-empty space stops being kept",
                    "'visible' once no individual part is actually in view. Entities with no snapshot fall",
                    "through to vanilla's normal union-box test unchanged.")
            .define("culling_enabled", true);

    private static final ForgeConfigSpec.IntValue CULLING_MAX_PARTS = COMMON_BUILDER
            .comment("Hard cap on how many PartOBB boxes are tested individually against the frustum per",
                    "entity per shouldRender() call. Entities over this cap fall back to vanilla's",
                    "whole-body union-box cull test. Kept separate from block_collision_max_parts since",
                    "this runs once per entity per frame (client-side only) rather than per axis-move —",
                    "cheaper per-call, so a higher default is reasonable.")
            .defineInRange("culling_max_parts", 256, 1, 4096);

    private static final ForgeConfigSpec.IntValue LIVING_PLATFORM_STALE_SNAPSHOT_TICKS = COMMON_BUILDER
            .comment("Separate, much tighter staleness window than stale_snapshot_ticks, used only by",
                    "KHP Phase 2E living platforms (entity-anchored VS2 shipyards). The client already",
                    "produces a fresh interpolated pose every rendered frame for any mob in range, so a",
                    "tight snapshot-acceptance window here on the server-authoritative transform-provider",
                    "side is not a new cost — it's just a tighter acceptance window on data that's already",
                    "being produced. Default 2, deliberately tighter than combat_stale_snapshot_ticks (4):",
                    "a swaying ship platform is far more visually sensitive to a stale pose than a hit-test",
                    "is. See KHP_Phase2E_Design.md §3.")
            .defineInRange("living_platform_stale_snapshot_ticks", 2, 0, 1200);

    private static final ForgeConfigSpec.BooleanValue LIVING_PLATFORM_EAGER_CREATION = COMMON_BUILDER
            .comment("If true, a whitelisted living-platform entity gets a ServerShip created for every",
                    "one of its anchors immediately on spawn/chunk-load. If false (default), ship creation",
                    "is lazy — the first anchor is only created the first time a player's raycast resolves",
                    "to it (see KHP_Phase2E_Design.md §6.2), matching this project's 'no ceremony' norm for",
                    "buildable-on mobs.")
            .define("living_platform_eager_creation", false);

    public static final ForgeConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ── CLIENT ──────────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue DEBUG_RENDER = CLIENT_BUILDER
            .comment("Render captured hitbox wireframes in-world (green = live capture).",
                    "Phase 1 acceptance tool — verify boxes are glued to animated models.")
            .define("debug_render", false);

    private static final ForgeConfigSpec.BooleanValue DEV_MODE_POSE_CAPTURE = CLIENT_BUILDER
            .comment("Dev-only. When on, automatically captures any on-screen entity pose that",
                    "doesn't closely match an existing blueprint pose for its entity type, and",
                    "appends it to config/khp/animstatecache_capture/ for later manual promotion",
                    "into a real data/khp/animstatecache/ blueprint file. See design doc §5.",
                    "Also toggleable live via keybind without restarting.")
            .define("dev_mode_pose_capture", false);

    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ── Baked values (read by hot paths) ────────────────────────────────────

    public static volatile boolean enabled = true;
    public static volatile double captureRadiusSqr = 64.0 * 64.0;
    public static volatile Set<ResourceLocation> blacklist = Set.of();
    public static volatile boolean captureLayers = false;
    public static volatile int staleSnapshotTicks = 100;
    public static volatile int combatStaleSnapshotTicks = 4;
    public static volatile Set<ResourceLocation> combatBlueprintAllowlist = Set.of();
    public static volatile boolean debugRender = false;
    public static volatile double opacityThreshold = 2.0 / 255.0;
    public static volatile double minPartThickness = 0.05;
    public static volatile double partBoneMismatchWarnDistance = 2.0;
    public static volatile boolean devModePoseCapture = false;

    public static volatile int blueprintSyncIntervalTicks = 10;
    public static volatile double blueprintSyncRadiusSqr = 64.0 * 64.0;

    public static volatile Set<ResourceLocation> debugCombatMissWatchlist = Set.of();
    public static volatile Set<ResourceLocation> debugOpacityWatchlist = Set.of();

    /** entity type id → set of exact-path-or-segment patterns. See BONE_EXCLUSIONS comment. */
    public static volatile Map<ResourceLocation, Set<String>> boneExclusions = Map.of();

    public static volatile boolean solidCollisionEnabled = true;
    public static volatile Set<ResourceLocation> solidCollisionBlacklist = Set.of();
    public static volatile int solidCollisionStaleSnapshotTicks = 4;
    public static volatile int solidCollisionMaxParts = 256;

    public static volatile boolean aabbResizeEnabled = false;
    public static volatile AabbResize.Mode aabbResizeMode = AabbResize.Mode.STABLE;
    public static volatile int aabbResizeMaxParts = 64;

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
            Set<ResourceLocation> cba = new HashSet<>();
            for (String s : COMBAT_BLUEPRINT_ALLOWLIST.get()) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) cba.add(rl);
            }
            combatBlueprintAllowlist = Set.copyOf(cba);
            opacityThreshold = OPACITY_THRESHOLD.get();
            minPartThickness = MIN_PART_THICKNESS.get();
            partBoneMismatchWarnDistance = PART_BONE_MISMATCH_WARN_DISTANCE.get();

            blueprintSyncIntervalTicks = BLUEPRINT_SYNC_INTERVAL_TICKS.get();
            double bsr = BLUEPRINT_SYNC_RADIUS.get();
            blueprintSyncRadiusSqr = bsr * bsr;

            Set<ResourceLocation> dcmw = new HashSet<>();
            for (String s : DEBUG_COMBAT_MISS_WATCHLIST.get()) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) dcmw.add(rl);
            }
            debugCombatMissWatchlist = Set.copyOf(dcmw);

            Set<ResourceLocation> dow = new HashSet<>();
            for (String s : DEBUG_OPACITY_WATCHLIST.get()) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) dow.add(rl);
            }
            debugOpacityWatchlist = Set.copyOf(dow);

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

    /**
     * Flip {@link #devModePoseCapture} live (keybind — see
     * {@code exp.CCnewmods.khp.blueprint.devcapture.DevModeKeyBindings}) and persist
     * it back to the actual config file, so the toggle survives a restart too, not
     * just the current session.
     */
    public static void setDevModePoseCapture(boolean value) {
        devModePoseCapture = value;
        DEV_MODE_POSE_CAPTURE.set(value);
    }

    private MahConfig() {
    }
}
