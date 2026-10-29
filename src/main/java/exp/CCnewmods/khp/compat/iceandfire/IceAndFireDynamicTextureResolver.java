package exp.CCnewmods.khp.compat.iceandfire;

import com.github.alexthe666.iceandfire.client.texture.ArrayLayeredTexture;
import exp.CCnewmods.khp.capture.TextureAlphaSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Maps Ice and Fire's (and Poison Dragons') synthetic dragon texture keys to their real layer files. */
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
            }
        }
        layers.removeIf(java.util.Objects::isNull);
        return layers;
    }

    private IceAndFireDynamicTextureResolver() {
    }
}
