package exp.CCnewmods.khp.api;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.blueprint.BlueprintResolver;
import exp.CCnewmods.khp.capture.BoneTable;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.capture.HitboxCaptureManager;
import exp.CCnewmods.khp.capture.HitboxRaycaster;
import exp.CCnewmods.khp.capture.HitboxStore;
import exp.CCnewmods.khp.capture.PartOBB;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Public API for MAH consumers (Misanthrope Core wounds/dissection, MisWorld
 * shockwaves/material solidity, and anything else that wants model-accurate geometry).
 *
 * <p>Phase 1 exposes snapshots, bone tables, entity keying, and the provider
 * registration points. The raycast surface ({@code raycast}, {@code sampleExposure},
 * {@code PartHitResult}) lands in phase 2; solidity is consumed by the collision layer
 * in phase 4 — register providers now, they take effect the moment that layer ships.
 */
public final class MahApi {

    private static final List<EntityKeyProvider> KEY_PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<PartSolidityProvider> SOLIDITY_PROVIDERS = new CopyOnWriteArrayList<>();

    private static final ResourceLocation UNKNOWN = new ResourceLocation("mah", "unknown");

    // ── Entity keying ────────────────────────────────────────────────────────

    /**
     * Register a provider that overrides which key (and therefore which BoneTable and,
     * later, which blueprint) an entity resolves to. First non-null answer wins.
     *
     * <p>Misanthrope Core registers one line for corpses:
     * {@code MahApi.registerEntityKeyProvider(e -> e instanceof CorpseEntity c ? c.getSourceMobType() : null);}
     * — replacing the old CorpseHitboxMixin entirely.
     */
    public static void registerEntityKeyProvider(EntityKeyProvider provider) {
        KEY_PROVIDERS.add(provider);
    }

    /** Resolve the MAH entity key for an entity: providers first, then the type id. */
    public static ResourceLocation entityKey(Entity entity) {
        for (EntityKeyProvider provider : KEY_PROVIDERS) {
            ResourceLocation key = provider.keyFor(entity);
            if (key != null) return key;
        }
        return entityTypeKey(entity);
    }

    /** The raw EntityType registry id, ignoring key providers. */
    public static ResourceLocation entityTypeKey(Entity entity) {
        ResourceLocation rl = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return rl != null ? rl : UNKNOWN;
    }

    // ── Snapshots & bone tables ──────────────────────────────────────────────

    /**
     * Live snapshot if fresh; otherwise a snapshot resolved from a loaded blueprint
     * (see {@code exp.CCnewmods.khp.blueprint.BlueprintResolver}), if one is
     * available for this entity; otherwise whatever live snapshot exists even if
     * stale (better than nothing), or {@code null} if there's genuinely nothing.
     *
     * <p>This is the point where the two producers described in
     * {@code KHP_Headless_Resolver_Design_v2.md} §2 actually merge into one
     * consumer — every caller below ({@link #raycast}, {@link #raycastEntity},
     * {@link #shapecast}) goes through this same method, so none of them need to
     * know or care whether the geometry they got came from a live render or a
     * resolved blueprint.
     */
    @Nullable
    public static EntityHitboxSnapshot getSnapshot(Entity entity) {
        EntityHitboxSnapshot live = HitboxStore.get(entity.getUUID());
        if (live != null && !live.isStale(entity.level().getGameTime(), MahConfig.staleSnapshotTicks)) {
            return live;
        }
        EntityHitboxSnapshot resolved = BlueprintResolver.resolve(entity);
        return resolved != null ? resolved : live;
    }

    /**
     * True if the entity has a genuinely <b>live</b> snapshot no older than the
     * configured staleness window — deliberately not blueprint-aware, unlike
     * {@link #getSnapshot}. A blueprint-resolved snapshot is synthesized fresh on
     * every call (never stale by construction — see {@code BlueprintResolver}), so
     * routing this method through {@link #getSnapshot} would make it report "fresh"
     * for approximate blueprint-replay data too, silently changing what "fresh"
     * means for any caller relying on this to mean "genuinely live, high-fidelity
     * geometry" (e.g. Phase 2C's combat-specific staleness threshold discussion).
     */
    public static boolean hasFreshSnapshot(Entity entity) {
        return hasFreshSnapshot(entity, MahConfig.staleSnapshotTicks);
    }

    /**
     * Same as {@link #hasFreshSnapshot(Entity)} but against a caller-supplied
     * staleness threshold instead of {@code MahConfig.staleSnapshotTicks} — Phase 2C
     * combat redirects use this with {@code MahConfig.combatStaleSnapshotTicks}
     * (default 4), a much tighter window than debug-render's 100.
     */
    public static boolean hasFreshSnapshot(Entity entity, int thresholdTicks) {
        EntityHitboxSnapshot snap = HitboxStore.get(entity.getUUID());
        return snap != null && !snap.isStale(entity.level().getGameTime(), thresholdTicks);
    }

    @Nullable
    public static BoneTable getBoneTable(ResourceLocation entityKey) {
        return HitboxStore.getBoneTable(entityKey);
    }

