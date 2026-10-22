# Rendering verification

This page inventories name, geometry, selector-overlay, render-lookup and
native-outline coverage and its known automated gaps. Read the
[verification guide](README.md) for the shared evidence and reporting rules.
Pending gameplay checks remain in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Name and geometry outcomes

The current suite covers:

- wheel sector colors;
- entity-block source modes and per-attempt `RENDERED`/`EMPTY`/`FAILED`
  outcomes, including fallback consequences;
- behavioral native-edge rendering.

`TargetNameComposerTest` covers custom color/italic stripping and base-component
identity. Its event-named case does not construct click or hover event fixtures,
so it is not event-specific regression coverage. `TargetNamePlainTextTest`
covers the plain-text reduction's UTF-8 byte bound: every cut keeps whole
supplementary code points and never turns a truncated surrogate into a
replacement byte, and the presentation text bound admits the maximum whole
emoji prefix without dropping literal punctuation or adding base text.

`ClientTargetNameDecoderTest` covers valid component decoding, malformed JSON
falling back to the unknown translatable name, and valid input emitting no
diagnostic. Its injected synchronous logger receives one bounded report with
the marker ID, exception details and decode source frame, without an additional
throwable argument. This callback fixture does not exercise production logger
admission or the no-op formatting bypass, and does not infer custom callbacks'
internal DEBUG enablement. Shared production-helper evidence is inventoried in
[shared exception diagnostics](integrations.md#shared-exception-diagnostics).

## Selector overlay layout and frame rendering

`SpatialOverlayRendererTimingTest` covers renderer timing models.
`SpatialOverlayTransitionsTest`,
`SpatialInventoryLayoutTest`, `SpatialOverlayRendererStyleTest`,
`SpatialOverlayRendererSectorTest`, `SpatialOverlayRendererBorderTest` and
`SpatialSquareProgressTest` cover the CPU
overlay models: appearance,
exit, reappearance, retargeting, reduced motion, rewind and bounded churn, including a synchronized non-input
relayout that commits Cartesian anchors together while scale and both coverage
channels keep easing, and a shared geometry rebase that keeps detached paint
and the current follower on one center without advancing time; row
layout and coordinate conversion with status separate from rows and unknown
counts; caller style overrides, legacy font scaling, alpha arithmetic, the
independent wheel-underlay and target-frame opacity split, the per-layer gate
that keeps only the transition-faded chrome when both opacity preferences are
zero and drops every underlay or text-bearing frame, and the Back progress path
tracing the frame's actually painted pixel extents and never leaving them;
half-scale child-orbit bounds, the non-root submenu-radius multiplier with the
root distance and legacy forms unchanged, and selected offsets; the square
progress walk's last-painted extents and the inventory view's immutable
back-affordance state; and sector scanline geometry, adjacent-sector tiling,
shared disabled/reserved alpha, and paint ordering;
`SpatialOverlayRendererGuideTest` covers the radial center guides headlessly:
one divider per true shared boundary, each emitted exactly once, across the
root's unequal spans, equal spans including Back, and the pair wrapping past
zero, while a single full disc or a gap between neighbors gains no phantom
divider; the closed deadzone circle as a clockwise polyline at the frozen
radius, with a non-positive or non-finite radius yielding none; non-default
frozen deadzones reaching that geometry through the controller read model;
guides placed at each menu's own origin under the rigid translation,
with an ancestor menu's guides dimming with that menu and a child menu's guides
on the child origin and a radially entered child records the radial entry
that opened it; and guide alpha following only the menu's own transition
fade, independent of both opacity preferences and of focus, selection or
release, with reduced motion and the exit tail handled by the shared transition
model, painted after the sector underlay and before the inventory panels. Every
endpoint is checked against geometry computed independently in the test; no
painted stroke, GuiGraphics or GPU submission is claimed.
`SpatialOverlayRendererFocusScaleTest` drives the production frame path through
recording paint and font-metrics ports: an eligible focused action or branch
selects the focused emphasis and sibling de-emphasis scales through the
existing finite interval from the displayed resting pose, a new follower starts
within its appearance fade, Back, disabled, reserved and deadzone focus keep the
established scales with no emphasis mode, an actionless branch focuses the same
way, reduced motion snaps, and inventory-list rows keep their own scale.
`SpatialOverlayTransitionsBackgroundTest` and
`SpatialOverlayRendererBackgroundTest` cover the independent
background-coverage channel: historical constructors delegate the channel from
the foreground alpha, bounds validate as a normalized channel, appearance
starts at zero coverage on its own finite interval, retargeting eases from the
displayed coverage and a separate alpha edit never restarts it, exit fades to
zero while detached data keeps its deadline and reappearance resumes from the
displayed coverage, reduced motion snaps and drops the exit tail, rewind cannot
rewind the displayed coverage, resting coverage uses the fractional base
coverages, eligible radial focus applies the confirmed sector and box gains to
the focused entry and its siblings, radial Back, disabled, reserved and deadzone focus
keep every resting gain, ancestor levels apply structural dimming and omit the
ancestor breadcrumb sector without repeating the foreground unselected factor,
the final byte rounds exactly once, each opacity knob reaches only its own
background layer, background-only paint is admitted but never acknowledges,
frame backgrounds fade with their own channel through a detached exit, the
active list and an empty active list emphasize their panel and header, the
selected row emphasizes only while direct release is committable with no Back
focus, retained-row filtering keeps only the entered item row while the
panel/window model stays complete and detached sibling tails are suppressed
before the return restores the window, a selection change draws the old
overlay's fade and the new overlay's growth without ever filling a
never-selected row, and the row-overlay draw honors reduced motion, opacity
zero and quantized zero.

`RadialGradientTest` records the production vertex emitter with independent
geometry and alpha expectations: center coverage and unchanged RGB, an outward
fade with soft shoulders and a transparent rim, fractional coverage and opacity
until final-byte rounding, clockwise sector and Back-half orientation, exact
shared edges for unequal and wrapped sectors, full-disc winding and
non-overlapping topology, bounded radius-independent work, and inert invalid or
quantized-zero inputs. The radial-route cases in
`SpatialOverlayRendererBackgroundTest` connect production frame planning to
that emitter through recording ports: root, submenu, ancestor Back, inventory
Back halves, item menus and detached exit paint use the same underlay route;
rectangular surfaces and foreground stay separate; wheel and target opacity
retain their independent scopes; background-peak admission is independent of
foreground alpha; and one underlay batch is bracketed before later layers.
These are source-reviewed emitter and recording-painter fixtures, not a
recorded test run, native GUI batching or GPU submission evidence.

`SpatialInventoryLayoutTest` covers content-driven panel measurement before the
viewport cap: detached row names, icon slots, full-sized quantities, status,
position headers and Forward chrome contribute their natural width, with all
bounded discovered rows measured independently of selection and the visible
window. Empty or short content does not fill the maximum. Header, footer and
whole-row capacity share the current GUI budget; a tiny GUI retains readable
metrics and a whole row rather than shrinking the pattern, and a retained
item-row anchor survives capacity and font relayout. Explicit-fraction fixtures
check independent width and whole-row capacity caps, finite off-grid caller
values without quantization, unchanged text metrics, viewport remeasurement
with the same fractions, rejection of non-positive or non-finite fractions in
both measurement forms, and compatibility overloads delegating to model
initialization. Exact-decimal budget fixtures use independent literal geometry
to check the whole-pixel width boundary and the last fitting complete row;
adjacent smaller and larger fraction or viewport inputs neither borrow boundary
geometry nor admit an extra pixel or row. Maximum-capacity assertions require
the measured panel to fit the independent budget while one more whole row
exceeds it. `SpatialOverlayRendererStyleTest` checks model-default delegation
for a previous style form, custom fractions through the explicit legacy-font
factory without changing supplied font, submenu or trail values, and rejection
of non-positive or non-finite fractions in either dimension. These are
source-reviewed layout and style fixtures, not recorded test runs.
The detached-display case in `SpatialInventoryRowDisplayTest` checks that
measurement and painting consume
the same transmitted name and owned icon rather than a short fallback label.
`SpatialInventoryTextTest` covers the production clipping plan for long
quantities, header/status text, Forward and tiny slots: configured scale stays
fixed, fitting text stays whole, and an ellipsis must itself fit the available
slot. These are layout, display-projection and clipping-plan seams, not native
text or scissor submission evidence.

The mirrored-side cases in `SpatialInventoryBackTest` record a complete Back
title frame outside the panel's actual parent-facing edge at its vertical
midpoint, its outward semicircle, and progress along the measured rectangular
frame's painted extents. Focus enhances only the underlay coverage, without
changing the title's geometry, scale or own background channel; wheel opacity
reaches the underlay independently, and navigation paint acknowledges no
fabricated choice. These are production-frame recording and perimeter-helper
checks, not native window or graphics submission evidence.

`SpatialOverlayRendererAncestorBreadcrumbTest` drives the production radial
planning and frame seams through recording paint and font-metrics ports: each
ancestor of the active path plans only its entry breadcrumb and Back
navigation, with its other option frames and sectors, including disabled and
reserved siblings, filtered from planning; each retained breadcrumb frame and
its entry/exit
anchor sit at that ancestor's own origin with no pointer ray, and the
breadcrumb's own sector is omitted while the ancestor Back keeps its frame and
sector; a lone breadcrumb contributes no
hidden divider while the defined centre guide stays; entering fades hidden
siblings through the ordinary exit and returning fades them back in, reduced
motion snaps both ways, a disabled sibling's frame and sector settle hidden and
restore with their disabled styling, including a mid-fade restore, a hidden
ancestor never acknowledges and a breadcrumb is never an active
acknowledgement, and the logical stack and selection geometry are never
mutated.
`SpatialInventoryClearanceTest` covers the item-menu clearance metric and
paint geometry: the pinned viewport uses the independent maximum of the
outer-sector coverage bound and the measured toward-panel Back frame bound
including the selected push, a short label stays at the sector bound while a
long localized label widens the distance, the frame follows the option text
scale and never the inventory scale, a taller font line grows the measured
extent, intermediate resize dimensions stay finite and never shrink, the full
Back frame stays clear of the panel in both orientations, actual first, mid and
settled paint keeps the sector and measured Back frame disjoint through
enlarge and shrink with the first frame committing the new panel-to-menu
clearance, the follower ray, guide, connector and actual title acknowledgement
stay on one geometry, a longer Back label at the same viewport relayouts the
whole pattern before the new frame paints, and a resize rebases a different
prior list's detached panel with the same pattern instead of leaving its old
GUI center. That mirrored-side fixture uses custom frozen fractions and a long
row, asserts the detached panel's newly measured width rather than a default
cap at the first frame, and checks first, intermediate and settled clearance;
invalid inputs are rejected. The content-shrink cases in
`SpatialInventoryNavigationIntegrationTest` exercise a quantity reduction and
new-row discovery together while an item menu is active, in both mirrored
orientations with normal motion. First, intermediate and settled frames check
every actually painted present or detached panel against the full child-sector
bounds and complete Back title frame, require nonempty panel and sector paints,
and confirm that the settled surface uses the smaller shared content width.
These are recording-painter geometry checks, not native rendering evidence.
`SpatialInventoryNavigationIntegrationTest` checks the production active-list
chrome through its recording painter: list entry exposes the moving virtual
crosshair, including while side intent suppresses vertical selection. Its
custom-fraction cases compare the session's logical metrics with recorded panel
width, height and whole-row capacity, and keep the complete exterior Back frame
outside that panel. A direct list release followed by resize records the actual
detached panel at its newly measured dimensions, only the rows within the new
capacity, and a still-visible finite exit. These are source-reviewed
recording-painter fixtures, not recorded test runs, native GUI-scale callbacks,
GLFW or GPU evidence. Its selection and item-return coverage is inventoried in
[interaction verification](interaction.md#inventory-list-gestures-and-navigation).
`SpatialOverlayRendererFollowerTest` additionally checks active-list crosshair
visibility with both frame opacity preferences at zero, including a return from
an eligible item follower. The same case keeps the item Back/deadzone pointer
visible, allows an eligible visible item follower to hide it, restores the
parent radial deadzone feedback on leaving the list, and checks an inactive
exit after that parent frame without connectors or choice acknowledgement.
Across moving follower poses, a connector is recorded only when painted frames
and previews do not already cover the remaining circle-to-title segment; an
independent segment-over-rectangle oracle over the recorded poses and layouts. These are recording-painter frame checks;
they do not establish the complete animated crosshair handoff or crosshair/trail
behavior during a direct active-list detached exit. The panel and row
remeasurement fixture above does not establish those feedback paths. The
[wheel owner](../../architecture/picking/wheel.md#inventory-list)
defines the list/radial presentation handoff; recording paint does not establish
native cursor visibility, `GuiGraphics` or GPU submission.
`SpatialVisualTrailTest` covers the production shared-history model headlessly:
one bounded history serves both the logical pointer and the certified
follower; native raw pointer batches are consumed once and follower-era samples
never replay on return; an ownership change or an interrupted owner starts from
the last displayed endpoint through a finite handoff that reaches the current
target, while a settled owner samples its target without a second lag filter; a
projection correction fences old points while keeping post-correction callbacks
and adds no stroke, an explicit rebase starts fresh by identity even at
coincident coordinates, and a rigid pan alone appends nothing; reduced motion, a
hard reset, a quick reopen, a rewound clock, and rapid retargeting stay bounded;
and a missing departing paint freezes the history while `clear` empties it.
`SpatialOverlayRendererTrailTest` drives the actual production paint/commit
order with independently recorded node centres: the painted payload is an
immutable history taken before this frame's node paints and committed after
them, painted above the sector underlay and guides and below every
text-bearing paint, so the history committed after this frame's paints enters
the next frame's payload, with handoff endpoints easing toward the certified
painted centre; only a successful paint of the actual animated pose with
a visible title frame admits a follower sample, while a failed paint, a
disabled or Back focus, an omitted active node, or an actual offscreen pose
cannot certify a predicted owner or borrow a detached duplicate; an inventory
list hides every trail paint including a prior tail and its rows never own it;
moving the shared list pointer records no trail samples, and system alignment
fences the prior tail without recording its seed. A list-to-item rebase is not
a joining stroke, and the item radial keeps the shared history below text
under the ordinary crosshair rule; a fractional
projection change or
rigid pan appends nothing, and trail visibility and its independent opacity
reach only the trail; an ordinary release fades with the exact departing
follower key without acknowledgement, connectors, or a snap to the origin, and
a new hold or reset discards the history; the retained bound holds under rapid
retargeting without changing paint admission; and overlapping rounded-square
pixels take maximum alpha with no duplicate coverage. An interrupted
pointer-to-follower or follower-to-follower handoff survives an ordinary
same-clock release and continues its original finite interval toward that
departing key's moving painted pose, never the outgoing duplicate's, while a
missing departing paint retains the displayed history and a later paint resumes
the same interval without retiming; only reduced motion, a hard reset, or a new
hold clears it. The release frame still paints the previously committed
immutable history, and a GUI resize during the exit tail reprojects from the
actual detached trail origin, so the committed endpoint matches the recorded
painted pose instead of being displaced by the new GUI center. These are
production-frame recording-painter and CPU-model seams, not `GuiGraphics`, GPU,
GLFW-window, or multiplayer evidence.
`SpatialOverlayRendererBorderTest` projects the real facade's typed border
color in both focus states, keeps the untyped and legacy `ping:` action
fallbacks, verifies the cancellation action's focus-independent color, and
retains the detached color through the exit transition.
`SpatialInventoryBorderTest` drives the production list planner and row-fill
seam: only the selected committable row carries a complete annotation-colored
rectangle, including with grey data quality. An unselected row retains its
annotation capability in the row data but paints neither that border nor an
added fill. Back focus, a retained item row and a noncommittable selected row
suppress direct-send decoration. `SpatialInventoryNavigationIntegrationTest`
drives native-normalized input through the real session and recorded production
frame: selection changes within one visible window move the border to the
current item key, and a scroll-window jump leaves the old selected row fading
without an outline. Ordinary close retains the current row's border through its
exit fade while the old row continues its original fade without regaining an
outline. Reselecting a departing row restores its authorized border on that
current key alone. These are headless input and recording-painter seams, not
live window or GPU evidence.
`SpatialSelectorSessionTest` checks distinct non-grey local-action colors
without inventing Ping actions, item annotation colors independently of the
whole-marker default, missing Attention authorization under content-intent OFF,
and live annotation-policy changes without changing selection.

`SpatialLabelAvoidanceTest` covers bounded vertical placement, stable shallow
collision resolution, fixed focus, resistance to small pointer jitter,
sector-preserving unsolvable crowds and reset. `SpatialOverlayReadabilityTest`
uses the production frame path with explicit font metrics: an English shallow
overlap separates while text dimensions and controller state remain unchanged;
the current focused node paints last among text-bearing nodes and detached old
focus does not retain that priority; movement follows the finite transition and
reset discards placement memory; connector segment subtraction leaves title
rectangles uncovered; and all bundled locales keep full single-line text,
horizontal anchors, bounded vertical offsets and sector membership across
viewport sizes. The existing follower, Precise, breadcrumb and inventory
clearance suites retain their own geometry coverage. These are CPU layout,
recording-painter and fill-seam checks, not evidence of native GUI batching,
resource-pack font readability or visual comfort during live input.
`SpatialViewOffsetTest` and
`SpatialViewOffsetProjectionTest` cover the selector's single rigid view
translation: exact endpoints, continuous re-aiming, an unchanged origin never
restarting its interval, reduced-motion snapping, an inert rewound clock and a
hard clear, and, driven through the real headless controller and selector
session, every active menu origin — root, pushed child, nested child and Back
return — projecting onto the GUI center while pointer, trail and menu vectors
stay rigid; the open inventory list centers on its active origin and an item
submenu keeps the selected row's logical anchor under the same translation; and
an inactive frame freezes the last displayed exit translation that a quick
reopen re-aims from. The interaction frame publishes that projection once, and
confinement, paint, release and resize read it unchanged: an intermediate pan
sample confines the pointer and the displayed pointer center stays within the
inclusive GUI pixel bounds even at fractional offsets; paint neither resamples
the pan nor mutates interaction; navigation inside the frame re-aims from its
single sample and snaps under reduced motion; a resize reuses the last
published offset; and the pointer chrome follows the published center rather
than a stale interpolated GUI center, while an exit-transition chrome state
keeps its detached paint geometry.
`SpatialOverlayRendererNodeLayoutTest` drives real controller snapshots through
the production radial planning, node layout, bounded label avoidance and frame
recording. Every title frame measures its complete single-line label at the
configured title scale and the production frame receives that complete label;
an external candidate preview consumes only the finite preview cell, so it
never resizes, shrinks or clips the title frame it is previewed beneath, and a
title or target font preference stays independent while its row has room.
Solvable fixtures keep the recorded frames and previews separated using actual
rounded bounds, including the disabled border's inclusive endpoint.
Budget-exceeding fixtures spanning multiple viewports, enlarged option text,
mixed or fully available menus, submenu-radius tiers and full rotation sweeps
in both language fixtures record complete localized titles, the configured
title scale, unchanged horizontal anchors and bounded vertical offsets whose
centers stay in their original sectors, while selected, focused and following
poses are not moved by avoidance. Those budget-exceeding cases accept residual
overlap rather than shrinking, clipping or unbounded spreading.

The preview-specific checks preserve the name's visible cap, whole-code-point
and style handling, including supplementary code points split across component
segments and legacy formatting that resolves to vanilla's painted order and
styles; a formatting-only detail allocates no area, and capping never reads the
overflow tail. A name within its visible cap is shown whole and wrapped in the
preview cell rather than cut to the title frame's width, while an over-cap name
keeps its retained visible text plus one ellipsis. The centered preview lines
paint outside and below the frame at the readable floor; a larger title font
preference can only reduce their clearance and scale down to that floor without
changing their visible text. The smallest viewport with large fonts keeps
complete titles and nonblank preview, Back and disabled-type text, and
Back-hover progress keeps tracing the title frame when a preview is supplied.
The painted preview retains every capped glyph, the preview budget never
changes the title's configured scale or complete text, and the preview adds no
opacity or admission layer while an exit transition retains its detached
component and its painted preview. An overfull glyph-width stress case keeps
the readable floor and applies no second truncation; arbitrary resource-pack
font metrics remain a gap. A selected or following node intentionally leaves
the resting layout, so these resting collision checks do not apply to its
moving frame or preview. These are CPU layout and production-frame
recording-painter checks: no `GuiGraphics`, GPU, native callback, gameplay frame
or manual visual validation is involved.

Tracking-line projection and its provider/runtime limitations are inventoried in
[inventory verification](inventory.md#inventory-preview-and-tracking-foundation).

## Render entity lookup and locator resolver seams

`RenderEntityLookupCacheTest` and focused locator-resolver unit seams cover the
render-only lookup algorithm and locator resolver boundary. The cache tests
exercise idle passes without scans; distinct,
repeated-hit, and miss UUID requests sharing one cold index; first-valid
duplicate selection; simulated HUD/outline warm reuse across passes; a different
world object with the same dimension; removed or unloaded entries; runtime-ID
reuse and UUID mutation; same-UUID replacement and a spawn after index creation
becoming visible in a later pass; an invalid warm entry causing one scan and
index build; pruning without touching an old entry during validation; clear/null-frame
handling; and null-world and null-UUID safety.

`EntityOutlineLocatorResolverTest` covers UUID and XP locators, runtime
non-orb rejection, gone/null/mismatched entities, map lookup, and the raw-cache
entity-mismatch seam. Neither this cache test nor these resolver tests are a
dragon-renderer test or establish canonicalization integration beyond their
stated seams. The cache fakes and access counters establish lookup-algorithm
behavior only: they are not actual Minecraft frame hooks, HUD callers, or
renderers, and they do not automatically cover the fresh non-render lookup
bypass. Source inspection remains useful for those call-site boundaries but is
not automated runtime coverage.

## Focused native block-outline regression coverage

The native VoxelShape route requires complementary checks rather than one broad
"outline works" assertion:

1. Production render-state coverage pins `BlockOutlineRenderType` to
   `VertexFormat.Mode.LINES`, vanilla `rendertype_lines`, a fixed width wider
   than vanilla selection lines,
   `NO_DEPTH_TEST`/`GL_ALWAYS`, color-only writes and late composite
   submission.
2. Native geometry coverage pins the live
   `BlockState#getShape`-to-`VoxelShape#forAllEdges` edge route so a full-cube,
   polygon or shape-equivalent substitute cannot satisfy the regression.
3. Render-frame snapshot coverage separately exercises late submission and
   frame ownership.

Native-glow whitelist/eligibility coverage is separate from these VoxelShape
checks. Passing either side alone does not prove the other, and structural or
behavioral tests do not by themselves prove actual visibility through occluders
or from arbitrary in-game camera angles.

## Known automated gaps

- continuous list/item crosshair handoff and crosshair/trail feedback during a
  direct active-list detached exit still need dedicated recording-frame coverage
  aligned with the [list presentation contract](../../architecture/picking/wheel.md#inventory-list);
  discrete active-list, zero-opacity return, item/radial and post-parent exit
  assertions do not cover those feedback paths; the direct-exit panel and row
  remeasurement fixture does not inspect that feedback;
- repeated-ping HUD compositing for one target: existing tests cover winner
  selection and independent visual lifetimes, but no test checks whether several
  display-active same-target records change the displayed HUD alpha; the
  invariant is owned by
  [client marker state](../../architecture/markers/client-state.md#winner-slots-are-not-the-render-marker-collection);
- the local `ModelBlockRendererMixin` position-seed guard:
  `@ModifyConstant(method = "*", require = 2)` counts the minimum number of
  matching class-wide `42L` constants, including an unused local, so it does not
  prove that both `RandomSource#setSeed` call sites remain covered; this is a
  coverage/guard limitation, not a product behavior change.

Render-entity lookup gaps remain for same-dimension world unload/rejoin and
runtime-ID reuse in a game session, shared epochs between real HUD and outline
callers, and integration proof that non-render callers bypass render-path cache
results. No automated evidence currently establishes CPU frame cost or
allocation behavior for one, ten, or fifty entity marks in a dense world at high
frame rates.
