package exp.CCnewmods.khp.compat.iceandfire;

import com.github.alexthe666.iceandfire.client.texture.ArrayLayeredTexture;
import exp.CCnewmods.khp.capture.TextureAlphaSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Teaches {@link TextureAlphaSampler} how to find the real, file-backed texture
 * layers behind Ice and Fire's (and Poison Dragons', which shares the same
 * convention per direct report) synthetic dragon-texture cache keys.
 *
 * <p>Confirmed via bytecode ({@code RenderDragonBase.getTextureLocation}): the
 * returned {@code ResourceLocation} isn't a resource-pack path at all — it's a
 * synthetic key (e.g. {@code "dragon_texture_brown2truefalsetruefalsefalse"}) under
 * which a composited {@code ArrayLayeredTexture} (base skin + conditional overlays
 * like fire/ice/lightning glow, armor) gets registered directly with
 * {@code TextureManager}, bypassing the resource-pack file system. Reading that key
 * as a file always fails — {@code layeredTextureNames} (public field on
 * {@code ArrayLayeredTexture}) is where the real, readable layer paths actually live.
 *
 * <p>Opacity across layers is approximated as max-across-layers by
 * {@code TextureAlphaSampler} itself, not here — this class's only job is finding
 * the real paths.
 */
public final class IceAndFireDynamicTextureResolver implements TextureAlphaSampler.DynamicTextureLayerResolver {

    public static void register() {
        TextureAlphaSampler.registerLayerResolver(new IceAndFireDynamicTextureResolver());
    }

    @Override
    public List<ResourceLocation> resolveLayers(ResourceLocation texture) {
        AbstractTexture registered = Minecraft.getInstance().getTextureManager().getTexture(texture);
        if (!(registered instanceof ArrayLayeredTexture layered)) return null;

        List<?> names = layered.layeredTextureNames;
        if (names == null || names.isEmpty()) return null;

        List<ResourceLocation> layers = new ArrayList<>(names.size());
        for (Object name : names) {
            if (name == null) continue;
            try {
                layers.add(ResourceLocation.tryParse(name.toString()));
            } catch (RuntimeException ignored) {
                // A malformed entry shouldn't sink every other real layer.
            }
        }
        layers.removeIf(java.util.Objects::isNull);
        return layers;
    }

    private IceAndFireDynamicTextureResolver() {
    }
}
