package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.preview.ClientPresentationPreview;
import nx.pingwheel.common.presentation.preview.PreviewPropertyEntries;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

/** One held target's authorized observation adapter. No marker data or sender is exposed to the facade. */
final class NativeSelectorContent implements SpatialSelectorSession.ContentPort<ClientInventory.PreviewEntryReference> {
	private final CapturedPingContext capture;
	private final ClientPresentationPreview preview;
	private final Supplier<ClientInventory> inventory;
	private final Function<CapturedPingContext, List<PingType>> propertyTypes;
	private final Function<PresentationPropertyRef, Component> fieldLabel;
	private final Function<String, Component> targetName;
	private final Map<String, Component> labels = new LinkedHashMap<>();
	private long request = ClientInventory.NO_REQUEST;
	private long revision;
	private ClientInventory.Preview previousInventory;
	private SpatialSelectorSession.ContentProjection<ClientInventory.PreviewEntryReference> previous;
	private boolean closed;

	NativeSelectorContent(CapturedPingContext capture, ClientPresentationPreview preview,
		Supplier<ClientInventory> inventory, Function<CapturedPingContext, List<PingType>> propertyTypes,
		Function<PresentationPropertyRef, Component> fieldLabel,
		Function<String, Component> targetName) {
		this.capture = capture;
		this.preview = preview;
		this.inventory = inventory;
		this.propertyTypes = propertyTypes;
		this.fieldLabel = fieldLabel;
		this.targetName = targetName;
	}

	void begin(Object levelIdentity) {
		if (closed) return;
		try {
			if (preview != null) preview.begin(new ClientPresentationPreview.Binding(capture.token(),
				capture.resolvedTarget().target(), capture.resolvedTarget().targetType().id(), levelIdentity));
		} finally {
			if (!closed) {
				ClientInventory current = inventory.get();
				if (current != null) {
					long opened = current.open(capture);
					if (closed) current.close(opened);
					else request = opened;
				}
			}
		}
	}

	long requestId() { return request; }
	void tick() { if (!closed && preview != null) preview.tick(); }
	void close() {
		if (closed) return;
		closed = true;
		try { if (preview != null) preview.abort(); }
		finally {
			ClientInventory current = inventory.get();
			if (current != null && request != ClientInventory.NO_REQUEST) current.close(request);
			labels.clear(); previous = null; previousInventory = null;
		}
	}

	Optional<nx.pingwheel.common.presentation.PresentationPropertyIntent> intent(PresentationPropertyRef ref, String annotation) {
		return closed || preview == null ? Optional.empty() : preview.intent(capture.token(), ref, annotation);
	}

	/** Null remains a defensive fallback; synthesized Back entries carry a localized key. */
	Component label(String key) {
		return key == null ? Component.empty() : labels.getOrDefault(key, Component.translatable(key));
	}

	@Override
	public SpatialSelectorSession.ContentProjection<ClientInventory.PreviewEntryReference> read(
		SpatialSelectorSession.CapturedTarget target, SpatialSelectorSession.ContentFence fence) {
		if (closed) return null;
		if (!capture.resolvedTarget().equals(target.resolvedTarget()) || !target.candidateId().equals(fence.candidateId())) return null;
		List<PingType> types = propertyTypes.apply(capture);
		PingType defaultType = types.isEmpty() ? null : types.getFirst();
		List<SpatialSelectorSession.Property> properties = new ArrayList<>();
		labels.clear();
		if (preview != null) preview.projection().ifPresent(projection -> {
			for (var entry : PreviewPropertyEntries.of(projection)) {
				var ref = entry.ref();
				if (ref.isRoot() && ref.fieldId().equals("minecraft:entity.health")
					&& projection.property(PresentationPropertyRef.root(ref.adapterId(), "minecraft:entity.max_health")).isEmpty()) continue;
				String id = propertyKey(ref);
				String labelId = "selector-property:" + id;
				Component name = ref.recordPath().isEmpty()
					? fieldLabel.apply(ref)
					: Component.literal(ref.recordPath().getLast());
				Component value = propertyLabel(projection, ref, entry.observed().value(), name);
				if (value == null) continue;
				labels.put(labelId, value);
				properties.add(new SpatialSelectorSession.Property(id, labelId, ref, entry.observed().value(), types, defaultType));
			}
		});
		ClientInventory current = inventory.get();
		ClientInventory.Preview state = current == null || request == ClientInventory.NO_REQUEST ? null : current.preview(request);
		SpatialSelectorSession.InventoryPreview<ClientInventory.PreviewEntryReference> list = null;
		boolean reset = state != null && previousInventory != null
			&& (state.baselineId() != previousInventory.baselineId() || state.statusRevision() != previousInventory.statusRevision()
				|| !state.entries().stream().map(ClientInventory.EntryView::key).collect(java.util.stream.Collectors.toSet())
					.containsAll(previousInventory.entries().stream().map(ClientInventory.EntryView::key).toList()));
		if (state != null) {
			Map<String, ClientInventory.PreviewEntryReference> references = new LinkedHashMap<>();
			current.selectable(request).forEach(reference -> references.put(reference.entryKey(), reference));
			List<SpatialSelectorSession.InventoryRow<ClientInventory.PreviewEntryReference>> rows = new ArrayList<>();
			for (var entry : state.entries()) {
				var reference = references.get(entry.key());
				if (reference != null) rows.add(new SpatialSelectorSession.InventoryRow<>(entry.key(), entry.label(),
						entry.count() < 0 ? null : entry.count(), entry.itemId(), entry.fallback() ? Status.COMPONENT_TOO_LONG
							: entry.quality() == null ? Status.READY : Status.fromName(entry.quality().name()), reference));
			}
			Status status = state.completeScan() && state.status() == InventoryS2CPacket.Status.READY ? Status.READY
				: Status.fromName(state.status().name());
			if (!state.completeScan() && status == Status.READY) status = Status.UPDATING;
			list = new SpatialSelectorSession.InventoryPreview<>("settings.pingforit.presentation.field.pingforit_inventory_items.name",
				status, rows, types, defaultType);
		}
		previousInventory = state;
		var next = new SpatialSelectorSession.ContentProjection<>(fence, revision + 1, reset,
			Status.READY, properties, list);
		if (previous != null && !reset && Objects.equals(previous.properties(), next.properties())
			&& Objects.equals(previous.inventory(), next.inventory())) return null;
		revision++;
		previous = next;
		return next;
	}

