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
- `targetTypeId`, including `entity_block`, surviving marker codec round trips
  without changing the protocol shape; and
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
cause, suppressed detail, source frame and committed external locator.
Both paths reject, release the materialized reference and leave the marker store
untouched. Its target-type admission cases accept `danger` for a resolved
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
cause while still draining the buffer. It does not assert the actual DEBUG
events from `readSafe`; public diagnostic-helper evidence is inventoried in
[shared exception diagnostics](integrations.md#shared-exception-diagnostics),
not an end-to-end packet decode or transport logging test.

Marker-store, packet, and update tests provide recipient-scoped state and
channel-mode transport seams, but do not establish the complete `ServerCore`
ordering and channel/admission matrix in a live client/server path. Loader
registration of the legacy, presentation-v1, presentation-policy, inventory-v1,
presentation-preview-v1, server-configuration-v3, and superseded marker routes
is confirmed by source inspection for Fabric, Forge, and NeoForge;
`ModIdentityTest` pins the fork identity, packet-ID namespaces, and the
versioned presentation-v1, inventory-v1, policy-v1, and server-configuration-v3
route IDs; focused packet tests pin those families' version boundaries and
codec shapes, and `PresentationPreviewWiringTest` statically confirms the
preview codec registration and main-thread handoff on every loader. These
checks do not establish server authority or live transport. There is no direct
automated test that a valid legacy location S2C packet or a superseded marker
S2C packet is ignored by the client, nor a live cross-loader network test.

## Client marker runtime coverage

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
or client rendering. The public entry wiring of this path is source-inspected,
not runtime-verified.

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

`ClientRateLimitPolicyTest` covers the initial courtesy fallback matching a
fresh validated server configuration, an explicitly applied policy replacing
that fallback, and a fresh limiter retaining the fallback independently of the
edited server object. These are source-reviewed automated fixtures, with no
passing run recorded here; they do not exercise server publication, reconnect,
live configuration changes, or real dedicated-server initialization. Narrow
isolated server-default initialization coverage is inventoried under
[configuration verification](configuration-ui.md#configuration-recovery-and-selection-grammar).

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
