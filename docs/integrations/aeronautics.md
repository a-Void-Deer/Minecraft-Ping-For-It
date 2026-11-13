# Aeronautics rotor targeting

## Scope and optional boundary

On Minecraft 1.21.1, the NeoForge client can refine ping targeting for an
assembled Aeronautics rotor. The normal and gyro assembled forms share the one
exact entity registry identity `aeronautics:propeller_bearing_contraption`; this
integration claims no other entity type. It is separate from the four Create
contraption IDs and does not require Flywheel or an outline backend.

The source identifier is `pingforit:aeronautics_propeller_raycast`. The owner is
resolved only after the `aeronautics` mod-ID check on client initialization and
join. Its Create-dependent delegate and native-shape view load lazily on the
first exact rotor trace; registration itself resolves no optional type. An
absent Aeronautics mod performs no owner class load. Registration is idempotent
across initialization and join, closes on leave, and can register again on a
later join.

The owner keeps its exact claim through a missing, drifted or failing delegate.
A delegate load or linkage failure makes the rotor `UNAVAILABLE`; a null result,
runtime callback failure or exhausted work is `FAILED`. Candidate handling
follows the shared [owned-result contract](../architecture/picking/local_geometry.md#immutable-ownership-and-candidate-admission).

## Geometry and native shapes

The rotor uses its current raw native base position as the anchor, not an
interpolated or rendered float. The query uses the optional frozen logical
parent frame supplied by [Sable](sable.md#transformed-entity-frames-and-frozen-block-proof).
The actual local model transform is sampled from the rotor's own virtual
transforms at partial tick `1.0`, so the active normal or gyro pivot is included.

The placed model is `T(P(nativeAnchor)) * R(parent) * L(localModel)`, where `P`
is the full parent placement and `R` its parent rotation. Parent translation,
rotation and scale therefore move the anchor, while only the parent rotation
orients the local rotor model; the local model is not multiplied by parent
scale.

The rotor reuses the captured local view and collision context owned by
[Create contraption ray targeting](create-contraption-raycast.md#local-geometry-and-optional-loading)
and the native-shape kernel owned by
[entity-local picking](../architecture/picking/local_geometry.md#distance-and-native-local-shapes).

## Discovery, work and failure

The rotor's native entity AABB is not an exactness or discovery gate. A parented
rotor can remain at remote native plot coordinates or expose a broad native
bound unrelated to its placed geometry. The source therefore registers with
`LOADED_EXACT` admission under the discovery and ordering rules owned by
[entity-local picking](../architecture/picking/local_geometry.md#immutable-ownership-and-candidate-admission).

Rotor traversal uses the request's work capability: bounded for Precise scans
and unlimited for ordinary captures. Provider access, every live map entry
before it is copied, frozen state/fluid/neighbor/block-entity reads, and every
native shape box are charged to that capability. Exhaustion and incomplete
traversal follow the shared [bounded-work contract](../architecture/picking/local_geometry.md#bounded-work-and-incomplete-traversal).
The four legacy Create IDs retain the separate
[unlimited path](create-contraption-raycast.md#cost-and-validation-boundaries).

## Consumers

The rotor remains a whole-entity target. Its authoritative anchor is owned by
[capture range](../architecture/picking/range.md#server-acceptance), and frame
failure adjudication by [target validation](../architecture/authority/target_validation.md#creation-time-validation-and-outcomes).

Client marker display and fallback are owned by
[client marker state](../architecture/markers/client-state.md#live-entity-display-position).
The optional Create entity-outline dispatcher applies the parent placement once
around the renderer's own local model; its placement and failure outcome are
owned by [Create](create.md#contraption-and-package-dispatch).

Automated coverage is inventoried in
[integration verification](../testing/verification/integrations.md#aeronautics-rotor-coverage);
the [pending manual matrix](../testing/verification/manual-integration.md#aeronautics-rotors)
owns live normal/gyro, world/sublevel, multiplayer and performance scenarios.
