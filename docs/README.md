# Documentation Guide

Use this page to select the contracts relevant to a task; most changes do not
require reading every document. These documents describe the existing Minecraft
1.21.1 product and its confirmed boundaries, not a proposal to reimplement it.

## Document authority

| Document kind | Responsibility |
| --- | --- |
| [Repository README](../README.md) | Tracked public entry point for project orientation, supported build/source sets, and install/build/verify commands. |
| Topic documents and focused architecture contracts below | Normative, executable product contracts for their named subsystem. |
| [Geometry-pipeline overview](architecture/geometry/geometry-pipeline.md) and decisions | Explanatory concepts, boundaries, and rationale. They link to, but do not replace, executable topic contracts. |
| [Testing and verification](testing/verification.md) | Existing automated-coverage inventory, known gaps, evidence rules and pending manual/integration scenarios. |

Focused architecture contracts are normative for their named subsystem
throughout input, picking, identity, authority, configuration, geometry, and
rendering; the [geometry-pipeline overview](architecture/geometry/geometry-pipeline.md)
and the decision records remain explanatory. Ownership follows change
responsibility: each substantive fact has exactly one primary owner, but one
feature or change commonly affects several independent contracts and updates
each owner rather than being forced into one artificial file. Secondary pages
retain only the necessary interface semantics and a link; they do not copy the
exact detailed rule, counters, constants, or failure matrix.

Practical ownership review: if changing one fact would require the same
substantive detail to be edited in more than one place, that fact has more than
one claimed owner and should be consolidated into its primary owner. A change
that legitimately crosses independent contract boundaries is not duplication and
may update several owners. Short reminders and stable interface summaries are
not treated as duplicated ownership.

Start with the tracked repository README, this guide and the topic documents
selected below. Consult a linked decision when changing the reasoned boundary,
not for routine use of an unchanged contract. Local maintainer or agent files,
when present, are supplementary execution guidance rather than public
documentation prerequisites or behavioral authority. Investigate and report
conflicts rather than silently choosing or dropping a requirement.

## Select documents by task

