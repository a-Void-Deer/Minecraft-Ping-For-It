package nx.pingwheel.common.presentation.client;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationStore;
import nx.pingwheel.common.presentation.PresentationValue;

/** One client connection's negotiated capabilities, server mask and retained presentation. */
public final class ClientPresentation {
	public static final String BASIC = "minecraft:basic";
	public static final String NAME = "minecraft:target.name";
	private static final int BASIC_SCHEMA = 1;
	private static final int MAX_HELLO_ATTEMPTS = 5;
	private static final long HELLO_RETRY_TICKS = 40;
	private static final String CREATE_FACTORY =
		"nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAdapters";

	private final PresentationRegistry registry = new PresentationRegistry();
	private final PresentationStore store = new PresentationStore();
	private final Consumer<IPacket> sender;
	private Map<String, Map<String, Set<String>>> mask = Map.of();
	private final Map<String, Map<String, PresentationField>> compatible = new LinkedHashMap<>();
	private final Map<String, Map<String, PresentationField>> acceptedServerCatalog = new LinkedHashMap<>();
	private final Map<String, PresentationUiProvider> providers = new LinkedHashMap<>();
	private String providerId = "default";
	private long epoch;
	private long view;
	private long ticks;
	private long catalogRevision;
	private int helloAttempts;
	private boolean offered;
	private boolean ready;

	public ClientPresentation(Consumer<IPacket> sender) {
		this.sender = Objects.requireNonNull(sender, "sender");
		registry.register(new BasicDescriptor());
		registerOptionalCreate();
		registerProvider("default", ClientPresentation::defaultLabels);
	}

	private void registerOptionalCreate() {
		PresentationAdapter present = createOptionalAdapter();
		if (present != null) registry.register(present);
	}

	/** Optional NeoForge adapter loading is lazy; Basic works when Create is absent. */
	private static PresentationAdapter createOptionalAdapter() {
		try {
			Object adapter = Class.forName(CREATE_FACTORY).getMethod("client").invoke(null);
			return adapter instanceof PresentationAdapter present ? present : null;
		} catch (ReflectiveOperationException | LinkageError | IllegalArgumentException ignored) {
			// Basic remains independently usable.
			return null;
		}
	}

	/** The local registered manifest, immutable; never a store or world value. */
	public List<PresentationAdapter> manifest() {
		return List.copyOf(registry.all());
	}

	/**
	 * The server-advertised fields accepted from the first valid offer, with the
	 * server's own default and label metadata after the local id/kind
	 * compatibility check. Empty before an offer and after {@link #close()};
	 * never exposes the retained value store.
	 */
	public PresentationFieldCatalog acceptedCatalog() {
		if (acceptedServerCatalog.isEmpty()) return PresentationFieldCatalog.empty();
		Map<String, List<PresentationField>> advertised = new LinkedHashMap<>();
		acceptedServerCatalog.forEach((adapter, fields) -> advertised.put(adapter, List.copyOf(fields.values())));
		return PresentationFieldCatalog.ofAccepted(advertised);
	}

	/** Monotonic stamp of accepted-catalog changes; cheap UI change detection. */
	public long catalogRevision() {
		return catalogRevision;
	}

	/**
	 * True after the first valid offer established this connection's catalog,
	 * even when that offer contained no compatible field. The UI must not show
	 * an accepted empty catalog as an authoritative empty list before an offer.
	 */
	public boolean catalogOffered() {
		return offered;
	}

	public void tick(boolean connected) {
		if (!connected) return;
		ticks++;
		if (!offered && helloAttempts < MAX_HELLO_ATTEMPTS
			&& (helloAttempts == 0 || ticks % HELLO_RETRY_TICKS == 0)) {
			Map<String, Integer> schemas = new LinkedHashMap<>();
			for (PresentationAdapter adapter : registry.all()) schemas.put(adapter.adapterId(), adapter.schema());
			sender.accept(PresentationC2SPacket.hello(schemas));
			helloAttempts++;
		}
	}

	public void close() {
		store.reset(0);
		compatible.clear();
		mask = Map.of();
		acceptedServerCatalog.clear();
		catalogRevision++;
		epoch = view = 0;
		ticks = 0;
		helloAttempts = 0;
		offered = ready = false;
	}

	public boolean ready() { return ready; }
	public long epoch() { return epoch; }
	/** Package-private verification seam; UI callers only receive {@link #view}. */
	PresentationStore store() { return store; }

