# Presentation snapshot contract

This topic owns the versioned presentation snapshot subsystem: its independent
negotiation/intent/mutation route, its separate policy rule-view route, the
adapter and field model, the demand-driven server capture and per-recipient
projection, the per-target-type server field policy, marker-owned property
selections and their code-defined property Ping Type policy, and the
session-scoped client value store. Packet
registration as a family and the legacy route boundary are summarized by
[network protocol](../network/protocol.md); marker record state, visual
deadlines, and winner slots remain owned by
[client marker state](../markers/client-state.md). Persisted keys live in
[client configuration](../../config/client.md) and
[server configuration](../../config/server.md); mod-specific adapters are owned
by [Create integration](../../integrations/create.md).

## Route and negotiated session

Presentation values travel on a dedicated versioned route (`presentation-v3`),
independent of the legacy location family and of the superseded authoritative
marker packet family. All three loaders register the route and dispatch it
through the common handlers. Client-to-server intents are `HELLO`, `CREATE`,
and `REMOVE`; server-to-client messages are `OFFER`, `RESET`, `CREATED`,
`SECTION`, `REMOVED`, `WINNER`, and `REJECT`.

A connection reaches presentation readiness in stages:

- The client sends `HELLO` with the manifest schemas it supports before any
  marker intent is valid. Retries are bounded and stop once an offer arrives;
  an unready client sends no create or remove.
- The server creates the session on the first `HELLO` and answers with an
  `OFFER` carrying a random session epoch, the compatible manifest, and matching
  schemas; a repeated `HELLO` before readiness resends the stored offer. The
  client accepts only the first valid offer for the connection, and only after
  its own `HELLO`.
- The server follows its offer with a `RESET` carrying the epoch, the
  non-regressing view, and the server-selected authorization mask. The mask is
  keyed by target type, adapter, and the field IDs the server will send for that
  target type; it is derived from the accepted server field policy, and the
  client has no local receive or display filter to intersect. There is no client
  subscription, no field acknowledgement, and no subscription generation.
- Only a `RESET` for the offered epoch and a non-regressing view turns the
  client ready, enables marker intents, and starts the new view generation. On
  that reset the client prunes its retained values — including values inside a
  frozen section — to the mask, so a field the server no longer authorizes is
  deleted rather than displayed; an empty mask authorizes no field.
  `CREATE`/`REMOVE` intents carry the negotiated epoch and are ignored unless
  the session is ready for that epoch, and a create carries no client field
  list.

`RESET` and the mutation messages are accepted only for the current epoch/view;
the first accepted `OFFER` establishes the epoch, and stale or off-view messages
are dropped rather than applied. A schema mismatch removes that adapter from the
session's compatible manifest. Basic is mandatory: an offer without a compatible
Basic adapter is rejected, so that connection has no negotiated presentation
session. The client validates a received section's field kinds and wire bounds
against the accepted manifest; it applies no allow or deny policy of its own.

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
never exposes retained or sampled world values. The server policy page reads
only the fields advertised by the accepted `OFFER` after the local adapter and
field ID/kind compatibility check; its offline preview is the locally registered
manifest rather than server truth. An accepted entry keeps the server's
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
message and no protocol version. There is no client-local receive or display
field policy and no property-entry editor; per-target-type selector editing
belongs to the server policy page.

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
authorized to receive; a field no recipient's mask includes is not read. Entity and
block captures resolve the current authoritative world state and fail to an
unavailable result rather than asserting stale content; a mismatch between the
stored block registry ID and the live block is unavailable. Name composition
rules, including player, item, custom-name, and localized base forms, are owned
by [names and chat](../rendering/names_chat.md); denied or malformed names stay
unavailable and are not displayed.

Basic fields are declared enabled by default. Under an unrestricted
per-target-type policy — no matching block selector and no whitelist-only mode —
the server includes a Basic field in the recipient's mask and projection
without an explicit allow selector. That covers entity health and maximum
health: no allow rule is needed for the health pair to travel. Deny rules,
whitelist-only, permission evaluation, target kind, and capture availability
still apply; existing deny selectors and whitelist-only configurations keep
their exact meaning, and default enablement never implies that every target or
every viewer has health. The default provider emits a
health line only when both current health and maximum health are present in the
authorized projection; a missing maximum never produces a partial line. That
line is provider output, not a name-composition rule; name composition remains
owned by [names and chat](../rendering/names_chat.md).

## External-block Basic sampling

External-block targets use the same Basic adapter and do not add a wire route,
catalogue entry, or default field. The server's detached sampling context
retains the existing typed external-block target as a nullable pure-domain
component. It contains no Minecraft or loader objects, is used only for
in-memory sampling, and is not a new serialized target form.

