package exp.CCnewmods.khp.blueprint.sweep;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds a GeckoLib entity's clips (from its own {@code .animation.json}) and forces them via
 * {@code AnimationController.setAnimation}, bypassing the mod's animation logic.
 */
public final class GeckoLibClipDiscovery {

    private static final Logger LOGGER = LogUtils.getLogger();

    public record ClipInfo(String name, double lengthTicks) {
    }

    /** Empty if not a GeckoLib renderer or the file can't be read. */
    public static List<ClipInfo> discoverClips(Entity entity) {
        List<ClipInfo> result = new ArrayList<>();
        if (!(entity instanceof GeoAnimatable animatable)) return result;

        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof GeoRenderer<?> geoRenderer)) return result;

        GeoModel<?> model = geoRenderer.getGeoModel();
        ResourceLocation animResource;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ResourceLocation loc = ((GeoModel) model).getAnimationResource(animatable);
            animResource = loc;
        } catch (RuntimeException e) {
            LOGGER.warn("[KHP] Sweep: getAnimationResource threw for {} — skipping GeckoLib clip sweep for this type.",
                    entity.getType(), e);
            return result;
        }
        if (animResource == null) return result;

        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(animResource);
        if (resource.isEmpty()) {
            LOGGER.warn("[KHP] Sweep: animation resource {} for {} not found — skipping clip sweep.",
                    animResource, entity.getType());
            return result;
        }

        try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject animations = root.getAsJsonObject("animations");
            if (animations == null) return result;
            for (String name : animations.keySet()) {
                double length = animations.getAsJsonObject(name).has("animation_length")
                        // seconds → ticks
                        ? animations.getAsJsonObject(name).get("animation_length").getAsDouble() * 20.0
                        : 20.0;
                result.add(new ClipInfo(name, length));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[KHP] Sweep: failed to parse {} for {} — skipping clip sweep.",
                    animResource, entity.getType(), e);
        }
        return result;
    }

    public static void forceClip(Entity entity, String clipName) {
        if (!(entity instanceof GeoAnimatable animatable)) return;
        AnimatableInstanceCache cache = animatable.getAnimatableInstanceCache();
        AnimatableManager<?> manager = cache.getManagerForId(entity.getId());
        if (manager == null) return;
        RawAnimation raw = RawAnimation.begin().thenPlay(clipName);
        for (AnimationController<?> controller : manager.getAnimationControllers().values()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            AnimationController raw_controller = controller;
            raw_controller.setAnimation(raw);
        }
    }

    private GeckoLibClipDiscovery() {
    }
}
