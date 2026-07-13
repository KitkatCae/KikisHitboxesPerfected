package exp.CCnewmods.khp.mixin.tacz;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips every mixin in {@code khp.tacz.mixins.json} entirely when TACZ isn't
 * loaded, so KHP never attempts to transform a class that doesn't exist. Paired
 * with {@code "required": false} on the config itself as a second layer of safety.
 *
 * <p>Uses {@link LoadingModList}, not {@code ModList}, deliberately — per this
 * project's own established learning: {@code ModList.get()} returns {@code null}
 * during the mixin PREPARE phase (before FML has finished constructing
 * {@code ModList}), throwing an NPE that silently disables an entire
 * {@code "required": false} config. {@code LoadingModList} is populated much
 * earlier and is safe to query from an {@link IMixinConfigPlugin}.
 *
 * <p>NOTE: unlike the rest of this session's work, {@code LoadingModList} itself
 * wasn't available to re-verify against real jar bytecode (it lives in Forge's
 * {@code fmlloader} module, which isn't present in the mapped MDK jar used for
 * everything else this session) — this follows the project's own previously
 * confirmed convention rather than a fresh bytecode check this session. Flagging
 * per the project's verify-before-assuming rule.
 *
 * <p>Existing {@code CitadelMixinPlugin}/{@code LionfishMixinPlugin} both predate
 * this fix, use {@code ModList} instead, and — separately — aren't actually wired
 * into their own {@code .mixins.json} via a {@code "plugin"} key at all, making them
 * dead code today. Left untouched here; flagged to Caelan as a known follow-up, not
 * silently fixed as a drive-by.
 */
public final class TaczMixinPlugin implements IMixinConfigPlugin {

    private static final String MODID = "tacz";

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
