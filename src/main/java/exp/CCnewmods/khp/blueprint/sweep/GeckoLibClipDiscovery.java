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
 * Discovers and forces GeckoLib clips for the sweep tool, using the fully public API
 * chain confirmed via {@code javap}/CFR against the pack's pinned
 * {@code geckolib-forge-1_20_1-4_8_4.jar} — no mixin, no reflection anywhere in this
 * class:
 *
 * <pre>
 * entity (GeoAnimatable)
 *   .getAnimatableInstanceCache().getManagerForId(entity.getId())  → AnimatableManager
 *   .getAnimationControllers()                                     → Map&lt;String, AnimationController&gt;
 *   controller.setAnimation(RawAnimation.begin().thenPlay(name))    → forces the clip,
 *       bypassing the mod's own AnimationStateHandler entirely (confirmed via javap -c:
 *       setAnimation does nothing but a null/empty-stage check before building the queue)
 * </pre>
 *
 * <p>Clip names come from the model's own {@code .animation.json} file — resolved
 * per-entity via {@code GeoRenderer.getGeoModel().getAnimationResource(entity)}
 * (confirmed present on the client {@code GeoModel} class), read through
 * {@code ResourceManager} (plain data, same non-GPU-bound pattern
 * {@code TextureAlphaSampler} already uses for textures), not by scanning every file
 * under {@code assets/<modid>/animations/} blindly — a shared animations directory can
 * hold files for entities other than the one being swept.
 */
public final class GeckoLibClipDiscovery {

    private static final Logger LOGGER = LogUtils.getLogger();

    public record ClipInfo(String name, double lengthTicks) {
    }

    /**
     * @return every clip declared in this entity's {@code .animation.json}, or an
     * empty list if the renderer isn't a {@code GeoRenderer}, the resource can't be
     * read, or the file has no {@code "animations"} object (all treated as "nothing
     * to sweep for this entity" rather than thrown — a missing/malformed animation
     * file falls back to whatever the vanilla-procedural sweep tier already covers).
     */
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
                        ? animations.getAsJsonObject(name).get("animation_length").getAsDouble() * 20.0 // seconds → ticks
                        : 20.0; // fallback: 1 second, better than skipping the clip entirely
                result.add(new ClipInfo(name, length));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[KHP] Sweep: failed to parse {} for {} — skipping clip sweep.",
                    animResource, entity.getType(), e);
        }
        return result;
    }

    /**
     * Force every {@link AnimationController} on this entity onto {@code clipName}.
     * No-op if the entity isn't a {@code GeoAnimatable} (defensive — callers should
     * already know this from {@link #discoverClips} returning non-empty).
     */
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
