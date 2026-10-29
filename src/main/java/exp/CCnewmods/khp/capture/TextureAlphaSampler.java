package exp.CCnewmods.khp.capture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads per-pixel alpha from texture files for transparency culling (the GPU copy isn't readable). Synthetic
 * texture keys (e.g. Ice and Fire dragons) are mapped to their real layers by registered resolvers, using the
 * max opacity across layers. Render thread only.
 */
public final class TextureAlphaSampler {

    private static final Map<ResourceLocation, NativeImage> CACHE = new HashMap<>();
    private static final Map<ResourceLocation, Boolean> FAILED = new HashMap<>();

    /** Empty list = resolvers tried, found nothing. */
    private static final Map<ResourceLocation, List<ResourceLocation>> RESOLVED_LAYERS = new HashMap<>();

    public interface DynamicTextureLayerResolver {
        List<ResourceLocation> resolveLayers(ResourceLocation texture);
    }

    private static final List<DynamicTextureLayerResolver> LAYER_RESOLVERS = new CopyOnWriteArrayList<>();

    public static void registerLayerResolver(DynamicTextureLayerResolver resolver) {
        LAYER_RESOLVERS.add(resolver);
    }

    /** 0.0-1.0. Unreadable textures count as opaque, so they never become pass-throughs. */
    public static float sampleOpacity(ResourceLocation texture, float u, float v) {
        return opacityAt(texture, u, v);
    }

    /** Minimum opacity over a UV rect; one opaque pixel keeps a face solid. */
    public static float sampleMinOpacityInRect(ResourceLocation texture, float u0, float v0, float u1, float v1) {
        float minU = Math.min(u0, u1), maxU = Math.max(u0, u1);
        float minV = Math.min(v0, v1), maxV = Math.max(v0, v1);

        final int SAMPLE_GRID = 4;
        float minOpacity = 1.0f;
        for (int gy = 0; gy < SAMPLE_GRID; gy++) {
            float v = SAMPLE_GRID == 1 ? (minV + maxV) * 0.5f
                    : minV + (maxV - minV) * (gy / (float) (SAMPLE_GRID - 1));
            for (int gx = 0; gx < SAMPLE_GRID; gx++) {
                float u = SAMPLE_GRID == 1 ? (minU + maxU) * 0.5f
                        : minU + (maxU - minU) * (gx / (float) (SAMPLE_GRID - 1));
                float opacity = opacityAt(texture, u, v);
                if (opacity < minOpacity) minOpacity = opacity;
            }
        }
        return minOpacity;
    }

    /** Whether a UV rect is opaque, transparent or mixed, plus the tight bounds of its opaque part. */
    public static RectAnalysis analyzeRect(ResourceLocation texture, float u0, float v0, float u1, float v1,
                                           float threshold) {
        float minU = Math.min(u0, u1), maxU = Math.max(u0, u1);
        float minV = Math.min(v0, v1), maxV = Math.max(v0, v1);

        final int SAMPLE_GRID = 12; // coarser grids miss the opaque/transparent edge and under-estimate bounds
        boolean anyOpaque = false;
        boolean anyTransparent = false;
        float opaqueMinU = Float.MAX_VALUE, opaqueMinV = Float.MAX_VALUE;
        float opaqueMaxU = -Float.MAX_VALUE, opaqueMaxV = -Float.MAX_VALUE;

        for (int gy = 0; gy < SAMPLE_GRID; gy++) {
            float v = minV + (maxV - minV) * (gy / (float) (SAMPLE_GRID - 1));
            for (int gx = 0; gx < SAMPLE_GRID; gx++) {
                float u = minU + (maxU - minU) * (gx / (float) (SAMPLE_GRID - 1));
                float opacity = opacityAt(texture, u, v);
                if (opacity > threshold) {
                    anyOpaque = true;
                    if (u < opaqueMinU) opaqueMinU = u;
                    if (v < opaqueMinV) opaqueMinV = v;
                    if (u > opaqueMaxU) opaqueMaxU = u;
                    if (v > opaqueMaxV) opaqueMaxV = v;
                } else {
                    anyTransparent = true;
                }
            }
        }

        if (!anyOpaque) {
            return new RectAnalysis(false, true, u0, v0, u1, v1);
        }
        // A rect collapsed to one sample is more likely edge bleed than a real sliver.
        float spanU = opaqueMaxU - opaqueMinU;
        float spanV = opaqueMaxV - opaqueMinV;
        float stepU = Math.abs(maxU - minU) / (SAMPLE_GRID - 1);
        float stepV = Math.abs(maxV - minV) / (SAMPLE_GRID - 1);
        float minSpan = Math.min(stepU, stepV) * 1.5f;
        if (spanU < minSpan || spanV < minSpan) {
            return new RectAnalysis(true, false, u0, v0, u1, v1);
        }
        // Pad by one grid step: a slightly big box beats visible geometry you can't hit.
        float paddedMinU = Math.max(minU, opaqueMinU - stepU);
        float paddedMaxU = Math.min(maxU, opaqueMaxU + stepU);
        float paddedMinV = Math.max(minV, opaqueMinV - stepV);
        float paddedMaxV = Math.min(maxV, opaqueMaxV + stepV);
        return new RectAnalysis(anyOpaque, anyTransparent, paddedMinU, paddedMinV, paddedMaxU, paddedMaxV);
    }

    /**
     * Both {@code anyOpaque} and {@code anyTransparent} means a mixed face; opaque bounds are only valid when {@code
     * anyOpaque}.
     */
    public record RectAnalysis(boolean anyOpaque, boolean anyTransparent,
                                float opaqueMinU, float opaqueMinV, float opaqueMaxU, float opaqueMaxV) {
    }

    private static float opacityAt(ResourceLocation texture, float u, float v) {
        NativeImage direct = get(texture);
        if (direct != null) {
            return pixelOpacity(direct, u, v);
        }

        List<ResourceLocation> layers = resolveLayers(texture);
        if (layers.isEmpty()) return 1.0f;

        float maxOpacity = 0.0f;
        for (ResourceLocation layer : layers) {
            NativeImage layerImage = get(layer);
            if (layerImage == null) continue;
            float opacity = pixelOpacity(layerImage, u, v);
            if (opacity > maxOpacity) maxOpacity = opacity;
        }
        return maxOpacity;
    }

    private static float pixelOpacity(NativeImage image, float u, float v) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) return 1.0f;
        int px = clampIndex(Math.round(u * width), width);
        int py = clampIndex(Math.round(v * height), height);
        int argb = image.getPixelRGBA(px, py);
        return FastColor.ABGR32.alpha(argb) / 255.0f;
    }

    private static List<ResourceLocation> resolveLayers(ResourceLocation texture) {
        if (texture == null) return Collections.emptyList();
        List<ResourceLocation> cached = RESOLVED_LAYERS.get(texture);
        if (cached != null) return cached;

        for (DynamicTextureLayerResolver resolver : LAYER_RESOLVERS) {
            List<ResourceLocation> layers = resolver.resolveLayers(texture);
            if (layers != null && !layers.isEmpty()) {
                RESOLVED_LAYERS.put(texture, layers);
                return layers;
            }
        }
        RESOLVED_LAYERS.put(texture, Collections.emptyList());
        return Collections.emptyList();
    }

    private static int clampIndex(int v, int size) {
        if (v < 0) return 0;
        if (v >= size) return size - 1;
        return v;
    }

    private static NativeImage get(ResourceLocation texture) {
        if (texture == null) return null;
        NativeImage cached = CACHE.get(texture);
        if (cached != null) return cached;
        if (FAILED.containsKey(texture)) return null;

        try (InputStream stream = Minecraft.getInstance().getResourceManager().open(texture)) {
            NativeImage image = NativeImage.read(stream);
            CACHE.put(texture, image);
            return image;
        } catch (IOException | RuntimeException e) {
            // Expected for synthetic keys; resolveLayers handles those.
            FAILED.put(texture, Boolean.TRUE);
            return null;
        }
    }

    public static void invalidateAll() {
        for (NativeImage image : CACHE.values()) {
            image.close();
        }
        CACHE.clear();
        FAILED.clear();
        RESOLVED_LAYERS.clear();
    }

    private TextureAlphaSampler() {
    }
}