For a committed external-block target, a recognized authoritative provider may
resolve the current live block state and local position for demanded, authorized
Basic fields. `minecraft:block.state` is captured only when it is demanded and
the provider observation succeeds. A provider-backed `minecraft:target.name`
request keeps its existing name-resolution semantics and does not require state
observation. An unavailable provider, unrecognized provider, uncommitted target,
wrong dimension, released source, unloaded or mismatched state, or arbitrary
opaque locator is unavailable rather than approximated; sampling does not force
load provider state or restore a client world.

If demanded external block state cannot be captured, the fresh Basic section is
not published. The last values that are still demanded remain atomically
retained with the stale marker under the generic capture rules; sampling never
extends the marker lifetime.

## Server capture and per-recipient projection

The server keeps a world-lifetime lease per active marker and samples sources on
the server thread. Sampling is demand-driven and bounded: a per-tick work and
capture budget bounds source scans, the field policy and each adapter's declared
interval bound how often a source is polled, and a configured interval can only
raise an adapter's declared minimum, never lower it. Each recipient's projection
is recomputed independently: a field is sent only when that recipient's session
manifest includes the field with the same kind, the marker's target-type rule
set and the recipient's server-derived mask include the field, and the
recipient's actual vanilla permission level meets the field's required level
(which a server permission override may replace). A failed or unavailable
optional source cannot break Basic or another adapter; the last demanded values
are retained with a stale marker instead.

Permission evaluation is replaceable and independent of field policy and
projection. The default compares the recipient's actual level to the required
level; a replacement provider receives the same recipient, field, actual, and
required inputs and may allow or deny, but a provider that throws, or an
out-of-range actual/required level, denies access. Clients cannot widen their own
projection: there is no client field list, and a client cannot grant itself a
field, a permission level, a marker removal, or winner state by sending
presentation values.

## Per-target-type field policy