| Task | Start with | Also consult when relevant |
| --- | --- | --- |
| Understand the end-to-end data flow | [Architecture: geometry pipeline](architecture/geometry/geometry-pipeline.md) | Identity, capture, authority, presentation and geometry topics below |
| Change Target/Marker identity or lifecycle | [Target model](architecture/identity/target_model.md) | [Validation](architecture/authority/target_validation.md), [winner selection](architecture/authority/ping_winner.md), Sable |
| Change server marker creation, removal, expiry or audience lifetime | [Marker lifecycle](architecture/authority/marker_lifecycle.md) | [Target model](architecture/identity/target_model.md), validation and winner selection |
| Change client marker synchronization, display deadlines or fallback | [Client marker state](architecture/markers/client-state.md) | [Marker lifecycle](architecture/authority/marker_lifecycle.md), [client configuration](config/client.md) and [names and chat](architecture/rendering/names_chat.md) |
| Change Target Types, Ping Types, priorities, defaults, keys or colors | [Catalogs](architecture/identity/catalogs.md) | Capture, names/chat and wheel |
| Change press handling, asynchronous capture, target locking, or the actual wheel-open eligibility/freezing boundary | [Capture](architecture/picking/capture.md) | [Long-press timing](architecture/input/long-press.md), [Long-press compatibility](architecture/input/long-press-compatibility.md), [Selection policy](architecture/picking/selection_policy.md), [Wheel](architecture/picking/wheel.md), [D0005 — Press-time capture](decisions/D0005-press-time-capture.md), local geometry, rate policy |
| Change long-press threshold/slice timing | [Long-press timing](architecture/input/long-press.md) | Capture, wheel, client configuration, and compatibility when enabled |
| Change rapid-click or deferred long-press compatibility | [Long-press compatibility](architecture/input/long-press-compatibility.md) | Capture, long-press timing, wheel, rate policy, and verification |
| Change target-selection toggles, block/fluid modes or entity-selection blacklist | [Selection policy](architecture/picking/selection_policy.md) | [Capture](architecture/picking/capture.md), Create raycast |
| Change exact entity picking or geometry ownership | [Local geometry picking](architecture/picking/local_geometry.md) | Create raycast integration and D0006 |
| Change client capture distance, optional long-range traces or server range acceptance | [Range](architecture/picking/range.md) | [Capture](architecture/picking/capture.md), [Target validation](architecture/authority/target_validation.md), server configuration and the affected integration |
| Change an already-open wheel's timeout, selection, or cancellation | [Wheel](architecture/picking/wheel.md) | Capture, client config and validation |
| Change target acceptance, admissibility or removal adjudication | [Target validation](architecture/authority/target_validation.md) | [Security](architecture/security.md), rate policy and identity |
| Change registered marker/legacy packet ingress or client packet acceptance | [Network protocol](architecture/network/protocol.md) | [Target validation](architecture/authority/target_validation.md), client marker state and compatibility |
| Change local invalid-target or server-rejection feedback | [Ping feedback](UI/ping-feedback.md) | [Target validation](architecture/authority/target_validation.md), rate policy and client configuration |
| Change which same-target ping is visible | [Ping winner](architecture/authority/ping_winner.md) | Identity, removal/expiry and external-target refresh |
| Change how trusted identity is established, how unauthorized requests are rejected, or failure-isolation and diagnostic detail | [Security](architecture/security.md) | Validation, [rate policy](architecture/config/rate-limit.md) and the affected provider/source contract |
| Change the complete client file/key catalogue, list syntax, locality, or format examples | [Client configuration](config/client.md) | Revisioning, capture, wheel, outline routing, and geometry modes |
| Change the persisted server file catalogue, fields, or examples | [Server configuration](config/server.md) | Range, rate policy, marker lifecycle, and revisioning |
| Change the client settings screen or server settings panel workflow, external file action, reset, or screen exposure | [Configuration UI](UI/settings-screen.md) | Client configuration, server configuration, revisioning, and verification |
| Change the remote server-configuration request, snapshot correlation, field mask, merge, apply, or no-ack transaction | [Changing server configuration](architecture/config/changing-server-config.md) | [Server configuration](config/server.md), [Server configuration authority](architecture/authority/server-config.md), [security](architecture/security.md), marker lifecycle, and revisioning |
| Change who may edit server configuration, edit eligibility, or the required permission | [Server configuration authority](architecture/authority/server-config.md) | [Security](architecture/security.md), [Changing server configuration](architecture/config/changing-server-config.md), and revisioning |
| Change config schema versions, preservation locks or migrations | [Configuration revisioning](architecture/config/revisioning.md) | [Client configuration](config/client.md), configuration UI, repository build entry |
| Change send-rate synchronization, enforcement or courtesy limiting | [Rate policy](architecture/config/rate-limit.md) | Validation, security and the server config catalogue |
| Change source order, adapter outcomes, failure handling or registration | [Geometry sources](architecture/geometry/geometry_sources.md) | Presentation subjects and the affected integration |
| Change native block-shape acquisition or edge generation | [VoxelShape geometry](architecture/geometry/voxel_shape.md) | Outline render state and D0002 |
| Change GPU outline routing, shared render-entity lookup, or production render state | [Outline rendering](architecture/rendering/outline.md) | VoxelShape geometry, source outcomes and model placement |
| Change multipart owner/master resolution, beds or doors | [Presentation subjects](architecture/rendering/presentation_subjects.md) | Geometry sources, D0003 and the affected integration |
| Change model offset, placement or seeded variants | [Model placement](architecture/rendering/model_placement.md) | Outline routing and verification limitations |
| Change target names, marker receipt feedback, chat templates or phrase emphasis | [Names and chat](architecture/rendering/names_chat.md) | Catalogs and authoritative validation |
| Change Create/Flywheel support | [Create integration](integrations/create.md) | [Create raycast supplement](integrations/create-contraption-raycast.md), local geometry and geometry sources |
| Change Sable/external-target support | [Sable integration](integrations/sable.md) | Target identity, validation, winner selection and presentation |
| Change Simulated stand-in/optional-content support | [Simulated integration](integrations/simulated.md) | Selection policy, compatibility and testing/verification |
| Change loader, mod-ID or optional-content boundaries | [Compatibility](compatibility.md) | The affected integration and security |
| Change build logic, source-set wiring, loader packaging or artifact identity verification | [Testing and verification](testing/verification.md#build-source-set-and-artifact-verification) | [Repository build instructions](../README.md#install-build-and-verify), compatibility and the affected loader build file |
| Assess coverage or plan validation | [Testing and verification](testing/verification.md) | The changed topic and applicable decision |

Every topic document is reachable from the task table above or the decision
index below.

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

1. Put product direction and scope in `spec.md`, Agent procedure in `AGENTS.md`,
   and software behavior in topic docs. Do not move product rules into
   `AGENTS.md` as an enforcement shortcut.
2. Update the owning topic for new behavior, the applicable decision only when
   rationale or a boundary changes, and verification when coverage, gaps or
   pending scenarios change.
3. The geometry-pipeline architecture page describes stages, boundaries and
   owner navigation. Focused architecture contracts are normative for their
   named behavior. Neither form is a Java file, class, or line-number tour.
4. Integration docs contain mod-specific gates and implementations. Generic
   picking-owner, geometry-source, outcome, lifecycle and failure contracts stay
   in their picking, geometry, authority or security owners.
5. Keep distinct terms distinct: model eligibility is not emitted geometry;
   picking `forAllBoxes` is not outline `forAllEdges`; canonical identity is not
   presentation ownership; existing coverage is not a test run.
6. Preserve product IDs, ordering, keys, canonical vocabularies, validation
   timing, fallback behavior, recoverable failure classes, and optional-mod
   boundaries. Do not mirror implementation numeric defaults, bounds, UI steps,
   or clamp formulas across normative pages; link the actual implementation
   reference when live tuning metadata is needed. Label inferred rationale and
   never invent historical evidence.
7. Check links, headings, fences and the relevant coverage inventory after a
   documentation change. Do not claim runtime or manual validation from document
   review alone.