    // ── Anatomical RenderLayer allowlist (Phase 1D) ─────────────────────────

    /**
     * Register a RenderLayer class whose geometry is real anatomy, not a cosmetic
     * overlay — captured as primary even while {@code capture_layers} is off for
     * everything else. Use this for the rare case where a mod implements actual
     * body parts as a RenderLayer (varying head counts, squishy secondary bodies,
     * etc.) rather than for armor/saddle/held-item layers, which should stay
     * excluded by default.
     */
    public static void registerAnatomicalRenderLayer(Class<?> layerClass) {
        HitboxCaptureManager.registerAnatomicalRenderLayer(layerClass);
    }

    // ── Solidity hooks (consumed by the phase-4 collision layer) ────────────

    /**
     * Register a provider that scales how solid a bone is for collision, 0 (pass
     * through) to 1 (fully solid). Providers chain: each receives the folded value so
     * far and returns the new one. MisWorld's material-physics addon uses this to make
     * e.g. a creeper's foliage-flesh yielding while its skeleton stays firm.
     */
    public static void registerSolidityProvider(PartSolidityProvider provider) {
        SOLIDITY_PROVIDERS.add(provider);
    }

    /** Fold all registered providers over the default solidity of 1.0. */
    public static float getSolidity(Entity entity, String boneName) {
        float solidity = 1.0f;
        for (PartSolidityProvider provider : SOLIDITY_PROVIDERS) {
            solidity = provider.getSolidity(entity, boneName, solidity);
        }
        return solidity < 0.0f ? 0.0f : solidity > 1.0f ? 1.0f : solidity;
    }

    // ── Raycast / shapecast (Phase 2) ────────────────────────────────────────

    /**
     * Margin added around a query's own bounds for the <b>fallback</b> candidate
     * pass only ({@link #gatherAllCandidates}) — entities {@link HitboxStore} has no
     * captured or blueprint-resolved geometry for yet, and so can't be found by
     * their own real extent via {@link #gatherExtentCandidates}. For anything
     * {@link HitboxStore} does know about, this margin plays no role at all; the
     * real bug this used to cause (a body whose visual geometry legitimately
     * reaches past both its registered vanilla box and this margin — see
     * {@code KHP_Snapshot_Extent_Candidate_Search_Design.md} §1) is what motivated
     * splitting candidate search into the two passes in the first place. Not
     * currently configurable; per design doc §7.2, not worth tuning further until a
     * concrete miss is reproduced against something with genuinely no snapshot at
     * all (freshly spawned, first tick after a client reconnect, etc.).
     */
    private static final double ENTITY_SEARCH_MARGIN = 16.0;

    /**
     * World-space raycast against every tracked entity's captured geometry.
     * Faces {@code faceMask} marks fully transparent are skipped outright; flat
     * parts (wings, membranes) additionally get a real per-pixel texture test at the
     * exact intersection point for any face that's neither fully opaque nor fully
     * transparent — see {@code HitboxRaycaster}'s class javadoc for the full order.
     * Returns every part hit along the ray, sorted nearest-first, or {@code null} if
     * nothing was hit.
     *
     * <p>Candidates are found two ways (see {@link #gatherAllCandidates} and
     * {@code KHP_Snapshot_Extent_Candidate_Search_Design.md} §5): primarily by each
     * tracked entity's own <b>real captured geometry extent</b>
     * ({@link #gatherExtentCandidates}), which finds a body whose visual geometry
     * legitimately reaches past its registered vanilla bounding box (a snake/worm/
     * tentacled boss) regardless of how far that is; and as a fallback, real level
     * entities within {@code maxDistance + ENTITY_SEARCH_MARGIN} of the ray's own
     * bounds for anything not yet tracked at all — not just entities that already
     * have a live snapshot — so this still finds blueprint-only entities too (the
     * case that matters on a real dedicated server before Phase 3's blueprint sync
     * populates {@code HitboxStore} directly).
     */
    @Nullable
    public static List<PartHitResult> raycast(Level level, Vec3 origin, Vec3 direction,
                                              double maxDistance, Predicate<Entity> entityFilter) {
        Vec3 dir = direction.normalize();
        Vec3 end = origin.add(dir.scale(maxDistance));
        AABB rayBounds = new AABB(origin, end);
        AABB searchBox = rayBounds.inflate(ENTITY_SEARCH_MARGIN);
        Map<Entity, EntityHitboxSnapshot> candidates = gatherAllCandidates(level, rayBounds, searchBox, entityFilter, MahApi::getSnapshot);
        return HitboxRaycaster.raycastWorld(candidates, origin, direction, maxDistance);
    }

