# Presentation snapshot contract

This topic owns the versioned presentation snapshot subsystem: its independent
negotiation/intent/mutation route, its separate policy rule-view route, the
adapter and field model, the demand-driven server capture and per-recipient
projection, the persisted field selection policy, and the session-scoped client
value store. Packet
registration as a family and the legacy route boundary are summarized by
[network protocol](../network/protocol.md); marker record state, visual
deadlines, and winner slots remain owned by
[client marker state](../markers/client-state.md). Persisted keys live in
[client configuration](../../config/client.md) and
[server configuration](../../config/server.md); mod-specific adapters are owned
by [Create integration](../../integrations/create.md).

## Route and negotiated session

Presentation values travel on a dedicated versioned route (`presentation-v2`),
independent of the legacy location family and of the superseded authoritative
marker packet family. All three loaders register the route and dispatch it
through the common handlers. Client-to-server intents are `HELLO`,
`SUBSCRIBE`, `CREATE`, and `REMOVE`; server-to-client messages are `OFFER`,
`RESET`, `CREATED`, `SECTION`, `REMOVED`, `WINNER`, and `REJECT`.

A connection reaches presentation readiness in stages:

- The client sends `HELLO` with the manifest schemas it supports before any
  marker intent is valid. Retries are bounded and stop once an offer arrives;
  an unready client sends no create or remove.
- The server creates the session on the first `HELLO` and answers with an
  `OFFER` carrying a random session epoch, the compatible manifest, and matching
  schemas; a repeated `HELLO` before readiness resends the stored offer. The
  client accepts only the first valid offer for the connection, and only after
  its own `HELLO`.
- The client subscribes with a strictly increasing generation and an
  intersection of compatible, receive-policy-authorized fields. A server accepts
  a subscription only for its current epoch and a strictly newer generation and
  then sends `RESET`.
- Only a `RESET` for the offered epoch, the current subscription generation, and
  a non-regressing view turns the client ready, enables marker intents, and
  starts the new view generation. `CREATE`/`REMOVE` intents carry the negotiated
  epoch and are ignored unless the session is ready for that epoch.

`RESET` and the mutation messages are accepted only for the current
epoch/subscription/view; the first accepted `OFFER` establishes the epoch, and
stale or off-generation messages are dropped rather than applied. A schema
mismatch removes that adapter from the session's compatible manifest. Basic is
mandatory: an offer without a compatible Basic adapter is rejected, so that
connection has no negotiated presentation session.

## Field manifest and value model

Adapters are explicitly registered on each endpoint; there is no reflective
object or field dumping. An adapter declares a namespaced adapter ID, a manifest
schema, a minimum sampling interval, and its fields. Each field declares a
namespaced ID, a kind (`TEXT`, `NUMBER`, `FLAG`, `SEQUENCE`, or `RECORD`), its
default enablement, a vanilla permission level, and a short label. Duplicate
adapter or field IDs are rejected at registration.

Values are detached and immutable: text, finite number, flag, sequence, or
string-keyed record, with nesting, entry-count, text-length, and per-field and
per-section byte bounds enforced on construction and again on the wire. A
denied or unknown field is skipped by its framed byte length and never enters
typed decoding. A whole section is replaced at a time — never merged field by
field — and carries an explicit stale marker. Section replacements and clears
are revisioned per adapter: an older revision cannot overwrite a newer value or
re-create a cleared adapter section, and a section clear deletes neither the
marker nor another adapter's section. Basic is delivered atomically: the
created-marker message carries both the canonical marker snapshot and the Basic
section, and a message without usable Basic is discarded before either part is
applied. The client also requires the carried marker ID to match the snapshot
ID.

The settings-UI field catalogue is a read-only view of one metadata source and
never exposes retained or sampled world values. The client panels read the
locally registered manifest; the server policy panel and the shared reference
read only the fields advertised by the accepted `OFFER` after the local adapter
and field ID/kind compatibility check. An accepted entry keeps the server's
advertised default enablement and label, never the local manifest's metadata for
that ID. Entries are grouped by the namespace of the field ID, not by the owning
adapter's mod ID, and a field ID that occurs more than once renders once. The
offered catalogue contains compatible fields only and need not list every field
the server supports. A structurally invalid offer is rejected before any
connection state changes and publishes no catalogue; a structurally valid offer
without a compatible Basic adapter opens no session and likewise publishes
nothing. The accepted catalogue is cleared when the connection closes and is
rebuilt only when accepted metadata changes; reading it from an open settings
page sends no request and samples no source. Listing the catalogue adds no
message and no protocol version.

