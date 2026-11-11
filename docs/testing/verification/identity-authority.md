# Domain, identity and authority

[Verification index and evidence rules](README.md) ·
[Pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)

These headless suites cover the domain, identity and authority seams below.
Compilation or model coverage is not a live server or connection result.

The catalogs and target model are covered by the fixed Target Type and Ping Type
sets (`entity_block` ordering includes `danger`), numeric-priority and
declaration-order resolution, optional-content fail-soft matching, target
identity and the captured-ray flow, Marker ID/codec round trips including
`targetTypeId`, same-target winner selection and recomputation, and source
ordering with one evaluation per consulted resolver.

`ServerMarkerStoreTest` covers recipient-scoped active-winner snapshots,
expiry-before-selection, arrival/larger-ID ordering, immutable results, a
non-mutating baseline query, and detached canonical-page cursor, population and
sentinel boundaries. Synchronized-deadline and visual-state seams for display
expiry and expire fallback are covered by `ClientMarkerDisplayDurationTest` and
`ClientMarkerStoreTest`.

`MarkerCreationServiceTest` captures actual Log4j events for admission after
external materialization: an ordinary admission rejection is quiet, while a
throwing admission callback emits one bounded WARN. A `NORMAL` fixture keeps a
validator or grouping failure to one WARN with reject-create handling; null
validator or resolver results stay quiet; a grouping failure is not reported
twice. Rejected admissions release the materialized reference and leave the
marker store untouched. Target-type cases accept `danger` for a resolved
`entity_block`, store the whole-marker type and reject `loot`. Authoritative-name
cases retain the external creation's fallback name and committed target for null
or throwing resolvers and use the validator's fail-safe name; null results stay
quiet and thrown runtime failures emit one bounded warning. These are headless
service/callback and reflective-validator seams, not a live provider, deployed
logging configuration or end-to-end server/client validation.

`PacketHandlerTest` covers full buffer consumption after valid or corrupt
decoding, a failed fallback constructor retaining its reflection cause, and
DEBUG-only reporting on finite class-keyed channels, including independent
classes and sides, explicit consumer side selection and no `NORMAL`
malformed-input report. The finite vocabulary admits known packet classes plus
one unknown-class fallback. These are decoder and reporting seams, not transport
registration or a live connection.

Marker-store, packet and update tests provide recipient-scoped state and
channel-mode transport seams, but not the complete `ServerCore` ordering and
channel/admission matrix in a live path. Source inspection confirms loader
registration of the channel-update, rate-policy, presentation-v2,
server-presentation-policy-v1, inventory-v2, presentation-preview-v1 and
server-configuration-v5 routes on Fabric, Forge and NeoForge. `ModIdentityTest`
pins the fork identity and all thirteen registered packet IDs; focused packet
tests pin the versioned families' version boundaries and codec shapes, and
`PresentationPreviewWiringTest` statically confirms preview codec registration
and main-thread handoff on every loader. No live cross-loader network test
exists, and these checks do not establish server authority or live transport.

## Logical container identity and authority

`CompositeTargetIdentityTest` covers physical derivation versus grouped keys,
invalid or noncanonical carriers, recipient-scoped arrival/ID ordering,
independent losing records, cancellation, expiry, audience cleanup, client
copy/reload/stale fallback/display duration, once-only creation resolution before
dedicated admission and storage, and external materialization/refresh identity.
Tank cases add controller-key equality and separation from physical and Vault
keys, incompatible carrier rejection, independent hits, owners and audiences,
and frozen keys and display deadlines through changed resolver results.

