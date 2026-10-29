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
 * Creates and finds living-platform ships, and freezes anchoring entities that have no pose data. VS2's
 * attachment storage isn't reliably readable (ships often come back as plain {@code ShipData}), so
 * {@link #ATTACHMENT_CACHE} is what's actually read.
 */
public final class LivingPlatformShipManager {

    /** entityUuid → (anchorId → shipId). Fast path only. */
    private static final Map<UUID, Map<String, Long>> SHIP_CACHE = new ConcurrentHashMap<>();

    /** shipId → attachment. The source of truth; session-only. */
    private static final Map<Long, LivingPlatformAttachment> ATTACHMENT_CACHE = new ConcurrentHashMap<>();

    private static final Set<UUID> FROZEN_ENTITIES = ConcurrentHashMap.newKeySet();

    /**
     * Manual shift-right-click holds, kept apart from {@link #FROZEN_ENTITIES} so releasing one doesn't re-enable AI.
     */
    private static final Set<UUID> HELD_ENTITIES = ConcurrentHashMap.newKeySet();

    public static ServerShip getOrCreateShip(ServerLevel level, Entity entity, LivingPlatformAnchor anchor) {
        ServerShip existing = findExistingShip(level, entity.getUUID(), anchor.id());
        if (existing != null) return existing;
        return createShip(level, entity, anchor);
    }

    @Nullable
    private static ServerShip findExistingShip(ServerLevel level, UUID entityUuid, String anchorId) {
        Long cachedId = SHIP_CACHE.getOrDefault(entityUuid, Map.of()).get(anchorId);
        org.valkyrienskies.core.internal.world.VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
        if (cachedId != null) {
            ServerShip ship = world.getAllShips().getById(cachedId);
            if (ship != null) return ship;
        }

        for (ServerShip ship : world.getAllShips()) {
            LivingPlatformAttachment attachment = getAttachment(ship);
            if (attachment == null) continue;
            if (attachment.entityUuid().equals(entityUuid) && attachment.anchorId().equals(anchorId)) {
                SHIP_CACHE.computeIfAbsent(entityUuid, k -> new ConcurrentHashMap<>()).put(anchorId, ship.getId());
                return ship;
            }
        }
        return null;
    }

    /** {@code ShipLoadEvent} never fires for a fresh zero-block ship, so setup is synchronous. */
    private static ServerShip createShip(ServerLevel level, Entity entity, LivingPlatformAnchor anchor) {
        String dimensionId = VSGameUtilsKt.getDimensionId(level);
        Vector3i blockPos = new Vector3i(entity.blockPosition().getX(), entity.blockPosition().getY(), entity.blockPosition().getZ());

        org.valkyrienskies.core.internal.world.VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
        ServerShip ship = world.createNewShipAtBlock(blockPos, false, 1.0, dimensionId);
        UUID entityUuid = entity.getUUID();

        org.valkyrienskies.mod.common.util.EntityShipCollisionUtils.markShipAsRecentlySpawned(
                ship.getId(), level.getServer().getTickCount());

        saveAttachment(ship, entityUuid, anchor);

        // Seed BEFORE attaching the transform provider: a driven zero-block ship has zero mass and crashes
        // VS2's physics thread (invMass = 1/0).
        LivingPlatformSeedShape seedShape = anchor.seedOrDefault();
        seedVolume(level, ship, seedShape);

        ship.setTransformProvider(new LivingPlatformServerTransformProvider(
                entityUuid, anchor.bone(), level, ship, seedShape));

        SHIP_CACHE.computeIfAbsent(entityUuid, k -> new ConcurrentHashMap<>()).put(anchor.id(), ship.getId());

        LivingPlatformNetworking.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity),
                new LivingPlatformSyncPacket(ship.getId(), entityUuid, anchor.id(), anchor.bone()));

        Mah.LOGGER.info("[KHP] Created living platform ship {} for entity {} ({}) anchor \"{}\" (bone \"{}\").",
                ship.getId(), entityUuid, entity.getType(), anchor.id(), anchor.bone());

        return ship;
    }

    private static void saveAttachment(ServerShip ship, UUID entityUuid, LivingPlatformAnchor anchor) {
        LivingPlatformAttachment attachment = new LivingPlatformAttachment(entityUuid, anchor.id(), anchor.bone());
        // The real save; the AttachmentHolder attempt below is best-effort.
        ATTACHMENT_CACHE.put(ship.getId(), attachment);

        if (ship instanceof LoadedServerShip loaded) {
            ((AttachmentHolder) loaded).setAttachment(attachment);
            return;
        }
        long shipId = ship.getId();
        Mah.LOGGER.warn("[KHP] Living platform ship {} for entity {} anchor {} was not a LoadedServerShip "
                + "immediately after creation — falling back to ShipLoadEvent for VS2's own AttachmentHolder "
                + "storage (KHP's own ATTACHMENT_CACHE is already saved regardless).", shipId, entityUuid, anchor.id());
        VSGameUtilsKt.getVsCore().getShipLoadEvent().once(
                event -> event.getShip().getId() == shipId,
                event -> {
                    ((AttachmentHolder) event.getShip()).setAttachment(attachment);
                    Mah.LOGGER.info("[KHP] Living platform ship {} attachment saved via deferred ShipLoadEvent "
                            + "fallback.", shipId);
                });
    }

    /**
     * Fills {@code shape} around the ship's chunk-claim center. Shell cells are {@code KHPPartialBlock}s players can
     * build into; interior cells are plain seed blocks with no block entity.
     *
     * <p>{@code shape} is in half-block units. An odd count can't be centered exactly; the 0.25-block offset is
     * cancelled in {@link LivingPlatformServerTransformProvider}'s {@code positionInModel}.
     */
    private static void seedVolume(ServerLevel level, ServerShip ship, LivingPlatformSeedShape shape) {
        Vector3i center = ship.getChunkClaim().getCenterBlockCoordinates(
                VSGameUtilsKt.getYRange(level), new Vector3i());
        BlockState seedMaterial = LivingPlatformBlocks.LIVING_PLATFORM_SEED.get().defaultBlockState();

        int halfWQ = shape.widthQ() / 2;
        int halfHQ = shape.heightQ() / 2;
        int halfDQ = shape.depthQ() / 2;

        int[] fineMin = {
                -halfWQ,
                -halfHQ,
                -halfDQ,
        };
        int[] fineMax = {
                shape.widthQ() - halfWQ - 1,
                shape.heightQ() - halfHQ - 1,
                shape.depthQ() - halfDQ - 1,
        };

        int minX = Math.floorDiv(fineMin[0], 2), maxX = Math.floorDiv(fineMax[0], 2);
        int minY = Math.floorDiv(fineMin[1], 2), maxY = Math.floorDiv(fineMax[1], 2);
        int minZ = Math.floorDiv(fineMin[2], 2), maxZ = Math.floorDiv(fineMax[2], 2);

        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    BlockPos pos = new BlockPos(center.x + dx, center.y + dy, center.z + dz);
                    boolean isShell = dx == minX || dx == maxX
                            || dy == minY || dy == maxY
                            || dz == minZ || dz == maxZ;
                    if (isShell) {
                        net.minecraft.core.Direction outward = khp$dominantOutwardDirection(dx, dy, dz);
                        boolean[] presence = computeQuadrantPresence(outward, dx, dy, dz, fineMin, fineMax);
                        placeSeedGridCell(level, pos, outward, presence);
                    } else {
                        // Interior: never visible, so no block entity.
                        level.setBlock(pos, seedMaterial, 3);
                    }
                }
            }
        }
    }

    /**
     * Which quadrants of a shell cell's exposed face are inside the shape. Axis convention must match {@code
     * KHPPartialBlock#quadrantBox}.
     */
    private static boolean[] computeQuadrantPresence(net.minecraft.core.Direction exposedFace,
                                                       int dx, int dy, int dz, int[] fineMin, int[] fineMax) {
        int[] blockCoord = {dx, dy, dz};

        int axisA;
        int axisB;
        switch (exposedFace.getAxis()) {
            case X -> { axisA = 1; axisB = 2; }
            case Y -> { axisA = 0; axisB = 2; }
            default -> { axisA = 0; axisB = 1; }
        }

        boolean[] presence = new boolean[4];
        for (int i = 0; i < 4; i++) {
            boolean upperA = (i == 1 || i == 3);
            boolean upperB = (i == 2 || i == 3);

            int fineA = blockCoord[axisA] * 2 + (upperA ? 1 : 0);
            int fineB = blockCoord[axisB] * 2 + (upperB ? 1 : 0);

            presence[i] = fineA >= fineMin[axisA] && fineA <= fineMax[axisA]
                    && fineB >= fineMin[axisB] && fineB <= fineMax[axisB];
        }
        return presence;
    }

    /** {@code presence} is NW/NE/SW/SE; absent quadrants are gaps. */
    private static void placeSeedGridCell(ServerLevel level, BlockPos pos,
                                           net.minecraft.core.Direction outward, boolean[] presence) {
        level.setBlock(pos, LivingPlatformBlocks.KHP_PARTIAL.get().defaultBlockState(), 3);

        if (level.getBlockEntity(pos) instanceof
                exp.CCnewmods.khp.livingplatform.partial.KHPPartialBlockEntity be) {
            be.initSeedCell(outward, presence[0], presence[1], presence[2], presence[3]);
        } else {
            Mah.LOGGER.warn("[KHP] Placed khp_partial seed cell at {} but its block entity wasn't "
                    + "the expected type immediately after placement — left at defaults.", pos);
        }
    }

    private static net.minecraft.core.Direction khp$dominantOutwardDirection(int dx, int dy, int dz) {
        int absX = Math.abs(dx), absY = Math.abs(dy), absZ = Math.abs(dz);
        if (absY >= absX && absY >= absZ) {
            return dy >= 0 ? net.minecraft.core.Direction.UP : net.minecraft.core.Direction.DOWN;
        } else if (absX >= absZ) {
            return dx >= 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST;
        } else {
            return dz >= 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
        }
    }

    public static java.util.List<ServerShip> findAllShipsForEntity(ServerLevel level, UUID entityUuid) {
        org.valkyrienskies.core.internal.world.VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(level);
        java.util.List<ServerShip> result = new java.util.ArrayList<>();
        for (ServerShip ship : world.getAllShips()) {
            LivingPlatformAttachment attachment = getAttachment(ship);
            if (attachment != null && attachment.entityUuid().equals(entityUuid)) {
                result.add(ship);
            }
        }
        return result;
    }

    /** Halts the entity via {@code setNoAi} while it has no pose data (or is held), releases it when it does. */
    public static void tickFreezeState(Entity entity) {
        UUID uuid = entity.getUUID();
        boolean hasData = MahApi.hasLivingPlatformData(entity);
        boolean held = HELD_ENTITIES.contains(uuid);
        boolean shouldFreeze = held || !hasData;
        boolean currentlyFrozen = FROZEN_ENTITIES.contains(uuid);

        if (shouldFreeze) {
            if (!currentlyFrozen) {
                if (entity instanceof Mob mob) {
                    mob.setNoAi(true);
                    mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    mob.getNavigation().stop();
                    FROZEN_ENTITIES.add(uuid);
                } else {
                    Mah.LOGGER.warn("[KHP] Living platform entity {} ({}) has no snapshot data and is not a Mob — "
                            + "can't halt its AI, freeze behavior not implemented for this entity class.",
                            uuid, entity.getType());
                }
            } else if (held && entity instanceof Mob mob) {
                // Every tick: setNoAi doesn't stop knockback or gravity.
                mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                mob.getNavigation().stop();
            }
        } else if (currentlyFrozen) {
            if (entity instanceof Mob mob) {
                mob.setNoAi(false);
            }
            FROZEN_ENTITIES.remove(uuid);
        }
    }

    public static boolean isFrozen(UUID entityUuid) {
        return FROZEN_ENTITIES.contains(entityUuid);
    }

    public static boolean isHeld(UUID entityUuid) {
        return HELD_ENTITIES.contains(entityUuid);
    }

    /** @return the new held state. Applied on the next {@link #tickFreezeState}. */
    public static boolean toggleHeld(Entity entity) {
        UUID uuid = entity.getUUID();
        if (!HELD_ENTITIES.add(uuid)) {
            HELD_ENTITIES.remove(uuid);
            return false;
        }
        return true;
    }

    static void forgetEntity(UUID entityUuid) {
        SHIP_CACHE.remove(entityUuid);
        FROZEN_ENTITIES.remove(entityUuid);
        HELD_ENTITIES.remove(entityUuid);
    }

    @Nullable
    public static LivingPlatformAttachment getAttachment(ServerShip ship) {
        LivingPlatformAttachment cached = ATTACHMENT_CACHE.get(ship.getId());
        if (cached != null) return cached;
        if (!(ship instanceof LoadedServerShip loaded)) return null;
        return ((AttachmentHolder) loaded).getAttachment(LivingPlatformAttachment.class);
    }

    @Nullable
    public static LivingPlatformAttachment getAttachmentAtPos(ServerLevel level, BlockPos pos) {
        ServerShip shipAtPos = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (shipAtPos == null) return null;
        return getAttachment(shipAtPos);
    }

    static void forgetShip(long shipId) {
        ATTACHMENT_CACHE.remove(shipId);
    }

    /** Client ships have no attachment, so the client falls back to {@link ClientLivingPlatformRegistry}. */
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
