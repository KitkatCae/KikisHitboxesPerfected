package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.compat.Vs2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/** Click passthrough to the anchoring mob, shared by the seed and shell-cell blocks. */
public final class LivingPlatformEntityRedirect {

    private LivingPlatformEntityRedirect() {
    }

    /** Empty for the anchoring mob itself (so it doesn't suffocate in its platform). */
    public static VoxelShape getCollisionShapeForSelf(BlockState state, BlockGetter level, BlockPos pos,
                                                        CollisionContext context, VoxelShape fallback) {
        // resolveOwningEntityUuid touches VS2 types.
        if (!Vs2Compat.isLoaded()) return fallback;
        if (context instanceof EntityCollisionContext ctx && ctx.getEntity() != null && level instanceof Level realLevel) {
            UUID owningEntityUuid = resolveOwningEntityUuid(realLevel, pos);
            if (owningEntityUuid != null && owningEntityUuid.equals(ctx.getEntity().getUUID())) {
                return Shapes.empty();
            }
        }
        return fallback;
    }

    @Nullable
    private static UUID resolveOwningEntityUuid(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
            if (ship == null) return null;
            LivingPlatformAttachment attachment = ClientLivingPlatformRegistry.getAttachmentByShipId(ship.getId());
            return attachment != null ? attachment.entityUuid() : null;
        }
        if (!(level instanceof ServerLevel serverLevel)) return null;
        LivingPlatformAttachment attachment = LivingPlatformShipManager.getAttachmentAtPos(serverLevel, pos);
        return attachment != null ? attachment.entityUuid() : null;
    }

    public static void handleAttack(Level level, BlockPos pos, Player player) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return;

        Entity target = resolveTargetEntity(serverLevel, pos);
        if (target == null) return;

        player.attack(target);
    }

    /** Falls back to {@code fallback} if the entity doesn't consume the click. */
    public static InteractionResult handleUse(Level level, BlockPos pos, Player player, InteractionHand hand,
                                                Supplier<InteractionResult> fallback) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        Entity target = resolveTargetEntity(serverLevel, pos);
        if (target == null) {
            return fallback.get();
        }

        InteractionResult result = target.interact(player, hand);
        if (result.consumesAction()) {
            return result;
        }
        return fallback.get();
    }

    @Nullable
    private static Entity resolveTargetEntity(ServerLevel serverLevel, BlockPos pos) {
        if (!Vs2Compat.isLoaded()) return null;
        LivingPlatformAttachment attachment = LivingPlatformShipManager.getAttachmentAtPos(serverLevel, pos);
        if (attachment == null) return null;

        Entity target = serverLevel.getEntity(attachment.entityUuid());
        if (target == null) {
            Mah.LOGGER.warn("[KHP] Living platform block at {} redirects to entity {} but it isn't loaded — "
                    + "interaction had no effect.", pos, attachment.entityUuid());
        }
        return target;
    }
}
