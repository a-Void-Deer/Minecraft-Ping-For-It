# Documentation Guide

Use this page to select the contracts relevant to a task; most changes do not
require reading every document. These documents describe the existing Minecraft
1.21.1 product and its confirmed boundaries, not a proposal to reimplement it.

## Document authority

| Document kind | Responsibility |
| --- | --- |
| [Repository README](../README.md) | Tracked public entry point for project orientation, supported build/source sets, and install/build/verify commands. |
| Topic documents and focused architecture contracts below | Normative, executable product contracts for their named subsystem. |
| [Geometry-pipeline overview](architecture/geometry-pipeline.md) and decisions | Explanatory concepts, boundaries, and rationale. They link to, but do not replace, executable topic contracts. |
| [Testing and verification](testing/verification.md) | Existing automated-coverage inventory, known gaps, evidence rules and pending manual/integration scenarios. |

Start with the tracked repository README, this guide and the topic documents
selected below. Consult a linked decision when changing the reasoned boundary,
not for routine use of an unchanged contract. Local maintainer or agent files,
when present, are supplementary execution guidance rather than public
documentation prerequisites or behavioral authority. Investigate and report
conflicts rather than silently choosing or dropping a requirement.

## Select documents by task

| Task | Start with | Also consult when relevant |
| --- | --- | --- |
| Understand the end-to-end data flow | [Architecture: geometry pipeline](architecture/geometry-pipeline.md) | Identity, capture, authority, presentation and geometry topics below |
| Change Target/Marker identity or lifecycle | [Target model](identity/target_model.md) | [Validation](architecture/authority/target_validation.md), [winner selection](architecture/authority/ping_winner.md), Sable |
| Change marker creation, removal, expiry or audience lifecycle | [Marker lifecycle](architecture/authority/marker_lifecycle.md) | [Target model](identity/target_model.md), validation and winner selection |
| Change Target Types, Ping Types, priorities, defaults, keys or colors | [Catalogs](identity/catalogs.md) | Capture, names/chat and wheel |
| Change press handling, asynchronous capture or target locking | [Capture](picking/capture.md) | [Long-press timing](architecture/input/long-press.md), [Long-press compatibility](architecture/input/long-press-compatibility.md), [Selection policy](picking/selection_policy.md), [Wheel](picking/wheel.md), local geometry, rate policy |
| Change long-press threshold/slice timing | [Long-press timing](architecture/input/long-press.md) | Capture, wheel, client configuration, and compatibility when enabled |
| Change rapid-click or deferred long-press compatibility | [Long-press compatibility](architecture/input/long-press-compatibility.md) | Capture, long-press timing, wheel, rate policy, and verification |
| Change target-selection toggles, block/fluid modes or entity-selection blacklist | [Selection policy](picking/selection_policy.md) | [Capture](picking/capture.md), Create raycast |
| Change exact entity picking or geometry ownership | [Local geometry picking](picking/local_geometry.md) | Create raycast integration and D0006 |
| Change client capture distance, optional long-range traces or server range acceptance | [Range](picking/range.md) | [Capture](picking/capture.md), [Target validation](architecture/authority/target_validation.md), server settings and the affected integration |
| Change wheel opening, timeout, selection or cancellation | [Wheel](picking/wheel.md) | Capture, client config and validation |
| Change packets, target acceptance, removal or rejection feedback | [Target validation](architecture/authority/target_validation.md) | [Security](security.md), rate policy and identity |
| Change registered marker/legacy packet ingress or client packet acceptance | [Network protocol](architecture/authority/network_protocol.md) | [Target validation](architecture/authority/target_validation.md), marker lifecycle and compatibility |
| Change which same-target ping is visible | [Ping winner](architecture/authority/ping_winner.md) | Identity, removal/expiry and external-target refresh |
| Change trust boundaries, failure isolation or diagnostic detail | [Security](security.md) | Validation, rate policy and the affected provider/source contract |
| Change the complete client file/key catalogue, list syntax, locality, or format examples | [Client configuration](config/client.md) | Revisioning, capture, wheel, outline routing, and geometry modes |
| Change client settings-screen workflow, file action, reset, or screen exposure | [Settings screen](UI/settings-screen.md) | Client configuration, server settings, revisioning, and verification |
| Change the server-settings panel, snapshot correlation, drafts or partial updates | [Server settings](config/server_settings.md) | [Security](security.md), [Rate policy](config/rate_limit.md), marker lifecycle, and revisioning |
| Change config schema versions, preservation locks or migrations | [Configuration revisioning](architecture/config/revisioning.md) | [Client configuration](config/client.md), settings screen, repository build entry |
| Change send-rate synchronization or courtesy limiting | [Rate policy](config/rate_limit.md) | Validation and security |
| Change source order, adapter outcomes, failure handling or registration | [Geometry sources](geometry/geometry_sources.md) | Presentation subjects and the affected integration |
| Change native block-shape acquisition or edge generation | [VoxelShape geometry](geometry/voxel_shape.md) | Outline render state and D0002 |
| Change GPU outline routing or production render state | [Outline rendering](rendering/outline.md) | VoxelShape geometry, source outcomes and model placement |
| Change multipart owner/master resolution, beds or doors | [Presentation subjects](rendering/presentation_subjects.md) | Geometry sources, D0003 and the affected integration |
| Change model offset, placement or seeded variants | [Model placement](rendering/model_placement.md) | Outline routing and verification limitations |
| Change target names, chat templates or phrase emphasis | [Names and chat](rendering/names_chat.md) | Catalogs and authoritative validation |
| Change Create/Flywheel support | [Create integration](integrations/create.md) | [Create raycast supplement](integrations/create-contraption-raycast.md), local geometry and geometry sources |
| Change Sable/external-target support | [Sable integration](integrations/sable.md) | Target identity, validation, winner selection and presentation |
| Change Simulated stand-in/optional-content support | [Simulated integration](integrations/simulated.md) | Selection policy, compatibility and testing/verification |
| Change loader, mod-ID or optional-content boundaries | [Compatibility](compatibility.md) | The affected integration and security |
| Change build logic, source-set wiring, loader packaging or artifact identity verification | [Testing and verification](testing/verification.md#build-source-set-and-artifact-verification) | [Repository build instructions](../README.md#install-build-and-verify), compatibility and the affected loader build file |
| Assess coverage or plan validation | [Testing and verification](testing/verification.md) | The changed topic and applicable decision |

Every topic document is reachable from the table above or the decision index
below. The complete topic set is:

- architecture: explanatory [geometry pipeline](architecture/geometry-pipeline.md),
  normative [long-press timing](architecture/input/long-press.md),
  [long-press compatibility](architecture/input/long-press-compatibility.md), and
  [configuration revisioning](architecture/config/revisioning.md);
- identity: [target model](identity/target_model.md) and
  [catalogs](identity/catalogs.md);
- picking: [capture](picking/capture.md),
  [selection policy](picking/selection_policy.md),
  [local geometry](picking/local_geometry.md), [range](picking/range.md), and
  [wheel](picking/wheel.md);
- authority: [marker lifecycle](architecture/authority/marker_lifecycle.md),
  [target validation](architecture/authority/target_validation.md),
  [network protocol](architecture/authority/network_protocol.md), and
  [ping winner](architecture/authority/ping_winner.md);
- configuration: [client configuration](config/client.md),
  [settings screen](UI/settings-screen.md), [server settings](config/server_settings.md),
  and [rate policy](config/rate_limit.md);
- geometry: [geometry sources](geometry/geometry_sources.md) and
  [VoxelShape geometry](geometry/voxel_shape.md);
- rendering: [outline](rendering/outline.md),
  [presentation subjects](rendering/presentation_subjects.md),
  [model placement](rendering/model_placement.md), and
  [names and chat](rendering/names_chat.md);
- integrations: [Create](integrations/create.md),
  [Create raycast](integrations/create-contraption-raycast.md),
  [Sable](integrations/sable.md), and
  [Simulated](integrations/simulated.md); and
- [compatibility](compatibility.md), [security](security.md), and
  [testing/verification](testing/verification.md), plus decisions D0001 through
  D0006 in the index below.

## Decision records

Decision records explain established boundaries and consequences. They must not
duplicate the exact constants, algorithms or error cases owned by topic docs.

| Decision | Boundary explained |
| --- | --- |
| [D0001 — Separate model from renderable](decisions/D0001-separate-model-from-renderable.md) | Why model eligibility or model availability is not the same as committing render geometry for the current frame. |
| [D0002 — VoxelShape fallback](decisions/D0002-voxel-shape-fallback.md) | Why eligible normal geometry is attempted before the native edge fallback. |
| [D0003 — Multipart presentation types](decisions/D0003-multipart-presentation-types.md) | Canonical target versus presentation subjects, and why multipart subjects retain the rendering type established for their form. |
| [D0004 — Server authority](decisions/D0004-server-authority.md) | Server validation, ownership, rate enforcement, recipient state and winner selection. |
| [D0005 — Press-time capture](decisions/D0005-press-time-capture.md) | Press-ray locking, asynchronous completion, the actual-open boundary and narrow deferred compatibility. |
| [D0006 — Exact owned geometry](decisions/D0006-exact-owned-geometry.md) | Why an owned non-hit cannot recover to a coarse AABB and how exact local shapes participate in picking. |

## Documentation maintenance

1. Give every substantive executable fact one primary topic owner. Other
   documents retain necessary interface semantics and a link rather than copying
   its catalogue, constant set, algorithm, or failure matrix.
2. Put product direction and scope in `spec.md`, Agent procedure in `AGENTS.md`,
   and software behavior in topic docs. Do not move product rules into
   `AGENTS.md` as an enforcement shortcut.
3. Update the owning topic for new behavior, the applicable decision only when
   rationale or a boundary changes, and verification when coverage, gaps or
   pending scenarios change.
4. The geometry-pipeline architecture page describes stages, data, lifetimes,
   and boundaries. Focused architecture contracts are normative for their named
   behavior. Neither form is a Java file, class, or line-number tour.
5. Integration docs contain mod-specific gates and implementations. Generic
   picking-owner, geometry-source, outcome, lifecycle and failure contracts stay
   in their picking, geometry, authority or security owners.
6. Keep distinct terms distinct: model eligibility is not emitted geometry;
   picking `forAllBoxes` is not outline `forAllEdges`; canonical identity is not
   presentation ownership; existing coverage is not a test run.
7. Preserve product IDs, ordering, keys, canonical vocabularies, validation
   timing, fallback behavior, recoverable failure classes, and optional-mod
   boundaries. Do not mirror implementation numeric defaults, bounds, UI steps,
   or clamp formulas across normative pages; link the actual implementation
   reference when live tuning metadata is needed. Label inferred rationale and
   never invent historical evidence.
8. Check links, headings, fences and the relevant coverage inventory after a
   documentation change. Do not claim runtime or manual validation from document
   review alone.