## Basic target fields

Basic (`minecraft:basic`, schema 1) is the stable common field set:

| Field | Kind | Capture |
| --- | --- | --- |
| `minecraft:target.name` | text | Composed target name. |
| `minecraft:entity.type` | text | Entity type registry ID. |
| `minecraft:entity.health` | number | Living entity health. |
| `minecraft:entity.max_health` | number | Living entity maximum health. |
| `minecraft:item.id` | text | Contained item registry ID for an item entity. |
| `minecraft:item.count` | number | Contained stack count. |
| `minecraft:item.icon` | flag | Whether an item icon is present. |
| `minecraft:block.state` | record | Block state properties by name. |

Values are captured on demand for the fields that at least one recipient is
authorized and subscribed to; a field with no demand is not read. Entity and
block captures resolve the current authoritative world state and fail to an
unavailable result rather than asserting stale content; a mismatch between the
stored block registry ID and the live block is unavailable. Name composition
rules, including player, item, custom-name, and localized base forms, are owned
by [names and chat](../rendering/names_chat.md); denied or malformed names stay
unavailable and are not displayed.

Basic fields are declared enabled by default. Under an unrestricted default
policy — no matching block selector and no whitelist-only mode — the server
projects a subscribed Basic field, and the client subscribes, retains, and
displays it without an explicit allow selector. That covers entity health and
maximum health: no allow rule is needed for the health pair to travel and
display. Deny rules, whitelist-only, permission evaluation, target kind, and
capture availability still apply; existing deny selectors and whitelist-only
configurations keep their exact meaning, and default enablement never implies
that every target or every viewer has health. The default provider emits a
health line only when both current health and maximum health are present in the
authorized projection; a missing maximum never produces a partial line. That
line is provider output, not a name-composition rule; name composition remains
owned by [names and chat](../rendering/names_chat.md).

## Server capture and per-recipient projection

The server keeps a world-lifetime lease per active marker and samples sources on
the server thread. Sampling is demand-driven and bounded: a per-tick work and
capture budget bounds source scans, the field policy and each adapter's declared
interval bound how often a source is polled, and a configured interval can only
raise an adapter's declared minimum, never lower it. Each recipient's projection
is recomputed independently: a field is sent only when that recipient's session
manifest includes the field with the same kind, that recipient's subscription
names it, the server field policy allows it, and the recipient's actual vanilla
permission level meets the field's required level (which a server permission
override may replace). A failed or unavailable optional source cannot break
Basic or another adapter; the last demanded values are retained with a stale
marker instead.

Permission evaluation is replaceable and independent of field policy and
subscription. The default compares the recipient's actual level to the required
level; a replacement provider receives the same recipient, field, actual, and
required inputs and may allow or deny, but a provider that throws, or an
out-of-range actual/required level, denies access. Clients cannot widen their own
projection: subscriptions are intersected with the server manifest and policy,
and a client cannot grant itself a field, a permission level, a marker removal,
or winner state by sending presentation values.

## Field selection policy

