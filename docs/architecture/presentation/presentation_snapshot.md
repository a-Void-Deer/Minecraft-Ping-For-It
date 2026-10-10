# Presentation snapshot contract

This topic owns the versioned presentation snapshot subsystem: its independent
negotiation/intent/mutation route, its separate policy rule-view route, the
adapter and field model, the demand-driven server capture and per-recipient
projection, the per-target-type server field policy, the pre-commit target
content preview with its field authorization, provenance and bounded fallback,
marker-owned property selections and their code-defined property Ping Type
policy, and the session-scoped client value store. Packet
registration as a family and the legacy route boundary are summarized by
[network protocol](../network/protocol.md); marker record state, visual
deadlines, and winner slots remain owned by
[client marker state](../markers/client-state.md). Persisted keys live in
[client configuration](../../config/client.md) and
[server configuration](../../config/server.md); mod-specific adapters are owned
by [Create integration](../../integrations/create.md). Generic source access,
capture results, cost accounting and sync publication are owned by
[shared source capture and sync](shared_sources.md), and the inventory preview
and tracking domain is owned by [inventory](inventory.md); this topic retains
negotiation, field policy, adapters, the target content preview and the session
value store.

## Route and negotiated session

Presentation values travel on a dedicated versioned route (`presentation-v5`),
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
  non-regressing view, the server-selected authorization mask, and the complete
  per-target-type child deny map. The mask is keyed by target type, adapter, and
  the field IDs the server will send for that target type; it is derived from
  the accepted server field policy, and the client has no local receive or
  display filter to intersect. The child deny map is likewise server-selected
  and complete: one list per target type, carrying the exact nested references
  that are not independently selectable or annotatable. A reset whose child
  deny map omits a target type, names an unknown type, or carries a duplicate,
  root, or malformed reference is rejected, and the mask and the child deny map
  are validated before either replaces the previous authorization. There is no
  client subscription, no field acknowledgement, and no subscription
  generation.
- Only a `RESET` for the offered epoch and a non-regressing view turns the
  client ready, enables marker intents, and starts the new view generation. On
  that reset the client prunes its retained values — including values inside a
  frozen section — to the mask and applies the child deny map, so a field the
  server no longer authorizes is deleted rather than displayed, a denied
  reference's annotation is removed, and a default reference naming it is
  cleared; an empty mask authorizes no field, and a denied child never strips
  keys from an authorized root record. `CREATE`/`REMOVE` intents carry the
  negotiated epoch and are ignored unless the session is ready for that epoch,
  and a create carries no client field list.