	private Component propertyLabel(ClientPresentationPreview.Projection projection, PresentationPropertyRef ref,
		PresentationValue value, Component name) {
		if (ref.isRoot() && ref.fieldId().equals("minecraft:target.name") && value instanceof PresentationValue.Text json)
			return targetName.apply(json.value());
		if (ref.isRoot() && ref.fieldId().equals("minecraft:entity.health") && value instanceof PresentationValue.NumberValue health) {
			var maximum = projection.property(PresentationPropertyRef.root(ref.adapterId(), "minecraft:entity.max_health"));
			return maximum.filter(entry -> entry.value() instanceof PresentationValue.NumberValue)
				.map(entry -> Component.translatable("presentation.pingforit.format.health_max", valueLabel(health), valueLabel(entry.value()))).orElse(null);
		}
		if (ref.isRoot() && ref.fieldId().equals("minecraft:item.id") && value instanceof PresentationValue.Text item) {
			Component itemName = itemName(item.value());
			var count = projection.property(PresentationPropertyRef.root(ref.adapterId(), "minecraft:item.count"));
			return count.filter(entry -> entry.value() instanceof PresentationValue.NumberValue)
				.map(entry -> Component.translatable("presentation.pingforit.format.item_count", itemName, valueLabel(entry.value())))
				.orElseGet(() -> Component.translatable("presentation.pingforit.format.item", itemName));
		}
		return name.copy().append(": ").append(valueLabel(value));
	}

	private static Component itemName(String value) {
		ResourceLocation id = ResourceLocation.tryParse(value);
		return id != null && BuiltInRegistries.ITEM.containsKey(id)
			? BuiltInRegistries.ITEM.get(id).getDescription() : Component.literal(shortText(value));
	}

	private static String shortText(String value) {
		String safe = value.replaceAll("[\\p{Cntrl}]", " ").strip();
		return safe.substring(0, Math.min(64, safe.length()));
	}

	/** Length framing preserves literal record keys, including commas and path separators. */
	private static String propertyKey(PresentationPropertyRef ref) {
		StringBuilder key = new StringBuilder();
		for (String part : java.util.stream.Stream.concat(java.util.stream.Stream.of(ref.adapterId(), ref.fieldId()), ref.recordPath().stream()).toList())
			key.append(part.length()).append(':').append(part);
		return key.toString();
	}

	private static Component valueLabel(PresentationValue value) {
		if (value instanceof PresentationValue.Text text) {
			return Component.literal(shortText(text.value()));
		}
		if (value instanceof PresentationValue.NumberValue number)
			return Component.literal(java.math.BigDecimal.valueOf(number.value()).stripTrailingZeros().toPlainString());
		if (value instanceof PresentationValue.Flag flag)
			return Component.translatable("presentation.pingforit." + (flag.value() ? "yes" : "no"));
		int size = value instanceof PresentationValue.RecordValue record ? record.values().size()
			: ((PresentationValue.Sequence) value).values().size();
		return Component.literal(Integer.toString(size));
	}
}
