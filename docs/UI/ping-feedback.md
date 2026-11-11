# Ping interaction feedback

This topic owns the local feedback a player receives while committing a ping or
when the server rejects an authoritative create. Rejection outcomes themselves
are owned by [target validation](../architecture/authority/target_validation.md);
new-marker receipt sound/chat and receipt de-duplication remain owned by
[names and chat](../architecture/rendering/names_chat.md).

## Local pre-commit target loss

When the captured target is invalid before commit, the interaction creates no
marker and immediately shows the local player the invalid-target chat line.

- For a short press, this is the point at which the press would commit its
  create.
- For the wheel, this is the point at which a chosen sector would commit its
  create.

A missing, dead, or cross-dimension entity, or a block replaced by a different
block type at commit, triggers this local error. Same-type block-state changes
are not an error. Cancellation is not equivalent to target-loss submission:
activating the wheel's downward
[Cancel Marker action](../architecture/picking/wheel.md#cancel-marker-selection)
does not create a marker and does not raise this message.

This local check is a client-side guard; it does not supersede authoritative
server validation.

## Server responses and silent outcomes

A server `TARGET_GONE` rejection shows the invalid-target line only for the
latest **actually dispatched** authoritative create request. Older or unknown
request responses and all other rejection reasons are debug-only. A
courtesy-throttled create is not sent and is not recorded as dispatched, so it
cannot produce that server-response line; its separate local notice is
[below](#courtesy-cooldown-notice). The courtesy gate and its dispatch boundary
are owned by [rate policy](../architecture/config/rate-limit.md).

Cancel with no eligible own marker, stale removal, invalid removal, and
unauthorized removal are silent no-ops or rejections. Their
selection and authorization rules are owned by
[wheel](../architecture/picking/wheel.md) and
[target validation](../architecture/authority/target_validation.md).

## Courtesy cooldown notice

A committed ordinary create that the client courtesy gate rejects shows one
local cooldown notice for that attempt. The gate drops the commit; its send,
dispatch-tracking, queueing and retry outcomes are owned by
[rate policy](../architecture/config/rate-limit.md#create-only-dispatch-boundary).

The notice uses the existing `[ping for it] ` prefix and the shared `#FF5555`
local failure color. Its English message is `Ping is on cooldown...`. It follows
the configured local placement; the `cooldownFeedback` field, its legal values,
and its `null` or unknown-value fallback are catalogued in
[client configuration](../config/client.md#persisted-field-catalogue).
`HIDDEN` suppresses only this notice.

The inventory tracking-creation failure line remains separate and is not
affected by this preference; that line and its trigger are owned by
[inventory tracking creation failure](#inventory-tracking-creation-failure).

The dropped commit keeps its existing `DEBUG` `reason=courtesy-throttled`
diagnostic with `sent=false`; reporting admission and disclosure are owned by
[diagnostics and logging](../architecture/logging.md).

## Inventory tracking creation failure

An inventory selection that cannot create its tracking target shows one
dedicated local chat line. The line uses the literal `[pingforit] ` marker from
its own language template, followed by the localized create-failure text and
the localized cause, and it uses the same `#FF5555` color as the other local
failure feedback. It does not use the ordinary `[ping for it] ` prefix.

Exactly one line is shown for an attempted selection or creation, including a
typed server rejection and a local pre-dispatch, courtesy or transport refusal.
A duplicate or replayed outcome does not repeat the line, and no attempt is
automatically resent. The creation outcome, cause vocabulary and no-retry
boundary are owned by [inventory](../architecture/presentation/inventory.md#selection-outcome-and-tracking-creation-failure);
this section owns its local presentation.

A creation that stored its marker but later failed to publish is a committed
creation, not a creation failure, and does not show this line. The presentation
`CREATE` rejection rules above remain separate.

## Presentation

The invalid-target feedback appears in the local player's **chat**.

The confirmed product color is RGB `#FF5555` (the production raw integer is
`0xFF5555`).

Its visible text begins with the exact marker `[ping for it]`, and that
marker is also supplied as a language resource.

