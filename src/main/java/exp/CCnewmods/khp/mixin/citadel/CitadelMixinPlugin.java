package exp.CCnewmods.khp.mixin.citadel;

import net.minecraftforge.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips every mixin in {@code mah.citadel.mixins.json} entirely when Citadel isn't
 * loaded, so MAH never attempts to transform a class that doesn't exist. Paired with
 * {@code "required": false} on the config itself as a second layer of safety.
 *
 * <p>NOTE: {@code ModList}/{@code IMixinConfigPlugin} weren't available to verify
 * against actual bytecode in this session (not present in the mapped-jar slice used
 * for the rest of MAH) — this follows the long-standing, unchanged public shape of
 * both APIs from established Forge/Mixin modding practice rather than a bytecode
 * check. Flagging per the project's verify-before-assuming rule.
 */
public final class CitadelMixinPlugin implements IMixinConfigPlugin {

    private static final String MODID = "citadel";

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
