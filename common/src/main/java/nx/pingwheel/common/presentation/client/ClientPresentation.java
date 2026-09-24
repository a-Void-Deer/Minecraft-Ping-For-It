package nx.pingwheel.common.presentation.client;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationPolicy;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationStore;
import nx.pingwheel.common.presentation.PresentationValue;

/** One client connection's negotiated capabilities, retained values and local UI projection. */
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
	private final Supplier<PresentationSettings> receiveSettings;
	private final Supplier<PresentationSettings> displaySettings;
	private final Map<String, Map<String, PresentationField>> compatible = new LinkedHashMap<>();
	private final Map<String, Map<String, PresentationField>> acceptedServerCatalog = new LinkedHashMap<>();
	private final Map<String, PresentationUiProvider> providers = new LinkedHashMap<>();
	private String providerId = "default";
	private String receiveFingerprint;
	private long epoch;
	private long subscription;
	private long view;
	private long ticks;
	private long catalogRevision;
	private int helloAttempts;
	private boolean offered;
	private boolean ready;

	public ClientPresentation(Consumer<IPacket> sender, Supplier<PresentationSettings> receiveSettings,
		Supplier<PresentationSettings> displaySettings) {
		this.sender = Objects.requireNonNull(sender, "sender");
		this.receiveSettings = Objects.requireNonNull(receiveSettings, "receiveSettings");
		this.displaySettings = Objects.requireNonNull(displaySettings, "displaySettings");
		registry.register(new BasicDescriptor());
		registerOptionalCreate();
		registerProvider("default", ClientPresentation::defaultLabels);
		receiveFingerprint = receive().fingerprint();
	}

	private void registerOptionalCreate() {
		PresentationAdapter present = createOptionalAdapter();
		if (present != null) registry.register(present);
	}

	/**
	 * Resolves the optional Create client adapter without constructing a
	 * connection runtime, so the settings UI can show the local preview while
	 * offline. The NeoForge factory tests mod presence and the supported Create
	 * version; no Create class is linked by common, Fabric or Forge when absent.
	 */
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
	 * The local Basic plus optional Create manifest without a connection
	 * runtime, for the offline settings preview. No packet, store, or runtime is
	 * created.
	 */
	public static List<PresentationAdapter> localManifest() {
		List<PresentationAdapter> adapters = new ArrayList<>();
		adapters.add(new BasicDescriptor());
		PresentationAdapter create = createOptionalAdapter();
		if (create != null) adapters.add(create);
		return List.copyOf(adapters);
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
		String next = receive().fingerprint();
		if (!next.equals(receiveFingerprint)) {
			receiveFingerprint = next;
			// Immediately delete denied fields even from frozen snapshots. A later
			// loosening cannot restore values that were deleted after server expiry.
			store.restrict((adapter, id) -> allowedToReceive(adapter, id));
			if (offered) subscribe();
		}
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
		acceptedServerCatalog.clear();
		catalogRevision++;
		epoch = subscription = view = 0;
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
					// Subscription/security keeps the local descriptor; the UI
					// catalogue keeps the server's advertised default and label.
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
		subscribe();
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

	private void subscribe() {
		if (subscription == Long.MAX_VALUE) { ready = false; return; }
		ready = false;
		subscription++;
		Set<String> fields = new LinkedHashSet<>();
		compatible.forEach((adapter, descriptors) -> descriptors.keySet().forEach(id -> {
			if (allowedToReceive(adapter, id)) fields.add(id);
		}));
		sender.accept(PresentationC2SPacket.subscribe(epoch, subscription, fields));
	}

	/** A reset is the only transition that enables intents and the new view. */
	public boolean reset(PresentationS2CPacket packet) {
		if (!offered || packet == null || packet.isCorrupt()
			|| packet.kind() != PresentationS2CPacket.Kind.RESET || packet.epoch() != epoch
			|| packet.subscription() != subscription || packet.view() < view) return false;
		store.generation(epoch, subscription, packet.view());
		view = packet.view();
		ready = true;
		return true;
	}

	public boolean current(PresentationS2CPacket packet) {
		return ready && packet != null && !packet.isCorrupt()
			&& packet.epoch() == epoch && packet.subscription() == subscription
			&& packet.view() == view;
	}

	/** Initial Basic and marker metadata are decoded before either is applied. */
	public boolean initial(PresentationS2CPacket packet) {
		if (!current(packet) || packet.kind() != PresentationS2CPacket.Kind.CREATED
			|| packet.snapshot() == null || packet.markerId() == null
			|| !packet.markerId().equals(packet.snapshot().id())) return false;
		PresentationSection basic = decode(packet.sectionBytes(), BASIC);
		if (basic == null) return false;
		long id = packet.markerId().value();
		if (!store.isKnown(id)) {
			store.initial(epoch, subscription, view, id, basic);
			return store.isKnown(id);
		}
		if (store.isFrozen(id)) return false;
		// Same-id baseline after a generation change updates only the presentation;
		// the caller must not re-apply the marker or renew its visual deadline.
		return store.replace(epoch, subscription, view, id, Math.max(1, packet.revision()), basic);
	}

	public boolean section(PresentationS2CPacket packet) {
		if (!current(packet) || packet.kind() != PresentationS2CPacket.Kind.SECTION || packet.markerId() == null
			|| !store.isKnown(packet.markerId().value())) return false;
		PresentationSection section = decode(packet.sectionBytes(), null);
		if (section == null) return false;
		return store.replace(epoch, subscription, view, packet.markerId().value(), packet.revision(), section);
	}

	public void removed(MarkerId id, long revision, boolean expired) {
		store.clear(epoch, id.value(), revision, expired);
	}

	public void evict(MarkerId id) { store.evict(id.value()); }

	private PresentationSection decode(byte[] bytes, String expectedAdapter) {
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
			PresentationSection decoded = PresentationCodec.read(buf,
				id -> fields.containsKey(id) && allowedToReceive(adapter, id));
			if (buf.isReadable()) return null;
			Map<String, PresentationValue> checked = new LinkedHashMap<>();
			decoded.fields().forEach((id, value) -> {
				if (fields.get(id).accepts(value)) checked.put(id, value);
			});
			return new PresentationSection(adapter, schema, checked, decoded.stale());
		} catch (RuntimeException invalid) {
			return null;
		} finally {
			buf.release();
		}
	}

	private boolean allowedToReceive(String adapter, String id) {
		Map<String, PresentationField> fields = compatible.get(adapter);
		return fields != null && fields.containsKey(id) && receive().policy().allows(id, true);
	}

	private PresentationSettings receive() {
		PresentationSettings settings = receiveSettings.get();
		return settings == null ? PresentationSettings.clientDefaults() : settings;
	}

	private PresentationSettings display() {
		PresentationSettings settings = displaySettings.get();
		return settings == null ? PresentationSettings.clientDefaults() : settings;
	}

	/** Apply display rules on each access, including local edits before the next tick. */
	public PresentationView view(MarkerId id) {
		if (id == null || !store.isKnown(id.value())) return PresentationView.empty();
		PresentationPolicy policy = display().policy();
		Map<String, PresentationSection> shown = new LinkedHashMap<>();
		store.sections(id.value()).forEach((adapter, entry) -> {
			Map<String, PresentationValue> fields = new LinkedHashMap<>();
			entry.section().fields().forEach((field, value) -> {
				if (allowedToReceive(adapter, field) && policy.allows(field, true)) fields.put(field, value);
			});
			if (!fields.isEmpty()) shown.put(adapter, new PresentationSection(adapter,
				entry.section().schema(), fields, entry.section().stale()));
		});
		return shown.isEmpty() ? PresentationView.empty() : new PresentationView(shown);
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
		List<String> labels = new ArrayList<>();
		var health = view.field(BASIC, "minecraft:entity.health");
		var maximum = view.field(BASIC, "minecraft:entity.max_health");
		if (health instanceof PresentationValue.NumberValue hp
			&& maximum instanceof PresentationValue.NumberValue max) {
			labels.add("HP %.1f / %.1f".formatted(hp.value(), max.value()));
		}
		var speed = view.field("create:presentation", "create:kinetic.speed");
		if (speed instanceof PresentationValue.RecordValue record
			&& record.values().get("effective_rpm") instanceof PresentationValue.NumberValue rpm) {
			labels.add("%.1f RPM".formatted(rpm.value()));
		}
		for (String field : List.of("create:inventory.summary", "create:fluid.summary")) {
			var summary = view.field("create:presentation", field);
			if (summary instanceof PresentationValue.RecordValue record
				&& record.values().get("counts") instanceof PresentationValue.RecordValue counts) {
				labels.add((field.contains("inventory") ? "Items: " : "Fluids: ")
					+ counts.values().size() + " types"
					+ (record.values().get("partial") instanceof PresentationValue.Flag flag && flag.value() ? " (partial)" : ""));
			}
		}
		return labels;
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
