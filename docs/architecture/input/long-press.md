# Long-press timing

This focused architecture contract owns local long-press and optional
gesture-opening timing meanings and their qualitative relationship. The
persisted fields are catalogued in
[client configuration](../../config/client.md).

## Ordinary long press

`wheelHoldMillis` defines the elapsed hold required for a sustained press to be
eligible to open the wheel without requiring pointer movement.
`longPressCompatibilitySliceMillis` defines the
short adjacency window used only by optional compatibility. The effective slice
is constrained to no more than half of the effective hold threshold. Changing
the threshold revalidates the dependent slice so this relationship remains true.

## Optional physical-hold gesture

The opt-in gesture shortcut offers an alternative to waiting for the ordinary
long-press threshold. It follows the player's rebindable Ping key, not a fixed
keyboard key or mouse button, and requires a continuous physical hold. Its
minimum wait is measured from that press; compatibility's virtual-held time
does not qualify. The preference and its relationship with compatibility mode
are owned by
[client configuration](../../config/client.md#spatial-selector-interaction).

Gesture eligibility requires both the frozen minimum wait to have elapsed,
including equality, and the current GUI-scaled net displacement from the
press-position baseline to be strictly greater than the frozen center deadzone.
Total path length and a historical excursion outside the deadzone are not
substitutes for current displacement. Crossing first still waits for the
minimum time, provided the pointer remains outside; crossing after that time
also qualifies. A held present frame may recognize eligibility without another
move callback. The ordinary long press remains an independent alternative:
neither insufficient movement nor an unfulfilled gesture wait prevents it.
This alternative eligibility is available only while the shortcut remains
active under [wheel's trajectory resource contract](../picking/wheel.md#pre-open-trajectory-and-resource-fallback).

## Actual-open boundary

Sampling the hold settings and determining actual wheel opening are owned by the
[press-time capture contract](../picking/capture.md#baseline-release-and-actual-wheel-opening).
Pre-open motion and the native input handoff are owned by
[wheel](../picking/wheel.md#mouse-ownership).
The opened selector's lifetime and selection behavior are owned by
[wheel](../picking/wheel.md#opening-and-selection);
[long-press compatibility](long-press-compatibility.md) owns the rapid and
deferred sequences. These local settings neither grant server acceptance nor
alter authoritative marker lifetime.
