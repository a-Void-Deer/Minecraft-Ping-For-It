# Rendering verification

This page inventories name, geometry, selector-overlay, render-lookup and
native-outline coverage and its known automated gaps. Read the
[verification guide](README.md) for the shared evidence and reporting rules.
Pending gameplay checks remain in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Name and geometry outcomes

The name and geometry suites cover wheel sector colors; entity-block source modes
and per-attempt `RENDERED`/`EMPTY`/`FAILED` outcomes with fallback consequences;
and behavioral native-edge rendering. `TargetNameComposerTest` covers custom
color/italic stripping and base-component identity; its event-named case does not
construct click or hover fixtures, so it is not event-specific regression
coverage. `TargetNamePlainTextTest` covers the UTF-8 byte bound: every cut keeps
whole supplementary code points, never turns a truncated surrogate into a
replacement byte, and the presentation bound admits the maximum whole emoji
prefix without dropping punctuation or adding base text.

`ClientTargetNameDecoderTest` covers valid decoding, malformed JSON falling back
to the unknown translatable name, and valid input emitting no diagnostic. Its
injected synchronous logger receives one bounded marker-ID report with exception
details and the decode source frame, without an additional throwable argument.
This callback fixture does not exercise production logger admission or the no-op
formatting bypass. Shared production-helper evidence is inventoried in
[shared exception diagnostics](integrations.md#shared-exception-diagnostics).

`TargetNameDiagnosticsTest` covers the injected client entity-name lookup seam:
expected absence returns the unknown name without a warning; a throwing lookup
keeps that fallback, consumes the getter once and emits one content-free `NORMAL`
warning. Higher disclosure retains the original message and cause without an
attached throwable; the server-side helper has the same `NORMAL` summary. These
cases do not exercise real client-world getters, block or external lookup,
server name encoding or the decoder's production logger admission.

`OutlineDiagnosticsTest` covers runner and registry seams: throwing model claims
allow the next adapter and a later retry; null outcomes and throwing observers
retain the successful sibling with independent once-only warnings; a failed
source ID or rejected duplicate cannot displace an accepted registration.
`NORMAL` reports carry finite category and handling codes; source attempts and
null outcomes are `ERROR`, while context, observer, probe and registration
failures are `WARN`. The source-render report states fallback eligibility without
claiming an applied fallback. A world-aware failure keeps its once key on the
real block registry identity and reuses the registration-validated adapter ID for
admitted detail. Higher disclosure retains the original cause in one bounded
string; a throwing sink consumes only the report opportunity, and log-level
changes do not reset an emitted warning. `BlockPresentationResolverTest` also
checks cause-bearing warnings while a failed resolver leaves later resolution or
the ordinary direct subject usable. These are headless source, resolver and
logging seams, not render-frame or GPU execution. Optional Flywheel and Create
diagnostic seams are inventoried under
[optional rendering coverage](integrations.md#optional-rendering-structural-coverage).

## Container presentation subjects

`VanillaChestPresentationTest` exercises production loaded-state adapters and the
common resolver for normal/trapped chests, every horizontal facing and either
hit. LEFT/RIGHT ordering preserves type and physical source; malformed, missing
or single pairs fall back to the valid direct hit. An unavailable or
registry-replaced source yields no subject without inventory reads or chunk
loading. Native frame preparation and the metadata port use the same resolver;
normal sources and late fallback consume one ordered snapshot, and geometry from
one half leaves the other eligible for fallback. These are headless
resolution/frame seams, not actual chest BER or GPU output.

NeoForge Vault registration, archive-backed metadata and complete per-cell
snapshot/fallback evidence are inventoried under
[Create topology verification](integrations.md#vault-topology-grouping-and-presentation).
The [presentation contract](../../architecture/rendering/presentation_subjects.md#vanilla-chests)
owns source ordering and coverage; compilation and these fixtures do not verify
live synchronization, installed models or whole-structure outline appearance.

`BlockPresentationContextTest` covers loaded-state source gates for known
containers without ordinary block-entity lookup, and an unavailable or replaced
tank source stopping before optional resolution or direct replacement rendering.
Tank shell-subject dispatch, shared frame snapshot and exact native-edge fallback
assertions are inventoried under
[tank topology verification](integrations.md#fluid-tank-topology-grouping-and-presentation).

## Base-fluid icon presentation

`SpatialFluidIconRendererTest` records production draw dispatch without a GPU: a
fluid whose legacy block is air still uses its native still sprite; null, missing,
wrong-atlas and throwing native routes try a non-air legacy particle sprite once
before text-only fallback. Ordinary unusable or absent sprites stay quiet; a
native failure with a legacy replacement is a `WARN` legacy-sprite handling,
terminal text-only resolution has its own `WARN`, and a failed GPU submission is
`ERROR` with separate draw-failed handling. `NORMAL` summaries stay content-free
while `DEEP` retains both original causes without mutating them; suppressed or
disabled reports read neither again. Production discovery and native-port
failures cross `FluidSprites.lookupStillSprite` unchanged and are reported only
after the renderer selects a fallback; the compatibility `stillSprite` entry
returns null after exactly its own lookup, absence stays null, and only a
successful service is cached. Recorded draws preserve alpha/tint composition;
replaced supplied sprite resources appear on the next draw, and a failed lookup
does not prevent later recovery. This simulates resource replacement, not a live
client reload. `FluidSpritesTest` covers native, linkage and service
configuration failures keeping their identity, fatal errors propagating from both
entries, quiet missing-service behavior, and one discovery per uncached call
without a negative cache. Loader API and actual tea-asset assertions are
inventoried under
[native fluid presentation](integrations.md#native-fluid-presentation). The added
diagnostic cases have no saved execution acceptance record; the
[shared diagnostics note](integrations.md#shared-exception-diagnostics) owns that
cross-domain gap, and the earlier
[combined-tree execution](build-checks.md#native-fluid-boundary-and-presentation-verification)
remains historical.

### Low-opacity quad regressions

`SpatialFluidIconQuadTest` checks the actual Minecraft shader assets and
GameRenderer binding for the stock entity-translucent-emissive shader with
`NEW_ENTITY`. Texture-alpha discard precedes transition opacity; a CPU oracle
using production vertex/uniform values retains a nonzero contribution from an
opaque texel at 5% opacity. Production mesh assertions preserve pose, UVs, tint
and vertex attributes; submission-order checks keep shader-local opacity after
default uniforms. Recording submission and fake GL-state seams cover
flush/submission failures, resource disposal, caller-state and explicitly
overridden uniform restoration, and later recovery; a missing shader cannot
produce an opaque fallback. The same renderer tests water tint, zero-opacity
lookup suppression and text-only fallback with recovery after submission failure.
The [combined-tree execution](build-checks.md#lane-policy-native-amount-and-icon-opacity-verification)
records their passing headless results; the separate
[stock-GUI GPU probe](build-checks.md#stock-gui-gpu-probe) records native
framebuffer, following-draw and focused JUnit results distinct from these
CPU/fake-state seams.

## Selector overlay layout and frame rendering

`SpatialOverlayRendererTimingTest` covers timing models. The transition, layout,
style, sector and square-progress suites cover CPU overlay models: appearance,
exit, retargeting, reduced motion, rewind and bounded churn; synchronized
non-input relayout and a shared geometry rebase; row/status coordinates; style
overrides, font scaling, alpha and independent wheel/target opacity, zero-opacity
layer gating, and Back progress within painted extents; child-orbit,
submenu-radius and selected offsets; square-progress extents and immutable
back-affordance state; and sector scanline geometry, tiling,
disabled/reserved alpha and paint order.

`SpatialOverlayRendererGuideTest` covers radial guides: one divider per true
shared boundary, including unequal, equal, Back and wrapped spans, with none for
a full disc or gap; the frozen deadzone circle and controller-provided deadzones;
menu-origin placement under rigid translation, ancestor dimming and child
radial-entry; and guide alpha following only the menu transition, independent of
opacity preferences and focus/selection/release, with reduced motion and exit
handled by the shared transition. Endpoints use independently computed geometry;
no painted stroke or GPU submission is claimed.

`SpatialOverlayRendererFocusScaleTest` drives the production frame through
recording ports: focused actions and branches use the focused emphasis and
sibling de-emphasis scales through the finite interval; Back, disabled, reserved
and deadzone focus keep established scales; actionless branches focus the same
way; reduced motion snaps; inventory rows keep their own scale.

`SpatialOverlayTransitionsBackgroundTest` and the background renderer cover the
independent coverage channel: constructor delegation and normalized bounds;
appearance, retargeting and reappearance from displayed coverage; exit while
detached data keeps its deadline; reduced motion and rewind; radial focus gains,
ancestor dimming and breadcrumb omission; final-byte rounding and per-knob layer
scope; background-only admission without acknowledgement; frame backgrounds;
active/empty panel and header emphasis; committable selected-row emphasis;
retained-row filtering without mutating the window model; old-fade/new-growth
selection change; and row-overlay reduced-motion/zero handling.

`RadialGradientTest` records the production emitter: center coverage/RGB, outward
fade with soft shoulders and transparent rim, fractional coverage/opacity to
rounding, clockwise sector/Back orientation, exact shared edges, full-disc
winding and non-overlap, bounded work, and inert invalid or quantized-zero
inputs. Background radial-route cases connect production planning to the emitter:
all menu and detached-exit underlays use that route, rectangular and foreground
paint stay separate, opacity scopes stay independent, and one underlay batch is
bracketed. These are source-reviewed fixtures, not a recorded run or GPU evidence.

`SpatialInventoryLayoutTest` covers content-driven measurement before the viewport
cap: natural row, icon, quantity, status, header and Forward widths; selection-
and window-independent discovered rows; no maximum fill for short content; shared
GUI budget; readable tiny GUI; and retained anchors across relayout. Fraction
fixtures cover independent caps, finite off-grid values, unchanged text metrics,
remeasurement, fraction rejection and compatibility overloads. Decimal fixtures
check the whole-pixel boundary, last fitting row and adjacent inputs without
borrowing geometry. Style delegation, detached name/icon reuse in
`SpatialInventoryRowDisplayTest`, and fixed-scale whole-text/ellipsis clipping in
`SpatialInventoryTextTest` are separate. These are layout/display/clipping seams,
not native text or scissor submission.

`SpatialInventoryQuantityPriorityTest` records production row-text submission
with supplied font metrics. At 320×240, the complete `12.345 B` quantity takes
width before the Water name; approximate/sub-mB indicators and localized large
units survive across scales; exact fits at 1.7 and the eight tested text scales
stay whole while a smaller slot remains bounded. An overfull quantity receives
the full text slot after the name yields, without font shrinking or panel
expansion, and item rows retain their allocation. These assertions passed in the
[combined-tree execution](build-checks.md#native-fluid-boundary-and-presentation-verification)
and do not establish native font fitting.

`SpatialInventoryBackTest` records a complete mirrored-side Back title frame
outside the panel's parent-facing edge at its vertical midpoint, its outward
semicircle, and progress along the measured rectangular frame's painted extents;
focus enhances only underlay coverage, wheel opacity reaches the underlay
independently, and navigation paint acknowledges no fabricated choice. These are
production-frame recording and perimeter-helper checks, not native window or
graphics submission.

`SpatialOverlayRendererAncestorBreadcrumbTest` drives production radial planning
and frame seams through recording paint and metrics ports: each active-path
ancestor plans only its entry breadcrumb and Back navigation, filtering its other
option frames and sectors including disabled and reserved siblings; each
retained breadcrumb frame and its entry/exit anchor sit at that ancestor's origin
with no pointer ray, and its own sector is omitted while ancestor Back keeps its
frame and sector; a lone breadcrumb contributes no hidden divider while the
defined centre guide stays; entering fades hidden siblings through the ordinary
exit and returning fades them back, reduced motion snaps both ways, and disabled
sibling frames/sectors settle hidden and restore with styling, including
mid-fade. A hidden ancestor never acknowledges, a breadcrumb is never an active
acknowledgement, and the logical stack and selection geometry are unchanged.

`SpatialInventoryClearanceTest` covers item-menu clearance: the pinned viewport
uses the independent maximum of outer-sector coverage and the toward-panel Back
frame bound including selected push; long labels widen the distance; the frame
follows option text scale, not inventory; taller font grows the extent;
intermediate resizes stay finite; the full Back frame stays clear in both
orientations; first, mid and settled paint stay disjoint, committing new clearance
on the first frame; follower ray, guide, connector and title acknowledgement stay
on one geometry; a longer Back label relayouts the pattern; and resize rebases a
prior detached panel. The mirrored fixture checks newly measured width and
clearance with invalid-input rejection. Content-shrink cases in the navigation
suite check every painted panel against child-sector and Back title bounds with
nonempty paints and smaller settled width. These are recording-painter checks,
not native rendering.
`SpatialInventoryNavigationIntegrationTest` checks active-list chrome through its
recording painter: list entry exposes the moving virtual crosshair, including
while side intent suppresses vertical selection; custom-fraction cases compare
logical metrics with recorded panel width, height and row capacity and keep the
complete exterior Back frame outside the panel; a direct list release followed by
resize records the detached panel at its newly measured dimensions, only rows
within the new capacity and a still-visible finite exit. Selection and item-return
coverage is inventoried in
[interaction verification](interaction.md#inventory-list-gestures-and-navigation).
These are source-reviewed recording-painter fixtures, not recorded runs, native
GUI-scale callbacks, GLFW or GPU evidence.

`SpatialOverlayRendererFollowerTest` checks active-list crosshair visibility with
both frame opacity preferences at zero, including a return from an eligible item
follower; the item Back/deadzone pointer stays visible, an eligible visible item
follower hides it, parent radial deadzone feedback restores on leaving the list,
and an inactive exit after that parent frame has no connectors or choice
acknowledgement. Across moving follower poses, a connector is recorded only when
painted frames and previews do not already cover the remaining circle-to-title
segment, using an independent segment-over-rectangle oracle. These are
recording-painter frame checks; they do not establish the complete animated
crosshair handoff or feedback during a direct active-list detached exit, nor
native cursor visibility or GPU submission. The
[wheel owner](../../architecture/picking/wheel.md#inventory-list) defines the
list/radial presentation handoff.
`SpatialVisualTrailTest` covers the shared-history model: one bounded history for
the logical pointer and certified follower; native pointer batches consumed once
with no follower-era replay; ownership/interruption handoff from the last
displayed endpoint; settled owner sampling without a second lag filter;
projection-correction fencing with no added stroke; identity-based explicit
rebase; rigid pan appending nothing; bounded reduced motion, reset, quick reopen,
rewound clock and retargeting; and missing-paint freeze/clear.

`SpatialOverlayRendererTrailTest` drives paint/commit order: an immutable history
captured before node paints and committed after them, above underlays/guides and
below text; only a successful actual-pose paint with a visible title admits a
follower sample; failed, disabled/Back, omitted or offscreen cases cannot certify
or borrow a duplicate; inventory lists hide the trail and never own it; shared
list-pointer movement records no samples; alignment fences without seeding; a
list-to-item rebase is not a joining stroke; fractional projection/pan appends
nothing; trail visibility/opacity reach only the trail; release fades the exact
departing follower without acknowledgement, connectors or origin snap; new hold
or reset discards; the retained bound holds; overlapping rounded pixels take
maximum alpha. Interrupted handoffs survive a same-clock release toward the
departing painted pose, not the outgoing duplicate; missing departing paint
retains history and resumes without retiming; only reduced motion, hard reset or
a new hold clears. The release frame paints committed history, and exit-tail
resize reprojects from the detached origin. These are recording-painter/CPU
seams, not native graphics, window or multiplayer evidence.
`SpatialOverlayRendererBorderTest` projects the real facade's typed border color
in both focus states, keeps untyped and legacy `ping:` fallbacks, verifies the
cancellation action's focus-independent color, and retains detached color through
exit. `SpatialInventoryBorderTest` drives the production list planner and row-fill
seam: only the selected committable row carries a complete annotation-colored
rectangle, including with grey data quality; an unselected row retains its
annotation capability but paints neither border nor added fill; Back focus, a
retained item row and a noncommittable selected row suppress direct-send
decoration. `SpatialInventoryNavigationIntegrationTest` drives native-normalized
input through the real session and recorded frame: selection changes within one
visible window move the border to the current item key, a scroll-window jump
leaves the old row fading without an outline, ordinary close retains the current
row's border through its fade while the old row continues its original fade, and
reselecting a departing row restores its authorized border on that key alone.
These are headless input and recording-painter seams, not live window or GPU
evidence. `SpatialSelectorSessionTest` checks distinct non-grey local-action
colors without inventing Ping actions, item annotation colors independent of the
whole-marker default, missing Attention authorization under content-intent OFF,
and live annotation-policy changes without changing selection.

`SpatialLabelAvoidanceTest` covers bounded vertical placement, stable shallow
collision resolution, fixed focus, resistance to small pointer jitter,
sector-preserving unsolvable crowds and reset. `SpatialOverlayReadabilityTest`
uses the production frame path with explicit font metrics: an English shallow
overlap separates while text dimensions and controller state remain unchanged;
the focused node paints last among text-bearing nodes and detached old focus
does not retain that priority; movement follows the finite transition and reset
discards placement memory; connector segment subtraction leaves title rectangles
uncovered; and all bundled locales keep full single-line text, horizontal
anchors, bounded vertical offsets and sector membership across viewport sizes.
The existing follower, Precise, breadcrumb and inventory-clearance suites retain
their own geometry coverage. These are CPU layout, recording-painter and
fill-seam checks, not native GUI batching, resource-pack font readability or
visual comfort during live input.

`SpatialViewOffsetTest` and its projection suite cover the single rigid view
translation: exact endpoints, continuous re-aiming, unchanged-origin interval
continuity, reduced-motion snap, inert rewound clock and hard clear; through the
real headless controller/session, every active menu origin projects to the GUI
center while pointer, trail and menu vectors stay rigid; the inventory list and
item submenu keep their active origin and selected-row anchor; an inactive frame
freezes the displayed exit translation for quick reopen. The interaction frame
publishes the projection once; confinement, paint, release and resize read it
unchanged, fractional pans stay within inclusive GUI pixel bounds, paint does not
resample or mutate, resize reuses the last offset, and chrome follows the
published center while exit chrome keeps detached geometry.

`SpatialOverlayRendererNodeLayoutTest` drives controller snapshots through radial
planning, node layout, bounded avoidance and frame recording: every title measures
its complete single-line label at the configured scale; an external candidate
preview consumes only the preview cell, never resizing or clipping the title; font
preferences stay independent while the row has room; solvable frames/previews use
actual rounded bounds including disabled-border endpoints; budget-exceeding
viewports, enlarged text, menu availability, radius tiers and rotation sweeps in
both languages keep complete localized titles, anchors and sector-contained
offsets while selected/focused/following poses are not moved, accepting residual
overlap instead of shrinking or clipping.

Preview checks preserve the visible cap, whole code points and styles, including
supplementary splits and legacy formatting; formatting-only detail allocates
nothing, capping never reads the overflow tail; within-cap names are whole and
wrapped in the cell, over-cap names keep visible text plus one ellipsis; centered
lines paint below the frame at the readable floor, and larger title fonts only
reduce clearance/scale; the smallest viewport with large fonts keeps complete
titles and nonblank preview/Back/disabled text; Back-hover progress still traces
the frame; the painted preview retains capped glyphs, the budget changes no title
text/scale, and exit retains the detached preview; overfull glyph stress keeps the
floor without second truncation, while arbitrary resource-pack metrics remain a
gap. Selected/following nodes intentionally leave resting layout. These are CPU
layout and recording-painter checks, not native callback, gameplay frame or manual
validation.

Tracking-line projection and its provider/runtime limitations are inventoried in
[inventory verification](inventory.md#inventory-preview-and-tracking-foundation).

## Render entity lookup and locator resolver seams

`RenderEntityLookupCacheTest` covers the render-only lookup algorithm: idle
passes without scans; distinct, repeated-hit and miss UUID requests sharing one
cold index; first-valid duplicate selection; simulated HUD/outline warm reuse
across passes; a different world object with the same dimension; removed or
unloaded entries; runtime-ID reuse and UUID mutation; same-UUID replacement and a
spawn after index creation becoming visible later; an invalid warm entry causing
one scan and index build; pruning without touching an old entry during validation;
clear/null-frame handling; and null-world/null-UUID safety.
`EntityOutlineLocatorResolverTest` covers UUID and XP locators, runtime non-orb
rejection, gone/null/mismatched entities, map lookup and the raw-cache
entity-mismatch seam. Neither establishes a dragon-renderer test or
canonicalization integration beyond these seams. The cache fakes and counters are
not actual frame hooks, HUD callers or renderers, and do not automatically cover
the fresh non-render lookup bypass; source inspection remains useful but is not
automated runtime coverage.

## Focused native block-outline regression coverage

The native VoxelShape route requires complementary checks:

1. Production render-state coverage pins `BlockOutlineRenderType` to
   `VertexFormat.Mode.LINES`, vanilla `rendertype_lines`, a fixed width wider
   than vanilla selection lines, `NO_DEPTH_TEST`/`GL_ALWAYS`, color-only writes
   and late composite submission.
2. Native geometry coverage pins the live `BlockState#getShape`-to-
   `VoxelShape#forAllEdges` edge route so a full-cube, polygon or
   shape-equivalent substitute cannot satisfy the regression.
3. Render-frame snapshot coverage separately exercises late submission and frame
   ownership.

Native-glow whitelist/eligibility coverage is separate; passing either side does
not prove the other, and structural or behavioral tests do not prove actual
visibility through occluders or from arbitrary camera angles.

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
