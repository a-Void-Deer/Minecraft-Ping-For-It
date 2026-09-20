# Long-press timing

This focused architecture contract owns local long-press timing meanings and
their qualitative relationship. The persisted fields are catalogued in
[client configuration](../../config/client.md#press-wheel-and-cancellation-interaction).

`wheelHoldMillis` defines the elapsed hold required for a sustained press to be
eligible to open the wheel. `longPressCompatibilitySliceMillis` defines the
short adjacency window used only by optional compatibility. The effective slice
is constrained to no more than half of the effective hold threshold. Changing
the threshold revalidates the dependent slice so this relationship remains true.

Sampling the hold threshold during an interaction and determining actual wheel
opening are owned by the
[press-time capture contract](../picking/capture.md#baseline-release-and-actual-wheel-opening).
Timeout behavior is owned by [wheel](../picking/wheel.md);
[long-press compatibility](long-press-compatibility.md) owns the rapid and
deferred sequences. These local settings neither grant server acceptance nor
alter authoritative marker lifetime.