`DoubleChestCreationTest` exercises the production server creation factory with
headless world/chunk fixtures for both vanilla chest kinds: opposite hits retain
their own target and anchor while sharing winner slots; cancellation, independent
expiry and split/rejoin preserve the existing marker identity; unavailable
sibling metadata falls back to the accepted physical-key ping; external and
unrelated targets do no ordinary group lookup; wrong-dimension and unsupported
platform cases retain the exact physical key. It also covers tank source,
registry and dimension gates before optional platform dispatch. These are
headless production factory/store/codec/client seams, not a live server or
multiplayer connection. NeoForge Vault and tank topology/creation evidence is
scoped under
[Create topology verification](integrations.md#vault-topology-grouping-and-presentation)
and
[tank topology verification](integrations.md#fluid-tank-topology-grouping-and-presentation).

Wire corruption and explicit-key acceptance are inventoried under
[presentation verification](presentation.md#logical-target-wire-coverage);
inventory quota and read authority under
[grouped inventory verification](inventory.md#logical-container-quotas-and-scan-sharing).

`ClientPingRuntimeContentReceiptTest` drives tank winner delivery before
`CREATED`, stale-session rejection, replay without deadline restart, explicit
cancellation and lost-removal expiry fallback. The surviving record keeps its
original hit and anchor; explicit removal waits for the authoritative winner
update. These are headless runtime/store seams.

## Client marker runtime coverage

`ClientPingRuntimeTest` drives the public runtime factory with its real
presentation session: `HELLO`, packet-dispatched `OFFER`/`RESET` and ordinary
location `CREATE` share the negotiated epoch. Before readiness and after `OFFER`
alone, creates send nothing, consume no allowance and establish no rejection
receipt; readiness does not replay them. At a fixed clock the first ready create
consumes the sole allowance and a later throttled create cannot replace its
receipt.

The same suite covers the local-store membership predicate around a same-ID
external-locator upsert. Packet-dispatched `CREATED` reaches both stores: a
same-ID newer initial updates locator and anchor while preserving identity,
arrival, expiry, visual deadline, target/Ping Type and winner slot; older or equal
revisions cannot roll back; feedback fires once for a newly seen marker; an
elapsed visual is not re-shown; an authoritatively expired or hard-removed marker
is not resurrected; a refreshed active winner keeps its slot and deadline. A
create after local record deletion remains the separate same-ID
[gap](#known-automated-gaps). These are headless store/port assertions: no live
world, network transport, Sable locator, sound playback, GUI chat delivery,
connection ordering or rendering is exercised; game-entry wiring is
source-inspected.

`ClientBusinessDiagnosticsTest` covers failed create and inventory `SELECT`
transport callbacks through the dispatcher: warnings retain message and cause
without an attached throwable, the prior receipt stays current, the next attempt
remains throttled instead of refunding the courtesy token, and create performs
one transport attempt. These are headless dispatcher and recording-log seams.

## Rate-policy courtesy behavior

The create-only client token-bucket gate covers dropping throttled committed
creates without queueing or dispatch tracking, `MarkerRemove` and channel-update
bypass, and corrupt-policy handling. `InventoryDispatchIntegrationTest` covers
the shared courtesy allowance in both orders: either route can throttle the
following one, each case observes only the first route's send, and the throttled
ordinary create cannot overwrite the inventory receipt.
`ClientPingActionDispatcherTest` covers one local cooldown notice for a throttled
committed create, no notice for a sent create, a cancellation or an unready
create, and the existing `reason=courtesy-throttled` `sent=false` `DEBUG` line;
`ClientPingRuntimeTest` and `InventoryDispatchIntegrationTest` carry the notice
through the runtime and the shared-allowance order, where an inventory throttle
keeps its separate tracking-failure line. `MinecraftLocalErrorSinkTest` checks
the prefixed cooldown-message composition, the shared `#FF5555` color, and
action-bar, chat and hidden placement through the headless placement helper.
`ClientRateLimitPolicyTest` covers the initial fallback matching a fresh
validated server configuration, an applied policy replacing it, and a fresh
limiter retaining the fallback independently. These are headless or
source-reviewed fixtures, with no passing run recorded here for these cases;
live action-bar or chat delivery, runtime reading of the configured placement,
server publication, reconnect, live configuration changes and real
dedicated-server initialization remain unexercised. Narrow isolated
server-default initialization coverage is under
[configuration verification](configuration-ui.md#configuration-recovery-and-selection-grammar).

## Frozen recipient authorization coverage and live gaps

The historical coverage above remains limited to its recorded execution or
source-review scope and does not prove
[D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md)'s
new-Ping authorization timing. Current-source cases do:

- `FrozenGrantIsolationTest` covers immutable per-recipient grants, exact child
  denial without removing its root, explicit deny entries, `retaining` keeping
  only the original audience, and the marker content grant kept separate from
  tracking kind.
- `FrozenPingCreationCutTest` covers fresh server permission, selector and child
  results frozen at create; an accepted session mask or advertised child map
  cannot alter them; opposite grants for two same-type creations; a later
  adapter field not extending an old grant; and a not-`HELLO` original-audience
  member whose later-ready projection keeps the creation grant. Its
  permission-call-count case covers the production wire projection, not the
  complete send loop.
- `FrozenGrantDeliveryTest` extends the cut to initial, baseline, refreshed and
  cached-send entry points and receipt projection: a later-denying provider is
  called zero times, creation-frozen Basic and optional SECTION values and the
  receipt still send, and a new create freezes its denial without rewriting the
  old marker's grant. These cases passed in the final combined execution
  recorded in
  [build checks](build-checks.md#provider-trust-and-frozen-authorization-final-verification);
  the delivery case is still a headless production-call seam and does not claim
  tick or session-mask behavior, non-Ping preview delivery, a live connection or
  live disconnect.

Pending evidence covers delivery and repair to an original-audience member
through a real connection, including the later `HELLO` gate when the member was
not ready at create; true disconnect after create; a live session where later
revocation or grant affects only new creates; and the complete
create-authorization result for every audience member under a real server world
and transport. Global field, selector, child, `RESET`, metadata and same-type
grant scenarios are owned by
[presentation coverage](presentation.md#frozen-recipient-content-authorization-coverage-and-live-gaps);
the fluid provider validation boundary by
[inventory coverage](inventory.md#frozen-recipient-authorization-and-fluid-provider-trust-coverage-and-live-gaps).

## Known automated gaps

- the complete `ServerCore` operation ordering and channel/admission matrix in an
  end-to-end server path;
- independent call-site diagnostic fixtures for `ServerCore` and the game-backed
  chat-display, template-lookup and selector exception-log catch paths in
  `ClientPingRuntime`; admission, dispatcher and headless pending-composition
  evidence does not establish those branches;
- same-ID marker creation after local record deletion in the full client
  runtime: production final deletion evicts retained presentation values under a
  session tombstone and drops the late create, while the isolated marker-record
  seam alone would reinsert it with a new visual deadline; no automated test
  drives that orchestration end to end;
- application of synchronized rate policy on reconnect and on effective live
  configuration change;
- server-side sanitization of negative rate-policy values.
