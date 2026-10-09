package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

/**
 * Manifest shared by server and client. The client uses a manifest-only
 * instance; the server injects a lazy, server-thread source callback. Neither
 * this class nor its manifest links Create or Minecraft classes.
 */
public final class CreatePresentationAdapter implements PresentationAdapter {
	private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	public static final String ADAPTER_ID = "create:presentation";
	public static final int SCHEMA = 1;
	public static final int MIN_UPDATE_INTERVAL_TICKS = 10;
	public static final String SPEED = "create:kinetic.speed";
	public static final String HAS_NETWORK = "create:kinetic.has_network";
	public static final String OVERSTRESSED = "create:kinetic.overstressed";
	public static final String STRESS = "create:kinetic.stress";
	public static final String CAPACITY = "create:kinetic.capacity";
	public static final String INVENTORY = "create:inventory.summary";
	public static final String FLUID = "create:fluid.summary";

	private static final List<PresentationField> FIELDS = List.of(
		new PresentationField(SPEED, PresentationField.Kind.RECORD, true, 0, "Kinetic speed (RPM)"),
		new PresentationField(HAS_NETWORK, PresentationField.Kind.FLAG, true, 0, "Kinetic network"),
		new PresentationField(OVERSTRESSED, PresentationField.Kind.FLAG, true, 0, "Overstressed"),
		new PresentationField(STRESS, PresentationField.Kind.NUMBER, true, 0, "Cached network stress (SU)"),
		new PresentationField(CAPACITY, PresentationField.Kind.NUMBER, true, 0, "Cached network capacity (SU)"),
		new PresentationField(INVENTORY, PresentationField.Kind.RECORD, false, 0, "Vault items by registry ID"),
		new PresentationField(FLUID, PresentationField.Kind.RECORD, false, 0, "Tank fluids by registry ID (mB)"));

	@FunctionalInterface
	public interface Source {
		/** Return null when the source is temporarily unavailable. */
		Observation observe(DetachedTarget target, Set<String> demand, CaptureBudget budget);
		/** Preview may observe an uncommitted candidate through a safe provider source. */
		default Observation observePreview(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			return observe(target, demand, budget);
		}
	}

	/** Pure detached values; no block entities, world, handlers or stacks escape. */
	public record Observation(Speed speed, Boolean hasNetwork, Boolean overStressed,
		Double stress, Double capacity, Summary items, Summary fluids) {
		public Observation {
			if ((stress != null && (!Double.isFinite(stress) || stress < 0))
				|| (capacity != null && (!Double.isFinite(capacity) || capacity < 0))) {
				throw new IllegalArgumentException("invalid cached network totals");
			}
		}
	}

	public record Speed(double effectiveRpm, double theoreticalRpm, boolean moving) {
		public Speed {
			if (!Double.isFinite(effectiveRpm) || !Double.isFinite(theoreticalRpm)) {
				throw new IllegalArgumentException("invalid kinetic speed");
			}
		}
	}

	/** Unavailable and verified-empty summaries are distinct. Counts are detached. */
	public record Summary(Map<String, Long> counts, boolean partial, int scanned, boolean available) {
		public Summary {
			counts = Map.copyOf(Objects.requireNonNull(counts, "counts"));
			if (scanned < 0 || (!available && !counts.isEmpty())) {
				throw new IllegalArgumentException("invalid summary state");
			}
			for (var entry : counts.entrySet()) {
				if (entry.getKey() == null || entry.getKey().length() > 128
					|| entry.getValue() == null || entry.getValue() < 0
					|| entry.getValue() > (1L << 53) - 1) {
					throw new IllegalArgumentException("invalid detached summary entry");
				}
			}
		}
	}

	private final Source source;
	private final int minInterval;

	/** Client manifest, intentionally without any world collector. */
	public CreatePresentationAdapter() {
		this(null, MIN_UPDATE_INTERVAL_TICKS);
	}

	/** Server collector; caller must invoke it on the authoritative server thread. */
	public CreatePresentationAdapter(Source source) {
		this(source, MIN_UPDATE_INTERVAL_TICKS);
	}

	/** Server-specific update cadence, while schema and fields remain identical. */
	public CreatePresentationAdapter(Source source, int minUpdateIntervalTicks) {
		if (minUpdateIntervalTicks < 1) {
			throw new IllegalArgumentException("invalid Create minimum update interval");
		}
		this.source = source;
		this.minInterval = minUpdateIntervalTicks;
	}

	@Override
	public String adapterId() {
		return ADAPTER_ID;
	}

	@Override
	public String modId() {
		return "create";
	}

	@Override
	public int schema() {
		return SCHEMA;
	}

	@Override
	public int minUpdateIntervalTicks() {
		return minInterval;
	}

