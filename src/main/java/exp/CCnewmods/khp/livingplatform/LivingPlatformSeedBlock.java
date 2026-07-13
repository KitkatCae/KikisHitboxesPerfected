package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The permanent understructure block filling a living-platform anchor's seed
 * volume — see {@code KHP_Phase2E_Design.md} §10. Modeled directly on vanilla's
 * own {@code BarrierBlock} (same {@code Properties}, same
 * {@code RenderShape.INVISIBLE} approach — bytecode-confirmed this session
 * against the real {@code BarrierBlock} class rather than assumed), with two
 * additions: punching it redirects to attacking the anchoring entity instead of
 * doing nothing, and it has no collision at all against that same entity (§13 —
 * without this, the seed volume sits exactly where the anchoring mob's own body
 * already is, and vanilla entity-block collision resolution shoves the mob out
 * of its own platform every tick; since the platform then re-appears at the
 * mob's new position next tick, this becomes a runaway feedback loop that
 * reads as the mob "flying into the sky").
 *
 * <p>Permanent understructure, not replaceable — this block is never removed by
 * normal player action (unbreakable in survival via {@code strength(-1, ...)},
 * same as barrier; no drop, no loot table). Players build around/over it, per
 * design sign-off.
 */
public final class LivingPlatformSeedBlock extends Block {

    public LivingPlatformSeedBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, net.minecraft.world.level.BlockGetter reader, BlockPos pos) {
        return true;
    }

    @Override
    public float getShadeBrightness(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return 1.0f;
    }

    /**
     * <b>Does not actually fix the main collision problem</b> — kept as a
     * secondary measure for whatever non-entity-movement collision queries
     * still consult a block's collision shape directly (particles, item
     * physics), but confirmed this session that VS2's real entity-vs-ship
     * movement collision never calls this at all: it resolves through a
     * pre-extracted polygon list fed into its own physics-engine collider
     * ({@code EntityShipCollisionUtils.getShipPolygonsCollidingWithEntity} →
     * {@code VsiEntityPolygonCollider}), bypassing {@code Block
     * .getCollisionShape}/{@code CollisionContext} entirely. The actual fix
     * for the entity-suffocating-in-its-own-platform problem is
     * {@code exp.CCnewmods.khp.mixin.vs2.EntityShipCollisionUtilsMixin} (§16),
     * which intercepts that real pipeline directly instead. See
     * {@code KHP_Phase2E_Design.md} §16 for the full story of how this was
     * found — this override was the original (wrong) fix attempt from §13.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                         CollisionContext context) {
        if (context instanceof EntityCollisionContext ctx && ctx.getEntity() != null && level instanceof Level realLevel) {
            UUID owningEntityUuid = resolveOwningEntityUuid(realLevel, pos);
            if (owningEntityUuid != null && owningEntityUuid.equals(ctx.getEntity().getUUID())) {
                return Shapes.empty();
            }
        }
        return super.getCollisionShape(state, level, pos, context);
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
        ServerShip ship = VSGameUtilsKt.getShipManagingPos(serverLevel, pos);
        if (ship == null) return null;
        LivingPlatformAttachment attachment = LivingPlatformShipManager.getAttachment(ship);
        return attachment != null ? attachment.entityUuid() : null;
    }

    /**
     * Redirects a punch on this block to a full normal melee attack
     * ({@code Player.attack(Entity)} — same method vanilla's own click handling
     * calls, so weapon damage/criticals/knockback/sweep all apply exactly as if
     * the player had clicked the entity directly) against whichever entity owns
     * the ship this block is part of. Resolved via
     * {@code VSGameUtilsKt.getShipManagingPos} + the ship's
     * {@link LivingPlatformAttachment}, not stored per-block — one less thing to
     * keep in sync if an anchor's owning entity ever needs to change.
     *
     * <p>Server-side only ({@code level.isClientSide()} guard) — this affects
     * real entity state and should have exactly one source of truth, not a
     * client-side echo of the same call.
     */
    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return;

        ServerShip ship = VSGameUtilsKt.getShipManagingPos(serverLevel, pos);
        if (ship == null) return;
        LivingPlatformAttachment attachment = LivingPlatformShipManager.getAttachment(ship);
        if (attachment == null) return;

        Entity target = serverLevel.getEntity(attachment.entityUuid());
        if (target == null) {
            Mah.LOGGER.warn("[KHP] Living platform seed block at {} redirects to entity {} but it isn't loaded — "
                    + "punch had no effect.", pos, attachment.entityUuid());
            return;
        }

        player.attack(target);
    }
}