    /**
     * Combat-scoped, world-scan counterpart to {@link #raycast} — for redirects
     * (vanilla melee/arrows) that don't already know which single entity they're
     * testing, unlike TACZ/Epic Fight's per-candidate or single-target redirects.
     * Candidates are resolved via {@link #getCombatSnapshot} instead of
     * {@link #getSnapshot} — an entity with no fresh live snapshot and an
     * untrusted blueprint tier is silently excluded from the candidate set
     * entirely, same policy as every other combat redirect.
     *
     * <p>Unlike {@link #raycastEntityCombat}/{@link #shapecastEntityCombat}, an
     * empty/{@code null} result here does <b>not</b> by itself distinguish
     * "nothing MAH trusts nearby" from "something MAH trusts but the ray missed
     * it" — callers that need to tell those apart (so a genuine miss on a
     * MAH-trusted entity doesn't fall through to vanilla's own, more permissive
     * box test and silently re-hit it) must additionally call
     * {@link #hasCombatCandidates} with the same origin/direction/maxDistance/
     * filter. See that method's javadoc for why this was split out.
     */
    @Nullable
    public static List<PartHitResult> raycastCombat(Level level, Vec3 origin, Vec3 direction,
                                                     double maxDistance, Predicate<Entity> entityFilter) {
        Vec3 dir = direction.normalize();
        Vec3 end = origin.add(dir.scale(maxDistance));
        AABB rayBounds = new AABB(origin, end);
        AABB searchBox = rayBounds.inflate(ENTITY_SEARCH_MARGIN);
        Map<Entity, EntityHitboxSnapshot> candidates = gatherAllCandidates(level, rayBounds, searchBox, entityFilter, MahApi::getCombatSnapshot);
        return HitboxRaycaster.raycastWorld(candidates, origin, direction, maxDistance);
    }

    /**
     * Whether {@link #raycastCombat} had any MAH-trusted candidate in range for
     * this exact origin/direction/maxDistance/filter, regardless of whether the
     * raycast actually connected with one of them.
     *
     * <p>This is what lets a caller like {@code ProjectileUtilMixin} tell "nothing
     * MAH trusts nearby" (fine to let vanilla's own, untouched loop run) apart from
     * "something MAH trusts is in range but the precise geometry missed it" (a real
     * miss — vanilla's own box test is strictly bigger than MAH's precise geometry,
     * so letting it run anyway means that entity's vanilla hitbox is still fully
     * functional for combat purposes even though it's no longer drawn). Previously
     * both cases were conflated and always fell through to vanilla — this is the
     * fix for that gap, not a new feature.</p>
     */
    public static boolean hasCombatCandidates(Level level, Vec3 origin, Vec3 direction,
                                               double maxDistance, Predicate<Entity> entityFilter) {
        Vec3 dir = direction.normalize();
        Vec3 end = origin.add(dir.scale(maxDistance));
        AABB rayBounds = new AABB(origin, end);
        AABB searchBox = rayBounds.inflate(ENTITY_SEARCH_MARGIN);
        Map<Entity, EntityHitboxSnapshot> candidates = gatherAllCandidates(level, rayBounds, searchBox, entityFilter, MahApi::getCombatSnapshot);
        return !candidates.isEmpty();
    }

    /** Entity-scoped raycast — cheaper than {@link #raycast} when the target is already known (melee, explosions). */
    @Nullable
    public static List<PartHitResult> raycastEntity(Entity target, Vec3 origin, Vec3 direction, double maxDistance) {
        return HitboxRaycaster.raycastEntity(target, getSnapshot(target), origin, direction, maxDistance);
    }

    // ── Combat redirects (Phase 2C) ──────────────────────────────────────────

    /**
     * Combat-specific fallback chain — see {@code KHP_Phase2C_Design.md} §3. Unlike
     * {@link #getSnapshot}, which always accepts a blueprint-resolved snapshot as a
     * fallback, this applies a stricter, combat-only policy:
     *
     * <ol>
     *   <li>A live snapshot fresh within {@code MahConfig.combatStaleSnapshotTicks}
     *       (default 4 — much tighter than debug-render's 100) is used.</li>
     *   <li>Otherwise, if {@link #isCombatBlueprintTrusted} says this entity's
     *       blueprint tier is trusted for combat, the blueprint-resolved snapshot
     *       from {@link #getSnapshot} is used anyway.</li>
     *   <li>Otherwise, if {@code entity} is a multipart-proxy recognized by
     *       {@link MultipartCompatRegistry} (Ice and Fire's {@code
     *       EntityMutlipartPart}, Ender's Cataclysm/vanilla-Ender-Dragon-style
     *       {@code PartEntity}), resolve the parent's own combat snapshot
     *       (recursively — the parent goes through this same chain) and
     *       synthesize a single-bone snapshot from whichever {@code PartOBB} in the
     *       parent's geometry is nearest the part's current position. Always
     *       resolves to some bone if the parent has any geometry at all — never
     *       falls back to the part's own box — but logs a throttled warning if the
     *       nearest match is farther than {@code MahConfig.
     *       partBoneMismatchWarnDistance}, since that's a real sign that this
     *       specific part likely mapped to the wrong bone. See
     *       {@link #synthesizePartSnapshot}.</li>
     *   <li>Otherwise {@code null} — the caller (TACZ/Epic Fight/vanilla melee
     *       redirect) falls back to vanilla {@code Entity.getBoundingBox()}.</li>
     * </ol>
     *
     * <p>Every combat redirect should go through this method (or
     * {@link #raycastEntityCombat}) rather than re-implementing the threshold +
     * allowlist check itself, so all combat redirects — TACZ, Epic Fight, vanilla
     * melee/arrows, and multipart-proxy resolution — stay in agreement.
     */
    @Nullable
    public static EntityHitboxSnapshot getCombatSnapshot(Entity entity) {
        if (hasFreshSnapshot(entity, MahConfig.combatStaleSnapshotTicks)) {
            return HitboxStore.get(entity.getUUID());
        }
        if (isCombatBlueprintTrusted(entity)) {
            return getSnapshot(entity);
        }
        Entity parent = MultipartCompatRegistry.resolveParent(entity);
        if (parent != null) {
            EntityHitboxSnapshot parentSnap = getCombatSnapshot(parent);
            if (parentSnap != null) {
                return synthesizePartSnapshot(entity, parent, parentSnap);
            }
        }
        return null;
    }