	/** Only the first valid offer for this connection opens negotiation. */
	public boolean offer(PresentationS2CPacket packet) {
		if (packet == null || packet.isCorrupt() || packet.kind() != PresentationS2CPacket.Kind.OFFER || offered
			|| helloAttempts == 0) return false;
		// A directly constructed packet bypasses the wire decoder's bounds; a
		// malformed manifest is rejected as a whole before any state changes.
		if (!validOfferStructure(packet)) return false;

		Map<String, Map<String, PresentationField>> nextCompatible = new LinkedHashMap<>();
		Map<String, Map<String, PresentationField>> nextAccepted = new LinkedHashMap<>();
		for (PresentationAdapter adapter : registry.all()) {
			if (!Objects.equals(packet.schemas().get(adapter.adapterId()), adapter.schema())) continue;
			List<PresentationField> advertised = packet.manifest().get(adapter.adapterId());
			if (advertised == null) continue;
			Map<String, PresentationField> local = new HashMap<>();
			for (PresentationField field : adapter.fields()) local.put(field.id(), field);
			Map<String, PresentationField> accepted = new LinkedHashMap<>();
			Map<String, PresentationField> serverAdvertised = new LinkedHashMap<>();
			for (PresentationField field : advertised) {
				PresentationField ours = local.get(field.id());
				if (ours != null && ours.kind() == field.kind()) {
					// The local descriptor enforces kind compatibility. The UI
					// catalogue keeps the server's advertised metadata.
					accepted.put(field.id(), ours);
					serverAdvertised.put(field.id(), field);
				}
			}
			nextCompatible.put(adapter.adapterId(), Map.copyOf(accepted));
			if (!serverAdvertised.isEmpty()) {
				nextAccepted.put(adapter.adapterId(), Map.copyOf(serverAdvertised));
			}
		}
		// Without compatible Basic an initial cannot be decoded or presented.
		if (!nextCompatible.containsKey(BASIC)) return false;

		// Publish the compatible descriptors and the server metadata atomically.
		compatible.clear();
		compatible.putAll(nextCompatible);
		acceptedServerCatalog.clear();
		acceptedServerCatalog.putAll(nextAccepted);
		epoch = packet.epoch();
		store.reset(epoch);
		offered = true;
		catalogRevision++;
		return true;
	}

	/**
	 * Mirrors the wire decoder's offer bounds so a directly constructed packet
	 * cannot publish a partial catalog. Duplicate adapter ids cannot exist in a
	 * map; a duplicate field id inside one adapter and a manifest/schema key
	 * mismatch are malformed and reject the whole offer. A structurally valid
	 * offer that merely lacks a compatible Basic adapter is ignored by the
	 * caller, not published.
	 */
	private static boolean validOfferStructure(PresentationS2CPacket packet) {
		Map<String, List<PresentationField>> manifest = packet.manifest();
		Map<String, Integer> schemas = packet.schemas();
		if (manifest.size() > PresentationC2SPacket.MAX_ADAPTERS) return false;
		if (!schemas.keySet().equals(manifest.keySet())) return false;
		int total = 0;
		for (var entry : manifest.entrySet()) {
			try {
				PresentationIds.validate(entry.getKey());
			} catch (RuntimeException invalid) {
				return false;
			}
			Integer schema = schemas.get(entry.getKey());
			if (schema == null || schema < 1 || schema > 255) return false;
			List<PresentationField> fields = entry.getValue();
			if (fields == null || fields.size() > PresentationCodec.MAX_FIELDS) return false;
			total += fields.size();
			if (total > PresentationC2SPacket.MAX_FIELDS) return false;
			Set<String> seen = new HashSet<>();
			for (PresentationField field : fields) {
				if (field == null || !seen.add(field.id())) return false;
			}
		}
		return true;
	}

	/** A reset is the only transition that enables intents and the new view. */
	public boolean reset(PresentationS2CPacket packet) {
		if (!offered || packet == null || packet.isCorrupt()
			|| packet.kind() != PresentationS2CPacket.Kind.RESET || packet.epoch() != epoch
			|| packet.view() < view || (ready && packet.view() == view)) return false;
		// A structurally valid but incomplete mask is deny-all for missing types.
		Map<String, Map<String, Set<String>>> nextMask = packet.mask();
		if (nextMask == null) nextMask = Map.of();
		mask = compatibleMask(nextMask);
		store.restrict(mask);
		store.generation(epoch, packet.view());
		view = packet.view();
		ready = true;
		return true;
	}

