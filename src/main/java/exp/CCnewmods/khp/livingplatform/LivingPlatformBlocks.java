package exp.CCnewmods.khp.livingplatform;

import exp.CCnewmods.khp.Mah;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * KHP's first registered content — previously a pure capture/API mod with no
 * blocks or items of its own. {@code SEED} properties are a direct copy of
 * vanilla {@code BarrierBlock}'s own registration (bytecode-confirmed this
 * session: {@code strength(-1.0f, 3600000.8f).noLootTable().noOcclusion()
 * .isValidSpawn(Blocks::never).noParticlesOnBreak()
 * .pushReaction(PushReaction.BLOCK)}), since the seed block needs exactly the
 * same "solid, invisible, unbreakable in survival" contract barrier already has
 * — no reason to reinvent those constants.
 *
 * <p><b>{@code isSuffocating}/{@code isViewBlocking} both forced false</b> (§21 —
 * the "still suffocating" bug). Confirmed via decompile that vanilla
 * {@code GLASS} uses exactly this pattern —
 * {@code .isSuffocating(Blocks::never).isViewBlocking(Blocks::never)} — to be
 * solid without ever triggering the suffocation-damage check
 * ({@code Entity.isInWall()} reads {@code BlockState.isSuffocating}
 * directly, confirmed via {@code BlockBehaviour.BlockStateBase
 * .isSuffocating}'s own implementation). Simpler and more correct than the
 * mixin approach considered earlier in this session (§19's diagnosis of VS2's
 * own {@code isInShipWall} inject) — this fixes the property at the source
 * instead of trying to intercept and second-guess the check downstream.
 * {@code Blocks::never} itself isn't accessible (private to vanilla's own
 * {@code Blocks} class) so this uses an equivalent inline lambda instead.
 */
public final class LivingPlatformBlocks {

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Mah.MODID);

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

    private LivingPlatformBlocks() {
    }
}