    /**
     * Builds a single-{@code PartOBB} snapshot for a multipart-proxy entity
     * ({@code part}) from its parent's own combat snapshot ({@code parentSnap}) —
     * whichever bone is nearest {@code part}'s current position, at the instant of
     * the call. Synthesized fresh on every call, same as a blueprint-resolved
     * snapshot — never "stale" by construction, so the returned snapshot's own
     * {@code capturedEntityPos} is {@code part}'s position right now, with zero
     * drift to correct for downstream.
     *
     * <p>Never returns {@code null} if {@code parentSnap} has at least one part —
     * always resolves to the nearest bone, regardless of how far away it actually
     * is. A bad match is signaled by a throttled log warning (once per part per
     * {@code MahConfig.partBoneMismatchWarnDistance} violation, not every call —
     * this runs on hot combat-query paths), not by refusing to resolve.
     */
    @Nullable
    private static EntityHitboxSnapshot synthesizePartSnapshot(Entity part, Entity parent, EntityHitboxSnapshot parentSnap) {
        if (parentSnap.parts().isEmpty()) return null;

        // Same formula PartOBB's own doc specifies and HitboxRaycaster.currentAnchor
        // applies internally — parentSnap.anchorPos() alone isn't enough, it needs
        // correcting for however much the parent has moved since parentSnap was
        // captured (parentSnap could be a few ticks old within the freshness
        // threshold, or blueprint-resolved with its own anchor convention). Shared
        // with gatherExtentCandidates below via currentAnchorFor — one definition,
        // can't drift apart between the two call sites.
        Vec3 parentCurrentAnchor = currentAnchorFor(parent, parentSnap);

        Vec3 partPos = part.position();
        PartOBB nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        Vec3 nearestWorldCenter = null;

        for (PartOBB candidate : parentSnap.parts()) {
            Matrix4f worldTransform = new Matrix4f()
                    .translate((float) parentCurrentAnchor.x, (float) parentCurrentAnchor.y, (float) parentCurrentAnchor.z)
                    .mul(candidate.transform());
            AABB box = candidate.localBox();
            Vector3f centerLocal = new Vector3f(
                    (float) ((box.minX + box.maxX) / 2.0),
                    (float) ((box.minY + box.maxY) / 2.0),
                    (float) ((box.minZ + box.maxZ) / 2.0));
            Vector3f centerWorld = worldTransform.transformPosition(centerLocal);
            Vec3 worldCenter = new Vec3(centerWorld.x, centerWorld.y, centerWorld.z);
            double distSq = worldCenter.distanceToSqr(partPos);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = candidate;
                nearestWorldCenter = worldCenter;
            }
        }
        if (nearest == null) return null; // unreachable given the isEmpty() check above, guards the compiler

        double nearestDist = Math.sqrt(nearestDistSq);
        if (nearestDist > MahConfig.partBoneMismatchWarnDistance) {
            khp$warnPartBoneMismatch(part, nearestDist);
        }

        // Re-anchor: this synthesized snapshot's anchorPos is the matched bone's
        // world position right now, capturedEntityPos is the part's position right
        // now (zero drift by construction — see method javadoc, matches how a
        // blueprint-resolved snapshot is already "never stale"). The bone's own
        // rotation/scale is preserved by stripping just the translation column from
        // its real transform and re-deriving position relative to the new anchor,
        // rather than discarding orientation entirely.
        Matrix4f boneWorldNoTranslation = new Matrix4f(nearest.transform())
                .setTranslation(0f, 0f, 0f);
        Matrix4f partLocalTransform = new Matrix4f()
                .translate(
                        (float) (nearestWorldCenter.x - partPos.x),
                        (float) (nearestWorldCenter.y - partPos.y),
                        (float) (nearestWorldCenter.z - partPos.z))
                .mul(boneWorldNoTranslation);

