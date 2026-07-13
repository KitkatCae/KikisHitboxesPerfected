package exp.CCnewmods.khp.mixin.vs2;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips every mixin in {@code khp.vs2.mixins.json} entirely when VS2 isn't
 * loaded — same pattern as {@code TaczMixinPlugin}, copied deliberately rather
 * than reinvented. Paired with {@code "required": false} on the config itself.
 *
 * <p>{@code LoadingModList}, not {@code ModList} — {@code ModList.get()} returns
 * {@code null} during the mixin PREPARE phase, before FML finishes constructing
 * it; {@code LoadingModList} is populated earlier and safe to query from an
 * {@link IMixinConfigPlugin}. See {@code TaczMixinPlugin}'s own javadoc for the
 * fuller explanation and the note that this project's own earlier
 * {@code CitadelMixinPlugin}/{@code LionfishMixinPlugin} both predate this
 * fix and aren't actually wired in via a {@code "plugin"} key — this class
 * follows the corrected convention, not the older one.
 */
public final class Vs2MixinPlugin implements IMixinConfigPlugin {

    private static final String MODID = "valkyrienskies";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return LoadingModList.get().getModFileById(MODID) != null;
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