	public boolean current(PresentationS2CPacket packet) {
		return ready && packet != null && !packet.isCorrupt()
			&& packet.epoch() == epoch
			&& packet.view() == view;
	}

	/** Initial Basic and marker metadata are decoded before either is applied. */
	public boolean initial(PresentationS2CPacket packet) {
		if (!current(packet) || packet.kind() != PresentationS2CPacket.Kind.CREATED
			|| packet.snapshot() == null || packet.markerId() == null
			|| !packet.markerId().equals(packet.snapshot().id())) return false;
		PresentationSection basic = decode(packet.sectionBytes(), BASIC, packet.snapshot().targetTypeId());
		if (basic == null) return false;
		long id = packet.markerId().value();
		if (!store.isKnown(id)) {
			if (!nx.pingwheel.common.presentation.PresentationSettings.isKnownTargetType(packet.snapshot().targetTypeId())
				|| packet.defaultRef() == null) return false;
			store.initial(epoch, view, id, packet.snapshot().targetTypeId(), packet.defaultRef(),
				project(basic, packet.snapshot().targetTypeId()));
			return store.isKnown(id);
		}
		if (store.isFrozen(id) || store.targetTypeId(id) == null) return false;
		// Same-id baseline after a generation change updates only the presentation;
		// the caller must not re-apply the marker or renew its visual deadline.
		return store.replace(epoch, view, id, Math.max(1, packet.revision()),
			project(basic, store.targetTypeId(id)));
	}

	public boolean section(PresentationS2CPacket packet) {
		if (!current(packet) || packet.kind() != PresentationS2CPacket.Kind.SECTION || packet.markerId() == null
			|| store.targetTypeId(packet.markerId().value()) == null) return false;
		PresentationSection section = decode(packet.sectionBytes(), null, store.targetTypeId(packet.markerId().value()));
		if (section == null) return false;
		return store.replace(epoch, view, packet.markerId().value(), packet.revision(),
			project(section, store.targetTypeId(packet.markerId().value())));
	}

	public void removed(MarkerId id, long revision, boolean expired) {
		store.clear(epoch, id.value(), revision, expired);
	}

	public void evict(MarkerId id) { store.evict(id.value()); }

	private PresentationSection decode(byte[] bytes, String expectedAdapter, String targetTypeId) {
		if (bytes == null || bytes.length == 0 || bytes.length > PresentationCodec.MAX_SECTION_BYTES + 5) return null;
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
		try {
			int start = buf.readerIndex();
			int size = buf.readVarInt();
			if (size < 0 || size > PresentationCodec.MAX_SECTION_BYTES || size != buf.readableBytes()) return null;
			String adapter = buf.readUtf(193);
			int schema = buf.readVarInt();
			if (expectedAdapter != null && !expectedAdapter.equals(adapter)) return null;
			PresentationAdapter local = registry.get(adapter);
			if (local == null || local.schema() != schema || !compatible.containsKey(adapter)) return null;
			buf.readerIndex(start);
			Map<String, PresentationField> fields = compatible.get(adapter);
			Set<String> allowed = mask.getOrDefault(targetTypeId, Map.of()).getOrDefault(adapter, Set.of());
			PresentationSection decoded = PresentationCodec.read(buf,
				id -> fields.containsKey(id) && allowed.contains(id));
			if (buf.isReadable()) return null;
			Map<String, PresentationValue> checked = new LinkedHashMap<>();
			decoded.fields().forEach((id, value) -> {
				if (fields.get(id).accepts(value)) checked.put(id, value);
			});
			Map<PresentationPropertyRef, String> annotations = new LinkedHashMap<>();
			decoded.annotations().forEach((ref, ping) -> {
				if (checked.containsKey(ref.fieldId())) annotations.put(ref, ping);
			});
			return new PresentationSection(adapter, schema, checked, decoded.stale(), annotations);
		} catch (RuntimeException invalid) {
			return null;
		} finally {
			buf.release();
		}
	}

	private PresentationSection project(PresentationSection section, String targetTypeId) {
		Set<String> allowed = mask.getOrDefault(targetTypeId, Map.of())
			.getOrDefault(section.adapterId(), Set.of());
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		section.fields().forEach((id, value) -> {
			if (allowed.contains(id)) fields.put(id, value);
		});
		Map<PresentationPropertyRef, String> annotations = new LinkedHashMap<>();
		section.annotations().forEach((ref, ping) -> {
			if (fields.containsKey(ref.fieldId())) annotations.put(ref, ping);
		});
		return new PresentationSection(section.adapterId(), section.schema(), fields, section.stale(), annotations);
	}

