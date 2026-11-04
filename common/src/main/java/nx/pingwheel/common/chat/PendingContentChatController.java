package nx.pingwheel.common.chat;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.client.PresentationView;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Main-thread-confined, bounded first-receipt state. Only accepted CREATED
 * metadata starts a receipt; properties use current authorized sections while
 * inventory uses the immutable atomic creation snapshot. There is no world access, capture, timer, ordinary
 * chat fallback, or permanent sent-ID history here.
 */
public final class PendingContentChatController {

	/** UNKNOWN is negotiation/missing authority, not an explicit denial. */
	public enum Authorization { ALLOWED, DENIED, UNKNOWN }

	/** Stable marker identity; an external locator/anchor refresh does not change it. */
	public record MarkerIdentity(MarkerId id, UUID owner, TargetKey target,
		String targetTypeId, String pingTypeId, long arrivalTick, long expiresAtTick) {
		public MarkerIdentity {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(owner, "owner");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(targetTypeId, "targetTypeId");
			Objects.requireNonNull(pingTypeId, "pingTypeId");
		}
		public static MarkerIdentity of(MarkerSnapshot marker) {
			return new MarkerIdentity(marker.id(), marker.owner(), marker.targetKey(),
				marker.targetTypeId(), marker.pingTypeId(), marker.arrivalTick(), marker.expiresAtTick());
		}
		public static MarkerIdentity of(ClientMarker marker) {
			return new MarkerIdentity(marker.id(), marker.owner(), marker.targetKey(),
				marker.targetTypeId(), marker.pingTypeId(), marker.arrivalTick(), marker.expiresAtTick());
		}
	}

	/**
	 * One detached read of the accepted stores. When ready, allowedFields is the
	 * accepted schema/current mask intersection for this marker's target type;
	 * an excluded root is denied, even if no value has arrived. Nested refs use
	 * their root's authorization plus the exact child deny fence. Inventory
	 * authority is separate from SECTION
	 * masks. live means current membership without authoritative removal/expiry,
	 * not visibility: a locally elapsed display deadline is irrelevant.
	 *
	 * <p>Inventory authorization is marker-scoped creation authority, not the
	 * ordinary Tracking lane policy. The creation snapshot is absent after
	 * revocation and never comes from Tracking or local Preview data.
	 */
	public record Current(long epoch, long view, boolean ready, boolean live,
		@Nullable MarkerIdentity identity, @Unmodifiable Map<String, @Unmodifiable Set<String>> allowedFields,
		Authorization inventoryAuthorization, PresentationView projection,
		ClientInventory.@Nullable Tracking tracking, @Nullable InventoryCreationSnapshot creationSnapshot) {
		public Current(long epoch, long view, boolean ready, boolean live, @Nullable MarkerIdentity identity,
			Map<String, Set<String>> allowedFields, Authorization inventoryAuthorization, PresentationView projection,
			ClientInventory.@Nullable Tracking tracking) {
			this(epoch, view, ready, live, identity, allowedFields, inventoryAuthorization, projection, tracking, null);
		}
		public Current {
			Map<String, Set<String>> detached = new LinkedHashMap<>();
			Objects.requireNonNull(allowedFields, "allowedFields").forEach((adapter, fields) ->
				detached.put(adapter, Set.copyOf(fields)));
			allowedFields = Map.copyOf(detached);
			Objects.requireNonNull(inventoryAuthorization, "inventoryAuthorization");
			Objects.requireNonNull(projection, "projection");
			if (tracking != null) tracking = new ClientInventory.Tracking(tracking.resourceKind(), tracking.markerId(), tracking.status(),
				tracking.grey(), tracking.complete(), tracking.baselineId(), tracking.statusRevision(),
				tracking.watermark(), List.copyOf(tracking.entries()));
		}
		boolean allows(PresentationPropertyRef ref) {
			return allowedFields.getOrDefault(ref.adapterId(), Set.of()).contains(ref.fieldId())
				&& !projection.childBlack().contains(ref);
		}
	}

