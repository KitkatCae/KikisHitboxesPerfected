# MAH Phase 1C — Ghost/Primary-Gate Fix, Mowzie's Mobs, and an Open Question on Slime/Hydra

## 1. Fixed: entities whose renderer bypasses `LivingEntityRenderer.render()` entirely

**Confirmed root cause** (bytecode: Ice and Fire's `RenderGhost`): the primary-gate
mechanism only *opened* via an injection into `LivingEntityRenderer.render()`'s own
method body. Renderers that fully reimplement `render(...)` — never calling
`super.render(...)`, instead calling their model's `renderToBuffer` directly from
their own overridden method — never reached that injection. The gate stayed closed
for the entity's *entire* render, so `shouldCaptureModelGeometry()` returned false for
every cube: zero boxes, no error, nothing to see in a log. Confirmed this is exactly
`RenderGhost`'s shape: a completely custom `render()` override with its own pose/anim
logic, calling `ModelGhost.renderToBuffer` directly partway through.

**Fix**: `HitboxCaptureManager.enterBracket` now defaults `primaryGate = true` for
every VANILLA_LIVING bracket. `LivingEntityRendererMixin` no longer *opens* the gate —
it only *closes* it (around the RenderLayer loop, same injection as before, just
dropped the "open" half) as a safety net for renderers that *do* go through the base
class normally. Net effect: entities using the standard path behave identically to
before (still correctly excluding armor/saddle layers by default); entities with a
fully custom `render()` override are now captured as fully primary instead of not at
all. This is a one-line-conceptually but real fix — worth re-testing the Ghost
specifically to confirm, and worth being aware this same pattern (renderer overrides
`render()` completely) could exist in other mods not yet tested.

## 2. Added: Mowzie's Mobs support

**Confirmed via bytecode**: Mowzie's Mobs uses real GeckoLib (`software.bernie.geckolib.*`
classes, genuine `GeoBone`/`GeoAnimatable`), but wraps it in its own
`com.bobmowzie.mowziesmobs.client.render.entity.MowzieGeoEntityRenderer`, which
extends GeckoLib's own `GeoEntityRenderer` but declares **its own** `renderRecursively`
override — the exact same shape as GeckoLib's own `DynamicGeoEntityRenderer`, which is
why that one needed its own dedicated mixin back in Phase 1. Mowzie's Mobs needed the
same treatment: a fourth `renderRecursively`-HEAD hook, targeting
`MowzieGeoEntityRenderer` specifically. Since the bone type is real GeckoLib `GeoBone`
(Java's override rules require the exact same parameter type), this reuses
`HitboxCaptureManager.captureGeoBone` completely unchanged — no new geometry-handling
code, just a new attachment point.

Wired as a fifth optional mixin config (`mah.mowziesmobs.mixins.json`,
`"required": false`, no plugin — same lesson as the Phase 1B hotfix) plus a
`compileOnly fg.deobf("curse.maven:mowzies-mobs-250498:7815705")` dependency matching
your existing Citadel/LionfishAPI style, and an optional `mods.toml` entry.

## 3. NOT fixed — open design question: Slime and Hydra heads

These turned out to have a **different root cause** from Ghost, and I want your call
on it rather than silently picking a direction.

**Confirmed via bytecode**: vanilla `Slime`'s visually-dominant, translucent outer body
— the big gooey cube that reads as "the slime" — is implemented as a `RenderLayer`
(`SlimeOuterLayer`, baked from `ModelLayers.SLIME_OUTER`), *not* as the primary model.
The primary model (`ModelLayers.SLIME`) is only the small solid inner core with the
face. `capture_layers` defaults to `false` specifically to exclude cosmetic RenderLayer
overlays (armor, saddles) from doubling up on body geometry — but here, the thing
excluded by that default *is the slime's actual body*, not a cosmetic extra.

Ice and Fire's Hydra has the same shape: heads are `ModelHydraHead`, rendered via
`LayerHydraHead` (a `RenderLayer`), while only the body+tail (`ModelHydraBody`) is
primary — which is exactly why you saw body/tail boxed but heads not. Both are
completely legitimate implementation choices by those mod authors (a Hydra's head
count varies, so implementing heads as a repeating layer makes sense) — but MAH's
"RenderLayer ≈ cosmetic overlay" heuristic breaks down for them, because here a
RenderLayer carries core anatomy instead.

**This is a real design fork, not a bug fix, so I didn't pick for you:**

- **(a) Flip `capture_layers` default to `true`.** Simplest change, zero new code.
  Fixes Slime and Hydra heads immediately. Cost: armor/saddle/held-item layers on
  every mob start getting boxed too, which may create duplicate/overlapping geometry
  for the wound system to deal with on any armored or ridden entity.
- **(b) Per-layer-class allowlist.** Add a registration point (similar in spirit to
  `EntityKeyProvider`) where specific `RenderLayer` classes are marked "always capture
  regardless of `capture_layers`" — register `SlimeOuterLayer` and `LayerHydraHead`
  this way, leave the armor/saddle default alone. More correct, but it's a per-mod,
  per-layer-class maintenance list that grows every time a new mod's creature uses
  this pattern (and I'd need to hunt down which layer is "anatomy" for each one, the
  same way I did here).
- **(c) Leave it as documented behavior for now**, revisit once the wound/dissection
  system tells us whether double-counted armor geometry is actually a problem in
  practice — if it isn't, (a) is free.

Let me know which direction, and I'll implement it as a scoped follow-up rather than
guessing at the trade-off you'd want.

## Re-test checklist

- Ice and Fire Ghost: should now get full-body boxes (Phase 1C fix #1).
- A Mowzie's Mobs boss (Elokosa, Frostmaw, Naga, etc.): should now get full GeckoLib
  bone capture (Phase 1C fix #2).
- Vanilla Slime / Alex's Caves / Better End slime variants and Ice and Fire Hydra:
  **still expected to show the same partial/no-box behavior** until you pick a
  direction on the open question above — this deliverable doesn't change that yet.

---

## Addendum: Mowzie's Mobs has TWO separate model frameworks, not one

After the first Phase 1C build, screenshots showed a clean split within Mowzie's Mobs
itself: Umvuthana, Sculptor, and Umvuthi captured correctly; Frostmaw, the flying
"dragon" (actually the Naga), and a ground-dwelling creature (likely Grottol) got
zero boxes. Checked every Mowzie's Mobs entity renderer by bytecode:

| Renderer | Extends | Framework |
|---|---|---|
| RenderUmvuthana, RenderSculptor, RenderUmvuthi | `MowzieGeoEntityRenderer` | Real GeckoLib (Phase 1C fix #2 covers this) |
| RenderFrostmaw, RenderNaga, RenderGrottol, RenderWroughtnaut | plain `MobRenderer<T,M>` | **Different framework entirely** |

The second group's models (`ModelFrostmaw`, `ModelNaga`, etc.) extend
`MowzieEntityModel`, which is built on `com.ilexiconn.llibrary.client.model.tools.
AdvancedModelRenderer` — a **third Tabula-style framework** (ilexiconn's LLibrary,
an older library predating both Citadel and GeckoLib), vendored directly inside the
Mowzie's Mobs jar under its original package. Structurally near-identical to
Citadel's/LionfishAPI's `AdvancedModelBox` (same `posX1..posZ2` pre-grow bounds, same
`render`/`doRender` split — `doRender` here is `protected` rather than `private`, no
functional difference for the mixin), but with one real difference: **no name field
at all** on the box (no `boxName` equivalent anywhere in the class).

**Naming fix, a new trick**: since LLibrary's box has no back-reference field to the
owning model (unlike Citadel/LionfishAPI, where `MahReflect.findSoleFieldOfType`
could find one), the reflective field-name recovery needs the model instance handed
to it rather than discovered from the box. `HitboxCaptureManager` now stashes the
active bracket's model instance (via the renderer's own `getModel()`, called once at
bracket-entry) in a new `getCurrentModel()` accessor — the LLibrary compat class asks
for that directly instead of reflecting a field out of the box. Small, general-purpose
addition; doesn't affect any existing capture path.

Added: `MowzieAdvancedModelRendererMixin` + `MowzieAdvancedBoxCapture`, registered in
the *same* `mah.mowziesmobs.mixins.json` config as the GeckoLib-wrapper mixin (no new
dependency coordinate needed — LLibrary is bundled inside the same jar).

### Re-test (updated)

Frostmaw, Naga, Grottol, and Wroughtnaut should now also get full-body boxes.

---

## Phase 1D: anatomical RenderLayer allowlist (option "b", scoped down)

Implements a targeted version of option (b) from the Slime/Hydra question above,
per your follow-up: rather than a global `capture_layers` flip or an open-ended
config list, a specific per-layer-class registration that defaults to capturing
nothing extra until a layer is explicitly confirmed to carry real anatomy.

**Mechanism**: `LivingEntityRendererMixin` now `@Redirect`s each individual
`RenderLayer.render(...)` call inside the vanilla layers loop (rather than the whole
loop). For most layers, nothing changes — render with the gate closed, same as
before. For a layer whose *concrete class* has been registered via
`MahApi.registerAnatomicalRenderLayer(Class<?>)`, the gate is briefly reopened for
just that one layer's render call, then closed again for whatever the loop renders
next. Registration happens once, on `FMLClientSetupEvent` (`MahCompatBootstrap`) —
deliberately *not* in a mixin plugin, since that's exactly the timing bug the Phase 1B
hotfix fixed (`ModList.get()` returns null far earlier in bootstrap than people
expect; `FMLClientSetupEvent` fires safely after mods are fully constructed).

**Registered out of the box**: vanilla `SlimeOuterLayer` (confirmed: this *is* the
slime's actual visible body) and Ice and Fire's `LayerHydraHead` (confirmed: this
*is* the extra heads, not a duplicate). Ice and Fire's registration is isolated in
its own tiny class, loaded only after confirming `ModList.isLoaded("iceandfire")`,
matching the same lazy-classloading pattern used for the other optional integrations.

**Deliberately NOT registered — checked and rejected**: you mentioned Drowned's
second layer as a candidate, so it got checked before deciding either way. Vanilla's
`DrownedOuterLayer` uses `coloredCutoutModelCopyLayerRender`: a *second full copy* of
the same `DrownedModel`, same shape, just a different texture
(`drowned_outer_layer.png`) — the same pattern as zombie villager professions or
husk-vs-zombie outer clothing layers. It's a cosmetic skin duplicate of the primary
body, not extra anatomy. Registering it would double-capture near-identical
overlapping boxes directly on top of Drowned's already-captured primary body —
exactly the problem `capture_layers=false` exists to prevent. Left unregistered; the
API is there if you have evidence I'm wrong about this one specifically, or want to
register anything else the same way (`MahApi.registerAnatomicalRenderLayer(...)` is
public, callable from Misanthrope Core or anywhere else with a classloaded reference
to the layer class).

### Re-test (Phase 1D)

Vanilla Slime and Ice and Fire Hydra should now get full body/head boxes. Drowned
should look unchanged (still armor/held-item-layer-excluded by default, as intended).
Alex's Caves / Better End slime variants: only fixed if their renderer actually reuses
vanilla `SlimeOuterLayer` itself — if either mod defines its own separate outer-body
layer class, that class needs its own registration call the same way Ice and Fire's
did. Worth a fresh set of screenshots to check which case it is.

---

## Phase 1E: Better End fixed the same way; Alex's Caves' Ferrouslime is a different,
## currently-unsolvable problem

Checked both. They needed genuinely different answers.

### Better End's End Slime — same pattern as vanilla Slime, registered

Confirmed via bytecode: `RendererEntityEndSlime extends MobRenderer` using the normal
`addLayer` mechanism — structurally identical to vanilla Slime, not a special case.
Its layer does two things: renders real extra decoration for lake/amber/chorus
variants (`renderFlower`/`renderCrop` — unambiguous extra anatomy), and renders the
main translucent slime body through a second `EndSlimeEntityModel` instance, mirroring
vanilla Slime's own primary-core/translucent-outer-layer split. Registered as
anatomical.

One honest caveat: unlike vanilla Slime and Ice and Fire's Hydra, I didn't directly
compare baked sizes between the "overlay" model and the primary model to rule out the
Drowned pattern (same shape, different texture) — I'm going on strong structural and
thematic resemblance to vanilla Slime plus the confirmed extra flower/crop geometry in
the same layer. Worth a screenshot check; if the overlay turns out to just be a
same-shape reskin after all, it should be unregistered.

One mechanical wrinkle: the layer class (`RendererEntityEndSlime.OverlayFeatureRenderer`)
is declared `private` on its enclosing renderer, so it can't be referenced as a normal
Java type from MAH's own code. Resolved via `Class.forName` instead — obtaining a
`Class` object doesn't require access permission the way calling its members would,
and the allowlist only ever needs `getClass()` equality. No compile-time dependency on
Better End was needed as a result.

### Alex's Caves' Ferrouslime — NOT a layer problem, and not fixable by whitelisting

This one needed correcting, not whitelisting. Checked `FerrouslimeRenderer` and
`FerrouslimeModel` directly:

- `FerrouslimeRenderer extends EntityRenderer` **directly** — not `LivingEntityRenderer`
  at all, so there's no `addLayer`/RenderLayer mechanism involved anywhere in its
  render path. "Outer layer" isn't the right mental model for what's happening here.
- The heads (rendered via `FerrouslimeModel.renderToBuffer`, called directly, twice,
  once per texture) **are** real Citadel `AdvancedModelBox` geometry
  (`FerrouslimeModel extends AdvancedEntityModel`) — these should already be captured
  today, unconditionally, since a renderer that isn't `LivingEntityRenderer` or
  `GeoRenderer` falls into MAH's `OTHER` kind, which captures everything with no
  primary/layer distinction at all.
- The actual gooey "gel" body — the part that looks like the main slime blob, the
  thing you'd expect an "outer layer" registration to fix — **is not model geometry
  at all**. `renderGel`/`renderGelSpikes` build it from scratch every frame with raw
  `VertexConsumer` calls (manual `.vertex(...).color(...).uv(...).normal(...).endVertex()`
  chains) — there is no `ModelPart` or `AdvancedModelBox` object backing that shape
  anywhere. MAH's entire capture approach hooks specific model-object render calls;
  there's no such object here to hook. This isn't a gap in the allowlist, it's outside
  what the current architecture can see at all.

**So**: no whitelist entry was added for Ferrouslime, because there's no layer to
whitelist. If the heads aren't currently showing boxes either, that's a separate,
genuine bug worth a fresh screenshot to confirm (my expectation is they already
work, given the `OTHER`-kind unconditional-capture path) — but the gel body itself
would need a materially different capture mechanism (something that derives bounds
from raw emitted vertices rather than from a model object) to ever be captured, which
is real new work, not a config/registration fix.

---

## Phase 1F: raw-vertex capture — Ferrouslime's gel body and spikes

Built the "materially different capture mechanism" flagged as needed above. This is
new territory for MAH — every prior capture path reads geometry off a model *object*
(a `ModelPart.Cube`, a `GeoCube`, an `AdvancedModelBox`'s bounds). Ferrouslime's gel
has no such object. This path instead derives a bounding box from the actual vertex
positions a mod's own hand-rolled rendering code emits, without touching what
actually gets drawn.

### Mechanism

New `RecordingVertexConsumer` (in `capture/`, fully generic — not Ferrouslime-specific)
is a transparent `VertexConsumer` decorator: every `vertex(double,double,double)` call
(which is the one method every other `vertex(...)` overload funnels through —
confirmed via bytecode: `vertex(Matrix4f, x,y,z)` transforms by the matrix and calls
this exact method) gets transformed by the bracket's `basisInverse` and folded into a
running min/max, then forwarded unchanged to the real delegate. Every other method
(`color`, `uv`, `normal`, `endVertex`, etc.) just passes straight through — actual
rendering is completely unaffected.

Two new manager methods bookend a span: `beginRawVertexCapture(name, delegate)`
(returns a wrapped consumer, or the original unchanged if called outside an active
bracket — safe to call unconditionally) and `endRawVertexCapture()` (pops the most
recent wrapper, emits a `PartOBB` with an **identity transform** — the box is already
entity-local, since the transform was baked into each point before computing bounds,
rather than kept separate the way every other capture path does it). Same
HEAD/RETURN balance discipline as the dispatcher bracket itself.

### Ferrouslime wiring

`FerrouslimeRendererMixin` wraps `renderGel` and `renderGelSpikes`'s own `consumer`
parameter at HEAD via `@ModifyVariable` — this substitutes the wrapper *in place of*
the original parameter, so the method's own unmodified body (which the mixin never
touches) renders into the wrapper transparently. A matching `@Inject` at RETURN
finalizes each span. `renderGelSpikes` loops over all eight spikes internally
(calling the private `spikeCubeFace` once per spike) — wrapping it as one span means
this currently produces **one combined box for all eight spikes**, not eight
individual ones. Splitting further is possible later if warranted.

Ferrouslime's heads use real Citadel `AdvancedModelBox` geometry and go through the
existing Citadel mixin unchanged — this only adds the two raw-vertex methods.

### Honesty check

This is meaningfully lower-confidence than everything else in Phase 1 so far. Every
prior fix was verified by reading the exact geometry/transform math out of a real
model object and reproducing it. Here there's no equivalent ground truth to check
against — the "correctness" of the resulting box rests on the coordinate-space
reasoning (matches the same convention every other capture path uses, and the
transform math is copied from the same `Vector4f.mul(Matrix4fc)` idiom already used
elsewhere in this codebase) rather than a direct verification. **This one genuinely
needs a debug-wireframe screenshot before treating it as trustworthy** — please spawn
a Ferrouslime with `debug_render` on and confirm the gel body and spikes get boxes
that actually look right (centered on the visible shape, roughly the right size), not
just that boxes appear at all.

### Re-test (Phase 1F)

Ferrouslime: heads should already have boxes (unchanged from before); gel body and
spikes should now get one box each. If either box looks wrong (offset, wrong scale,
or covering the wrong region), that points at a coordinate-space assumption being off
somewhere in `RecordingVertexConsumer`, not a typo — flag it and I'll dig back in.

### Addendum: per-spike granularity

First pass wrapped `renderGelSpikes` as a single span, which produced one huge box
spanning the full radial burst of all eight spikes at once — confirmed by screenshot,
and too coarse for the wound system to tell which spike actually landed a hit.

Fix: `renderGelSpikes` loops over all eight spikes itself, calling the private
`spikeCubeFace` once per spike — wrapping *that* method instead means each call
naturally gets its own begin/end span, since it's invoked eight separate times per
render. A small `@Unique` counter on the mixin (reset at `renderGelSpikes`'s HEAD,
incremented after each `spikeCubeFace` call) names each span `gel_spike_0` through
`gel_spike_7` in loop order. Safe as a plain instance field despite the renderer
being a shared singleton across all Ferrouslime entities: everything here runs
single-threaded on the render thread, and one entity's eight-spike loop always
completes before the next entity's render begins.

Now produces eight separate, individually-hittable boxes instead of one combined
one. Worth a fresh debug-wireframe screenshot to confirm each spike gets its own
tightly-fitted box rather than the previous single oversized one.

### Second addendum: diagonal spikes need oriented boxes, not axis-aligned ones

Screenshot after the per-spike fix showed the axis-aligned (N/E/S/W) spikes fitting
tightly, but the diagonal (NE/SE/SW/NW) ones producing boxes far too large. This is
the expected behavior of an axis-aligned bounding box over rotated geometry — a
diagonal spike's tip sticks out along both X and Z simultaneously, forcing the box to
balloon out to a square that's mostly empty air, while an axis-aligned spike has near
-zero extent on its perpendicular axis and so stays tight by comparison. Not a typo,
a real limitation of always computing bounds in a fixed (entity-local) frame.

**Fix**: added a second, "oriented" mode to the whole raw-vertex mechanism —
`beginRawVertexCapture(name, delegate, localTransform)`. Instead of transforming
incoming vertices all the way back to entity-local space and taking an axis-aligned
box there, it transforms them by the *inverse of the shape's own local transform*
(the same per-spike rotation matrix Ferrouslime already computes), landing bounds in
the spike's own unrotated frame — tight, since the geometry is axis-aligned in its
own frame regardless of which way it's rotated into the world. The resulting box
carries `basisInverse * localTransform` as its actual transform, matching the exact
"local box + separate transform" convention every other capture path in MAH already
uses (vanilla `ModelPart`, GeckoLib `GeoBone`, all three `AdvancedModelBox`
frameworks) — the spike boxes now rotate with the spikes instead of staying
world/entity-axis-aligned.

Wiring this needed the spike's transform matrix and its `VertexConsumer` together at
one injection point, which the earlier two-separate-injectors design (one
`@ModifyVariable` for the consumer, a separate `@Inject` for bookkeeping) didn't
provide. Replaced with a single `@Redirect` on the call *from* `renderGelSpikes` *to*
`spikeCubeFace` — the redirect handler receives both, wraps the consumer using the
matrix it's also holding, calls through to the real method (via a `@Shadow` stub,
since `spikeCubeFace` is private and third-party `@Shadow` carries none of the
SRG/refmap risk that bit us on vanilla fields back in the Phase 1B hotfix — no
mapping involved at all for a literal-name, remap=false target), then finalizes.
Gel body capture is unchanged (still axis-aligned — it's one blob with no
per-instance rotation to preserve, so there was nothing to fix there).

The oriented-mode addition to `RecordingVertexConsumer`/`beginRawVertexCapture` is
itself generic, not Ferrouslime-specific — any future raw-vertex capture case with a
rotated local frame can reuse it the same way.






