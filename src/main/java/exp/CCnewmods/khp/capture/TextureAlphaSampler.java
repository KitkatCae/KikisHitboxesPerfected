package exp.CCnewmods.khp.capture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Samples per-pixel alpha directly from a resource-pack texture file, for Phase 2c's
 * transparency culling.
 *
 * <h3>Why this reads the file, not the GPU texture</h3>
 * Verified against forge-1.20.1-47.4.20 mapped jar: {@code SimpleTexture} (the class
 * backing ordinary entity skins) has no field anywhere in its hierarchy that retains
 * the {@link NativeImage} after {@code doLoad} uploads it to the GPU — the CPU-side
 * pixel buffer is not kept around. There is no live pixel data to read back off a
 * bound entity texture. This class instead re-reads the texture PNG directly via
 * {@link Minecraft#getResourceManager()}, which has the added benefit of already
 * being exactly "whatever the currently active resource pack provides" for any
 * texture read <b>after</b> a cache miss — but the {@link #CACHE}/{@link #FAILED}
 * maps below only ever get populated, never invalidated, on their own.
 * {@link #invalidateAll()} is the fix for that, and is now actually wired to both a
 * real resource-reload listener ({@code client.TextureCacheReloadListener}) and a
 * manual refresh command ({@code client.MahDebugCommand}, {@code /khprefresh}) — a
 * gap flagged since the very first opacity design doc (Phase 2A) as "not done" and
 * left that way through every phase since, until now.
 *
 * <h3>Dynamic/composited textures (Ice and Fire, Poison Dragons, and anything sharing
 * that convention)</h3>
 * Some renderers don't call {@code getTextureLocation} with a real resource-pack
 * path at all — Ice and Fire's dragons build a synthetic cache key (e.g.
 * {@code "dragon_texture_brown2truefalsetruefalsefalse"}, no {@code .png}, no
 * {@code textures/entity/} prefix — confirmed via bytecode:
 * {@code RenderDragonBase.getTextureLocation} builds a {@code List<String>} of real
 * layer paths, feeds it to {@code ArrayLayeredTexture}, and registers the composited
 * result with {@code TextureManager} under that synthetic key) and register a
 * composited {@code AbstractTexture} directly with the GPU, bypassing the
 * resource-pack file system entirely. Reading such a key as a file always fails.
 *
 * <p>{@link #registerLayerResolver} lets an optional compat class (kept out of this
 * core, mod-agnostic sampler — same isolation pattern as every other compat/ class)
 * teach this sampler how to recover the real underlying layer paths for a
 * mod-specific synthetic key, without giving this class a hard dependency on that
 * mod. When a resolver finds real layers, opacity at a point is the <b>max</b> across
 * all layers — an approximation (the real compositor's exact blend rule wasn't
 * verified), but a reasonable one: overlay layers in this style of system are
 * typically cosmetic additions over an already-opaque base, not the source of a
 * cutout shape, so "transparent only if every layer agrees it's transparent there"
 * should track the base layer's real cutouts correctly in the common case.
 *
 * <h3>Threading</h3>
 * Render-thread only, same as every other class in this package — the caches are
 * plain {@link HashMap}s, not concurrent ones (the resolver list is
 * {@link CopyOnWriteArrayList} only because registration happens once at compat
 * bootstrap, a different lifecycle than the per-frame sampling calls).
 */
public final class TextureAlphaSampler {

    private static final Logger LOGGER = LoggerFactory.getLogger("MAH/opacity-debug");

    private static final Map<ResourceLocation, NativeImage> CACHE = new HashMap<>();
    /** Locations that failed to load as a direct file, so they're not retried every call. */
    private static final Map<ResourceLocation, Boolean> FAILED = new HashMap<>();

    /** A synthetic key resolved to real layer paths (empty list = resolvers tried, found nothing). */
    private static final Map<ResourceLocation, List<ResourceLocation>> RESOLVED_LAYERS = new HashMap<>();

    public interface DynamicTextureLayerResolver {
        /** Real, file-backed resource locations behind {@code texture}, or {@code null}/empty if this resolver doesn't recognize it. */
        List<ResourceLocation> resolveLayers(ResourceLocation texture);
    }

    private static final List<DynamicTextureLayerResolver> LAYER_RESOLVERS = new CopyOnWriteArrayList<>();

    /** Called from an optional compat class's bootstrap — see {@code IceAndFireLayerRegistration} for the reference implementation. */
    public static void registerLayerResolver(DynamicTextureLayerResolver resolver) {
        LAYER_RESOLVERS.add(resolver);
    }

    /**
     * TEMPORARY diagnostic for the tarantula-hawk over-culling bug report: logs the
     * first {@link #DEBUG_LOG_LIMIT_PER_TEXTURE} opacity samples <b>per distinct
     * texture</b> — raw u/v and the resulting opacity, so a real UV-space mismatch
     * shows up directly in the log instead of being guessed at. Remove once the cause
     * is confirmed; not meant to ship long-term.
     *
     * <p><b>Per-texture, not global</b> (fixed — was a single session-wide cap of 60
     * lines total): {@code sampleOpacity} is called on every capture-time vertex
     * sample across every rendered entity, every frame, so a global cap gets fully
     * consumed by whichever ambient mob captures most often within the first second
     * or two of gameplay — long before whatever's actually being investigated gets a
     * single line logged. Exact same bug, and exact same fix, as {@code
     * HitboxCaptureManager.flatDebug}'s own per-texture cap; this one just never got
     * ported over when that fix landed.
     */
    private static final int DEBUG_LOG_LIMIT_PER_TEXTURE = 60;
    private static final Map<ResourceLocation, AtomicInteger> DEBUG_LOG_COUNTS = new ConcurrentHashMap<>();

    /**
     * Opacity at one normalized UV coordinate, as a fraction of 255 (0.0-1.0).
     * {@code u}/{@code v} are clamped into [0,1] before mapping to a pixel; missing
     * or unreadable textures return 1.0 (opaque) - a texture MAH can't read must not
     * silently turn faces into raycast pass-throughs.
     */
    public static float sampleOpacity(ResourceLocation texture, float u, float v) {
        float opacity = opacityAt(texture, u, v);

        if (texture != null) {
            AtomicInteger count = DEBUG_LOG_COUNTS.computeIfAbsent(texture, t -> new AtomicInteger(0));
            int n = count.incrementAndGet();
            if (n <= DEBUG_LOG_LIMIT_PER_TEXTURE) {
                LOGGER.info("MAH opacity-debug [{}/{}] texture={} rawUV=({}, {}) opacity={}",
                        n, DEBUG_LOG_LIMIT_PER_TEXTURE, texture, u, v, opacity);
            }
        }
        return opacity;
    }

    /**
     * Opacity across a whole rectangular UV footprint (min-corner and max-corner
     * normalized coordinates, in either order) - the minimum sampled opacity in the
     * rect, i.e. "is there any non-opaque pixel here", not an average. A face is
     * transparent only if every pixel is transparent (design doc's locked rule:
     * "semi-transparent stays hittable"), so the rect-level answer callers actually
     * want is "was every sampled pixel at/below threshold", for which the minimum
     * suffices - a single opaque pixel anywhere is enough to keep the face solid.
     *
     * <p>Sampled on a fixed small grid rather than every pixel in the rect - this is
     * a deliberate accuracy/cost trade-off, runs once per model bake (cached), not
     * per frame or per raycast.
     */
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

    /**
     * One raster scan over a face's UV rect, serving two needs at once: whether the
     * face is uniformly opaque/transparent or a mix (so callers know whether Option
     * B's per-pixel raycast test is even necessary), and - when there's at least one
     * opaque sample - the tight bounding sub-rect of the opaque region, in the same
     * UV space, for Option A's capture-time box tightening.
     *
     * @param threshold same meaning as {@link exp.CCnewmods.khp.MahConfig#opacityThreshold}
     */
    public static RectAnalysis analyzeRect(ResourceLocation texture, float u0, float v0, float u1, float v1,
                                           float threshold) {
        float minU = Math.min(u0, u1), maxU = Math.max(u0, u1);
        float minV = Math.min(v0, v1), maxV = Math.max(v0, v1);

        final int SAMPLE_GRID = 12; // was 6 - too coarse: the true opaque/transparent edge falls between sample points often enough to systematically under-estimate the real bounds (confirmed via report: tightened boxes visibly smaller than the actual wing panel silhouette)
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
            return new RectAnalysis(false, true, u0, v0, u1, v1); // fully transparent - bounds meaningless, caller should drop/cull instead
        }
        // Safeguard: a bounding rect that collapsed to (near) a single sample point
        // isn't trustworthy evidence of the face's true opaque shape - far more likely
        // anti-aliasing/edge-bleed on one stray sample near a mostly-transparent
        // region than a genuine sliver worth tightening to. Confirmed via real report:
        // this was silently collapsing tightened boxes to zero volume. Falling back to
        // "don't tighten" (same as the fully-opaque case) rather than trusting a
        // single-point bound.
        float spanU = opaqueMaxU - opaqueMinU;
        float spanV = opaqueMaxV - opaqueMinV;
        float stepU = Math.abs(maxU - minU) / (SAMPLE_GRID - 1);
        float stepV = Math.abs(maxV - minV) / (SAMPLE_GRID - 1);
        float minSpan = Math.min(stepU, stepV) * 1.5f;
        if (spanU < minSpan || spanV < minSpan) {
            return new RectAnalysis(true, false, u0, v0, u1, v1); // treat like "fully opaque, no ambiguity" - too little evidence to tighten confidently
        }
        // Pad outward by one grid step on each side, clamped to the original rect -
        // the true edge falls somewhere between the last-opaque and first-transparent
        // sample points, and finding it exactly would need per-pixel scanning instead
        // of a grid. Padding toward "slightly too generous" rather than splitting the
        // difference or leaving it tight: clipping real geometry (a box someone can
        // see but can't hit) is a worse failure than a box a few pixels bigger than
        // strictly necessary.
        float paddedMinU = Math.max(minU, opaqueMinU - stepU);
        float paddedMaxU = Math.min(maxU, opaqueMaxU + stepU);
        float paddedMinV = Math.max(minV, opaqueMinV - stepV);
        float paddedMaxV = Math.min(maxV, opaqueMaxV + stepV);
        return new RectAnalysis(anyOpaque, anyTransparent, paddedMinU, paddedMinV, paddedMaxU, paddedMaxV);
    }

    /**
     * @param anyOpaque      at least one sampled point exceeded the threshold
     * @param anyTransparent at least one sampled point was at/below the threshold
     *                       - {@code anyOpaque && anyTransparent} means "mixed",
     *                       needing Option B's per-pixel test at raycast time rather
     *                       than trusting the (now-tightened) box outright
     * @param opaqueMinU/V/opaqueMaxU/V bounding rect of the opaque samples, in the
     *                       same UV space as the input rect - only meaningful when
     *                       {@code anyOpaque} is true
     */
    public record RectAnalysis(boolean anyOpaque, boolean anyTransparent,
                                float opaqueMinU, float opaqueMinV, float opaqueMaxU, float opaqueMaxV) {
    }

    /**
     * Unified per-point opacity lookup every public sampling method routes through -
     * direct single-file texture first, falling back to resolved dynamic-composite
     * layers (max opacity across layers) when the direct read fails and a registered
     * resolver recognizes the key, falling back to the safe "treat as opaque" default
     * if neither applies.
     */
    private static float opacityAt(ResourceLocation texture, float u, float v) {
        NativeImage direct = get(texture);
        if (direct != null) {
            return pixelOpacity(direct, u, v);
        }

        List<ResourceLocation> layers = resolveLayers(texture);
        if (layers.isEmpty()) return 1.0f; // unreadable, not a recognized composite - safe default, never a silent pass-through

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
                LOGGER.info("MAH texture-debug: resolved dynamic texture={} to {} real layer(s): {}",
                        texture, layers.size(), layers);
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
            // TEMPORARY diagnostic: naturally fires only once per distinct texture
            // (FAILED short-circuits every call after this one), so no separate
            // throttling needed. Remove once dynamic-texture resolution is confirmed
            // to actually cover the cases it's meant to.
            LOGGER.warn("MAH texture-debug: FAILED to load texture={} directly ({}: {})",
                    texture, e.getClass().getSimpleName(), e.getMessage());
            FAILED.put(texture, Boolean.TRUE);
            return null;
        }
    }

    /**
     * Drop every cached texture. Called on resource reload (texture-pack switch, via
     * {@code client.TextureCacheReloadListener}) and from the manual refresh command
     * ({@code /khprefresh}, {@code client.MahDebugCommand}) — the latter also covers
     * EMF-style client-side model/texture swaps, which don't fire a resource reload
     * and which MAH has no way to detect on its own.
     */
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
