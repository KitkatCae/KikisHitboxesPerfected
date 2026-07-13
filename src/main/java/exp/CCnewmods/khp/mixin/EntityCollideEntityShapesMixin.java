package exp.CCnewmods.khp.mixin;

import exp.CCnewmods.khp.Mah;
import exp.CCnewmods.khp.api.MahApi;
import exp.CCnewmods.khp.capture.EntityHitboxSnapshot;
import exp.CCnewmods.khp.collision.SolidCollisionShapes;
import exp.CCnewmods.khp.collision.Vs2ShipCollisionBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Phase 2D, Part A2 — solid mob collision, shape-accuracy axis.
 *
 * <p>Redirects the call site for {@code this.collide(pPos)} from inside
 * {@code Entity.move(MoverType, Vec3)} — not {@code collide()} itself. This
 * pack has another mod that fully replaces {@code collide()}'s body (most
 * likely via {@code @Overwrite}), and Mixin's merge ordering lets that
 * replacement win over any injection targeting the same method from a
 * different config — confirmed via diagnostic injectors during development:
 * {@code move()}'s call to {@code collide()} genuinely executes at runtime, but
 * an {@code @Inject(HEAD)} placed directly inside {@code collide()} never fired,
 * in an environment where every other {@code Entity} injection (including two
 * different diagnostics on {@code move()} itself) fired reliably. Redirecting
 * the call site from {@code move()}'s side sidesteps whatever {@code collide()}
 * has become entirely.
 *
 * <p>Vanilla's actual per-axis resolution math is untouched:
 * {@code Entity.collideBoundingBox(...)} (confirmed {@code public static}) is
 * called exactly the way {@code collide()} itself always called it — only the
 * candidate {@code List<VoxelShape>} fed into it differs, built from MAH's
 * {@code PartOBB} geometry via {@link SolidCollisionShapes#preciseShapesOrNull}
 * where available (one shape per part, unmerged — see that method's own javadoc
 * for why), falling back to vanilla's plain whole-body box otherwise.
 *
 * <h3>Removed: Part A3 (precise per-part terrain collision)</h3>
 * An earlier revision of this class also swept the mob's own body against
 * terrain part-by-part instead of using its whole-body {@code aabb}. Pulled
 * after real testing: taking the most-restrictive result across parts meant
 * any single part whose captured geometry drifted slightly into a block
 * (capture-to-collision alignment isn't pixel-perfect) could zero out that
 * mob's movement on an axis, and in practice this showed up as widespread
 * clipping/falling-through-floor across most mob types, not an edge case.
 * Replaced by an opt-in, off-by-default AABB-resize approach instead — see
 * {@code exp.CCnewmods.khp.collision.AabbResize} and
 * {@code EntityMakeBoundingBoxMixin} — which keeps mobs on vanilla's normal
 * single-box collision entirely unmodified, just with a bigger/differently-
 * shaped box, rather than trying to sweep multiple boxes as one rigid body.
 */
@Mixin(Entity.class)
public abstract class EntityCollideEntityShapesMixin {

    // TEMP DIAGNOSTIC — proves whether this @Redirect actually applies at runtime,
    // independent of any snapshot/staleness question. Logs once (per JVM run) the
    // first time the redirect body actually executes, then stays silent. If this
    // line never appears in the log after walking into a mob, the redirect isn't
    // installed — either lost to another mod's Mixin config on the same call site,
    // or a config/apply-order issue — and everything downstream (precise shapes,
    // staleness, blueprints) is irrelevant until that's fixed first. Remove once
    // resolved.
    private static final AtomicBoolean khp$redirectConfirmed = new AtomicBoolean(false);

    // TEMP DIAGNOSTIC — per-candidate-entity throttle so this doesn't spam every
    // tick once the redirect is confirmed firing; reports exactly why a given
    // candidate fell back to its vanilla box instead of precise shapes. Remove
    // once resolved.
    private static final ConcurrentHashMap<Integer, Long> khp$fallbackLogCooldown = new ConcurrentHashMap<>();
    private static final long khp$FALLBACK_LOG_COOLDOWN_TICKS = 100; // 5s

    // Lazily checked, not a static final field initialized at class-load time — this
    // mixin merges directly into Entity.class, which loads very early in Forge's
    // bootstrap, well before ModList.get() is safe to call (same timing risk
    // MahCompatBootstrap's own javadoc already documents for exactly this API).
    // First real call happens on the first entity movement tick, long past that risk
    // window, and the result is cached from then on.
    private static volatile Boolean khp$vs2Present;
    private static final AtomicBoolean khp$bridgeCallConfirmed = new AtomicBoolean(false);

    private static boolean khp$isVs2Present() {
        Boolean cached = khp$vs2Present;
        if (cached != null) return cached;
        boolean loaded = ModList.get().isLoaded("valkyrienskies");
        khp$vs2Present = loaded;
        // TEMP DIAGNOSTIC — logs the actual result of this check exactly once, so
        // "is the guard even passing" is directly observable instead of inferred
        // from whether downstream VS2 diagnostics fire. Remove once resolved.
        Mah.LOGGER.info("[KHP DIAG] EntityCollideEntityShapesMixin.khp$isVs2Present() = {} "
                + "(first check, cached from here on)", loaded);
        return loaded;
    }

    @Redirect(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 khp$preciseCollide(Entity self, Vec3 pVec) {
        if (khp$redirectConfirmed.compareAndSet(false, true)) {
            Mah.LOGGER.info("[KHP DIAG] EntityCollideEntityShapesMixin redirect is firing "
                    + "(first call, entity={}, level={})", self.getType(),
                    self.level().isClientSide() ? "client" : "server");
        }
        AABB aabb = self.getBoundingBox();

        // VS2 ship-collision composition — see Vs2ShipCollisionBridge's own javadoc for
        // the full story: VS2's own hook (MixinEntity.collideWithShips) sits on this exact
        // same collide() call site as a @WrapOperation, and this @Redirect fully replacing
        // that call site means VS2's hook never runs at all — confirmed via diagnostic
        // logging (KHP_Phase2E_Design.md §16/§17). Composing it in explicitly here restores
        // the behavior that bypass silently dropped, gated so KHP still runs fine without
        // VS2 present (mandatory in mods.toml as of this pack, but this check makes the
        // class itself not hard-depend on that staying true). All VS2 type references live
        // in Vs2ShipCollisionBridge, not here — see that class's javadoc for why that
        // isolation is required, not just tidy.
        Vec3 pVecAdjusted = pVec;
        if (pVec.lengthSqr() != 0.0 && khp$isVs2Present()) {
            pVecAdjusted = Vs2ShipCollisionBridge.adjustForShipCollisions(self, pVec, aabb.expandTowards(pVec));
            if (khp$bridgeCallConfirmed.compareAndSet(false, true)) {
                // TEMP DIAGNOSTIC — confirms the bridge call site itself was reached and
                // returned, independent of whether VS2's own internal method logs anything.
                // Remove once resolved.
                Mah.LOGGER.info("[KHP DIAG] Vs2ShipCollisionBridge.adjustForShipCollisions called "
                        + "(first call, entity={}, pVec={}, result={})", self.getType(), pVec, pVecAdjusted);
            }
        }

        List<VoxelShape> list = khp$preciseEntityCollisions(self, aabb.expandTowards(pVecAdjusted));

        Vec3 vec3 = pVecAdjusted.lengthSqr() == 0.0
                ? pVecAdjusted
                : Entity.collideBoundingBox(self, pVecAdjusted, aabb, self.level(), list);

        boolean movedX = pVecAdjusted.x != vec3.x;
        boolean movedY = pVecAdjusted.y != vec3.y;
        boolean movedZ = pVecAdjusted.z != vec3.z;
        boolean onGroundish = self.onGround() || (movedY && pVecAdjusted.y < 0.0);
        float stepHeight = self.getStepHeight();

        if (stepHeight > 0.0f && onGroundish && (movedX || movedZ)) {
            Vec3 stepAttempt = Entity.collideBoundingBox(
                    self, new Vec3(pVecAdjusted.x, stepHeight, pVecAdjusted.z), aabb, self.level(), list);
            Vec3 headroomCheck = Entity.collideBoundingBox(
                    self, new Vec3(0.0, stepHeight, 0.0), aabb.expandTowards(pVecAdjusted.x, 0.0, pVecAdjusted.z), self.level(), list);

            if (headroomCheck.y < stepHeight) {
                Vec3 alt = Entity.collideBoundingBox(
                                self, new Vec3(pVecAdjusted.x, 0.0, pVecAdjusted.z), aabb.move(headroomCheck), self.level(), list)
                        .add(headroomCheck);
                if (alt.horizontalDistanceSqr() > stepAttempt.horizontalDistanceSqr()) {
                    stepAttempt = alt;
                }
            }
            if (stepAttempt.horizontalDistanceSqr() > vec3.horizontalDistanceSqr()) {
                Vec3 settle = Entity.collideBoundingBox(
                        self, new Vec3(0.0, -stepAttempt.y + pVecAdjusted.y, 0.0), aabb.move(stepAttempt), self.level(), list);
                return stepAttempt.add(settle);
            }
        }

        return vec3;
    }

    private List<VoxelShape> khp$preciseEntityCollisions(Entity self, AABB pCollisionBox) {
        if (pCollisionBox.getSize() < 1.0E-7) {
            return List.of();
        }

        Predicate<Entity> vanillaPredicate = EntitySelector.NO_SPECTATORS.and(self::canCollideWith);

        // KHP's own solidity, independent of whatever an entity subclass's own
        // canBeCollidedWith() override says — see EntityCanBeCollidedWithMixin's
        // javadoc for why this can't rely on that alone.
        Predicate<Entity> khpSolidPredicate = candidate ->
                SolidCollisionShapes.isSolidMob(candidate)
                        && !candidate.isSpectator()
                        && !candidate.isPassengerOfSameVehicle(self);

        Predicate<Entity> predicate = vanillaPredicate.or(khpSolidPredicate);

        List<VoxelShape> shapes = new ArrayList<>();

        // Primary pass: candidates found by their own REAL captured geometry
        // extent, not just their vanilla AABB. Level.getEntities (the fallback
        // pass below) only ever finds a candidate whose OWN vanilla bounding box
        // overlaps the query region — for a body whose precise geometry legitimately
        // reaches past that vanilla box (Wither heads, tentacled/multi-part
        // bosses), a mover positioned near/inside that reaching-out geometry but
        // outside the vanilla box would never see the entity as a candidate at
        // all, precise shapes or not. Mirrors MahApi.gatherExtentCandidates,
        // already proven for raycast/combat against exactly this class of body.
        Map<Entity, EntityHitboxSnapshot> extentCandidates = MahApi.gatherExtentCandidates(
                self.level(), pCollisionBox, predicate, SolidCollisionShapes::resolveCollisionSnapshot);
        for (Map.Entry<Entity, EntityHitboxSnapshot> entry : extentCandidates.entrySet()) {
            List<VoxelShape> precise = SolidCollisionShapes.shapesFromSnapshot(entry.getKey(), entry.getValue());
            if (precise != null) {
                shapes.addAll(precise);
            } else {
                shapes.add(Shapes.create(entry.getKey().getBoundingBox()));
                khp$logFallback(entry.getKey());
            }
        }

        // Supplementary pass: real level entities whose own vanilla AABB overlaps
        // pCollisionBox, for anything the extent pass doesn't know about at all yet
        // (no captured or blueprint geometry) — same fallback-to-vanilla-box
        // behavior as before. Never re-processes an entity the extent pass already
        // handled.
        List<Entity> candidates = self.level().getEntities(self, pCollisionBox.inflate(1.0E-7), predicate);
        for (Entity candidate : candidates) {
            if (extentCandidates.containsKey(candidate)) continue;
            List<VoxelShape> precise = SolidCollisionShapes.preciseShapesOrNull(candidate);
            if (precise != null) {
                shapes.addAll(precise);
            } else {
                shapes.add(Shapes.create(candidate.getBoundingBox()));
                khp$logFallback(candidate);
            }
        }

        return List.copyOf(shapes);
    }

    // TEMP DIAGNOSTIC — remove once resolved.
    private void khp$logFallback(Entity candidate) {
        long now = candidate.level().getGameTime();
        Long last = khp$fallbackLogCooldown.get(candidate.getId());
        if (last != null && now - last < khp$FALLBACK_LOG_COOLDOWN_TICKS) return;
        khp$fallbackLogCooldown.put(candidate.getId(), now);
        Mah.LOGGER.info("[KHP DIAG] {} (id {}) fell back to vanilla box for solid collision — {}",
                candidate.getType(), candidate.getId(), SolidCollisionShapes.khp$diagnoseFallbackReason(candidate));
    }
}
