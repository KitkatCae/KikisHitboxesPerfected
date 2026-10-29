package exp.CCnewmods.khp.livingplatform.partial.client;

import exp.CCnewmods.khp.livingplatform.partial.KHPPartialBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a shell cell's look from each quadrant's material model at quad-request time (works for any block).
 * {@link #cropToQuadrant} assumes the {@code DefaultVertexFormat.BLOCK} layout (8 ints/vertex).
 */
public class KHPPartialBakedModel extends BakedModelWrapper<BakedModel> {

    public static final ModelProperty<Direction> EXPOSED_FACE = new ModelProperty<>();
    public static final ModelProperty<BlockState[]> QUADRANTS = new ModelProperty<>();

    public KHPPartialBakedModel(BakedModel templateModel) {
        super(templateModel);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        if (level.getBlockEntity(pos) instanceof KHPPartialBlockEntity be) {
            return ModelData.builder()
                    .with(EXPOSED_FACE, be.getExposedFace())
                    .with(QUADRANTS, be.snapshotQuadrants())
                    .build();
        }
        return modelData;
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        // Every layer, so each material renders on whichever layer it uses.
        return ChunkRenderTypeSet.of(RenderType.solid(), RenderType.cutout(),
                RenderType.cutoutMipped(), RenderType.translucent());
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                     ModelData data, @Nullable RenderType renderType) {
        if (side == null) {
            return List.of();
        }

        Direction exposedFace = data.get(EXPOSED_FACE);
        BlockState[] quadrants = data.get(QUADRANTS);
        if (exposedFace == null || quadrants == null) {
            return List.of();
        }

        List<BakedQuad> result = new ArrayList<>();

        if (side == exposedFace) {
            for (int i = 0; i < 4; i++) {
                BlockState material = quadrants[i];
                if (material == null) {
                    continue;
                }
                BakedModel materialModel = materialModel(material);
                if (renderType != null
                        && !materialModel.getRenderTypes(material, rand, ModelData.EMPTY).contains(renderType)) {
                    continue;
                }
                for (BakedQuad quad : materialModel.getQuads(material, side, rand, ModelData.EMPTY, renderType)) {
                    result.add(cropToQuadrant(quad, side, i));
                }
            }
            return result;
        }

        // Backing faces are almost always culled; drawn so an exposed one doesn't show a hole.
        BlockState backing = firstNonNull(quadrants);
        if (backing != null) {
            BakedModel materialModel = materialModel(backing);
            if (renderType == null
                    || materialModel.getRenderTypes(backing, rand, ModelData.EMPTY).contains(renderType)) {
                result.addAll(materialModel.getQuads(backing, side, rand, ModelData.EMPTY, renderType));
            }
        }
        return result;
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        BlockState[] quadrants = data.get(QUADRANTS);
        BlockState backing = quadrants != null ? firstNonNull(quadrants) : null;
        return backing != null
                ? materialModel(backing).getParticleIcon(ModelData.EMPTY)
                : super.getParticleIcon(data);
    }

    private static BakedModel materialModel(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
    }

    @Nullable
    private static BlockState firstNonNull(BlockState[] quadrants) {
        for (BlockState s : quadrants) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** Squeezes the quad into one quarter of the face (NW/NE/SW/SE); UVs untouched, so the full texture shows. */
    private static BakedQuad cropToQuadrant(BakedQuad quad, Direction face, int quadrantIndex) {
        int[] src = quad.getVertices();
        int[] out = src.clone();

        int axisA;
        int axisB;
        switch (face.getAxis()) {
            case X -> { axisA = 1; axisB = 2; }
            case Y -> { axisA = 0; axisB = 2; }
            default -> { axisA = 0; axisB = 1; }
        }

        float originA = (quadrantIndex == 1 || quadrantIndex == 3) ? 0.5f : 0.0f;
        float originB = (quadrantIndex == 2 || quadrantIndex == 3) ? 0.5f : 0.0f;

        int intsPerVertex = src.length / 4;
        for (int v = 0; v < 4; v++) {
            int base = v * intsPerVertex;
            float a = Float.intBitsToFloat(src[base + axisA]);
            float b = Float.intBitsToFloat(src[base + axisB]);
            out[base + axisA] = Float.floatToRawIntBits(originA + a * 0.5f);
            out[base + axisB] = Float.floatToRawIntBits(originB + b * 0.5f);
        }

        return new BakedQuad(out, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade());
    }
}