	/** Never promote a mask entry that the accepted offer could not decode. */
	private Map<String, Map<String, Set<String>>> compatibleMask(
		Map<String, Map<String, Set<String>>> received) {
		Map<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
		received.forEach((type, adapters) -> {
			Map<String, Set<String>> allowed = new LinkedHashMap<>();
			adapters.forEach((adapter, fields) -> {
				Map<String, PresentationField> descriptors = compatible.get(adapter);
				if (descriptors != null) {
					Set<String> accepted = new HashSet<>(fields);
					accepted.retainAll(descriptors.keySet());
					allowed.put(adapter, Set.copyOf(accepted));
				}
			});
			result.put(type, Map.copyOf(allowed));
		});
		return Map.copyOf(result);
	}

	/** Only values authorized by the latest server mask may reach a UI provider. */
	public PresentationView view(MarkerId id) {
		if (id == null || !store.isKnown(id.value())) return PresentationView.empty();
		Map<String, PresentationSection> shown = new LinkedHashMap<>();
		store.sections(id.value()).forEach((adapter, entry) -> {
			PresentationSection authorized = project(entry.section(), store.targetTypeId(id.value()));
			if (!authorized.fields().isEmpty()) shown.put(adapter, authorized);
		});
		Map<String, Map<String, String>> labels = new LinkedHashMap<>();
		acceptedServerCatalog.forEach((adapter, descriptors) -> {
			Map<String, String> names = new LinkedHashMap<>();
			descriptors.forEach((fieldId, descriptor) -> names.put(fieldId, descriptor.label()));
			labels.put(adapter, names);
		});
		return new PresentationView(store.targetTypeId(id.value()), store.defaultRef(id.value()), shown, labels);
	}

	/** Provider changes affect presentation only: no packets or source work. */
	public void registerProvider(String id, PresentationUiProvider provider) {
		Objects.requireNonNull(id, "id");
		if (id.isBlank() || providers.containsKey(id)) throw new IllegalArgumentException("duplicate UI provider");
		providers.put(id, Objects.requireNonNull(provider, "provider"));
	}

	public boolean selectProvider(String id) {
		if (!providers.containsKey(id)) return false;
		providerId = id;
		return true;
	}

	public List<String> labels(PresentationView projection) {
		try {
			List<String> supplied = providers.get(providerId).labels(Objects.requireNonNull(projection));
			if (supplied == null) return List.of();
			List<String> bounded = new ArrayList<>();
			for (String line : supplied) {
				if (line != null && !line.isBlank()) bounded.add(line.substring(0, Math.min(64, line.length())));
				if (bounded.size() == 3) break;
			}
			return List.copyOf(bounded);
		} catch (RuntimeException ignored) {
			return List.of();
		}
	}

	/** Package-private verification seam for the built-in label projection. */
	static List<String> defaultLabels(PresentationView view) {
		return PresentationPropertyFormatter.labels(view);
	}

	/** Manifest-only Basic descriptor; no client world sampling is possible. */
	private static final class BasicDescriptor implements PresentationAdapter {
		private static final List<PresentationField> FIELDS = List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Target name"),
			new PresentationField("minecraft:entity.type", PresentationField.Kind.TEXT, true, 0, "Entity type"),
			new PresentationField("minecraft:entity.health", PresentationField.Kind.NUMBER, true, 0, "Health"),
			new PresentationField("minecraft:entity.max_health", PresentationField.Kind.NUMBER, true, 0, "Maximum health"),
			new PresentationField("minecraft:item.id", PresentationField.Kind.TEXT, true, 0, "Item"),
			new PresentationField("minecraft:item.count", PresentationField.Kind.NUMBER, true, 0, "Count"),
			new PresentationField("minecraft:item.icon", PresentationField.Kind.FLAG, true, 0, "Item icon"),
			new PresentationField("minecraft:block.state", PresentationField.Kind.RECORD, true, 0, "Block state"));
		@Override public String adapterId() { return BASIC; }
		@Override public String modId() { return "minecraft"; }
		@Override public int schema() { return BASIC_SCHEMA; }
		@Override public int minUpdateIntervalTicks() { return 1; }
		@Override public List<PresentationField> fields() { return FIELDS; }
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			return null;
		}
	}
}
