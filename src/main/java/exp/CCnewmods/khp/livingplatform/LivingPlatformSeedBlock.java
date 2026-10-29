package exp.CCnewmods.khp.livingplatform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible, unbreakable understructure block (barrier-like) in a platform's seed volume. Clicks pass through to
 * the anchoring entity, which also doesn't collide with it.
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

    /** Not used by VS2 movement collision (see {@code EntityShipCollisionUtilsMixin}); other queries still read it. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                         CollisionContext context) {
        VoxelShape fallback = super.getCollisionShape(state, level, pos, context);
        return LivingPlatformEntityRedirect.getCollisionShapeForSelf(state, level, pos, context, fallback);
    }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        LivingPlatformEntityRedirect.handleAttack(level, pos, player);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        return LivingPlatformEntityRedirect.handleUse(level, pos, player, hand,
                () -> super.use(state, level, pos, player, hand, hit));
    }
}
