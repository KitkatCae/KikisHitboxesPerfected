# MAH Phase 1B — Third-Party Model Capture (Citadel / LionfishAPI)

Adds hitbox capture for entities rendered through the two Tabula-style model
frameworks discovered during Phase 1 wireframe testing: Citadel (used by Alex's
Mobs, e.g. the cockroach — zero boxes) and LionfishAPI (used throughout Ender's
Cataclysm — the boss screenshots).

## Root cause

Both frameworks ship their own `AdvancedModelBox`/`BasicModelPart` model system —
a from-scratch reimplementation of the pre-1.20 Techne/Tabula model, structurally
parallel to vanilla `ModelPart` but a completely separate class hierarchy in a
third-party package. MAH's Phase 1 only had hooks for vanilla `ModelPart` and
GeckoLib's `GeoBone`; neither framework touches those, so their geometry was
invisible to capture — not corrupted, just unreached.

**Correction to a claim made during Phase 1 wireframe review:** the partial capture
seen on Ender's Cataclysm bosses (wings/arms boxed, torso/head not) was guessed at
the time as vanilla-`ModelPart` arms reused for held-item compatibility. Checking
Cataclysm's actual entity model classes (`Deepling_Brute_Model`,
`Endermaptera_Model`, and others) shows **zero vanilla `ModelPart` or GeckoLib
usage anywhere** — every part is LionfishAPI's own `AdvancedModelBox`. Whatever was
producing those partial boxes wasn't Cataclysm's own body geometry passing through
an existing hook; it wasn't explained, only worked around by this deliverable. If
boxes still appear in unexpected places after this update, that's a separate,
still-open question worth another look.

## What's new

- `AdvancedModelBox.doRender` HEAD capture for both Citadel
  (`com.github.alexthe666.citadel.client.model.AdvancedModelBox`) and LionfishAPI
  (`com.github.L_Ender.lionfishapi.client.model.tools.AdvancedModelBox`) — two
  separate mixins (Mixin targets one literal class each), sharing the same
  generic math in `HitboxCaptureManager.captureAdvancedBoxCube`.
- Bounds come from each framework's own pre-grow `posX1..posZ2` fields (the same
  convention as vanilla `Cube.minX..maxZ` — pre-deformation, model units).
- Both frameworks' `AdvancedEntityModel` extends vanilla `EntityModel`, and their
  renderers are plain `LivingEntityRenderer`/`MobRenderer` subtypes, so the
  existing dispatcher bracket + primary-gate machinery (`shouldCaptureModelGeometry`,
  renamed from `shouldCaptureVanillaCubes` to reflect the broader scope) applies
  unchanged — no changes needed to the bracket logic itself.

## Naming

- **Citadel**: many consumers (Alex's Mobs) pass a real `boxName` at construction
  — used directly when present.
- **LionfishAPI**: verified that Cataclysm's own models *never* set `boxName`
  (every box uses the no-name constructor) — so the reflective fallback (the
  concrete model's own declared field name for that box instance, e.g.
  `this.left_leg`, recovered via `MahReflect.mapFieldNamesByIdentity`) is the
  **primary** naming source for every Cataclysm mob, not just a fallback.
- Both compat classes cache the field-name map per model instance (`WeakHashMap`),
  built once via reflection, not per-frame.

## Safety / optionality

- Both mods are **soft dependencies**: `compileOnly` in Gradle (jars in `libs/`,
  not bundled into MAH's own jar), `mandatory = false` in `mods.toml`.
- Both new mixin configs (`mah.citadel.mixins.json`, `mah.lionfishapi.mixins.json`)
  are `"required": false` **and** gated by a small `IMixinConfigPlugin`
  (`CitadelMixinPlugin` / `LionfishMixinPlugin`) that checks `ModList.isLoaded(...)`
  and skips the mixin entirely if the target mod isn't present. A modpack without
  Alex's Mobs/Citadel or Ender's Cataclysm/LionfishAPI loads MAH exactly as before.
- Flagged: `ModList` and `IMixinConfigPlugin` weren't available to verify against
  actual bytecode this session (not present in the mapped-jar slice used for the
  rest of MAH) — both are long-stable, unchanged Forge/Mixin platform APIs, but
  this rests on established knowledge rather than a bytecode check, per the
  project's verify-before-assuming rule.

## Not yet covered

- Neither mod's `boxName`/field-name uniqueness has been audited for collisions
  within a single model tree (two different branches reusing the same box field
  name). Unverified; flagged rather than assumed safe.
- LionfishAPI/Citadel armor or layer-renderer geometry (analogous to vanilla's
  `capture_layers` config gate) hasn't been specifically tested — the gate should
  apply the same way it does for vanilla ModelPart, but this wasn't part of this
  session's screenshots.

## Acceptance test

Same debug-wireframe approach as Phase 1: spawn an Alex's Mobs cockroach (should
now get full-body boxes, previously zero) and a handful of Ender's Cataclysm
bosses (should now get full-body boxes covering torso/head, not just the
previously-unexplained partial geometry). Re-check whether the earlier partial
boxes on Cataclysm bosses turn out to be the boss's own body (in which case this
fixes it completely) or something else entirely (in which case a second pass of
wireframe screenshots would help pin down what that geometry actually is).
