# Distant Horizons integration

After a native trace misses, Distant Horizons can supply a distant terrain hit
for ordinary capture or Precise Location. [Capture range](../architecture/picking/range.md#capture-pipeline-application)
defines the ray, range and fallback for each path.

## World binding

The trace starts only if exactly one loaded Distant Horizons level matches the
client world captured for the cast. Multiplayer checks the loaded level
wrappers; single-player also checks the integrated level's client alias. A
dimension name or type alone cannot distinguish worlds.

When the queued callback runs on the client thread, it rechecks the binding.
It delivers the hit only if the captured world still matches exactly one level
wrapper and both that wrapper and the terrain data source are the ones used for
the trace. An unavailable API or a scheduling or trace failure also leaves
capture without a distant hit.

## Terrain cache

Distant Horizons caches terrain by position. A trace can reuse a cache only
with the same level wrapper and terrain data source, so the same coordinates
in another world cannot return the previous world's terrain.

When a trace finds an expired cache, it retires it before requesting a
replacement. If the retired cache has no readers, that trace clears it;
otherwise the last reader clears it when it finishes. This cleanup applies
even if the replacement cannot be created.

See [automated trace and cache coverage](../testing/verification/interaction.md#capture-and-acceptance-range)
and [pending in-game checks](../testing/verification/manual-integration.md#distant-horizons).
