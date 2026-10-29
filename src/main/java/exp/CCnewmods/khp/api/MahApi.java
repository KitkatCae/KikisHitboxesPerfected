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

/** Public API: model-accurate entity geometry, raycasts/shapecasts, and the hooks other mods can register. */
public final class MahApi {

    private static final List<EntityKeyProvider> KEY_PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<PartSolidityProvider> SOLIDITY_PROVIDERS = new CopyOnWriteArrayList<>();

    private static final ResourceLocation UNKNOWN = new ResourceLocation("mah", "unknown");

    // ── Entity keying ────────────────────────────────────────────────────────

    /**
     * Override which key (and so which BoneTable and blueprint) an entity uses. First non-null answer wins, e.g.
     * {@code MahApi.registerEntityKeyProvider(e -> e instanceof CorpseEntity c ? c.getSourceMobType() : null);}
     */
    public static void registerEntityKeyProvider(EntityKeyProvider provider) {
        KEY_PROVIDERS.add(provider);
    }

    public static ResourceLocation entityKey(Entity entity) {
        for (EntityKeyProvider provider : KEY_PROVIDERS) {
            ResourceLocation key = provider.keyFor(entity);
            if (key != null) return key;
        }
        return entityTypeKey(entity);
    }

    /** The raw EntityType id, ignoring key providers. */
    public static ResourceLocation entityTypeKey(Entity entity) {
        ResourceLocation rl = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return rl != null ? rl : UNKNOWN;
    }

    // ── Snapshots & bone tables ──────────────────────────────────────────────

    /** Fresh live snapshot, else blueprint, else stale live snapshot, else {@code null}. */
    @Nullable
    public static EntityHitboxSnapshot getSnapshot(Entity entity) {
        EntityHitboxSnapshot live = HitboxStore.get(entity.getUUID());
        if (live != null && !live.isStale(entity.level().getGameTime(), MahConfig.staleSnapshotTicks)) {
            return live;
        }
        EntityHitboxSnapshot resolved = BlueprintResolver.resolve(entity);
        return resolved != null ? resolved : live;
    }

    /** True if a <b>live</b> snapshot exists within the staleness window (blueprints don't count). */
    public static boolean hasFreshSnapshot(Entity entity) {
        return hasFreshSnapshot(entity, MahConfig.staleSnapshotTicks);
    }

    public static boolean hasFreshSnapshot(Entity entity, int thresholdTicks) {
        EntityHitboxSnapshot snap = HitboxStore.get(entity.getUUID());
        return snap != null && !snap.isStale(entity.level().getGameTime(), thresholdTicks);
    }

    @Nullable
    public static BoneTable getBoneTable(ResourceLocation entityKey) {
        return HitboxStore.getBoneTable(entityKey);
    }

    // ── Anatomical RenderLayer allowlist ────────────────────────────────────

    /** Captures this layer as primary geometry even with capture_layers off. For real anatomy, not armor. */
    public static void registerAnatomicalRenderLayer(Class<?> layerClass) {
        HitboxCaptureManager.registerAnatomicalRenderLayer(layerClass);
    }

    // ── Solidity hooks ──────────────────────────────────────────────────────

    /**
     * Scales a bone's collision solidity, 0 (pass through) to 1 (solid). Providers chain over the folded value.
     *
     * <p>TODO: solid collision doesn't consume {@link #getSolidity} yet.
     */
    public static void registerSolidityProvider(PartSolidityProvider provider) {
        SOLIDITY_PROVIDERS.add(provider);
    }

    public static float getSolidity(Entity entity, String boneName) {
        float solidity = 1.0f;
        for (PartSolidityProvider provider : SOLIDITY_PROVIDERS) {
            solidity = provider.getSolidity(entity, boneName, solidity);
        }
        return solidity < 0.0f ? 0.0f : solidity > 1.0f ? 1.0f : solidity;
    }

    // ── Raycast / shapecast ──────────────────────────────────────────────────

    /** Search margin for entities with no captured or blueprint geometry yet. */
    private static final double ENTITY_SEARCH_MARGIN = 16.0;

