# Capture range and authoritative acceptance

This topic owns the distinct client capture-range and server acceptance-range
boundaries. A capture limit only controls what a client can select; it is never
authority to create a marker. Conversely, the server check does not extend a
client's trace.

## Fields and ownership

| Field / value | Sampling and owner |
| --- | --- |
| Client `pingDistance` | Read once at hold start; local only, not synchronized to a server. Its persisted-file catalogue is [client configuration](../../config/client.md). |
| Client `raycastDistance` | Read once at hold start; native vanilla/Create trace cap. Its persisted-file catalogue is [client configuration](../../config/client.md). |
| Effective native trace distance | `min(raycastDistance, pingDistance)`: one finite frozen segment for the ordinary vanilla world/entity selection and its Create candidate refinement. |
| Precise live cast segment | Current camera ray, re-cast on the configured capture period while the Precise branch is active; bounded by the hold's frozen client range snapshot. Ownership is [Precise live candidate capture](capture.md#precise-live-candidate-capture). |
| Distant Horizons trace range | Fixed integration range, independent of either client field; started only after the native trace misses using the press origin/direction. |
| Server `pingDistance` | Constructed into each authoritative validator; separate from the client field and not sent to clients as a capture setting. |

The client field and the server field share a name but are separate
configuration objects. The client setter/UI does not synchronize a server
setting, and server configuration is not a promise that a native client trace
will reach that far. Server-settings editing and its permission/UI boundary are
owned elsewhere; this document records only the range used for acceptance.

## Capture pipeline application

At ordinary capture start, the client captures one ray and reads the current
client fields once. It builds the native finite segment with
`min(hidden raycastDistance, client pingDistance)` and gives that exact segment
to the native raycast. The native trace selects Minecraft world
blocks/fluids and entity candidates, while a claimed Create candidate receives
the same segment through the common raycast request. Create transforms that
already-bounded segment into its local space and scans its frozen local shapes;
it does not reuse Create's interaction picker or add a second range. This
segment reuse is the ordinary native trace only; the Precise branch's live
casts are owned by
[capture](capture.md#precise-live-candidate-capture) and their range by
[Selector candidate supplements](#selector-candidate-supplements).

The ordinary Sable capture attempt occurs only after that native route yielded
a block hit. It receives the native hit plus the same origin and endpoint of
the effective segment; Sable candidate projection requires the transformed
point to lie on that segment, with only the integration's small projection
epsilon. It therefore has no independent range expansion. Sable's separate
transformed-behind discovery is the additive provider supplement bounded in
[Selector candidate supplements](#selector-candidate-supplements).

After a native miss, Distant Horizons is a separate optional asynchronous route.
It receives the frozen press origin and direction but calls its API with an
integration-specific fixed range. This route is not clipped by client
`pingDistance` or the current hidden `raycastDistance` value/effective native
segment. A distant hit replaces the native location-miss
snapshot; a no-hit, failure, unavailable integration, or scheduling failure
uses the original native miss/location fallback. The eventual target still faces
the independent server acceptance check below.

The deferred and rapid-click compatibility captures described in
[long-press compatibility](../input/long-press-compatibility.md)
retain a ray rather than a target. When their
new baseline capture actually starts, it reads the current range fields and
then follows this same pipeline. They do not freeze range at the raw deferred
press edge.

## Selector candidate supplements

The native selector's Precise branch re-casts a bounded supplemental scan from
the current camera ray while the branch is active, not a second capture range.
Each cast shares the hold's frozen
[selection policy](selection_policy.md#raycast-use-and-blacklist-boundary) and
client range snapshot and is bounded by that hold's frozen client
`pingDistance`; within that bound it may find surfaces behind occluders along
the live ray. It never extends, replaces or re-samples the ordinary
press-frozen native effective segment
(`min(raycastDistance, pingDistance)`), and it neither starts nor changes the
Distant Horizons route; the location candidate's independent Distant Horizons
completion is owned by
[Precise live candidate capture](capture.md#precise-live-candidate-capture).
The same hold-frozen bound applies to provider supplements: Sable's
transformed-behind discovery shares the live ray and the hold-frozen client
`pingDistance`, and its bounded local traversal, completion and candidate rules
are owned by the
[Sable integration](../../integrations/sable.md#supplemental-transformed-behind-discovery).
A missing, incomplete or failed scan disables the affected Precise leaf even
when an ordinary result exists; it is not replaced by the ordinary result or
the Distant Horizons miss fallback. Supplemental
candidates still face the independent server acceptance check.

## Server acceptance

For every create request, the server uses its own `pingDistance` to validate
range. It measures the requesting player's current eye
against the authoritative anchor and rejects a squared distance strictly greater
than range squared as `OUT_OF_RANGE`; equality is accepted. The relevant anchor
is the live entity position, block center, exact location, or provider-derived
external anchor. The server validates identity/current dimension/live state and
classification separately; it does not replay the client ray or infer validity
from a client capture distance.

For a large Create contraption this means a client may select a nearby exact
surface but still be rejected if the whole entity's authoritative anchor is too
far away. For Sable, provider validation first supplies a normalized,
nonallocating candidate/match context and logical-pose **validation anchor**;
the server range-checks that anchor. Only later can materialization replace it
with committed target/anchor values. There is no second range check on that
replacement anchor. The two-phase provider transaction and its release handling
are owned by [Sable server validation and materialization](../../integrations/sable.md#server-validation-and-materialization).
See [target validation](../authority/target_validation.md) for the admission and
ordinary lifecycle contract.

## Integration matrix

| Route | Client segment / input | Range source | Result before authority |
| --- | --- | --- | --- |
| Vanilla blocks, fluids, entities | Frozen finite press segment | `min(client raycastDistance, client pingDistance)` | Native hit or location miss |
| Selector candidate supplements | Live current camera ray while the Precise branch is active; occluders allowed within the hold-frozen client ping distance | Hold-frozen client `pingDistance` | Nearest certified candidate per type, or incomplete/unavailable; a missing or incomplete scan disables the leaf even when an ordinary result exists |
| Sable transformed-behind supplement | Live current camera ray; provider-local sublevel traversal | Hold-frozen client `pingDistance` | Provider candidate only when the bounded local trace completes; otherwise incomplete/unavailable |
| Create contraption local shapes | Ordinary trace passes the finite segment through the common entity-candidate request and transforms it locally; Precise live casts use the current ray | Ordinary trace reuses the native effective segment; Precise live casts use the hold-frozen range; no Create interaction-picker range | Exact whole-entity hit, or owned miss/unavailable/failure with no coarse-AABB revival |
| Sable external candidate (ordinary projection) | Native block hit plus that same segment's frozen origin/end | Reuses native effective segment; point must project onto the segment | External candidate only after provider checks; otherwise existing projected/location or vanilla fallback |
| Distant Horizons | Frozen press origin/direction after the ordinary native miss; the live cast ray for the Precise location candidate | Integration-specific fixed API trace, independent of both client fields | Distant block hit, or the ordinary native location miss |
| Server validator | Current server player eye and authoritative validation anchor | Server `pingDistance` | Accept or `OUT_OF_RANGE`, independently of client capture; an external candidate's provider validation anchor is checked before later materialization |

## Evidence and remaining verification

Automated coverage and remaining end-to-end scenarios are owned by
[verification](../../testing/verification.md#capture-and-acceptance-range).