`RESET` and the mutation messages are accepted only for the current epoch/view;
the first accepted `OFFER` establishes the epoch, and stale or off-view messages
are dropped rather than applied. A schema mismatch removes that adapter from the
session's compatible manifest. Basic is mandatory: an offer without a compatible
Basic adapter is rejected, so that connection has no negotiated presentation
session. The client validates a received section's field kinds and wire bounds
against the accepted manifest; it applies only the advertised reset mask and
child deny map and no allow or deny policy of its own.

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
created-marker message carries the canonical marker snapshot, the Basic section,
and the receipt content descriptor, and a message without usable Basic is
discarded before any part is applied. The client also requires the carried
marker ID to match the snapshot ID. The descriptor's wire grammar is owned by
[network protocol](../network/protocol.md);
its projection policy is owned by
[Receipt content descriptor](#receipt-content-descriptor).

The settings-UI field catalogue is a read-only view of one metadata source and
never exposes retained or sampled world values. The server policy page reads
only the fields advertised by the accepted `OFFER` after the local adapter and
field ID/kind compatibility check; only the accepted offer populates it, and no
offline local-manifest catalogue is published. An accepted entry keeps the
server's advertised default enablement and label, never the local manifest's
metadata for that ID. Entries are grouped by the namespace of the field ID, not
by the owning adapter's mod ID, and a field ID that occurs more than once
renders once. The offered catalogue contains compatible fields only and need not
list every field the server supports. A structurally invalid offer is rejected
before any
connection state changes and publishes no catalogue; a structurally valid offer
without a compatible Basic adapter opens no session and likewise publishes
nothing. The accepted catalogue is cleared when the connection closes and is
rebuilt only when accepted metadata changes; reading it from an open settings
page sends no request and samples no source. Listing the catalogue adds no
message and no protocol version. There is no client-local receive or display
field policy and no property-entry editor; per-target-type selector editing
belongs to the server policy page.

## Dedicated delivery adapters

An adapter's delivery mode is either the legacy framed `SECTION` route or
`DEDICATED`. The manifest negotiates and the policy catalogue lists both modes;
a dedicated adapter owns its own request, collection and publication path and
is never sampled, masked, published or rendered by the section machinery — it
is not charged or sent as an empty section, and its collector is never reached
from that route. The inventory adapter (`pingforit:inventory`, schema 1) is the
first dedicated adapter: it declares one record field,
`pingforit:inventory.items`, enabled by default at permission level zero, and
its values travel only on the dedicated inventory route owned by
[network protocol](../network/protocol.md). Dedicated adapters share the same
per-field authorization rule as section fields: the target type's selector
policy decides the manifest default, then the recipient permission callback
decides the effective level; a missing setting, field or target type denies.

## Basic target fields

Basic (`minecraft:basic`, schema 1) is the stable common field set:

| Field | Kind | Capture |
| --- | --- | --- |
| `minecraft:target.name` | text | Composed target name. |
| `minecraft:target.custom_name` | text | Present custom name as plain text; absent otherwise. |
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

`minecraft:target.custom_name` is a separate plain-text observation, never a
reverse parse of the composed name value: it carries the present custom name's
plain text only when a non-empty custom name exists, and stays absent for a
missing or empty custom name, for a player or location target, and for a dropped
item whose contained stack has no custom name; an entity-level custom name never
substitutes for the stack rule. The composed `minecraft:target.name` keeps its
established composition and is demanded independently, so neither name field
forces the other. Both fields derive from one name observation of the same
target, and an absent custom name is never fabricated as an empty value. It is
declared enabled by default at permission level zero and remains subject to the
field policy, mask and permission evaluation like every other Basic field.

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
the provider observation succeeds. A provider-backed name request (the composed
`minecraft:target.name` or the separate plain `minecraft:target.custom_name`)
keeps its existing name-resolution semantics and does not require state
observation; both fields come from the same provider name observation under
their own demand, and a missing or empty custom name stays absent rather than
becoming the composed or base name. An unavailable provider, unrecognized
provider, uncommitted target, wrong dimension, released source, unloaded or
mismatched state, or arbitrary
opaque locator is unavailable rather than approximated; sampling does not force
load provider state or restore a client world.

A provider-confirmed read source is the separate binding used for read-only
external content sampling. It keeps the original detached target identity and
carries the provider-confirmed physical local position and the range anchor as
separate fields: physical read coordinates never replace the target identity,
and the exact opaque provider locator is part of the binding, so two external
candidates that compare equal as targets are not interchangeable. A candidate
binding is resolved only for an uncommitted candidate and a committed binding
only for an active committed target; resolution and read never materialize a
target, acquire or release a provider reference, or persist provider state, and
positive provider membership precedes every field, state or block-entity read,
including the root position. A committed binding follows the provider's active
committed source state and ignores a stale locator; a released or missing
reference cannot resolve. An unavailable provider, unrecognized provider, wrong
dimension, unloaded member, mismatched expected registry, or unconfirmed
membership is unavailable rather than approximated.

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
(which a server permission override may replace). A child-denied exact
reference is never annotated even when its root field is projected, and the
parent value stays complete. A failed or unavailable
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
selectors plus an exact child deny list for each of the five target types. The
persisted shape, the field-selector syntax, the child reference form, and the
semantic child-deny defaults are catalogued in
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
- A child deny reference matches only the exact case-sensitive
  adapter/field/nonempty-literal-record-path tuple: it covers no descendant, no
  sibling, and not the root field, and a field allow match never overrides it.
  Child denial is not root-value redaction: the parent field and its complete
  value stay authorized and projectable, so an authorized record still formats
  from all of its entries, while the denied reference is never independently
  selectable or annotated. A reference naming a missing field or an absent
  optional adapter is retained but inert.
- The effective child deny set is the advertised `RESET` map unioned with the
  fresh policy: a newly denied reference is enforced immediately, while a newly
  allowed reference stays denied until a new `RESET` re-baselines the advertised
  view, because the client never widens an advertised map in place.
- The server derives each recipient's `RESET` mask and child deny map from this
  policy; the policy is part of the server's effective configuration
  fingerprint, and when the fingerprint changes — including a child-only change
  that leaves the mask unchanged — every ready session advances its view and is
  re-projected rather than keeping values authorized under the previous policy.
- The client has no receive or display policy of its own. Values excluded by a
  new mask are deleted on reset, including values inside an expired/frozen
  snapshot, and re-allowing a field cannot restore deleted values; the reset's
  child deny map prunes retained annotations and default references under the
  same no-restore rule.

## Server policy rule view

The persisted per-target-type field policy is also exposed through its own
versioned route (`server-presentation-policy-v3`), independent of both the
marker session above and the ordinary server-configuration request/update
transaction. A read request carries a positive request identifier and is
answered with all five target-type rule views — each type's white list, black
list, whitelist-only flag, and persisted child deny list — the server's
in-memory rule-view revision, a status, and the recipient's edit hint. Only
those selector values and child deny references are disclosed; sampling limits,
permission and interval overrides, and every other persisted server setting
never travel on this route. The client mirror retains all five views, including
each type's child deny list, as connection-scoped metadata even though only the
server policy page displays the editable selector state; the mirror is
informational, its child deny lists are disclosure only and are not editable
through this route, and the client derives no local filter from the mirror.

A mutation carries the selected target type and applies one add or remove of a
single selector, or one whitelist-only assignment, to that type's rule set; a
selector or whitelist-only mutation preserves that type's persisted child deny
list, and this route never edits child references. A mutation naming a missing
or unknown target type is rejected without touching
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
a credential. Selector and child-reference grammar and capacity belong to this
policy object, catalogued in
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
section, and applies the reset's child deny map to retained and frozen
annotations and default references, while an ordinary view generation change
clears retained sections and leaves frozen values in place. A denied reference
is removed from annotations and a default reference naming it is cleared, but
the parent field and its complete value stay; an incoming section's denied
annotations are dropped before storage, so a later loosening or a later section
cannot resurrect a denied reference.

There is no client receive or display policy. A UI consumer asks for a marker's
view and receives an immutable projection of the retained, mask-authorized
fields per adapter; an empty projection carries no sections, and a property
lookup for a child-denied reference yields nothing even while the parent
field's value stays visible. UI consumers never
receive the retained store itself, and registered UI providers
are manifest-only: a provider supplies at most three short HUD lines (longer
lines are truncated and blank lines dropped), can narrow the supplied view but
cannot acquire a store, sender, or capture callback, and switching or failing a
provider performs no network or source work. Display text is presentation only;
it never changes marker identity, winner slots, or server state.

## Target content preview

The wheel's content branch may present a captured target's authorized
observations before any Ping exists. It is client-first: it shows a value the
client can observe locally and falls back to the bounded server preview only
for a field it cannot observe locally. The preview is a presentation read: it
creates no marker, audience, lease, winner slot, chat output, or marker
lifetime, and a missing or unavailable preview never changes the plain default
action. Its provisional projection is not the retained marker store and never
supplies marker records or marker names.
The [wheel](../picking/wheel.md) owns the branch's native
presentation, navigation, selection input and lifecycle, and
[capture](../picking/capture.md#interaction-lifecycle-aborts) owns the
interaction abort; this section owns the preview's field authorization,
provenance and fallback mechanics. This is the adopted contract for the
confirmed preview design; coverage scope and pending runtime evidence are owned
by [testing and verification](../../testing/verification.md).

- **Field authorization.** A preview value is eligible only under the current
  accepted presentation epoch and view. The field must be included by the
  accepted `RESET` mask for the target's exact target type and must intersect
  the accepted manifest's compatible section fields with a matching kind; the
  client adds no grant of its own. Manifest metadata such as a field's default
  enablement or label is not a grant by itself, and an unready session, a stale
  view, an unknown field, a target-type mismatch, or a mask exclusion makes the
  field ineligible. The reset's child deny map applies at the same
  granularity: a denied nested reference is ineligible even when its root field
  is eligible, is filtered from the content branch's property entries and
  dispatchable intents, and never becomes a selection. Dedicated adapters,
  including inventory, are excluded from this preview; inventory's own list,
  stream and variant rules stay owned by [inventory](inventory.md).
- **Provenance and precedence.** A value the client already legitimately
  received for the exact target is shown as `CLIENT_SYNCED`; only a field the
  client cannot observe locally may fall back to the bounded server preview,
  and a local observation that satisfies the field sends no server request.
  Every preview value is detached, bounded and provisional — `CLIENT_SYNCED`
  and server-preview values alike are not fresh world authority and never
  bypass the server's authoritative recapture at create
  ([Property Ping](#property-ping)). An unsynced or unknown value, including an
  unverified constructor/default value, is never treated as a zero, an empty
  result, or an observed absence, and a missing fallback result is absent or
  unavailable rather than an empty value. Legitimately observed or synchronized
  zero, false, and empty values remain usable data.
- **Local observations.** Local values come from explicit code-registered
  readers per field, never a reflective or arbitrary dump, and must match the
  captured target: the live dimension and canonical entity locator, or the live
  block identity. A block value requires actually loaded state, never a pending
  prediction, and an empty or merely named generic block entity is not evidence
  of content. An optional-mod reader that is missing, fails, or returns no
  usable observation — including an unverified constructor/default value or
  missing synchronization evidence — yields no local value, so the field is
  locally unavailable and falls back per field without breaking
  another field or an unrelated ping. A synchronized entity custom name is a
  direct literal observation, while a name behind a generic block entity
  without synchronization evidence is not a local value for either name field,
  so the unavailable field falls back to the server preview. The plain
  custom-name field is never reconstructed from the composed name value. An
  external block is observed only through a provider-confirmed read source
  ([external-block Basic sampling](#external-block-basic-sampling)); the
  provider-resolved physical block must still pass the same received-chunk,
  pending-prediction and expected-registry gates as an ordinary block, and its
  single safe name observation feeds both name fields under independent demand.
  Preview never materializes a temporary external target.
- **Bounded server fallback.** A fallback request carries its own one-shot
  per-target request identity, created before and independent of any Ping,
  marker or inventory identity; no preview Ping is invented. It is rooted in
  the already-authorized demand as a hint, not a subscription, and the server
  derives actual demand from its own authorization, so the request cannot
  widen authorization. A response is accepted only when correlated to its
  request token, the target identity including dimension, the session epoch
  and the view; a stale or off-view response is rejected, as after an
  interaction abort, a `RESET`, or a target identity change. Server-side, the
  fallback samples with bounded residual work under the existing per-tick work
  and capture budget; it adds no direct unbudgeted ingress read, no continuous
  polling or heartbeat, and no migration onto the inventory route. It reuses the
  established capture helper, so the Basic, create and `SECTION` semantics and
  their global bounds are unchanged and no second capture owner is introduced.
  An external-block candidate is first put through the server's nonallocating
  provider validation and range/type acceptance, and its exact read binding
  including the opaque provider locator must still match before any provider
  source read; that read never materializes, acquires or persists provider
  state, and unconfirmed membership is unavailable rather than approximated.
  An adapter's preview capture is a separate entry from its committed
  collection: a candidate-aware adapter may observe an uncommitted target
  through its safe source, while its committed collection resolves the
  committed binding and is never widened by preview. The same separation
  governs a provider property adapter's preview capability and its committed
  sampling. Generic source access, capture-result and cost
  mechanics remain owned by
  [shared source capture and sync](shared_sources.md), and wire grammar and
  message identity remain owned by
  [network protocol](../network/protocol.md).
- **Selection and optional cache.** Selecting an actual provisional property
  creates the existing typed property-selection intent, and the server still
  recaptures or rejects the whole create atomically; an explicit property
  rejection is never silently retried as a plain create without it. A
  previously received marker value for the exact target may optionally serve as
  a cached preview only with exact identity, fields pruned to the current mask,
  historical-stale provenance, and property annotations stripped; using it is
  not required, and no dominance between the preview forms is established.

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
value and every addressable record entry carries a presence flag and, only when
that flag is true, a Ping Type ID; a false flag decodes to null and means no
explicit property Ping for that entry, with no ID payload written. Annotations
never appear inside a sequence, and a denied top-level field skips its whole
frame including annotations.

An uploaded value is not world authority. The server recaptures the current
authorized world value for every accepted selection from the same authoritative
capture used for projection: a valid but stale client claim is replaced by the
actual value, and a selection whose reference is the wrong kind or unknown, or
whose field or tag is forbidden, or whose exact reference is child-denied, or
whose source is unavailable, rejects the entire create rather than applying
partially. The exact child predicate is checked in the pre-capture intent pass,
before any source is sampled, so a denied reference never reaches a collector.
After acceptance the marker owns the selections and the captured values update
with the live world; a nested path
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
  property the HUD presents first; it is not a selection and never produces a
  content receipt; it does not limit which fields remain available or received,
  and it is not a legacy marker-shape fallback. For entity-block,
  block, and location targets, the `minecraft:target.name` default is consumed by
  the authoritative marker name line rather than added as a second property line.
  The marker name is sent whenever the server authorizes and knows it,
  independently of the default reference and of any non-null property annotation;
  the name is not exempt from the field policy. The HUD presents the default
  non-name reference and the properties carrying an explicit non-null Ping Type
  annotation in a deterministic, de-duplicated order within the existing
  per-provider line cap. A field whose annotation is null is still retained and
  server-projected but is not displayed unless the default reference selects it,
  and presenting count context still requires its parent field to be authorized.
  An annotation on `minecraft:target.name` does not add another name line.

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
each applicable adapter's fields, filtered by the server field policy and the
exact child deny list, so a denied nested reference is not offered for selection
even when its root field is authorized. A Create
RPM property is offered only for the actual machines the adapter can observe,
and a count selection still requires its parent field to be authorized.
Presentation attribute names, default labels, property Ping Type text, and
formatted values use presentation-owned resource keys, not the whole-marker Ping
Type phrase or display keys owned by [catalogs](../identity/catalogs.md) and
[names and chat](../rendering/names_chat.md). Whole-marker names and labels keep
their established meaning, and a property the default reference or a non-null
annotation selects for display is presented to every authorized viewer without
requiring a local selection control. Only an admitted explicit property Ping
selection or an active committed inventory selection produces the content
message family, whose template, wait and fence rules are owned by
[names and chat](../rendering/names_chat.md#content-message-family).

## Receipt content descriptor

The atomic initial carries, beside the canonical marker snapshot and the Basic
section, one per-recipient receipt content descriptor: selection metadata only,
whose referenced content still arrives solely through the existing authorized
stores. Its exact wire grammar and strict decoding are owned by
[network protocol](../network/protocol.md#presentation-snapshot-route-presentation-v5);
the message composition, wait and fence rules are owned by
[names and chat](../rendering/names_chat.md#content-receipt-lifecycle).

Projection uses the recipient's current fresh authorization: the accepted
manifest and schema kind, the advertised mask, the exact child deny predicate,
and the recipient's fresh permission must all include a selected reference, and
the accepted dedicated inventory route view must include the marker's target
type for the inventory kind. A recomputed initial for a replayed or refreshed
marker applies the same current authorization, so a revocation suppresses a
later delivery.

- No admitted non-null property Ping selection — including a create whose
  selections are all null-annotation observations — and no active committed
  inventory tracking yields the whole-marker kind; those null observations stay
  retained and projected as authorized data rather than being discarded.
- Only admitted non-null Ping Type selections enter the explicit reference set:
  the property kind carries exactly the complete set of admitted non-null
  references in their deterministic order, so a create mixing nullable and
  annotated selections contributes that full non-null subset only and a partial
  list is never sent. A null-annotation observation is retained and projected as
  authorized data but is not an explicit reference.
- A marker with an active committed inventory tracking association yields the
  inventory kind, whose values and count arrive only through the dedicated
  inventory store; the kind is derived from that existing tracking association,
  not from a second chat metadata cache.
- For a content kind, any explicit selected reference that is field-denied,
  child-denied, or incompatible, any denied required formatting dependency of a
  selected property (the health pair requires its maximum-health field), or a
  denied Basic target name yields the suppressed kind with no references,
  hiding the whole content message rather than showing a partial list.

Missing authorized data does not suppress the descriptor: a selected reference
that is authorized but whose value has not yet arrived still yields its kind,
and the receipt waits under the names-and-chat lifecycle.

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

The `presentation-v5` and `server-presentation-policy-v3` route IDs do not
decode the previous presentation or policy wire versions, and no fallback
decodes them. Whether any exact legacy registration remains registered as inert
ingress is checked by the source owner; this contract promises no automatic
decode or compatibility shim for a previous wire version.

## Evidence

Existing coverage inventory and remaining integration gaps are owned by
[testing and verification](../../testing/verification.md).
