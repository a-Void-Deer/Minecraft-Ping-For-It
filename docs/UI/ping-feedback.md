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
are not an error. Cancellation and timeout are not equivalent to target-loss
submission: choosing the center cancel action or letting the wheel time out
does not create a marker and does not raise this message.

This local check is a client-side guard; it does not supersede authoritative
server validation.

## Server responses and silent outcomes

A server `TARGET_GONE` rejection shows the invalid-target line only for the
latest **actually dispatched** authoritative create request. Older or unknown
request responses and all other rejection reasons are debug-only. A
courtesy-throttled create is not sent and is not recorded as dispatched, so it
adds no such feedback; the courtesy gate and its dispatch boundary are owned by
[rate policy](../architecture/config/rate-limit.md).

Wheel timeout, cancel with no eligible own marker, stale removal, invalid
removal, and unauthorized removal are silent no-ops or rejections. Their
selection and authorization rules are owned by
[wheel](../architecture/picking/wheel.md) and
[target validation](../architecture/authority/target_validation.md).
Recoverable geometry uses its separate
[source outcome contract](../architecture/geometry/geometry_sources.md); fatal
JVM/resource errors must not be swallowed. These boundaries are established
product boundaries, not evidence of a completed security audit; see
[security](../architecture/security.md) and
[D0004](../decisions/D0004-server-authority.md).

## Presentation

The invalid-target feedback is a local **chat** line. It is not an action-bar
message, an on-screen overlay, or a toast.

The confirmed product color is RGB `#FF5555` (the production raw integer is
`0xFF5555`). The text is currently a hardcoded literal with no localization key.
It is identified by `PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE`, whose
definition can be located in
[`PingInteractionAction.java`](../../common/src/main/java/nx/pingwheel/common/interaction/state/PingInteractionAction.java).
This identifier is named only to locate the temporary constant; the page does
not restate the literal text, invent a translation key, or claim that the
message is localized.

The pending localization task is tracked in
[pending localization](../testing/verification.md#pending-localization).