    /**
     * Raycast against every tracked entity's captured geometry, honouring transparent faces and flat-part
     * textures. Returns every part hit, nearest first, or {@code null}.
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
     * Like {@link #raycast} but only trusts combat-grade snapshots ({@link #getCombatSnapshot}). {@code null} doesn't
     * tell "nothing trusted nearby" from "trusted but missed"; use {@link #hasCombatCandidates} for that.
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

    /** Whether {@link #raycastCombat} had any trusted candidate in range, hit or not. */
    public static boolean hasCombatCandidates(Level level, Vec3 origin, Vec3 direction,
                                               double maxDistance, Predicate<Entity> entityFilter) {
        Vec3 dir = direction.normalize();
        Vec3 end = origin.add(dir.scale(maxDistance));
        AABB rayBounds = new AABB(origin, end);
        AABB searchBox = rayBounds.inflate(ENTITY_SEARCH_MARGIN);
        Map<Entity, EntityHitboxSnapshot> candidates = gatherAllCandidates(level, rayBounds, searchBox, entityFilter, MahApi::getCombatSnapshot);
        return !candidates.isEmpty();
    }

    /** Cheaper than {@link #raycast} when the target is known. */
    @Nullable
    public static List<PartHitResult> raycastEntity(Entity target, Vec3 origin, Vec3 direction, double maxDistance) {
        return HitboxRaycaster.raycastEntity(target, getSnapshot(target), origin, direction, maxDistance);
    }

    // ── Combat redirects ─────────────────────────────────────────────────────

    /**
     * Combat snapshot: fresh live (within {@code combat_stale_snapshot_ticks}), else a trusted blueprint, else for
     * a multipart proxy the parent's bone nearest the part, else {@code null} (use vanilla's box). All combat
     * redirects should go through this so they agree.
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

    /** Single-bone snapshot for multipart proxy {@code part}: the parent bone nearest its current position. */
    @Nullable
    private static EntityHitboxSnapshot synthesizePartSnapshot(Entity part, Entity parent, EntityHitboxSnapshot parentSnap) {
        if (parentSnap.parts().isEmpty()) return null;

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
        if (nearest == null) return null; // unreachable after isEmpty()

        double nearestDist = Math.sqrt(nearestDistSq);
        if (nearestDist > MahConfig.partBoneMismatchWarnDistance) {
            khp$warnPartBoneMismatch(part, nearestDist);
        }

        // Keep the bone's rotation/scale, re-anchored at its current world position.
        Matrix4f boneWorldNoTranslation = new Matrix4f(nearest.transform())
                .setTranslation(0f, 0f, 0f);
        Matrix4f partLocalTransform = new Matrix4f()
                .translate(
                        (float) (nearestWorldCenter.x - partPos.x),
                        (float) (nearestWorldCenter.y - partPos.y),
                        (float) (nearestWorldCenter.z - partPos.z))
                .mul(boneWorldNoTranslation);

        PartOBB reboned = new PartOBB(nearest.localBox(), partLocalTransform, nearest.boneId(),
                nearest.faceMask(), nearest.flatFaceUV(), nearest.allFaceUVs());
        List<PartOBB> rebonedParts = List.of(reboned);
        return new EntityHitboxSnapshot(
                entityKey(part), part.getId(), nearestWorldCenter, partPos, part.getYRot(),
                part.level().getGameTime(), rebonedParts, EntityHitboxSnapshot.computeLocalBounds(rebonedParts),
                parentSnap.source(), parentSnap.texture());
    }

    /** {@code snap}'s anchor now, corrected for how far the entity moved since capture. */
    public static Vec3 currentAnchorFor(Entity entity, EntityHitboxSnapshot snap) {
        Vec3 delta = entity.position().subtract(snap.capturedEntityPos());
        return snap.anchorPos().add(delta);
    }

    private static final Map<UUID, Long> LAST_PART_BONE_MISMATCH_WARN = new ConcurrentHashMap<>();
    private static final long PART_BONE_MISMATCH_WARN_COOLDOWN_TICKS = 200; // 10s

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

