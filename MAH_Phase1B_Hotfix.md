# MAH Phase 1B — Hotfix (ModList timing)

## Bug

Both `CitadelMixinPlugin` and `LionfishMixinPlugin` called `ModList.get().isLoaded(...)`
from `shouldApplyMixin`, which Mixin invokes during `MixinInfo.<init>` —
`FMLLoader.beforeStart` / early display setup, well before FML has constructed and
populated `ModList`. `ModList.get()` returns `null` at that point, so the plugin threw
an NPE. Because both configs were `"required": false`, Mixin caught the failure and
silently disabled the *entire config* rather than crashing — no error dialog, no boxes,
no visible sign anything was wrong. Confirmed directly from `latest.log`:

```
Caused by: java.lang.NullPointerException: Cannot invoke "net.minecraftforge.fml.ModList.isLoaded(String)"
because the return value of "net.minecraftforge.fml.ModList.get()" is null
    at exp.CCnewmods.khp.mixin.citadel.CitadelMixinPlugin.shouldApplyMixin(CitadelMixinPlugin.java:37)
```//same for lionfishapi

## Fix

Removed `CitadelMixinPlugin` and `LionfishMixinPlugin` entirely, and dropped the
`"plugin"` key from both `mah.citadel.mixins.json` and `mah.lionfishapi.mixins.json`.

The plugins were solving a problem Mixin already solves on its own: `"required": false`
at the config level already causes Mixin to gracefully skip a config (WARN log, no
crash) when its target class can't be found — that's the documented purpose of the
flag, no plugin required. Adding a plugin on top didn't add safety, it added a new,
badly-timed failure mode that broke the exact case it was meant to protect. Both
configs now rely purely on `"required": false`, matching how the always-present
`mah.geckolib.mixins.json` config would behave too if GeckoLib were ever absent (it
isn't, since GeckoLib is a mandatory dependency — but the mechanism is the same).

No other changes. Doc comments in `CitadelAdvancedModelBoxMixin` /
`LionfishAdvancedModelBoxMixin` / `CitadelBoxCapture` / `LionfishBoxCapture` updated to
stop referencing the removed plugin classes.

## Re-test

Same acceptance test as before: Alex's Mobs cockroach and a handful of Ender's
Cataclysm bosses should now get full-body boxes. If Cataclysm bosses still show the
earlier partial (wings/arms-only) boxes *in addition to* new full-body boxes, that
confirms the mystery geometry noted in the original Phase 1B changelog is something
separate, not Cataclysm's own model — worth a fresh screenshot pass to chase down.
