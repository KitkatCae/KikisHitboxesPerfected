package exp.CCnewmods.khp.livingplatform.partial;

import exp.CCnewmods.khp.livingplatform.LivingPlatformEntityRedirect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * KHP's own shell-cell block (rather than a Copycat block) so suffocation, view blocking and rendering can
 * depend on KHP's seed state. Any block can still be used as a material. Collision is the union of present
 * quadrants.
 */
public class KHPPartialBlock extends Block implements EntityBlock {

    public KHPPartialBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!(level.getBlockEntity(pos) instanceof KHPPartialBlockEntity be)) {
            return Shapes.block();
        }

        VoxelShape shape = Shapes.empty();
        Direction exposedFace = be.getExposedFace();
        for (int i = 0; i < KHPPartialBlockEntity.QUADRANTS; i++) {
            if (be.getQuadrant(i) != null || be.isQuadrantPresent(i)) {
                shape = Shapes.or(shape, Shapes.create(quadrantBox(exposedFace, i)));
            }
        }
        return shape;
    }

    /** Quadrant order (NW/NE/SW/SE) must match {@code KHPPartialBakedModel#cropToQuadrant}. */
    private static AABB quadrantBox(Direction exposedFace, int quadrantIndex) {
        double[] min = {0, 0, 0};
        double[] max = {1, 1, 1};

        int axisA;
        int axisB;
        switch (exposedFace.getAxis()) {
            case X -> { axisA = 1; axisB = 2; }
            case Y -> { axisA = 0; axisB = 2; }
            default -> { axisA = 0; axisB = 1; }
        }

        double originA = (quadrantIndex == 1 || quadrantIndex == 3) ? 0.5 : 0.0;
        double originB = (quadrantIndex == 2 || quadrantIndex == 3) ? 0.5 : 0.0;
        min[axisA] = originA;
        max[axisA] = originA + 0.5;
        min[axisB] = originB;
        max[axisB] = originB + 0.5;

        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    /** Not used by VS2 movement collision (see {@code EntityShipCollisionUtilsMixin}); other queries still read it. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape fallback = getShape(state, level, pos, context);
        return LivingPlatformEntityRedirect.getCollisionShapeForSelf(state, level, pos, context, fallback);
    }

    /** Clicks always pass through to the anchoring entity, whatever's been built. */
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KHPPartialBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }
}
