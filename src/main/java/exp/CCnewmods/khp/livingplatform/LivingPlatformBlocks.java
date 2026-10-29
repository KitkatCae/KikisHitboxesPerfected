package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.livingplatform.partial.KHPPartialBlock;
import exp.CCnewmods.khp.livingplatform.partial.KHPPartialBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * {@code SEED} is barrier-like (solid, invisible, unbreakable) but never suffocates. {@code KHP_PARTIAL} only
 * suffocates once something has been built into it.
 */
public final class LivingPlatformBlocks {

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Mah.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Mah.MODID);

    public static final RegistryObject<Block> LIVING_PLATFORM_SEED = BLOCKS.register("living_platform_seed",
            () -> new LivingPlatformSeedBlock(BlockBehaviour.Properties.of()
                    .strength(-1.0f, 3600000.8f)
                    .noLootTable()
                    .noOcclusion()
                    .isValidSpawn((state, level, pos, entityType) -> false)
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false)
                    .noParticlesOnBreak()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)));

    public static final RegistryObject<KHPPartialBlock> KHP_PARTIAL = BLOCKS.register("khp_partial",
            () -> new KHPPartialBlock(BlockBehaviour.Properties.of()
                    .strength(-1.0f, 3600000.8f)
                    .noLootTable()
                    .noOcclusion()
                    .isValidSpawn((state, level, pos, entityType) -> false)
                    .noParticlesOnBreak()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
                    .isSuffocating((state, level, pos) ->
                            !(level.getBlockEntity(pos) instanceof KHPPartialBlockEntity be) || !be.isPureSeed())
                    .isViewBlocking((state, level, pos) ->
                            !(level.getBlockEntity(pos) instanceof KHPPartialBlockEntity be) || !be.isPureSeed())));

    public static final RegistryObject<BlockEntityType<KHPPartialBlockEntity>> KHP_PARTIAL_BE =
            BLOCK_ENTITY_TYPES.register("khp_partial",
                    () -> BlockEntityType.Builder.of(KHPPartialBlockEntity::new, KHP_PARTIAL.get()).build(null));

    private LivingPlatformBlocks() {
    }
}