The persisted field policy compiles allow and deny selectors for both client
receive/display policy and server projection. The shared object shape and
selector syntax are catalogued in
[server configuration](../../config/server.md#presentation-policy-object); this
topic owns policy application:

- Evaluation is allow, then deny, then the manifest field's default enablement.
- An allow match wins over a deny match; otherwise a deny match is evaluated
  before the manifest field's default enablement.
- In whitelist-only mode an unmatched field is denied even when its manifest
  default is enabled.
- A deny-all policy state denies every field instead of widening access; which
  invalid selector, override, or collection inputs produce it is owned by
  [server configuration](../../config/server.md#presentation-policy-object).
- Denied values already retained by the client are deleted, including values
  inside an expired/frozen snapshot; tightening receive policy also drops
  readiness and requires a fresh subscription/reset, and re-loosening cannot
  restore deleted values.

The policy is part of the server's effective configuration fingerprint; when
the fingerprint changes, every ready session advances its view and is
re-projected rather than keeping values authorized under the previous policy.

## Server policy rule view

The persisted field selection policy is also exposed through its own versioned
route, independent of both the marker session above and the five-field
server-configuration request/update transaction. A read request carries a
positive request identifier and is answered with the current white list, black
list, and whitelist-only rule view, the server's in-memory rule-view revision, a
status, and the recipient's edit hint. Only those three selector values are
disclosed; sampling limits, permission and interval overrides, and every other
persisted server setting never travel on this route.

Mutations are bounded and atomic: one add or remove of a single selector, or one
whitelist-only assignment. The complete candidate policy is validated before any
persisted value changes, so an invalid, duplicate, missing, over-capacity, or
no-op request leaves the stored policy, the revision, and other clients
untouched and is answered with the unchanged rule view plus a status. A
validated candidate is installed while the persistence attempt runs, and a
failed persistence restores the previous rule view, so the file, the revision,
and other clients never observe a candidate that was not saved. A successful
persistence is the only outcome that advances the revision, replies to the
requester with the applied rule view, and broadcasts the new view to other
connected clients; a failed persistence answers the requester without advancing
the revision or broadcasting.

The client keeps one connection-scoped mirror per connection. It starts unknown,
and only an accepted valid rule view makes it known; an error, timeout, or
silence never fabricates an empty rule view, and a late response to an expired
or replaced request is ignored. At most one correlated request is outstanding: a
read retry may replace another read but never an in-flight mutation, and a
mutation may start only from a known view that still grants edit. A correlated
response is accepted only for the pending request identifier and always
completes that request, even when a newer unsolicited broadcast already
published a later revision, so retained values never regress; an unsolicited
broadcast is accepted only after the view is already known and only when its
revision strictly advances. A request that outlives the bounded timeout expires:
an expired read becomes retryable, while an expired mutation keeps the last
known view, marks the outcome uncertain, and requires a fresh successful read
before another write. Disconnecting clears the mirror, so a late packet cannot
repopulate it. How the settings UI renders the mirror is owned by the
[configuration UI](../../UI/settings-screen.md#presentation-category).

Read and mutation authority is owned by
[server configuration authority](../authority/server-config.md), and the
server-side derivation and enforcement of that check are owned by
[security](../security.md#server-configuration-update-enforcement). The client
gates mutation allocation on the authoritative edit hint, but the hint is never
a credential. Selector grammar and capacity are the shared policy object's,
catalogued in
[server configuration](../../config/server.md#presentation-policy-object).

## Client retention and display

The client keeps one session store per connection: known markers, per-adapter
sections, per-adapter clear tombstones, and whole-marker tombstones. A marker
removal reason that only expires the marker freezes the retained sections, so
later sections cannot modify them and their values can still be displayed until
the record is hard-removed; hard removals tombstone even unseen IDs, and local
eviction also leaves a tombstone so a delayed initial cannot resurrect the
marker. Tombstones and per-marker section history are bounded; when history
exhausts, the store fails closed instead of discarding the history that prevents
resurrection. The store resets on a new epoch and clears retained sections when
the subscription or view generation advances, while frozen values survive a
generation change.

Receive policy gates decoding, subscription, and retained values. Display policy
is applied when a UI consumer asks for a marker's view: the client builds an
immutable projection of receive-authorized and display-authorized fields per
adapter, and an empty projection carries no sections. UI consumers receive only
that projection — never the retained receive store — and registered UI providers
are manifest-only: a provider supplies at most three short HUD lines (longer
lines are truncated and blank lines dropped), can narrow the supplied view but
cannot acquire a store, sender, or capture callback, and switching or failing a
provider performs no network or source work. Display text is presentation only;
it never changes marker identity, winner slots, or server state.

## Legacy and superseded routes

Legacy location packets keep their existing legacy behavior and are not part of
this subsystem. The superseded authoritative marker C2S create/remove routes are
registered but disabled: their handlers perform no marker mutation, so only the
negotiated presentation `CREATE`/`REMOVE` intents reach the private admission
and removal adjudicators. The superseded marker S2C create/remove/reject/winner
handlers are also registered no-ops; because they carry no negotiated epoch or
Basic section, a valid old marker name packet can never supply the new client's
display name or marker state. The old S2C route is retained only as inert
ingress, and the retained values/name used for chat and labels come from the
Basic atomic initial described above.

## Evidence

Existing coverage inventory and remaining integration gaps are owned by
[testing and verification](../../testing/verification.md).
