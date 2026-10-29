package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.primitives.AABBic;
import org.valkyrienskies.core.api.attachment.AttachmentHolder;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * When an anchoring mob dies or is permanently removed, clears its seed blocks and detaches its ships (deleting
 * any nobody built on). {@code /kill} doesn't fire {@link LivingDeathEvent}, hence the leave-level backup,
 * which ignores chunk unloads.
 */
// Registered by LivingPlatformWiring only when VS2 is present.
public final class LivingPlatformDeathHandler {

    @SubscribeEvent
    static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;
        handleEntityGoneForever(serverLevel, entity);
    }

    @SubscribeEvent
    static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        Entity entity = event.getEntity();

        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) return;

        handleEntityGoneForever(serverLevel, entity);
    }

    private static void handleEntityGoneForever(ServerLevel serverLevel, Entity entity) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (typeId == null || !LivingPlatformStore.isWhitelisted(typeId)) return;

        for (ServerShip ship : LivingPlatformShipManager.findAllShipsForEntity(serverLevel, entity.getUUID())) {
            // Detaching hands the ship to VS2's normal physics.
            // TODO: assumes setTransformProvider accepts null.
            ship.setTransformProvider(null);

            boolean onlySeedBlocks = clearSeedBlocksAndCheckIfShipIsNowEmpty(serverLevel, ship);

            if (ship instanceof LoadedServerShip loaded) {
                ((AttachmentHolder) loaded).removeAttachment(LivingPlatformAttachment.class);
            }
            LivingPlatformShipManager.forgetShip(ship.getId());

            if (onlySeedBlocks) {
                VSGameUtilsKt.getShipObjectWorld(serverLevel).deleteShip(ship);
                Mah.LOGGER.info("[KHP] Living platform ship {} for entity {} had no player-built blocks — "
                        + "deleted entirely rather than left as an empty husk.", ship.getId(), entity.getUUID());
            } else {
                Mah.LOGGER.info("[KHP] Living platform ship {} for entity {} detached and its seed blocks "
                        + "cleared; player-built structure remains and the ship falls under normal physics.",
                        ship.getId(), entity.getUUID());
            }
        }

        LivingPlatformShipManager.forgetEntity(entity.getUUID());
    }

    /** @return whether the ship is now empty and should be deleted. */
    private static boolean clearSeedBlocksAndCheckIfShipIsNowEmpty(ServerLevel level, ServerShip ship) {
        AABBic shipAABB = ship.getShipAABB();
        if (shipAABB == null) return true;

        Block seedBlock = LivingPlatformBlocks.LIVING_PLATFORM_SEED.get();
        boolean hasOtherBlocks = false;

        for (int x = shipAABB.minX(); x <= shipAABB.maxX(); x++) {
            for (int y = shipAABB.minY(); y <= shipAABB.maxY(); y++) {
                for (int z = shipAABB.minZ(); z <= shipAABB.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(seedBlock)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    } else if (!state.isAir()) {
                        hasOtherBlocks = true;
                    }
                }
            }
        }

        return !hasOtherBlocks;
    }

    private LivingPlatformDeathHandler() {
    }
}
