# MAH Phase 1G — Epic Fight Skinned Mesh Capture

Adds capture for Epic Fight's combat-override rendering — a completely separate
GPU-skinned mesh system that substitutes for whatever an entity would normally
render with (vanilla, GeckoLib, Citadel — doesn't matter) for the duration a mob is
locked onto a combat target, or a player is holding a weapon.

## Confirmed via bytecode

- Epic Fight hooks Forge's own `RenderLivingEvent.Pre` and cancels it
  (`event.setCanceled(true)`) when `entitypatch.overrideRender()` is active,
  substituting its own draw call. This fires from *inside* the window MAH's own
  dispatcher bracket is already open for, but *before* whatever the entity's
  original renderer would have called — so every one of MAH's other hooks (vanilla
  `ModelPart`, GeckoLib, Citadel, etc.) goes dark for exactly this window, the same
  underlying "a bypass exists" issue as Ice and Fire's Ghost, just via Forge's event
  system rather than a fully-overridden `render()` method.
- Geometry is drawn via `SkinnedMesh` — a real vertex-weighted skeletal mesh, not a
  cube tree. Vertices carry per-vertex bone weights (`affectingJointCounts`/
  `affectingJointIndices`/`affectingWeightIndices`, COLLADA vcount/vindices-style —
  a vertex can be influenced by multiple joints at once). The skeleton itself
  (`Armature`/`Joint`) is named and hierarchical, and `armature.getPoseMatrices()`
  gives live, per-joint, currently-animated transform matrices every frame — this
  was the key piece that made a real per-frame-accurate capture feasible at all
  rather than falling back to a static rest pose.
- `PatchedLivingEntityRenderer.render()` is a single shared method — checked every
  per-mob-type `P*Renderer` subclass present (Creeper, Humanoid, IronGolem, Spider
  spot-checked directly; the broader list — Drowned, Enderman, Hoglin, Illager,
  Ravager, Stray, Vex, Vindicator, Witch, Wither, WitherSkeletonMinion,
  ZombieVillager — all share the same base class shape) and none override `render()`
  themselves. Also confirmed the player-weapon-holding path
  (`RenderEngine.renderEntityArmatureModel`) calls the exact same method. **One
  mixin covers both mobs and players.**
- Not covered: the ender dragon, which uses the simpler `PatchedEntityRenderer` base
  directly via its own `PEnderDragonRenderer`, bypassing `PatchedLivingEntityRenderer`
  entirely. Known, low-priority gap — one boss entity.

## Mechanism

**Baking (once per mesh, cached — `EpicFightMeshBaker`)**: for each vertex, find its
*dominant* joint (highest weight among however many joints influence it) and fold
its rest-pose position into that joint's running bounding box. All the mesh
accessors used (`positions()`, `weights()`, `affectingJointCounts()`,
`affectingJointIndices()`, `affectingWeightIndices()`) are public — no reflection
needed anywhere in this integration.

This is why the result comes out "regular mob cube"-sized without any cross-system
name mapping: Epic Fight's own `VanillaModelTransformer`/`GeoModelTransformer`/
`AzureModelTransformer` classes build every `SkinnedMesh` *from* the entity's
original model at load time — the rest-pose vertices already are a (re-triangulated,
possibly smoothed at joint boundaries) copy of the original cube geometry. Baking
bounds straight from those vertices gets the right sizes for free.

**Capture (every frame — `EpicFightBoxCapture`)**: converts each joint's current
`OpenMatrix4f` pose to JOML's `Matrix4f` (via the identical `FloatBuffer` round-trip
Epic Fight's own `OpenMatrix4f.importFromMojangMatrix` uses in the opposite
direction — deliberately not a hand-written 16-argument constructor, to avoid any
row/column transposition risk), then hands `(jointName, bakedRestBox, poseMatrix)`
to a new general-purpose manager entry point,
`HitboxCaptureManager.captureExternalPart(name, localBox, localTransform)` — for
integrations that already have fully-known local geometry and just need composing
with the bracket's own `basisInverse`, same as everything else.

**Wiring**: getting both `armature` and the live pose-matrix array at one injection
point needs `@Redirect` (not `@ModifyVariable`, which only exposes the one variable
being modified) — same reasoning as the Ferrouslime spike fix in Phase 1F. Redirects
the single `mesh.draw(...)` call inside `PatchedLivingEntityRenderer.render()`,
captures, then calls the real `draw` through unchanged so rendering itself is
completely unaffected.

## Grouping policy — a real tradeoff, not free

Assigning each vertex to its single dominant joint (rather than splitting it across
every joint that influences it) keeps joint boxes from bloating into their smoothly-
blended neighbors, at the cost of a thin sliver of geometry right at each joint
boundary not being covered by either neighbor's box. Reasonable first pass; revisit
if boundary coverage turns out to matter for the wound system in practice.

## Dependency note

