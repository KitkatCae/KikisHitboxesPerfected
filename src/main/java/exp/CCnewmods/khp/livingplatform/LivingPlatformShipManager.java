package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.MahConfig;
import exp.CCnewmods.khp.api.MahApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3i;
import org.valkyrienskies.core.api.attachment.AttachmentHolder;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns living-platform ship creation, lookup, and the entity-freeze fallback — see
 * {@code KHP_Phase2E_Design.md} §6.
 *
 * <h3>Attachment API correction (bytecode re-verified this session)</h3>
 * The original design doc's §2.4 assumed {@code ServerShip.getAttachment}/
 * {@code saveAttachment(Class, T)} were the live API. Decompiling
 * {@code ServerShip.class} shows both are {@code @Deprecated(level = ERROR)} and
 * unconditionally throw {@code NotImplementedError} — "Since VS 2.5, attachments
 * can only be saved/retrieved from a LoadedServerShip". {@code LoadedServerShip}'s
 * own {@code Class}-taking overloads are <i>also</i> deprecated (delegating to the
 * real thing). The actual current API is
 * {@code AttachmentHolder.setAttachment(T value)} (class inferred from the value's
 * runtime type) / {@code AttachmentHolder.getAttachment(Class<T>)} /
 * {@code getOrPutAttachment(Class<T>, Supplier<T>)} — {@code LoadedServerShip
 * extends AttachmentHolder}, so every ship this manager creates is cast to
 * {@code AttachmentHolder} to use it. This is what this class actually does below;
 * flagging the correction here since it changes real code, not just phrasing.
 *
 * <h3>Ship lookup / recovery</h3>
 * A same-session, same-JVM cache ({@code SHIP_CACHE}) is the fast path. On a cache
 * miss (first touch this session, or after a server restart), {@link #findExistingShip}
 * scans {@code ServerShipWorld.getAllShips()} once for a ship whose
 * {@link LivingPlatformAttachment} matches — not indexed by VS2 itself, so this is
 * an O(ships) scan, acceptable per Q2E-6 (no performance budget for this pass).
 */
public final class LivingPlatformShipManager {

    /** entityUuid → (anchorId → shipId). Fast-path cache only, not the source of truth. */
    private static final Map<UUID, Map<String, Long>> SHIP_CACHE = new ConcurrentHashMap<>();

    /** Entities currently halted by {@link #tickFreezeState} because no snapshot data exists at all. */
    private static final Set<UUID> FROZEN_ENTITIES = ConcurrentHashMap.newKeySet();

    /**
     * Resolves (creating if necessary) the {@link ServerShip} for {@code anchor} on
     * {@code entity}. Safe to call every tick for eager creation or once per player
     * interaction for lazy creation (see {@code MahConfig.livingPlatformEagerCreation}) —
     * a cache/attachment hit is cheap, only a genuine miss creates a new ship.
     */
    public static ServerShip getOrCreateShip(ServerLevel level, Entity entity, LivingPlatformAnchor anchor) {
        ServerShip existing = findExistingShip(level, entity.getUUID(), anchor.id());
        if (existing != null) return existing;
        return createShip(level, entity, anchor);
    }

    @Nullable
    private static ServerShip findExistingShip(ServerLevel level, UUID entityUuid, String anchorId) {
        Long cachedId = SHIP_CACHE.getOrDefault(entityUuid, Map.of()).get(anchorId);
        // getShipObjectWorld(ServerLevel) returns VsiServerShipWorld directly (confirmed via
        // javap) — no cast needed to reach createNewShipAtBlock below or getAllShips() here.
        org.valkyrienskies.core.internal.world.VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
        if (cachedId != null) {
            ServerShip ship = world.getAllShips().getById(cachedId);
            if (ship != null) return ship;
            // Cached id no longer resolves (deleted/rebuilt) — fall through to a real scan
            // and let it get overwritten below, rather than trusting a stale cache entry.
        }

        for (ServerShip ship : world.getAllShips()) {
            if (!(ship instanceof LoadedServerShip loaded)) continue;
            LivingPlatformAttachment attachment = ((AttachmentHolder) loaded).getAttachment(LivingPlatformAttachment.class);
            if (attachment == null) continue;
            if (attachment.entityUuid().equals(entityUuid) && attachment.anchorId().equals(anchorId)) {
                SHIP_CACHE.computeIfAbsent(entityUuid, k -> new ConcurrentHashMap<>()).put(anchorId, ship.getId());
                return ship;
            }
        }
        return null;
    }

    /**
     * Creates the ship and does setup synchronously, same as the original working
     * version — {@code seedVolume}/{@code setTransformProvider}/the sync packet never
     * actually needed a {@link LoadedServerShip}, only plain {@link ServerShip}, and
     * deferring all of them to {@code ShipLoadEvent} (an earlier fix this session)
     * turned out to be wrong: that event apparently never fires at all for a
     * freshly-created, zero-block ship like this — its actual block storage almost
     * certainly lives in a separate shipyard chunk region nothing forces to load just
     * because a player is near the *entity* — so gating everything on it meant nothing
     * downstream ever ran (confirmed in-game: no seed blocks, no transform provider,
     * nothing). Reverted to synchronous for everything except the one thing that
     * genuinely does need a confirmed {@link LoadedServerShip}: saving the attachment
     * (§15 — {@code ServerShip.setAttachment} throws unconditionally, only
     * {@code AttachmentHolder} via a real {@code LoadedServerShip} works). That part
     * alone still uses {@code ShipLoadEvent.once(...)} as a best-effort fallback when
     * the immediate cast fails, instead of blocking everything else on it.
     */
    private static ServerShip createShip(ServerLevel level, Entity entity, LivingPlatformAnchor anchor) {
        String dimensionId = VSGameUtilsKt.getDimensionId(level);
        Vector3i blockPos = new Vector3i(entity.blockPosition().getX(), entity.blockPosition().getY(), entity.blockPosition().getZ());

        org.valkyrienskies.core.internal.world.VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
        // false + zero blocks: bytecode-confirmed stable/valid this session — see
        // KHP_Phase2E_Design.md §2.2. Every real VS2 call site uses false; PerfTestCommand
        // proves a zero-block ship created this way is immediately usable with no further
        // readiness step.
        ServerShip ship = world.createNewShipAtBlock(blockPos, false, 1.0, dimensionId);
        UUID entityUuid = entity.getUUID();

        org.valkyrienskies.mod.common.util.EntityShipCollisionUtils.markShipAsRecentlySpawned(
                ship.getId(), level.getServer().getTickCount());

        saveAttachment(ship, entityUuid, anchor);

        // Seed BEFORE attaching the transform provider — see KHP_Phase2E_Design.md §14.
        // A ship with an active transform provider but zero blocks has zero mass, and VS2's
        // physics thread validates invMass (= 1.0 / shipMass) on ships it's actively driving;
        // 1.0/0.0 = Infinity crashes the physics thread. Confirmed via decompiling the exact
        // check this session. Ordering this way means the ship never exists in that state.
        seedVolume(level, ship, anchor.seedOrDefault());

        ship.setTransformProvider(new LivingPlatformServerTransformProvider(entityUuid, anchor.bone(), level, ship));

        SHIP_CACHE.computeIfAbsent(entityUuid, k -> new ConcurrentHashMap<>()).put(anchor.id(), ship.getId());

        LivingPlatformNetworking.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity),
                new LivingPlatformSyncPacket(ship.getId(), entityUuid, anchor.id(), anchor.bone()));

        Mah.LOGGER.info("[KHP] Created living platform ship {} for entity {} ({}) anchor \"{}\" (bone \"{}\").",
                ship.getId(), entityUuid, entity.getType(), anchor.id(), anchor.bone());

        return ship;
    }

    /**
     * Immediate save if {@code ship} is already a {@link LoadedServerShip} (the common
     * case); otherwise falls back to {@code ShipLoadEvent.once(...)} so the attachment
     * still gets saved eventually rather than being silently dropped (§15's actual bug)
     * — but as a supplement to the synchronous path, not a replacement/blocker for it.
     */
    private static void saveAttachment(ServerShip ship, UUID entityUuid, LivingPlatformAnchor anchor) {
        if (ship instanceof LoadedServerShip loaded) {
            ((AttachmentHolder) loaded).setAttachment(new LivingPlatformAttachment(entityUuid, anchor.id(), anchor.bone()));
            return;
        }
        long shipId = ship.getId();
        Mah.LOGGER.warn("[KHP] Living platform ship {} for entity {} anchor {} was not a LoadedServerShip "
                + "immediately after creation — falling back to ShipLoadEvent. If this ship never shows up "
                + "having its attachment saved, that event isn't firing for ships like this and the fallback "
                + "isn't working either.", shipId, entityUuid, anchor.id());
        VSGameUtilsKt.getVsCore().getShipLoadEvent().once(
                event -> event.getShip().getId() == shipId,
                event -> {
                    ((AttachmentHolder) event.getShip()).setAttachment(
                            new LivingPlatformAttachment(entityUuid, anchor.id(), anchor.bone()));
                    Mah.LOGGER.info("[KHP] Living platform ship {} attachment saved via deferred ShipLoadEvent "
                            + "fallback.", shipId);
                });
    }

    /**
     * Fills {@code shape}'s volume, centered on {@code ship}'s chunk-claim center,
     * with {@link LivingPlatformSeedBlock} cells — the permanent understructure
     * that gives players an actual surface to build against from the moment the
     * ship exists (see {@code KHP_Phase2E_Design.md} §10 for why this is needed
     * at all: an empty ship has nothing rendered for a player to raycast a block
     * placement against).
     *
     * <p>Center-point math ({@code ChunkClaim.getCenterBlockCoordinates}) is the
     * exact call {@code ShipCreatorItem} itself uses to find where its relocated
     * block should land — same pivot convention {@link LivingPlatformServerTransformProvider}
     * implicitly relies on via {@code toBuilder()} inheriting the ship's original
     * creation-time {@code positionInModel}, so a seed volume centered here lines
     * up with the bone position the transform provider drives every tick.
     *
     * <p>Centering per axis uses {@code -half .. size-half-1} rather than a
     * symmetric {@code -half..+half}, so both even and odd sizes produce exactly
     * {@code size} cells with no off-by-one — a 4-wide axis gets cells at
     * {@code -2, -1, 0, 1} relative to center, not 5 cells or an asymmetric gap.
     */
    private static void seedVolume(ServerLevel level, ServerShip ship, LivingPlatformSeedShape shape) {
        Vector3i center = ship.getChunkClaim().getCenterBlockCoordinates(
                VSGameUtilsKt.getYRange(level), new Vector3i());
        BlockState seedState = LivingPlatformBlocks.LIVING_PLATFORM_SEED.get().defaultBlockState();

        int halfW = shape.width() / 2;
        int halfH = shape.height() / 2;
        int halfD = shape.depth() / 2;

        for (int dx = -halfW; dx < shape.width() - halfW; dx++) {
            for (int dy = -halfH; dy < shape.height() - halfH; dy++) {
                for (int dz = -halfD; dz < shape.depth() - halfD; dz++) {
                    BlockPos pos = new BlockPos(center.x + dx, center.y + dy, center.z + dz);
                    level.setBlock(pos, seedState, 3);
                }
            }
        }
    }

    /** Every ship currently known to belong to {@code entityUuid} — used by {@link LivingPlatformDeathHandler}. */
    public static java.util.List<ServerShip> findAllShipsForEntity(ServerLevel level, UUID entityUuid) {
        org.valkyrienskies.core.internal.world.VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
        java.util.List<ServerShip> result = new java.util.ArrayList<>();
        for (ServerShip ship : world.getAllShips()) {
            if (!(ship instanceof LoadedServerShip loaded)) continue;
            LivingPlatformAttachment attachment = ((AttachmentHolder) loaded).getAttachment(LivingPlatformAttachment.class);
            if (attachment != null && attachment.entityUuid().equals(entityUuid)) {
                result.add(ship);
            }
        }
        return result;
    }

    /**
     * Per-tick freeze bookkeeping for one whitelisted entity — halts it via
     * {@code Mob#setNoAi} when {@link MahApi#hasLivingPlatformData} reports nothing
     * usable at all (neither a fresh live snapshot nor a blueprint), and releases it
     * again once data returns. Confirmed via {@code javap}: {@code Mob.setNoAi(boolean)}
     * and {@code Entity.setDeltaMovement(Vec3)}/{@code PathNavigation.stop()} are real,
     * public, mapped methods.
     *
     * <p>Only {@link Mob} can actually be halted this way (vanilla AI hook) — a
     * non-{@code Mob} {@link LivingEntity} whitelisted here would need a different
     * freeze mechanism, not yet designed; logs once and no-ops rather than guessing.
     */
    public static void tickFreezeState(Entity entity) {
        boolean hasData = MahApi.hasLivingPlatformData(entity);
        boolean currentlyFrozen = FROZEN_ENTITIES.contains(entity.getUUID());

        if (!hasData && !currentlyFrozen) {
            if (entity instanceof Mob mob) {
                mob.setNoAi(true);
                mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                mob.getNavigation().stop();
                FROZEN_ENTITIES.add(entity.getUUID());
            } else {
                Mah.LOGGER.warn("[KHP] Living platform entity {} ({}) has no snapshot data and is not a Mob — "
                        + "can't halt its AI, freeze behavior not implemented for this entity class.",
                        entity.getUUID(), entity.getType());
            }
        } else if (hasData && currentlyFrozen) {
            if (entity instanceof Mob mob) {
                mob.setNoAi(false);
            }
            FROZEN_ENTITIES.remove(entity.getUUID());
        }
    }

    public static boolean isFrozen(UUID entityUuid) {
        return FROZEN_ENTITIES.contains(entityUuid);
    }

    static void forgetEntity(UUID entityUuid) {
        SHIP_CACHE.remove(entityUuid);
        FROZEN_ENTITIES.remove(entityUuid);
    }

    /** Reads back the {@link LivingPlatformAttachment} previously saved on {@code ship}, if any. */
    @Nullable
    public static LivingPlatformAttachment getAttachment(ServerShip ship) {
        if (!(ship instanceof LoadedServerShip loaded)) return null;
        return ((AttachmentHolder) loaded).getAttachment(LivingPlatformAttachment.class);
    }

    /**
     * Resolves the owning entity's UUID directly from a {@link Ship} reference —
     * used by {@code EntityShipCollisionUtilsMixin} (§16), which already has the
     * {@code Ship} object in hand from VS2's own collision-candidate loop, so no
     * spatial {@code getShipManagingPos} lookup is needed here unlike
     * {@link LivingPlatformSeedBlock}'s original (architecturally dead-end, see
     * §16) per-block approach. Works on both sides: server ships read via
     * {@code AttachmentHolder} (real API, confirmed working this session once
     * the {@code LoadedServerShip} timing issue was fixed — §15); client ships
     * have no attachment holder at all (confirmed §9), so this falls through to
     * {@link ClientLivingPlatformRegistry}'s own synced map instead.
     */
    @Nullable
    public static UUID resolveOwningEntityUuid(org.valkyrienskies.core.api.ships.Ship ship) {
        if (ship instanceof LoadedServerShip loaded) {
            LivingPlatformAttachment attachment = ((AttachmentHolder) loaded).getAttachment(LivingPlatformAttachment.class);
            return attachment != null ? attachment.entityUuid() : null;
        }
        LivingPlatformAttachment attachment = ClientLivingPlatformRegistry.getAttachmentByShipId(ship.getId());
        return attachment != null ? attachment.entityUuid() : null;
    }

    private LivingPlatformShipManager() {
    }
}
