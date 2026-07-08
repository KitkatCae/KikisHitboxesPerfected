package exp.CCnewmods.mah.api;

import exp.CCnewmods.mah.capture.BoneTable;
import exp.CCnewmods.mah.capture.EntityHitboxSnapshot;
import exp.CCnewmods.mah.capture.HitboxStore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    @Nullable
    public static EntityHitboxSnapshot getSnapshot(Entity entity) {
        return HitboxStore.get(entity.getUUID());
    }

    /** True if the entity has a snapshot no older than the configured staleness window. */
    public static boolean hasFreshSnapshot(Entity entity) {
        EntityHitboxSnapshot snap = getSnapshot(entity);
        return snap != null && !snap.isStale(entity.level().getGameTime(),
                exp.CCnewmods.mah.MahConfig.staleSnapshotTicks);
    }

    @Nullable
    public static BoneTable getBoneTable(ResourceLocation entityKey) {
        return HitboxStore.getBoneTable(entityKey);
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

    private MahApi() {
    }
}
