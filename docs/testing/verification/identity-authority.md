# Domain, identity and authority

[Verification index and evidence rules](README.md) ·
[Pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)

The current suite covers:

- the fixed Target Type and Ping Type catalogs, including their values and the
  `entity_block` ordered Ping Type set that includes `danger`;
- numeric-priority and declaration-order Target Type resolution;
- optional-content fail-soft matching;
- target identity and the captured-ray flow;
- Marker ID and marker codec behavior;
- `targetTypeId`, including `entity_block`, surviving marker codec round trips;
- same-target winner selection and recomputation;
- `ServerMarkerStoreTest` covers the active-recipient winner snapshot: expired
  markers are filtered before selection, ordering uses arrival then larger ID,
  the returned list is immutable, and a baseline query does not mutate the
  stored winner; its detached canonical-page tests cover removed cursors and
  holes, a captured population boundary, sentinel and maximum-ID boundaries,
  and immutable results without changing winner selection;
- synchronized-deadline and visual-state seams for display expiry and
  expire-fallback behavior through `ClientMarkerDisplayDurationTest` and
  `ClientMarkerStoreTest`; and
- source ordering and one evaluation of each consulted resolver in
  `DefaultTargetResolverTest`.

`MarkerCreationServiceTest` captures actual Log4j events for admission after
external materialization: an ordinary admission-type rejection emits no WARN,
while a throwing admission callback emits one WARN with the original message,
cause, suppressed detail, source frame and committed external locator. A
separate `NORMAL` fixture keeps a validator or grouping contract failure to one
`WARN` with reject-create handling and no raw target or cause; ordinary null
validator or resolver results stay quiet, and the grouping failure is not
reported a second time by the caller. The admission paths reject, release the
materialized reference and leave the marker store untouched. Its target-type
admission cases accept `danger` for a resolved
`entity_block`, record that whole-marker type on the stored marker, reject the
disallowed `loot` type with `INVALID_PING_TYPE`, and keep only the accepted
marker. This is a headless service/callback seam, not a live provider,
deployed logging configuration or end-to-end server/client validation.

Its authoritative-name cases cover a null or throwing resolver retaining the
external creation's fallback name and committed target, and the validator's
name helper returning its fail-safe name. Null results emit no warning; thrown
runtime failures emit one bounded warning with resolver identity, original
message and cause and no attached throwable. The validator helper is invoked
reflectively with uninitialized server/player tokens, not through world-backed
target validation.

`PacketHandlerTest` covers full buffer consumption after valid or corrupt
decoding and a failed fallback constructor preserving its reflection failure
cause while still draining the buffer. Its decode-failure cases assert
DEBUG-only reporting on finite class-keyed channels: repeats of one packet
class share a channel, another class and the other logical side admit
independently, an explicit consumer selects its side before decode, and
`NORMAL` never reports malformed input. The finite route vocabulary covers the
known packet classes plus one fallback for an unknown caller class; malformed
input cannot create a new channel. These are decoder and reporting seams, not
an end-to-end packet decode, transport registration or live connection test.

Marker-store, packet, and update tests provide recipient-scoped state and
channel-mode transport seams, but do not establish the complete `ServerCore`
ordering and channel/admission matrix in a live client/server path. Loader
registration of the channel-update, rate-policy, presentation-v2,
server-presentation-policy-v1, inventory-v2, presentation-preview-v1, and
server-configuration-v5 routes is confirmed by source inspection for Fabric,
Forge, and NeoForge. `ModIdentityTest` pins the fork identity and all thirteen
registered packet IDs in the fork namespaces; focused packet tests pin the
versioned families' version boundaries and codec shapes, and
`PresentationPreviewWiringTest` statically confirms the preview codec registration
and main-thread handoff on every loader. These
checks do not establish server authority or live transport. There is no live
cross-loader network test.

## Logical container identity and authority

`CompositeTargetIdentityTest` covers physical derivation versus grouped keys,
invalid/noncanonical key carriers, recipient-scoped arrival/ID winner ordering,
independent losing records, cancellation, expiry and audience cleanup. Client
copy, reload, stale fallback and display-duration cases preserve the explicit
group. Creation cases resolve once after final classification, Ping Type and
property acceptance, pass the same key to dedicated admission and storage, and
reject an incompatible resolver result before either. External materialization
and refresh preserve their established identity boundary.

