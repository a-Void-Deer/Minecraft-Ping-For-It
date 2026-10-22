# Double-click Danger

This focused architecture contract owns the client-local repeated short-press
gesture that derives additional ordinary `danger` creates from one short-press
interaction. It does not own press-time capture, target identity, catalog
membership, validation, rate enforcement, marker lifecycle, presentation or
feedback, and it does not define a second marker type. The two preference keys
are catalogued in
[client configuration](../../config/client.md#press-wheel-and-cancellation-interaction),
and the controls that expose them are owned by the
[configuration UI](../../UI/settings-screen.md#input-interaction-controls).

## Ordinary first short press

A first short press is never delayed to detect a repeat. Its release follows the
ordinary [baseline release outcome](../picking/capture.md#baseline-release-and-actual-wheel-opening)
unchanged: an immediate default-Ping-Type create once capture is ready, a wait
for a pending capture, or the same default create after an eligibility threshold
when no wheel actually opened. The first press's own action is never replaced by
`danger`, and no extra double-click wait is inserted before it.

## Adjacent-press qualification

A later physical press participates only when two independent conditions hold:

- its physical down edge is no later than the configured adjacent-press window
  after the preceding qualifying physical down edge, with equality at the window
  boundary still qualifying; and
- its pointer turn remains strictly inside the configured angle tolerance.

The window is compared between adjacent down edges, while the angle is always
measured from the first press of the sequence. Second and later presses can
therefore each qualify on their own adjacency and still derive the same
`danger` intent, so a sequence can continue beyond a single pair. A tolerance at
the angle boundary does not qualify, and a zero tolerance disables the gesture
entirely rather than demanding an exactly unchanged ray. The window and the
tolerance are bounded implementation values; the
[client configuration](../../config/client.md#press-wheel-and-cancellation-interaction)
catalogue owns their keys and meanings, while numeric defaults, bounds and
control steps are not mirrored here.

## Capture, identity and dispatch

A qualifying press's short-release action reuses the sequence's first capture:
its target, ray and resolved Target Type. It dispatches one ordinary `danger`
create carrying a new
interaction token and request identity derived from that stored capture; it does
not cast a second selection ray for the short-press target, so a later ray or
camera motion never retargets the action. Reusing the frozen capture is input
policy only: [capture](../picking/capture.md) retains ownership of the captured
context and of the asynchronous-completion fence, and
[long-press compatibility](long-press-compatibility.md) retains ownership of its
own fresh and deferred presses.

Derivation also depends on the first interaction's default create reaching its
qualifying local dispatch outcome: a first action whose create is rejected before
dispatch, or whose interaction is aborted, discards the sequence instead of
yielding derived creates.

The derived create is otherwise an ordinary action. Each one receives its own
local and server validation, courtesy rate decision, Marker ID assignment,
arrival ordering, expiry, audience, chat and sound feedback, and same-target
winner outcome. It adds no network message shape, no server-side mutation and no
new feedback event. The whole-marker type is `danger`, so the resolved Target
Type's ordered Ping Type set must allow it under
[catalogs](../identity/catalogs.md#target-type-resolution-and-fixed-order); no
other type is substituted, and property or item Ping Type permissions are not
widened.

## Active mouse input

The angle uses the player's active mouse input. Only an ordinary gameplay mouse callback with the
mouse grabbed and the window focused contributes. Each valid sample is converted
to degrees with the vanilla sensitivity in force when that sample is delivered,
without camera smoothing, including vanilla spyglass sensitivity and invert
options. Yaw and pitch keep their direction and combine into one net turn, so an
out-and-back movement cancels. The compared angle is the three-dimensional angle
between the first press ray and the virtual endpoint reached by applying that net
turn to the same ray.

Smoothed camera residuals, camera motion applied directly by another mod, input
while a GUI is open or the selector owns the mouse, an unfocused window, and
vanilla's ignored initial callback contribute nothing. Observing this input
neither consumes nor alters it, and normal camera look continues unchanged.

The gesture therefore depends on the ordinary mouse-handler route. A mod that
fakes cursor positions or bypasses that route is not guaranteed to compose with
it.

## Short release, wheel and pending first capture

A qualifying press's short release dispatches the derived `danger` create. If
that press actually opens a wheel, only that press follows the wheel: the open
selector's hold timing is measured from that press, that press's own ray is
captured under [capture](../picking/capture.md#ordinary-and-asynchronous-capture),
and the sequence does not retroactively change the open selector or replay into
another press. Without an actual open, the ordinary short-tap boundary applies
unchanged.

While the first capture is still pending, qualifying later presses may be
retained within a bounded store (a memory bound, not a product tap-count cap)
and submitted one at a time after the first interaction's default create
reaches its qualifying local dispatch outcome.

A later press that does not match is not deferred by this gesture: it returns to
the ordinary fresh-press path. If that fresh press supersedes the first
interaction, the retained dependent `danger` presses become stale with it and
are discarded. [Capture](../picking/capture.md#ordinary-and-asynchronous-capture)
owns ordinary token replacement and pending ownership; this gesture adds no
deferred press.

A first interaction that is aborted, or whose create is not handed to the
sender, clears those dependent presses instead of submitting them. Submission
does not wait for server acknowledgement, and a late server rejection of the
first create does not roll back a later submitted create. This is not a
multi-target queue: it holds only the dependent repeated presses of one
sequence, and the existing fresh/deferred ownership remains in force.

## Compatibility and input priority

Long-press compatibility and this gesture are mutually exclusive. The
[mode owner](long-press-compatibility.md#observed-mode-and-slice) determines
when the gesture is unavailable and how its recognition state is cleared, and
keeps the abort behavior of an observed mode change. The persisted angle is
held at its disabled zero value while compatibility is enabled; that persistence
relationship is owned by
[client configuration](../../config/client.md#press-wheel-and-cancellation-interaction)
and [configuration revisioning](../config/revisioning.md#same-version-shape-normalization).

Interaction lifecycle aborts still take precedence over every sequence state.
While an [inventory selection stay-open](../picking/wheel.md#inventory-selection-stay-open)
session is active, it owns subsequent presses instead of this gesture.

## Verification boundary

Source-reviewed coverage and known gaps are inventoried in
[interaction verification](../../testing/verification/interaction.md#double-click-danger-and-inventory-stay-open);
that inventory is not exhaustive. Configuration evidence is inventoried under
[configuration verification](../../testing/verification/configuration-ui.md#double-click-danger-and-inventory-stay-open-configuration).
Live scenarios remain pending in the
[manual and integration matrix](../../testing/verification/manual-integration.md#pending-manual-and-integration-matrix).