	@Override
	public List<PresentationField> fields() {
		return FIELDS;
	}

	@Override
	public PresentationSection collect(DetachedTarget target, Set<String> demand,
		CaptureBudget budget) {
		return collect(target, demand, budget, false);
	}

	/**
	 * Preview only: an external candidate is observed through its
	 * provider-resolved local position; ordinary {@link #collect} stays
	 * committed-only.
	 */
	@Override
	public PresentationSection collectPreview(DetachedTarget target, Set<String> demand,
		CaptureBudget budget) {
		return collect(target, demand, budget, true);
	}

	private PresentationSection collect(DetachedTarget target, Set<String> demand,
		CaptureBudget budget, boolean preview) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(demand, "demand");
		Objects.requireNonNull(budget, "budget");
		if (source == null || budget.remaining() == 0 || !isBlockTarget(target.kind())) {
			return null;
		}
		Set<String> requested = Set.copyOf(demand);
		if (requested.stream().noneMatch(CreatePresentationAdapter::knownField)) {
			return PresentationSection.empty(ADAPTER_ID, SCHEMA);
		}
		try {
			Observation observed = preview ? source.observePreview(target, requested, budget)
				: source.observe(target, requested, budget);
			return observed == null ? null : project(observed, requested);
		} catch (RuntimeException | LinkageError failure) {
			// A failed optional source cannot break Basic or another adapter.
			return null;
		}
	}

	private static boolean isBlockTarget(String kind) {
		return "block".equals(kind) || "entity_block".equals(kind);
	}

	private static boolean knownField(String id) {
		return SPEED.equals(id) || HAS_NETWORK.equals(id) || OVERSTRESSED.equals(id)
			|| STRESS.equals(id) || CAPACITY.equals(id) || INVENTORY.equals(id)
			|| FLUID.equals(id);
	}

	static PresentationSection project(Observation observed, Set<String> demand) {
		Map<String, PresentationValue> result = new LinkedHashMap<>();
		if (demand.contains(SPEED) && observed.speed() != null) {
			Speed speed = observed.speed();
			result.put(SPEED, new PresentationValue.RecordValue(Map.of(
				"effective_rpm", new PresentationValue.NumberValue(speed.effectiveRpm()),
				"theoretical_rpm", new PresentationValue.NumberValue(speed.theoreticalRpm()),
				"moving", new PresentationValue.Flag(speed.moving()))));
		}
		if (demand.contains(HAS_NETWORK) && observed.hasNetwork() != null) {
			result.put(HAS_NETWORK, new PresentationValue.Flag(observed.hasNetwork()));
		}
		if (demand.contains(OVERSTRESSED) && observed.overStressed() != null) {
			result.put(OVERSTRESSED, new PresentationValue.Flag(observed.overStressed()));
		}
		if (demand.contains(STRESS) && observed.stress() != null) {
			result.put(STRESS, new PresentationValue.NumberValue(observed.stress()));
		}
		if (demand.contains(CAPACITY) && observed.capacity() != null) {
			result.put(CAPACITY, new PresentationValue.NumberValue(observed.capacity()));
		}
		if (demand.contains(INVENTORY) && observed.items() != null && observed.items().available()) {
			result.put(INVENTORY, summarize(observed.items()));
		}
		if (demand.contains(FLUID) && observed.fluids() != null && observed.fluids().available()) {
			result.put(FLUID, summarize(observed.fluids()));
		}
		return new PresentationSection(ADAPTER_ID, SCHEMA, result, false);
	}

	private static PresentationValue.RecordValue summarize(Summary summary) {
		if (summary.counts().size() > Math.min(PresentationCodec.MAX_ENTRIES, 16)) {
			throw new IllegalArgumentException("summary exceeds codec count");
		}
		Map<String, PresentationValue> counts = new LinkedHashMap<>();
		for (var entry : summary.counts().entrySet()) {
			if (entry.getValue() == null || entry.getValue() < 0) {
				throw new IllegalArgumentException("invalid summary amount");
			}
			// Entries are constructed by the bounded collector as plain IDs, not
			// stack display names; ensure hostile Source callbacks cannot inject
			// arbitrary record keys into an encoded snapshot.
			if (!REGISTRY_ID.matcher(entry.getKey()).matches()) {
				throw new IllegalArgumentException("invalid registry identifier");
			}
			counts.put(entry.getKey(), new PresentationValue.NumberValue(entry.getValue().doubleValue()));
		}
		// The nested record preserves registry-ID aggregation without leaking
		// variant metadata; both partial and scanned remain explicit.
		return new PresentationValue.RecordValue(Map.of(
			"counts", new PresentationValue.RecordValue(counts),
			"partial", new PresentationValue.Flag(summary.partial()),
			"scanned", new PresentationValue.NumberValue(summary.scanned())));
	}
}