The persisted field policy is server-owned and compiles allow and deny
selectors for each of the five target types. The persisted shape and the
field-selector syntax are catalogued in
[server configuration](../../config/server.md#presentation-policy-object); this
topic owns policy application:

- A marker is evaluated against the rule set of its exact target type; a
  missing, unknown, or invalid rule set denies every field of that type instead
  of widening access.
- Evaluation is allow, then deny, then the manifest field's default enablement.
- An allow match wins over a deny match; otherwise a deny match is evaluated
  before the manifest field's default enablement.
- In a rule set's whitelist-only mode an unmatched field is denied even when its
  manifest default is enabled.
- The server derives each recipient's `RESET` mask from this policy; the policy
  is part of the server's effective configuration fingerprint, and when the
  fingerprint changes every ready session advances its view and is re-projected
  rather than keeping values authorized under the previous policy.
- The client has no receive or display policy. Values excluded by a new mask are
  deleted on reset, including values inside an expired/frozen snapshot, and
  re-allowing a field cannot restore deleted values.

## Server policy rule view

The persisted per-target-type field policy is also exposed through its own
versioned route (`server-presentation-policy-v2`), independent of both the
marker session above and the five-field server-configuration request/update
transaction. A read request carries a positive request identifier and is
answered with all five target-type rule views — each type's white list, black
list, and whitelist-only flag — the server's in-memory rule-view revision, a
status, and the recipient's edit hint. Only those selector values are disclosed;
sampling limits, permission and interval overrides, and every other persisted
server setting never travel on this route. The client mirror retains all five
views as connection-scoped metadata even though only the server policy page
displays them, and the client derives no local filter from them.

A mutation carries the selected target type and applies one add or remove of a
single selector, or one whitelist-only assignment, to that type's rule set; a
mutation naming a missing or unknown target type is rejected without touching
stored policy. The complete candidate policy is validated before any
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
[configuration UI](../../UI/settings-screen.md#server-presentation-category).

Read and mutation authority is owned by
[server configuration authority](../authority/server-config.md), and the
server-side derivation and enforcement of that check are owned by
[security](../security.md#server-configuration-update-enforcement). The client
gates mutation allocation on the authoritative edit hint, but the hint is never
a credential. Selector grammar and capacity belong to this policy object,
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
resurrection. The store resets on a new epoch; a new `RESET` mask prunes retained
sections to the fields it still authorizes, including values inside a frozen
section, while an ordinary view generation change clears retained sections and
leaves frozen values in place.

There is no client receive or display policy. A UI consumer asks for a marker's
view and receives an immutable projection of the retained, mask-authorized
fields per adapter; an empty projection carries no sections. UI consumers never
receive the retained store itself, and registered UI providers
are manifest-only: a provider supplies at most three short HUD lines (longer
lines are truncated and blank lines dropped), can narrow the supplied view but
cannot acquire a store, sender, or capture callback, and switching or failing a
provider performs no network or source work. Display text is presentation only;
it never changes marker identity, winner slots, or server state.

## Property Ping

A create intent may carry a bounded list of uploaded property selections. Each
selection names an adapter ID, a field ID, and a record path; an empty path
addresses the field's root value, and a nested path addresses one record entry,
such as one registry-ID entry of an item-count summary. A path never indexes a
sequence: a sequence can be selected only as a whole value, not per element.

Each selection may carry the typed observed value the client saw — text, number,
flag, record, or sequence, under the existing value bounds — and a nullable
Ping Type ID, one Ping Type per property. A null Ping Type means the property is
not specially pinged: it adds no additional ping emphasis and does not by itself
add a HUD line, and it is not a claim that the field is unreceived, unknown, or
absent from the authorized projection. A non-null Ping Type is a code-defined
property Ping Type and drives that property's additional display line. One
intent may co-annotate several properties. On the wire, every top-level field
value and every addressable record entry carries a nullable Ping Type
annotation; an absent annotation decodes to null and means no explicit property
Ping for that entry, and there is no separate presence flag. Annotations never
appear inside a sequence, and a denied top-level field skips its whole frame
including annotations.

An uploaded value is not world authority. The server recaptures the current
authorized world value for every accepted selection from the same authoritative
capture used for projection: a valid but stale client claim is replaced by the
actual value, and a selection whose reference is the wrong kind or unknown, or
whose field or tag is forbidden, or whose source is unavailable, rejects the
entire create rather than applying partially. After acceptance the marker owns
the selections and the captured values update with the live world; a nested path
that no longer exists is not fabricated as zero. A selection changes no source
or entity persistence, and it never changes whole-marker identity, the main ping
type, marker lifetime, or the winner slot. An admission failure rolls back any
external target materialization.

The atomic initial message carries a default display reference chosen by the
server per target type even when the client uploaded no selection: entity health
(`minecraft:entity.health`, exactly; there is no health alias), presented under
the existing health-line rule that requires both current health and maximum
health to be authorized and captured, a dropped item's `minecraft:item.id`
formatted with its count, and `minecraft:target.name` for entity-block, block,
and location targets. The default reference controls the marker's ordinary
display, may be formatted without a Ping Type, and selects which authorized
property the HUD presents first; it does not limit which fields remain available
or received, and it is not a legacy marker-shape fallback. The marker name is
sent whenever the server authorizes and knows it, independently of the default
reference and of any non-null property annotation; the name is not exempt from
the field policy. The HUD presents the default reference and the properties
carrying an explicit non-null Ping Type annotation in a deterministic,
de-duplicated order within the existing per-provider line cap. A field whose
annotation is null is still retained and server-projected but is not displayed
unless the default reference selects it, and presenting count context still
requires its parent field to be authorized.

Property Ping Types are code-defined and independent of the whole-marker Ping
Type list owned by [catalogs](../identity/catalogs.md). The default allowed set
is `attention` and `danger`, and code-defined ordered override rules remove or
add Ping Types; a later rule may re-add a Ping Type an earlier rule removed. The
built-in `#c:chests` rule removes `danger` and adds `request`. A property Ping
Type ID is not a new custom Ping Type and is not user-configurable.

A property override selector matches the target's registry content:

- a `#` selector matches the target's actual tag IDs — block tags, including
  Sable block tags, for block and entity-block targets; the contained item's
  tags for a dropped item; and entity-type tags for an ordinary entity. A `#`
  selector never falls back to a registry ID when the tag is absent, so
  `#c:chests` matches only an actual chest tag.
- a selector without `#` matches the target's registry ID.

Both forms accept namespace and path wildcards, for example `#create:*` and
`#*:iron_ingot` on the tag side and `cyclic:*` and `*:*` on the registry side.
This target-selector grammar belongs to the property Ping policy and is distinct
from the field-ID selector grammar of the persisted field policy.

The selectable attributes are code-defined as well: the Basic field set plus
each applicable adapter's fields, filtered by the server field policy. A Create
RPM property is offered only for the actual machines the adapter can observe,
and a count selection still requires its parent field to be authorized.
Presentation attribute names, default labels, property Ping Type text, and
formatted values use presentation-owned resource keys, not the whole-marker Ping
Type phrase or display keys owned by [catalogs](../identity/catalogs.md) and
[names and chat](../rendering/names_chat.md). Existing marker names, labels, and
chat are unaffected, and a property the default reference or a non-null
annotation selects for display is presented to every authorized viewer without
requiring a local selection control.

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

The `presentation-v3` and `server-presentation-policy-v2` route IDs do not
decode the previous presentation or policy wire versions, and no fallback
decodes them. Whether any exact legacy registration remains registered as inert
ingress is checked by the source owner; this contract promises no automatic
decode or compatibility shim for a previous wire version.

## Evidence

Existing coverage inventory and remaining integration gaps are owned by
[testing and verification](../../testing/verification.md).