    /** Listed in combat_blueprint_allowlist, or (list empty) the blueprint is VANILLA_LIVING. */
    private static boolean isCombatBlueprintTrusted(Entity entity) {
        ResourceLocation typeId = entityTypeKey(entity);
        if (!MahConfig.combatBlueprintAllowlist.isEmpty()) {
            return MahConfig.combatBlueprintAllowlist.contains(typeId);
        }
        var blueprint = BlueprintResolver.lookupBlueprint(entity);
        return blueprint != null && "VANILLA_LIVING".equals(blueprint.rendererKind());
    }

    /** Combat-trusted {@link #raycastEntity}; {@code null} means use vanilla hit-testing. */
    @Nullable
    public static List<PartHitResult> raycastEntityCombat(Entity target, Vec3 origin, Vec3 direction, double maxDistance) {
        EntityHitboxSnapshot snap = getCombatSnapshot(target);
        if (snap == null) return null;
        return HitboxRaycaster.raycastEntity(target, snap, origin, direction, maxDistance);
    }

    /** Combat-trusted single-entity {@link #shapecast} (Epic Fight OBB colliders). */
    @Nullable
    public static List<PartHitResult> shapecastEntityCombat(Entity target, CastShape shape) {
        EntityHitboxSnapshot snap = getCombatSnapshot(target);
        if (snap == null) return null;
        return HitboxRaycaster.shapecast(Map.of(target, snap), shape);
    }

    /** Every part overlapping {@code shape}. For an expanding shape, call each tick with new parameters. */
    @Nullable
    public static List<PartHitResult> shapecast(Level level, CastShape shape, Predicate<Entity> entityFilter) {
        AABB shapeBounds = shape.boundingBox();
        AABB searchBox = shapeBounds.inflate(ENTITY_SEARCH_MARGIN);
        Map<Entity, EntityHitboxSnapshot> candidates = gatherAllCandidates(level, shapeBounds, searchBox, entityFilter, MahApi::getSnapshot);
        return HitboxRaycaster.shapecast(candidates, shape);
    }

    /**
     * Candidates found by their real captured extent rather than a fixed margin, so long bodies are found. Each
     * snapshot goes through {@code resolver} so the caller's trust policy still applies.
     */
    public static Map<Entity, EntityHitboxSnapshot> gatherExtentCandidates(Level level, AABB queryShapeBounds,
                                                                            @Nullable Predicate<Entity> entityFilter,
                                                                            Function<Entity, EntityHitboxSnapshot> resolver) {
        Map<Entity, EntityHitboxSnapshot> candidates = new HashMap<>();
        for (Map.Entry<UUID, EntityHitboxSnapshot> entry : HitboxStore.view().entrySet()) {
            EntityHitboxSnapshot storedSnap = entry.getValue();
            Entity entity = level.getEntity(storedSnap.entityId());
            // HitboxStore isn't per-level; make sure this is the same entity.
            if (entity == null || !entity.getUUID().equals(entry.getKey())) continue;
            if (entityFilter != null && !entityFilter.test(entity)) continue;

            EntityHitboxSnapshot resolvedSnap = resolver.apply(entity);
            if (resolvedSnap == null) continue;

            Vec3 currentAnchor = currentAnchorFor(entity, resolvedSnap);
            AABB worldBounds = resolvedSnap.worldBounds(currentAnchor);
            if (!queryShapeBounds.intersects(worldBounds)) continue;

            candidates.put(entity, resolvedSnap);
        }
        return candidates;
    }

    /** Extent candidates plus a margin-based pass for entities with no geometry yet. */
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

    // ── Living Platforms ─────────────────────────────────────────────────────

    /**
     * Bone transform for living platforms: fresh live (within living_platform_stale_snapshot_ticks), else blueprint,
     * else {@code null}. Also {@code null} if the bone doesn't exist.
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

    /** Whether {@link #getLivingPlatformBoneTransform} has any data for {@code entity}. */
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