	@FunctionalInterface
	public interface ReadPort {
		@Nullable Current current(MarkerId markerId);
	}

	/** Formatting-only seam. The normal constructor below uses the real composer. */
	public interface Formatter {
		Optional<Component> name(String authoritativeNameJson);
		Optional<Component> properties(Component author, Component target, PresentationView view,
			List<PresentationPropertyRef> selectedRefs);
		Optional<Component> inventory(Component author, Component target, ClientInventory.EntryView entry);
		default Optional<Component> inventory(Component author, Component target, ClientInventory.EntryView entry,
			boolean quantityUncertain) { return inventory(author, target, entry); }
	}

	private static final PresentationPropertyRef NAME =
		PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME);

	/** Only immutable receipt/author/fences/identity survive between callbacks. */
	private record Pending(long epoch, long view, MarkerIdentity identity,
		String author, PresentationReceiptContent content) {}
	private record Inputs(PresentationView projection, String nameJson,
		@Nullable InventoryCreationSnapshot creation) {}

	private final int limit;
	private final ReadPort reader;
	private final Formatter formatter;
	private final Consumer<Component> sink;
	private final Map<MarkerId, Pending> pending = new LinkedHashMap<>();
	private long lifecycleVersion;
	// In-flight only, including across clear/reset: never a sent-ID registry.
	private final Set<MarkerId> attempting = new HashSet<>();

	public PendingContentChatController(int limit, ReadPort reader, Formatter formatter, Consumer<Component> sink) {
		if (limit < 1) throw new IllegalArgumentException("pending content receipt limit must be positive");
		this.limit = limit;
		this.reader = Objects.requireNonNull(reader, "reader");
		this.formatter = Objects.requireNonNull(formatter, "formatter");
		this.sink = Objects.requireNonNull(sink, "sink");
	}

	public PendingContentChatController(int limit, ReadPort reader,
		Function<String, Optional<Component>> nameJsonDecoder,
		Function<String, Optional<Component>> itemDisplayDecoder,
		ContentChatComposer.TemplateSource templates, Consumer<Component> sink) {
		this(limit, reader, composer(nameJsonDecoder, itemDisplayDecoder, templates), sink);
	}

	private static Formatter composer(Function<String, Optional<Component>> nameJsonDecoder,
		Function<String, Optional<Component>> itemDisplayDecoder, ContentChatComposer.TemplateSource templates) {
		Objects.requireNonNull(nameJsonDecoder, "nameJsonDecoder");
		Objects.requireNonNull(itemDisplayDecoder, "itemDisplayDecoder");
		Objects.requireNonNull(templates, "templates");
		return new Formatter() {
			@Override public Optional<Component> name(String json) { return nameJsonDecoder.apply(json); }
			@Override public Optional<Component> properties(Component author, Component target, PresentationView view,
				List<PresentationPropertyRef> refs) {
				return ContentChatComposer.propertyMessage(author, target, view, refs, nameJsonDecoder, templates);
			}
			@Override public Optional<Component> inventory(Component author, Component target, ClientInventory.EntryView entry) {
				return ContentChatComposer.inventoryMessage(author, target, entry, false, itemDisplayDecoder, templates);
			}
			@Override public Optional<Component> inventory(Component author, Component target, ClientInventory.EntryView entry,
				boolean quantityUncertain) {
				return ContentChatComposer.inventoryMessage(author, target, entry, quantityUncertain, itemDisplayDecoder, templates);
			}
		};
	}

	/**
	 * Call only after initial acceptance and marker upsert, with newlySeen saved
	 * from membership BEFORE the upsert. A known same-ID CREATED must pass false
	 * (or skip begin). WHOLE belongs to the existing immediate path; SUPPRESSED
	 * and capacity overflow have no fallback. This does not attempt delivery.
	 */
	public boolean begin(PresentationS2CPacket acceptedCreated, boolean newlySeen) {
		if (!newlySeen || acceptedCreated == null || acceptedCreated.isCorrupt()
			|| acceptedCreated.kind() != PresentationS2CPacket.Kind.CREATED
			|| acceptedCreated.snapshot() == null || acceptedCreated.ownerName() == null
			|| acceptedCreated.content() == null) return false;
		PresentationReceiptContent content = acceptedCreated.content();
		if (content.kind() != PresentationReceiptContent.Kind.PROPERTIES
			&& content.kind() != PresentationReceiptContent.Kind.INVENTORY) return false;
		MarkerId id = acceptedCreated.markerId();
		if (!acceptedCreated.snapshot().id().equals(id) || pending.containsKey(id) || pending.size() >= limit)
			return false;
		Pending capture = new Pending(acceptedCreated.epoch(), acceptedCreated.view(),
			MarkerIdentity.of(acceptedCreated.snapshot()), acceptedCreated.ownerName(), content);
		long version = lifecycleVersion;
		@Nullable Current current = reader.current(id);
		// The read port can re-enter; never overwrite a receipt installed by it.
		if (version != lifecycleVersion || !matches(capture, current) || !current.ready()
			|| pending.containsKey(id) || pending.size() >= limit)
			return false;
		pending.put(id, capture);
		return true;
	}

	/** Attempts exactly one complete line, always re-reading authority and values. */
	public boolean attempt(MarkerId id) {
		@Nullable Pending capture = pending.get(id);
		if (capture == null || attempting.size() >= limit || !attempting.add(id)) return false;
		try {
			@Nullable Current before = reader.current(id);
			if (pending.get(id) != capture) return false;
			@Nullable Inputs inputs = inputs(capture, before);
			if (inputs == null) return false;
			Optional<Component> message;
			try {
				@Nullable Component target = formatter.name(inputs.nameJson()).orElse(null);
				if (target == null || pending.get(id) != capture) return false;
				Component author = Component.literal(capture.author());
				if (capture.content().kind() == PresentationReceiptContent.Kind.PROPERTIES)
					message = formatter.properties(author, target, inputs.projection(), capture.content().selectedRefs());
				else {
					InventoryCreationSnapshot creation = Objects.requireNonNull(inputs.creation());
					message = formatter.inventory(author, target, inventoryEntry(creation), creation.quantityUncertain())
						.map(ClientItemDisplay::freezeComponent);
				}
			} catch (RuntimeException unavailable) {
				Diagnostics.warn("chat/content-compose", "receipt",
					() -> "markerId=" + id.value() + " kind=" + capture.content().kind() + "; receipt remains pending", unavailable);
				return false; // No partial or ordinary fallback on formatting failure.
			}
			if (message == null || message.isEmpty() || pending.get(id) != capture) return false;
			@Nullable Current after = reader.current(id);
			if (pending.get(id) != capture) return false;
			@Nullable Inputs latest = inputs(capture, after);
			// Callbacks may reset/clear, revoke, remove, or replace a received value.
			if (pending.get(id) != capture || !inputs.equals(latest)) return false;
			pending.remove(id);
			// Remove before entering the sink, even if the sink re-enters or throws.
			sink.accept(message.orElseThrow());
			return true;
		} finally {
			attempting.remove(id);
		}
	}

	private static boolean matches(Pending capture, @Nullable Current current) {
		return current != null && current.epoch() == capture.epoch() && current.view() == capture.view()
			&& current.live() && capture.identity().equals(current.identity());
	}

	private @Nullable Inputs inputs(Pending capture, @Nullable Current current) {
		MarkerId id = capture.identity().id();
		if (!matches(capture, current)) {
			pending.remove(id, capture);
			return null;
		}
		if (!current.ready()) return null;
		// Check ALL authorization before completeness: a missing allowed ref must
		// not hide denial of another selection or the independent target name.
		if (!current.allows(NAME)) {
			pending.remove(id, capture);
			return null;
		}
		for (PresentationPropertyRef ref : capture.content().selectedRefs()) {
			if (!current.allows(ref) || requiredContextDenied(current, ref)) {
				pending.remove(id, capture);
				return null;
			}
		}
		boolean inventory = capture.content().kind() == PresentationReceiptContent.Kind.INVENTORY;
		if (inventory && current.inventoryAuthorization() == Authorization.DENIED) {
			pending.remove(id, capture);
			return null;
		}
		PresentationView view = freshProjection(current);
		if (!capture.identity().targetTypeId().equals(view.targetTypeId())
			|| !(view.property(NAME) instanceof PresentationValue.Text name)) return null;
		for (PresentationPropertyRef ref : capture.content().selectedRefs()) {
			@Nullable PresentationSection section = view.sections().get(ref.adapterId());
			if (section == null || view.property(ref) == null || section.annotations().get(ref) == null
				|| !requiredContextPresent(view, ref)) return null;
		}
		@Nullable InventoryCreationSnapshot creation = null;
		if (inventory) {
			if (current.inventoryAuthorization() != Authorization.ALLOWED) return null;
			creation = capture.content().inventorySnapshot();
			if (creation == null || !creation.equals(current.creationSnapshot())) return null;
		}
		return new Inputs(view, name.value(), creation);
	}
	private static ClientInventory.EntryView inventoryEntry(InventoryCreationSnapshot creation) {
		Objects.requireNonNull(creation, "creation");
		return new ClientInventory.EntryView(creation.resourceKind(), creation.opaqueKey(), creation.resourceId(), creation.label(),
			creation.displayJson(), creation.amount(), creation.aggregate(), null,
			creation.annotationPingType());
	}

	private static boolean requiredContextDenied(Current current, PresentationPropertyRef ref) {
		return ref.isRoot() && PresentationBasic.HEALTH.equals(ref.fieldId())
			&& !current.allows(PresentationPropertyRef.root(ref.adapterId(), PresentationBasic.MAX_HEALTH));
	}

	private static boolean requiredContextPresent(PresentationView view, PresentationPropertyRef ref) {
		return !ref.isRoot() || !PresentationBasic.HEALTH.equals(ref.fieldId())
			|| view.property(ref) instanceof PresentationValue.NumberValue
				&& view.field(ref.adapterId(), PresentationBasic.MAX_HEALTH) instanceof PresentationValue.NumberValue;
	}

	/** Optional formatter context may be omitted, but never stale or unauthorized. */
	private static PresentationView freshProjection(Current current) {
		Map<String, PresentationSection> sections = new LinkedHashMap<>();
		current.projection().sections().forEach((adapter, section) -> {
			if (section.stale()) return;
			Set<String> allowed = current.allowedFields().getOrDefault(adapter, Set.of());
			Map<String, PresentationValue> fields = new LinkedHashMap<>();
			section.fields().forEach((field, value) -> { if (allowed.contains(field)) fields.put(field, value); });
			if (fields.isEmpty()) return;
			Map<PresentationPropertyRef, String> annotations = new LinkedHashMap<>();
			section.annotations().forEach((ref, ping) -> {
				// Filtering keeps/removes whole roots; existing section paths remain valid.
				if (fields.containsKey(ref.fieldId()) && current.allows(ref)) annotations.put(ref, ping);
			});
			sections.put(adapter, new PresentationSection(adapter, section.schema(), fields, false, annotations));
		});
		return new PresentationView(current.projection().targetTypeId(), current.projection().defaultRef(),
			sections, current.projection().fieldLabels(), current.projection().childBlack());
	}

	/** Removal, EXPIRED, eviction and authorization revocation all use this hook. */
	public void cancel(MarkerId id) { lifecycleVersion++; pending.remove(id); }
	/** Accepted presentation RESET must cancel even when its new mask is looser. */
	public void reset() { clear(); }
	/** Disconnect, local store clear, and connection replacement. */
	public void clear() { lifecycleVersion++; pending.clear(); }
	/** Reconcile local housekeeping with actual live membership, never HUD visibility. */
	public void retainLive(Set<MarkerId> liveIds) {
		Objects.requireNonNull(liveIds, "liveIds");
		lifecycleVersion++;
		pending.keySet().retainAll(liveIds);
	}
	public int pendingCount() { return pending.size(); }
}