        PartOBB reboned = new PartOBB(nearest.localBox(), partLocalTransform, nearest.boneId(),
                nearest.faceMask(), nearest.flatFaceUV());
        List<PartOBB> rebonedParts = List.of(reboned);
        return new EntityHitboxSnapshot(
                entityKey(part), part.getId(), nearestWorldCenter, partPos, part.getYRot(),
                part.level().getGameTime(), rebonedParts, EntityHitboxSnapshot.computeLocalBounds(rebonedParts),
                parentSnap.source(), parentSnap.texture());
    }

    /**
     * World-space anchor for {@code snap} right now, drift-corrected from its own
     * capture-time anchor/position — the "entity moved since capture" delta every
     * consumer of a snapshot needs. {@link #synthesizePartSnapshot} and
     * {@link #gatherExtentCandidates} both need exactly this; factored out here so
     * neither reimplements it inline and the two can't drift apart. See
     * {@link PartOBB}'s own javadoc for the underlying
     * {@code translate(anchorPos + drift) * transform} contract this composes.
     */
    public static Vec3 currentAnchorFor(Entity entity, EntityHitboxSnapshot snap) {
        Vec3 delta = entity.position().subtract(snap.capturedEntityPos());
        return snap.anchorPos().add(delta);
    }

    private static final Map<UUID, Long> LAST_PART_BONE_MISMATCH_WARN = new ConcurrentHashMap<>();
    private static final long PART_BONE_MISMATCH_WARN_COOLDOWN_TICKS = 200; // 10s, avoid spamming a hot combat path

    private static void khp$warnPartBoneMismatch(Entity part, double distance) {
        long now = part.level().getGameTime();
        Long last = LAST_PART_BONE_MISMATCH_WARN.get(part.getUUID());
        if (last != null && now - last < PART_BONE_MISMATCH_WARN_COOLDOWN_TICKS) return;
        LAST_PART_BONE_MISMATCH_WARN.put(part.getUUID(), now);
        Mah.LOGGER.warn("[KHP] Multipart-proxy {} (uuid {}) resolved to a parent bone {} blocks away — "
                        + "likely mismatched. Nearest bone was used anyway (never blocked), but this is worth "
                        + "checking: part_bone_mismatch_warn_distance is currently {}.",
                part.getType(), part.getUUID(), String.format("%.2f", distance), MahConfig.partBoneMismatchWarnDistance);
    }

    /**
     * True if {@code entity} is allowed to fall back to blueprint-resolved geometry
     * for combat purposes when it has no fresh live snapshot.
     *
     * <p>{@code MahConfig.combatBlueprintAllowlist} explicitly listing this entity's
     * type id is always sufficient. If the allowlist is empty (the default — "auto"),
     * membership is instead decided by resolving this entity's blueprint via
     * {@link BlueprintResolver#lookupBlueprint} (the same baby-first/adult-fallback
     * lookup {@code resolve} itself uses) and checking whether its
     * {@code rendererKind} is {@code VANILLA_LIVING} — the only tier where
     * walk/attack pose-matching is actually attempted, per
     * {@link BlueprintResolver}'s class javadoc. A non-empty allowlist is explicit
     * and does not additionally auto-include VANILLA_LIVING entities that aren't
     * listed — an author who's populated the list has opted into curating it.
     */
    private static boolean isCombatBlueprintTrusted(Entity entity) {
        ResourceLocation typeId = entityTypeKey(entity);
        if (!MahConfig.combatBlueprintAllowlist.isEmpty()) {
            return MahConfig.combatBlueprintAllowlist.contains(typeId);
        }
        var blueprint = BlueprintResolver.lookupBlueprint(entity);
        return blueprint != null && "VANILLA_LIVING".equals(blueprint.rendererKind());
    }

    /**
     * Combat-scoped counterpart to {@link #raycastEntity} — uses
     * {@link #getCombatSnapshot} instead of {@link #getSnapshot}, so a stale/no
     * snapshot on a non-allowlisted entity correctly returns {@code null} instead of
     * a possibly-wrong-pose blueprint hit. Callers must fall back to vanilla
     * hit-testing themselves when this returns {@code null} — same contract as
     * {@link #raycastEntity}.
     */
    @Nullable
    public static List<PartHitResult> raycastEntityCombat(Entity target, Vec3 origin, Vec3 direction, double maxDistance) {
        EntityHitboxSnapshot snap = getCombatSnapshot(target);
        if (snap == null) return null;
        return HitboxRaycaster.raycastEntity(target, snap, origin, direction, maxDistance);
    }

    /**
     * Combat-scoped, single-entity counterpart to {@link #shapecast} — for callers
     * (Epic Fight's OBB weapon colliders) that already know exactly which entity
     * they're testing and just need a shape-overlap answer against it, not a
     * level-wide scan. Same {@link #getCombatSnapshot} policy as
     * {@link #raycastEntityCombat}.
     */
    @Nullable
    public static List<PartHitResult> shapecastEntityCombat(Entity target, CastShape shape) {
        EntityHitboxSnapshot snap = getCombatSnapshot(target);
        if (snap == null) return null;
        return HitboxRaycaster.shapecast(Map.of(target, snap), shape);
    }

    /**
     * Every part any tracked entity's captured geometry overlaps {@code shape} — for
     * shockwaves/breath-attacks/expanding rings hitting everything in a volume at
     * once. See {@code HitboxRaycaster.shapecast}'s javadoc for the sample-point
     * precision note and which result fields are best-effort for a shapecast
     * specifically. {@link CastShape} ships sphere/box/cone/torus/ring shapes;
     * an expanding shape is expressed by calling this again each tick with updated
     * parameters, not by animating the shape object itself.
     *
     * <p>Same candidate-gathering change as {@link #raycast} — real level entities
     * near {@code shape}'s bounds, not just ones with an existing live snapshot.
     */
    @Nullable
    public static List<PartHitResult> shapecast(Level level, CastShape shape, Predicate<Entity> entityFilter) {
        AABB shapeBounds = shape.boundingBox();
        AABB searchBox = shapeBounds.inflate(ENTITY_SEARCH_MARGIN);
        Map<Entity, EntityHitboxSnapshot> candidates = gatherAllCandidates(level, shapeBounds, searchBox, entityFilter, MahApi::getSnapshot);
        return HitboxRaycaster.shapecast(candidates, shape);
    }

    /**
     * Candidate entities found via their own <b>real captured geometry extent</b> —
     * {@link EntityHitboxSnapshot#worldBounds} for every entity {@link HitboxStore}
     * currently has data on — rather than a fixed world-space margin around the
     * query shape. See {@code KHP_Snapshot_Extent_Candidate_Search_Design.md} §5.1:
     * this is what finds a snake/worm/tentacled body whose visual geometry
     * legitimately extends past its registered vanilla bounding box, where the old
     * fixed-{@link #ENTITY_SEARCH_MARGIN} pass ({@link #gatherAllCandidates}'
     * fallback half) would miss it entirely once the query point is far enough from
     * the entity's own tracked position — no per-mod tuning, no guessing how big a
     * margin is "enough."
     *
     * <p>Scoped to whatever {@link HitboxStore} already knows about — bounded by
     * {@code MahConfig.captureRadiusSqr} client-side (live captures) and, once the
     * blueprint-sync job (design doc §9) lands, an equivalent server-side radius —
     * so this is never a full-level scan. An entity {@link HitboxStore} doesn't know
     * about at all (never captured, no blueprint, not yet synced) simply isn't found
     * here; the margin-based fallback in {@link #gatherAllCandidates} remains the
     * only source for that population, same as it is today.
     *
     * <p>Each candidate's snapshot is resolved fresh via {@code resolver} (not read
     * directly off the {@link HitboxStore} entry) so this respects whatever policy
     * the caller is using — {@link #getSnapshot} vs. {@link #getCombatSnapshot}'s
     * stricter freshness/trust rules — exactly like the margin-based pass always
     * has; {@link HitboxStore} is only consulted here to know <i>which entities to
     * even consider</i> and their real extent, never to skip the policy check.
     *
     * <p>Public — not folded into {@link #gatherAllCandidates} as a private
     * implementation detail, so future consumers beyond this class's own raycast/
     * shapecast entry points can call it directly (design doc §7.4).
     */
    public static Map<Entity, EntityHitboxSnapshot> gatherExtentCandidates(Level level, AABB queryShapeBounds,
                                                                            @Nullable Predicate<Entity> entityFilter,
                                                                            Function<Entity, EntityHitboxSnapshot> resolver) {
        Map<Entity, EntityHitboxSnapshot> candidates = new HashMap<>();
        for (Map.Entry<UUID, EntityHitboxSnapshot> entry : HitboxStore.view().entrySet()) {
            EntityHitboxSnapshot storedSnap = entry.getValue();
            Entity entity = level.getEntity(storedSnap.entityId());
            // Guard against a stale/cross-dimension entityId collision: HitboxStore
            // isn't level-scoped (a dedicated server can have multiple loaded
            // dimensions, each with its own entity-id space), so confirm the entity
            // this level actually handed back is the same one the snapshot was
            // captured from before trusting it at all.
            if (entity == null || !entity.getUUID().equals(entry.getKey())) continue;
            if (entityFilter != null && !entityFilter.test(entity)) continue;

            EntityHitboxSnapshot resolvedSnap = resolver.apply(entity);
            if (resolvedSnap == null) continue; // storedSnap existing doesn't mean the caller's own policy (e.g. combat trust) accepts it

            Vec3 currentAnchor = currentAnchorFor(entity, resolvedSnap);
            AABB worldBounds = resolvedSnap.worldBounds(currentAnchor);
            if (!queryShapeBounds.intersects(worldBounds)) continue;

            candidates.put(entity, resolvedSnap);
        }
        return candidates;
    }

    /**
     * Merges {@link #gatherExtentCandidates} (primary — real captured extent, no
     * fixed margin) with a margin-based fallback pass (real level entities within
     * {@code marginedSearchBox}, {@link #ENTITY_SEARCH_MARGIN}-padded) into one
     * candidate map. The fallback pass's job is narrower than the old
     * {@code gatherCandidates} it replaces used to have (design doc §5.2): the only
     * population it's still actually the right tool for is entities the extent pass
     * can't see at all yet (no captured or blueprint-resolved geometry) — anything
     * {@link HitboxStore} already knows about is found via its own real extent
     * instead, however far that reaches.
     *
     * <p>An entity found by the extent pass is never re-resolved by the fallback
     * pass — {@code resolver} is called at most once per entity, not once per pass;
     * both passes ultimately go through the same {@code resolver} for a given
     * entity, so there's nothing to gain from calling it twice. {@code null}
     * resolutions are dropped entirely (no snapshot of any kind available, or not
     * combat-trusted).
     *
     * @param tightQueryBounds  the query shape's own bounds, unpadded — what
     *                          {@link #gatherExtentCandidates} tests each candidate's
     *                          real {@link EntityHitboxSnapshot#worldBounds} against.
     * @param marginedSearchBox {@code tightQueryBounds} inflated by
     *                          {@link #ENTITY_SEARCH_MARGIN} — what the fallback
     *                          pass queries the level with.
     */
    private static Map<Entity, EntityHitboxSnapshot> gatherAllCandidates(Level level, AABB tightQueryBounds, AABB marginedSearchBox,
                                                                          @Nullable Predicate<Entity> entityFilter,
                                                                          Function<Entity, EntityHitboxSnapshot> resolver) {
        Map<Entity, EntityHitboxSnapshot> candidates = new HashMap<>(
                gatherExtentCandidates(level, tightQueryBounds, entityFilter, resolver));
        List<Entity> nearby = level.getEntities((Entity) null, marginedSearchBox,
                e -> entityFilter == null || entityFilter.test(e));
        for (Entity entity : nearby) {
            if (candidates.containsKey(entity)) continue;
            EntityHitboxSnapshot snap = resolver.apply(entity);
            if (snap != null) candidates.put(entity, snap);
        }
        return candidates;
    }

    // ── Combat-miss diagnostics (temporary — design doc §8) ─────────────────

    /**
     * Logs why a combat redirect just resolved to a genuine miss, for every nearby
     * entity whose type is in {@code MahConfig.debugCombatMissWatchlist} — added
     * for the Maze Mother investigation
     * ({@code KHP_Snapshot_Extent_Candidate_Search_Design.md} §8): its "can't hit
     * it" symptom doesn't fit the search-margin explanation the rest of that
     * design's fix addresses (registered box and cull-box inflate are both well
     * inside {@link #ENTITY_SEARCH_MARGIN}, and its model is an entirely ordinary
     * {@code ModelPart}/{@code EntityModel}, the same capture path already
     * confirmed working elsewhere) — so instead of a fourth guess from bytecode,
     * this gets a real trace from the next actual missed swing.
     *
     * <p>No-ops immediately (one {@code Set.isEmpty()} check) when the watchlist is
     * empty, which is the default — effectively zero cost when not actively
     * investigating something. Intended to be removed once whatever's actually
     * wrong with Maze Mother is found; not meant as a permanent feature.
     *
     * @param level        the level the swing happened in.
     * @param origin       ray origin (attacker eye/weapon position).
     * @param rayVec       ray direction, not necessarily normalized (matches the
     *                     {@code rayVec}/{@code maxDistance} split already used by
     *                     {@code ProjectileUtilMixin}'s callers).
     * @param maxDistance  ray length actually tested.
     * @param entityFilter same combat filter the caller's redirect used, so this
     *                     reports on exactly the entities that redirect actually
     *                     considered, not a looser set.
     */
    public static void logCombatMissDiagnostics(Level level, Vec3 origin, Vec3 rayVec,
                                                  double maxDistance, Predicate<Entity> entityFilter) {
        if (MahConfig.debugCombatMissWatchlist.isEmpty()) return;

        Vec3 dir = rayVec.normalize();
        Vec3 end = origin.add(dir.scale(maxDistance));
        AABB rayBounds = new AABB(origin, end);
        // Deliberately wider than ENTITY_SEARCH_MARGIN — this is diagnosing
        // candidate-search behavior itself, so it needs to see watchlisted entities
        // the real search might be about to miss, not just the ones it already found.
        AABB watchBounds = rayBounds.inflate(32.0);

        List<Entity> nearby = level.getEntities((Entity) null, watchBounds,
                e -> MahConfig.debugCombatMissWatchlist.contains(entityTypeKey(e))
                        && (entityFilter == null || entityFilter.test(e)));
        if (nearby.isEmpty()) return;

        for (Entity entity : nearby) {
            EntityHitboxSnapshot stored = HitboxStore.get(entity.getUUID());
            EntityHitboxSnapshot combatSnap = getCombatSnapshot(entity);
            boolean freshLive = hasFreshSnapshot(entity, MahConfig.combatStaleSnapshotTicks);

            String storedDesc = stored == null ? "none"
                    : stored.source() + ", " + stored.parts().size() + " parts, "
                    + (level.getGameTime() - stored.gameTime()) + " ticks old";
            String combatDesc = combatSnap == null ? "null (no combat-trusted geometry at all)"
                    : combatSnap.source() + ", " + combatSnap.parts().size() + " parts";

            double distToRay = squaredDistanceToBox(rayBounds, entity.position());
            boolean inExtentWorldBounds = combatSnap != null
                    && rayBounds.intersects(combatSnap.worldBounds(currentAnchorFor(entity, combatSnap)));

            Mah.LOGGER.info("[KHP debug] combat-miss trace for {} (uuid {}, entityId {}): "
                            + "hasFreshLiveSnapshot(combatStaleSnapshotTicks)={}, storedSnapshot=[{}], "
                            + "getCombatSnapshot=[{}], distSqrToRayBounds={}, wouldBeFoundByExtentSearch={}",
                    entity.getType(), entity.getUUID(), entity.getId(), freshLive, storedDesc, combatDesc,
                    String.format("%.2f", distToRay), inExtentWorldBounds);
        }
    }

    /**
     * Squared distance from {@code point} to the nearest point on {@code box} (0 if
     * {@code point} is inside it) — written by hand rather than assumed as a
     * built-in {@code AABB} method, since that couldn't be verified against the
     * real mapped jar in this session (per this project's own bytecode-verify-
     * before-assuming rule). Diagnostic-only use ({@link #logCombatMissDiagnostics}),
     * not on any hot path.
     */
    private static double squaredDistanceToBox(AABB box, Vec3 point) {
        double dx = Math.max(box.minX - point.x, Math.max(0.0, point.x - box.maxX));
        double dy = Math.max(box.minY - point.y, Math.max(0.0, point.y - box.maxY));
        double dz = Math.max(box.minZ - point.z, Math.max(0.0, point.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    // ── Living Platforms (Phase 2E) ──────────────────────────────────────────

    /**
     * Current world-space transform of a named bone on {@code entity}, for KHP
     * Phase 2E's VS2 entity-anchored shipyards — see {@code KHP_Phase2E_Design.md}
     * §3/§6.4. Fallback chain (deliberately distinct from {@link #getSnapshot}):
     *
     * <ol>
     *   <li>A live snapshot fresh within
     *       {@code MahConfig.livingPlatformStaleSnapshotTicks} (default 2, much
     *       tighter than {@code staleSnapshotTicks}'s 100 — a swaying ship reads
     *       stale pose far more readily than a debug wireframe does) is used.</li>
     *   <li>Otherwise a blueprint-resolved snapshot ({@link BlueprintResolver}) is
     *       used if one exists — same approximate-pose tradeoff the dedicated
     *       server already accepts everywhere else in MAH.</li>
     *   <li>Otherwise {@code null} — genuinely nothing available. The caller
     *       (living-platform ship manager) is responsible for halting the entity
     *       itself until this starts returning non-null again; this method makes
     *       no gameplay decision of its own, it only reports data availability.</li>
     * </ol>
     *
     * <p>Returns {@code null} also if a snapshot was found but has no part whose
     * bone resolves to {@code boneName} in this entity key's {@link BoneTable} —
     * a whitelist authored against a bone that doesn't actually exist on this
     * model/blueprint.
     */
    @Nullable
    public static Matrix4f getLivingPlatformBoneTransform(Entity entity, String boneName) {
        EntityHitboxSnapshot live = HitboxStore.get(entity.getUUID());
        EntityHitboxSnapshot snap;
        if (live != null && !live.isStale(entity.level().getGameTime(), MahConfig.livingPlatformStaleSnapshotTicks)) {
            snap = live;
        } else {
            snap = BlueprintResolver.resolve(entity);
        }
        if (snap == null) return null;

        BoneTable boneTable = HitboxStore.getBoneTable(snap.entityKey());
        if (boneTable == null) return null;

        PartOBB matched = null;
        for (PartOBB part : snap.parts()) {
            String name = boneTable.nameOf(part.boneId());
            if (boneName.equals(name)) {
                matched = part;
                break;
            }
        }
        if (matched == null) return null;

        Vec3 anchor = currentAnchorFor(entity, snap);
        return new Matrix4f()
                .translate((float) anchor.x, (float) anchor.y, (float) anchor.z)
                .mul(matched.transform());
    }

    /**
     * Whether {@link #getLivingPlatformBoneTransform} currently has <b>any</b>
     * usable snapshot for {@code entity} at all (live-fresh or blueprint), without
     * needing a specific bone name — the living-platform ship manager uses this to
     * decide whether to un-freeze an entity it previously halted, one check per
     * entity per tick rather than once per anchor.
     */
    public static boolean hasLivingPlatformData(Entity entity) {
        EntityHitboxSnapshot live = HitboxStore.get(entity.getUUID());
        if (live != null && !live.isStale(entity.level().getGameTime(), MahConfig.livingPlatformStaleSnapshotTicks)) {
            return true;
        }
        return BlueprintResolver.resolve(entity) != null;
    }

    private MahApi() {
    }
}