Epic Fight's CurseForge listing has third-party sharing disabled — `curse.maven`
coordinates don't resolve for this mod at all, regardless of file id (confirmed:
every CurseForge file page for this mod explicitly states this). Wired via the
`libs/` flatDir approach instead (both `epic-fight-20.14.16` and its
`invincible-lib` runtime dependency copied into `libs/`), same mechanism used before
the other integrations switched to CurseMaven.

## Re-test

Spawn a hostile mob and let it lock onto a target (or hold a weapon as a player) with
`debug_render` on — boxes should now track the live combat animation (limbs actually
moving mid-swing, not frozen at a rest pose), across the joint hierarchy, sized close
to the entity's normal (non-combat) box sizes. This is new, unverified-by-screenshot
territory — please confirm before trusting it the way the fully-tested Phase
1/1B–1F paths are.

---

## Hotfix: native crash on world load (`EXCEPTION_ACCESS_VIOLATION` in JOML)

First build crashed the JVM itself on loading into a world — a native access
violation, not a managed Java exception, inside `org.joml.MemUtil$MemUtilUnsafe`.

**Root cause, confirmed precisely from the hs_err file**: `EpicFightBoxCapture`'s
scratch conversion buffer was `FloatBuffer.allocate(16)` — a heap-backed buffer.
JOML's `Matrix4f.set(FloatBuffer)` has an internal `Unsafe`-based fast path that
reads a buffer's native memory address directly (via `sun.misc.Unsafe.getLong`) and
dereferences it. A heap buffer has no real native address for that path to read — it
gets garbage, then crashes trying to use it as a pointer. The crash register `R8`
held exactly `0x10`, matching `MemUtilUnsafe.ADDRESS`'s field offset constant found
in the same hs_err file's memory dump — not a guess, a direct match.

(The `ClassNotFoundException: yesman.epicfight.api.collider.OBBCollider` visible
earlier in the same log is unrelated — a pre-existing, non-fatal, caught-and-logged
error in Epic Fight's own cape/cloth-simulation code, not a MAH issue and not the
crash cause.)

**Fix**: allocate the scratch buffer as direct (`ByteBuffer.allocateDirect(...).asFloatBuffer()`)
instead of heap-backed. Checked the rest of the project for the same pattern —
this was the only heap-buffer allocation anywhere near JOML matrix code; nothing
else needed changing.

---

## Second hotfix: boxes huge and "attached to the camera"

Game loaded fine after the crash fix, but debug wireframes on the local player
(weapon-holding, so combat-override render active) were enormous and appeared to
track the camera rather than the player's actual position.

**Root cause, confirmed via bytecode** (`SkinnedMesh.drawPosed`): the actual
per-vertex skinning transform Epic Fight applies is

```
poseStack.pose() * poses[jointId] * joint.getToOrigin() * restVertexPos
```

Two things were missing from the first build, both from the same oversight — I
treated `poses[jointId]` alone as equivalent to the fully-composed `pose.pose()`
every other capture path reads, when it's neither:

1. **`joint.getToOrigin()`** (the inverse bind-pose matrix, baked once per joint from
   the hierarchy at load time) was never applied. Rest bounds were baked directly
   from raw mesh-space vertex positions, but `poses[jointId]` expects to receive
   coordinates already in that joint's own local frame — feeding it raw mesh-space
   positions instead produces results with no reliable relationship to the joint's
   actual position at all.
2. **`poseStack.pose()`** — the entity's own current camera-relative placement — was
   never composed in either. Only `basisInverse * poses[jointId]` was stored, missing
   the middle term entirely.

Both explain the observed symptoms directly: wrong space (nothing anchoring the
boxes to the entity) and wrong scale (mesh-space coordinates interpreted as if they
were already joint-local, which they weren't).

**Fix, in two parts**:

- `EpicFightMeshBaker.bakeRestBounds` now takes the `Armature` too, and transforms
  every rest vertex by its dominant joint's `getToOrigin()` (via
  `OpenMatrix4f.transform(matrix, src, dest)`, confirmed correct against its own
  bytecode body) *before* folding it into that joint's running bounding box — so the
  baked box is already in the joint-local space `poses[jointId]` expects, computed
  once and cached, same as before.
- `EpicFightBoxCapture.capture` now also takes `poseStack` (the mixin already had it
  in scope, just wasn't passing it through) and composes
  `poseStack.pose() * convertedJointMatrix` as the transform handed to
  `captureExternalPart`, which then composes it with the bracket's own
  `basisInverse` exactly the same way every other capture path in MAH does.

## Re-test (updated)

Same as before — spawn/engage combat with a mob, or hold a weapon as the player,
`debug_render` on. Boxes should now be sized close to the entity's normal (non-
combat) box sizes and track the entity's actual position and animation, not the
camera. This has gone through two rounds of "looked wrong, found the exact
bytecode-confirmed reason, fixed it" without an actual successful screenshot yet —
still the least-verified integration in the project; please confirm this round
actually looks right before trusting it.


