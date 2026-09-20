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

Focused architecture contracts under `architecture/config` and
`architecture/authority`, together with the
[security](architecture/security.md) contract, are normative for their named
behavior. Each substantive fact has exactly one primary owner; other pages
retain only the necessary interface semantics and a link.

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
| Change Target/Marker identity or lifecycle | [Target model](architecture/identity/target_model.md) | [Validation](architecture/authority/target_validation.md), [winner selection](architecture/authority/ping_winner.md), Sable |
| Change marker creation, removal, expiry or audience lifecycle | [Marker lifecycle](architecture/authority/marker_lifecycle.md) | [Target model](architecture/identity/target_model.md), validation and winner selection |
| Change Target Types, Ping Types, priorities, defaults, keys or colors | [Catalogs](architecture/identity/catalogs.md) | Capture, names/chat and wheel |
| Change press handling, asynchronous capture or target locking | [Capture](architecture/picking/capture.md) | [Long-press timing](architecture/input/long-press.md), [Long-press compatibility](architecture/input/long-press-compatibility.md), [Selection policy](architecture/picking/selection_policy.md), [Wheel](architecture/picking/wheel.md), local geometry, rate policy |
| Change long-press threshold/slice timing | [Long-press timing](architecture/input/long-press.md) | Capture, wheel, client configuration, and compatibility when enabled |
| Change rapid-click or deferred long-press compatibility | [Long-press compatibility](architecture/input/long-press-compatibility.md) | Capture, long-press timing, wheel, rate policy, and verification |
| Change target-selection toggles, block/fluid modes or entity-selection blacklist | [Selection policy](architecture/picking/selection_policy.md) | [Capture](architecture/picking/capture.md), Create raycast |
| Change exact entity picking or geometry ownership | [Local geometry picking](architecture/picking/local_geometry.md) | Create raycast integration and D0006 |
| Change client capture distance, optional long-range traces or server range acceptance | [Range](architecture/picking/range.md) | [Capture](architecture/picking/capture.md), [Target validation](architecture/authority/target_validation.md), server configuration and the affected integration |
| Change wheel opening, timeout, selection or cancellation | [Wheel](architecture/picking/wheel.md) | Capture, client config and validation |
| Change packets, target acceptance, removal or rejection feedback | [Target validation](architecture/authority/target_validation.md) | [Security](architecture/security.md), rate policy and identity |
| Change registered marker/legacy packet ingress or client packet acceptance | [Network protocol](architecture/authority/network_protocol.md) | [Target validation](architecture/authority/target_validation.md), marker lifecycle and compatibility |
| Change which same-target ping is visible | [Ping winner](architecture/authority/ping_winner.md) | Identity, removal/expiry and external-target refresh |
| Change trust boundaries, failure isolation or diagnostic detail | [Security](architecture/security.md) | Validation, [rate policy](architecture/config/rate-limit.md) and the affected provider/source contract |
| Change the complete client file/key catalogue, list syntax, locality, or format examples | [Client configuration](config/client.md) | Revisioning, capture, wheel, outline routing, and geometry modes |
| Change the persisted server file catalogue, fields, or examples | [Server configuration](config/server.md) | Range, rate policy, marker lifecycle, and revisioning |
| Change client configuration-UI workflow, file action, reset, or screen exposure | [Configuration UI](UI/config.md) | Client configuration, server configuration, revisioning, and verification |
| Change the remote server-configuration change transaction or the server-settings panel workflow | [Changing server configuration](architecture/config/changing-server-config.md) | [Server configuration](config/server.md), [Server configuration authority](architecture/authority/server-config.md), [security](architecture/security.md), marker lifecycle, and revisioning |
| Change config schema versions, preservation locks or migrations | [Configuration revisioning](architecture/config/revisioning.md) | [Client configuration](config/client.md), configuration UI, repository build entry |
| Change send-rate synchronization, enforcement or courtesy limiting | [Rate policy](architecture/config/rate-limit.md) | Validation, security and the server config catalogue |
| Change source order, adapter outcomes, failure handling or registration | [Geometry sources](architecture/geometry/geometry_sources.md) | Presentation subjects and the affected integration |
| Change native block-shape acquisition or edge generation | [VoxelShape geometry](architecture/geometry/voxel_shape.md) | Outline render state and D0002 |
| Change GPU outline routing or production render state | [Outline rendering](architecture/rendering/outline.md) | VoxelShape geometry, source outcomes and model placement |
| Change multipart owner/master resolution, beds or doors | [Presentation subjects](architecture/rendering/presentation_subjects.md) | Geometry sources, D0003 and the affected integration |
| Change model offset, placement or seeded variants | [Model placement](architecture/rendering/model_placement.md) | Outline routing and verification limitations |
| Change target names, chat templates or phrase emphasis | [Names and chat](architecture/rendering/names_chat.md) | Catalogs and authoritative validation |
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
  [long-press compatibility](architecture/input/long-press-compatibility.md),
  [configuration revisioning](architecture/config/revisioning.md),
  [changing server configuration](architecture/config/changing-server-config.md), and
  [rate policy](architecture/config/rate-limit.md);
- identity: [target model](architecture/identity/target_model.md) and
  [catalogs](architecture/identity/catalogs.md);
- picking: [capture](architecture/picking/capture.md),
  [selection policy](architecture/picking/selection_policy.md),
  [local geometry](architecture/picking/local_geometry.md), [range](architecture/picking/range.md), and
  [wheel](architecture/picking/wheel.md);
- authority: [marker lifecycle](architecture/authority/marker_lifecycle.md),
  [target validation](architecture/authority/target_validation.md),
  [server configuration authority](architecture/authority/server-config.md),
  [network protocol](architecture/authority/network_protocol.md), and
  [ping winner](architecture/authority/ping_winner.md);
- configuration: [client configuration](config/client.md),
  [configuration UI](UI/config.md), and [server configuration](config/server.md);
- geometry: [geometry sources](architecture/geometry/geometry_sources.md) and
  [VoxelShape geometry](architecture/geometry/voxel_shape.md);
- rendering: [outline](architecture/rendering/outline.md),
  [presentation subjects](architecture/rendering/presentation_subjects.md),
  [model placement](architecture/rendering/model_placement.md), and
  [names and chat](architecture/rendering/names_chat.md);
- integrations: [Create](integrations/create.md),
  [Create raycast](integrations/create-contraption-raycast.md),
  [Sable](integrations/sable.md), and
  [Simulated](integrations/simulated.md); and
- [compatibility](compatibility.md), [security](architecture/security.md), and
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
