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
	private final Map<String, PresentationUiProvider> providers = new LinkedHashMap<>();
	private String providerId = "default";
	private String receiveFingerprint;
	private long epoch;
	private long subscription;
	private long view;
	private long ticks;
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
		try {
			// The NeoForge factory tests mod presence and the supported Create version.
			// No Create class is linked by common, Fabric or Forge when absent.
			Object adapter = Class.forName(CREATE_FACTORY).getMethod("client").invoke(null);
			if (adapter instanceof PresentationAdapter present) registry.register(present);
		} catch (ReflectiveOperationException | LinkageError | IllegalArgumentException ignored) {
			// Basic remains independently usable.
		}
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
		compatible.clear();
		for (PresentationAdapter adapter : registry.all()) {
			if (!Objects.equals(packet.schemas().get(adapter.adapterId()), adapter.schema())) continue;
			List<PresentationField> advertised = packet.manifest().get(adapter.adapterId());
			if (advertised == null) continue;
			Map<String, PresentationField> local = new HashMap<>();
			for (PresentationField field : adapter.fields()) local.put(field.id(), field);
			Map<String, PresentationField> accepted = new LinkedHashMap<>();
			Set<String> seen = new HashSet<>();
			for (PresentationField field : advertised) {
				if (!seen.add(field.id())) { accepted.clear(); break; }
				PresentationField ours = local.get(field.id());
				if (ours != null && ours.kind() == field.kind()) accepted.put(field.id(), ours);
			}
			compatible.put(adapter.adapterId(), Map.copyOf(accepted));
		}
		// Without compatible Basic an initial cannot be decoded or presented.
		if (!compatible.containsKey(BASIC)) return false;
		epoch = packet.epoch();
		store.reset(epoch);
		offered = true;
		subscribe();
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

	private static List<String> defaultLabels(PresentationView view) {
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
