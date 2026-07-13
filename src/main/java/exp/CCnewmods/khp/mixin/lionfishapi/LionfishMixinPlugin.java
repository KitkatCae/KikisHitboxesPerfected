package exp.CCnewmods.khp.mixin.lionfishapi;

import net.minecraftforge.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips every mixin in {@code mah.lionfishapi.mixins.json} entirely when LionfishAPI
 * isn't loaded. Paired with {@code "required": false} on the config itself.
 *
 * <p>Same unverified-by-bytecode note as {@code CitadelMixinPlugin} applies here.
 */
public final class LionfishMixinPlugin implements IMixinConfigPlugin {

    private static final String MODID = "lionfishapi";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return ModList.get().isLoaded(MODID);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