`DoubleChestCreationTest` exercises the actual production server creation factory
with constructor-free recording world/chunk fixtures for both vanilla chest
kinds. Opposite hits retain their own target and anchor while sharing recipient
winner slots; cancellation reveals the surviving record, expiry remains
independent, and split/rejoin changes new creation and current presentation
without rekeying old markers. Unavailable sibling metadata falls back to an
accepted physical-key ping; external and unrelated targets perform no ordinary
group lookup. The wrong-dimension and unsupported-platform cases retain the
exact physical key.
These are headless production factory/store/codec/client seams, not a live server
or multiplayer connection. NeoForge Vault creation/platform evidence is scoped
under [Create topology verification](integrations.md#vault-topology-grouping-and-presentation).

Wire corruption and explicit-key client acceptance are inventoried under
[presentation verification](presentation.md#logical-target-wire-coverage), and
inventory quota/read authority under
[grouped inventory verification](inventory.md#logical-container-quotas-and-scan-sharing).

`CompositeTargetIdentityTest` covers tank controller-key equality, separation
from physical and Vault keys, incompatible carrier rejection, once-only creation
resolution, independent hits, anchors, owners, audiences, cancellation and
expiry, and frozen keys/display deadlines through changed resolver results and
client stale copies. `DoubleChestCreationTest` also covers tank source load,
registry and dimension gates before optional platform dispatch, and the exact-key
unsupported-platform default. NeoForge topology and creation evidence belongs under
[tank topology verification](integrations.md#fluid-tank-topology-grouping-and-presentation).

`ClientPingRuntimeContentReceiptTest` drives packet-dispatched tank winner
delivery before `CREATED`, stale-session rejection, replay without deadline
restart, explicit cancellation and lost-removal expiry fallback. The surviving
record keeps its original hit and anchor; explicit removal waits for the
separate authoritative winner update. These are headless runtime/store seams.

## Client marker runtime coverage

`ClientPingRuntimeTest` exercises the public runtime factory with its real
presentation session: `HELLO`, packet-dispatched `OFFER`/`RESET`, and subsequent
ordinary location `CREATE` share the negotiated epoch. Before readiness and
after `OFFER` alone, create attempts send nothing, consume no courtesy allowance,
and establish no rejection receipt; becoming ready does not replay them. At a
fixed clock the first ready create consumes the sole allowance, while a later
throttled create cannot replace its receipt. These are headless factory and
recording-transport checks, not a live connection.

`ClientPingRuntimeTest` covers the current-local-store membership predicate
before and after a same-ID external-locator upsert. Through a recording receipt
port it also drives the packet-dispatched CREATED path into the presentation
store and client marker store: a same-ID newer initial upserts the changed
locator/anchor while preserving ID, arrival, expiry, local visual deadline,
target/Ping Type and any winner slot; older and equal-revision initials cannot
roll back the authoritative payload; feedback fires once for a newly seen
marker rather than on refresh; an elapsed visual on a still-stored record is not
re-shown and a preserved winner slot does not expose it; an authoritatively
expired or hard-removed marker is not resurrected by a later initial, while a
create after local housekeeping deleted the record remains the separate same-ID
[gap](#known-automated-gaps); and a refreshed active winner
keeps its slot and visual deadline. These are headless store/port assertions:
they do not exercise a live world, network transport, Sable locator resolution,
actual sound playback or GUI chat delivery, packet ordering over a connection,
or client rendering. The surrounding game-entry wiring is source-inspected,
not exercised in a running client.

`ClientBusinessDiagnosticsTest` covers failed create and inventory SELECT
transport callbacks through the dispatcher: warnings retain the original message
and cause without an attached throwable, the prior dispatch receipt remains
current, and the next attempt remains throttled rather than refunding the
consumed courtesy token. Its create case also checks that only one transport
attempt occurs. These are headless dispatcher and recording-log seams, not live
packet transport or server acceptance.

## Rate-policy courtesy behavior

The current suite covers the create-only client token-bucket courtesy gate,
dropping throttled committed creates without queueing or dispatch tracking,
`MarkerRemove` and channel-update bypass, and corrupt-policy handling. This
client coverage does not close the server and end-to-end policy
[gaps](#known-automated-gaps).

`InventoryDispatchIntegrationTest` covers shared courtesy allowance in both
orders: an ordinary presentation `CREATE` throttles the following inventory
`SELECT`, and a `SELECT` throttles the following ordinary `CREATE`. Each case
uses one allowance at a fixed clock and observes only the first route's send;
the throttled ordinary create cannot overwrite the inventory receipt. These
are headless runtime/session and recording-transport checks.

`ClientRateLimitPolicyTest` covers the initial courtesy fallback matching a
fresh validated server configuration, an explicitly applied policy replacing
that fallback, and a fresh limiter retaining the fallback independently of the
edited server object. These are source-reviewed automated fixtures, with no
passing run recorded here; they do not exercise server publication, reconnect,
live configuration changes, or real dedicated-server initialization. Narrow
isolated server-default initialization coverage is inventoried under
[configuration verification](configuration-ui.md#configuration-recovery-and-selection-grammar).

## Frozen recipient authorization pending

The historical coverage and evidence records above remain limited to their
recorded execution or source-review scope; they do not prove
[D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md)'s
new-Ping authorization timing. The current-source cases below do.

Current-source automated cases cover the authorization value and the server
creation cut. `FrozenGrantIsolationTest` covers immutable per-recipient grants,
exact child denial without removing its root, explicit deny entries,
`retaining` keeping only the original audience, and the marker content grant
keeping authorization separate from its dedicated tracking kind.
`FrozenPingCreationCutTest` covers fresh server permission, selector and child
results frozen at create; an
accepted session mask or advertised child map that cannot alter them; two
same-type creations keeping opposite grants; a later adapter field not extending
an old grant; and a not-`HELLO` original-audience member whose later-ready wire
projection keeps the creation grant. Its permission-call-count case exercises
the production wire projection only, not the complete send loop.
`FrozenGrantDeliveryTest` extends that creation-cut scope to the actual initial,
baseline, refreshed and cached-send entry points plus receipt projection: with a
later-denying provider installed, those deliveries call it zero times, still send
the creation-frozen Basic and optional SECTION values and receipt, and a new
create resolves the provider and freezes its denial without rewriting the old
marker's grant. These cases passed in the final combined execution recorded in
[build checks](build-checks.md#provider-trust-and-frozen-authorization-final-verification).
The delivery case is still a headless production-call seam; it does not claim
tick or session-mask behavior, non-Ping preview delivery, a live connection or
live disconnect.

Pending evidence still covers:

- delivery and repair to an original-audience member through a real connection,
  including the later `HELLO` gate when the member was not ready at create;
- true disconnect behavior after create;
- a live session where later revocation or grant affects only new creates; and
- the complete create-authorization result for every audience member under a
  real server world and transport.

Global field, selector, child, `RESET`, metadata and same-type Ping grant scenarios
are owned by the
[presentation pending section](presentation.md#frozen-recipient-content-authorization-verification-pending);
the fluid provider validation boundary is owned by the
[inventory pending section](inventory.md#frozen-recipient-authorization-and-fluid-provider-trust-pending).

## Known automated gaps

- the complete `ServerCore` operation ordering and channel/admission matrix in
  an end-to-end server path;
- independent call-site diagnostic fixtures for `ServerCore` and the game-backed
  chat-display, template-lookup and selector exception-log catch paths in
  `ClientPingRuntime`; the admission, dispatcher and headless pending-composition
  evidence does not establish those branches;
- same-ID marker creation after local record deletion in the full client
  runtime: production final deletion evicts retained presentation values under
  a session tombstone, so the late create is dropped, while the isolated
  marker-record seam alone would reinsert it as a new insertion with a new
  visual deadline; no automated test drives that production orchestration end
  to end;
- application of synchronized rate policy on reconnect and on effective live
  configuration change;
- server-side sanitization of negative rate-policy values;
