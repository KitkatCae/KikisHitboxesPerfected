package exp.CCnewmods.khp.livingplatform.partial;

import exp.CCnewmods.khp.livingplatform.LivingPlatformBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.NbtUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * A shell cell's exposed face and its four quadrants: whether each is present (inside the seed shape) and what
 * material, if any, a player has built into it ({@code null} = still seed).
 */
public class KHPPartialBlockEntity extends BlockEntity {

    public static final int QUADRANTS = 4;

    private Direction exposedFace = Direction.UP;
    private final BlockState[] quadrants = new BlockState[QUADRANTS];
    private final boolean[] presentQuadrants = new boolean[QUADRANTS];

    public KHPPartialBlockEntity(BlockPos pos, BlockState state) {
        super(LivingPlatformBlocks.KHP_PARTIAL_BE.get(), pos, state);
    }

    public Direction getExposedFace() {
        return exposedFace;
    }

    @Nullable
    public BlockState getQuadrant(int index) {
        return quadrants[index];
    }

    public boolean isQuadrantPresent(int index) {
        return presentQuadrants[index];
    }

    public BlockState[] snapshotQuadrants() {
        return Arrays.copyOf(quadrants, QUADRANTS);
    }

    /** Read by {@code KHP_PARTIAL}'s suffocation/view-blocking: pure-seed cells never suffocate. */
    public boolean isPureSeed() {
        for (BlockState s : quadrants) {
            if (s != null) {
                return false;
            }
        }
        return true;
    }

    public void initSeedCell(Direction exposedFace) {
        initSeedCell(exposedFace, true, true, true, true);
    }

    /** No sync needed: the client rebuilds this from the update tag. */
    public void initSeedCell(Direction exposedFace, boolean nw, boolean ne, boolean sw, boolean se) {
        this.exposedFace = exposedFace;
        presentQuadrants[0] = nw;
        presentQuadrants[1] = ne;
        presentQuadrants[2] = sw;
        presentQuadrants[3] = se;
        Arrays.fill(quadrants, null);
        setChanged();
    }

    /** {@code null} = leave unchanged. Syncs once, only if something changed. */
    public void setQuadrants(@Nullable BlockState nw, @Nullable BlockState ne,
                              @Nullable BlockState sw, @Nullable BlockState se) {
        BlockState[] next = {nw, ne, sw, se};
        boolean changed = false;
        for (int i = 0; i < QUADRANTS; i++) {
            if (next[i] != null && !Objects.equals(quadrants[i], next[i])) {
                quadrants[i] = next[i];
                presentQuadrants[i] = true;
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        setChanged();
        requestModelDataUpdate();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("exposedFace", exposedFace.getSerializedName());
        for (int i = 0; i < QUADRANTS; i++) {
            tag.putBoolean("present" + i, presentQuadrants[i]);
            if (quadrants[i] != null) {
                tag.put("q" + i, NbtUtils.writeBlockState(quadrants[i]));
            }
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        Direction parsedFace = Direction.byName(tag.getString("exposedFace"));
        exposedFace = parsedFace != null ? parsedFace : Direction.UP;
        for (int i = 0; i < QUADRANTS; i++) {
            String key = "q" + i;
            quadrants[i] = tag.contains(key)
                    ? NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound(key))
                    : null;

            String presentKey = "present" + i;
            // Older saves have no presence data; every quadrant was present then.
            presentQuadrants[i] = tag.contains(presentKey) ? tag.getBoolean(presentKey) : true;
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }
}
