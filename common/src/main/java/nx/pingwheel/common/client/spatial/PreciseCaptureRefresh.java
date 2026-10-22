package nx.pingwheel.common.client.spatial;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.candidate.Candidate;
import nx.pingwheel.common.interaction.candidate.PreciseSlot.Availability;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.math.RaycastPolicy;

/**
 * Main-thread live capture owner, independent of ordinary capture completion.
 * Native slots publish synchronously. The independent location route has one
 * physical request in flight and one coalesced successor, not a growing queue.
 * Each slot carries its own ray/generation; pending DH never borrows a newer ray.
 */
public final class PreciseCaptureRefresh {

	public record Inputs(InteractionToken token, Object level, CapturedRay ray,
		RaycastPolicy policy, double nativeDistance, double pingDistance) {
		public Inputs {
			Objects.requireNonNull(token); Objects.requireNonNull(level); Objects.requireNonNull(ray); Objects.requireNonNull(policy);
			if (!Double.isFinite(nativeDistance) || !Double.isFinite(pingDistance)
				|| nativeDistance < 0 || pingDistance < nativeDistance) throw new IllegalArgumentException("invalid frozen range");
		}
	}
	public record Request(long generation, Inputs inputs) {
		public Request { if (generation <= 0) throw new IllegalArgumentException("invalid generation"); Objects.requireNonNull(inputs); }
	}
	public record Outcome(Optional<Candidate> candidate, Availability availability) {
		public Outcome {
			Objects.requireNonNull(candidate); Objects.requireNonNull(availability);
			if (candidate.isPresent() != (availability == Availability.AVAILABLE)) throw new IllegalArgumentException("invalid outcome");
		}
		public static Outcome available(Candidate value) { return new Outcome(Optional.of(value), Availability.AVAILABLE); }
		public static Outcome missing() { return new Outcome(Optional.empty(), Availability.MISSING); }
		public static Outcome incomplete() { return new Outcome(Optional.empty(), Availability.INCOMPLETE); }
	}
	/** Native map contains all four scanned types; location is separate from their certification. */
	public record Scan(Map<PreciseTargetType, Outcome> nativeSlots, Outcome location, boolean needsDistant) {
		public Scan {
			nativeSlots = Map.copyOf(nativeSlots); Objects.requireNonNull(location);
			if (nativeSlots.size() != 4 || nativeSlots.containsKey(PreciseTargetType.LOCATION))
				throw new IllegalArgumentException("expected four native slots");
			for (var entry : nativeSlots.entrySet()) if (entry.getValue().candidate()
				.filter(value -> !entry.getKey().matches(value.resolvedTarget())).isPresent())
				throw new IllegalArgumentException("native slot has wrong canonical type");
			if (location.candidate().filter(value -> !PreciseTargetType.LOCATION.matches(value.resolvedTarget())).isPresent())
				throw new IllegalArgumentException("location route has wrong canonical type");
		}
		public static Scan incomplete() {
			var slots = new EnumMap<PreciseTargetType, Outcome>(PreciseTargetType.class);
			for (var type : PreciseTargetType.values()) if (type != PreciseTargetType.LOCATION) slots.put(type, Outcome.incomplete());
			return new Scan(slots, Outcome.incomplete(), false);
		}
	}
	public record Slot(long generation, Inputs inputs, Outcome outcome) {
		public Slot {
			Objects.requireNonNull(inputs); Objects.requireNonNull(outcome);
			if (generation <= 0) throw new IllegalArgumentException("invalid slot generation");
		}
		public Optional<CapturedPingContext> context() {
			return outcome.candidate().map(candidate -> new CapturedPingContext(inputs.token(), candidate.resolvedTarget(),
				inputs.ray(), candidate.entityLocalGeometryMetadata(), candidate.blockHitFace()));
		}
	}
	public record Published(long revision, long generation, Inputs inputs, Map<PreciseTargetType, Slot> slots) {
		public Published {
			Objects.requireNonNull(inputs); slots = Map.copyOf(slots);
			if (revision <= 0 || generation <= 0 || slots.size() != PreciseTargetType.values().length)
				throw new IllegalArgumentException("invalid five-slot publication");
			for (var entry : slots.entrySet()) {
				if (entry.getValue().inputs().token() != inputs.token() || entry.getValue().inputs().level() != inputs.level()
					|| entry.getValue().generation() > generation
					|| entry.getValue().outcome().candidate().filter(value -> !entry.getKey().matches(value.resolvedTarget())).isPresent())
					throw new IllegalArgumentException("foreign live slot");
			}
		}
	}
	@FunctionalInterface public interface NativeCapture { Scan capture(Request request); }
	/** Completion must be handed back to the main thread by the adapter. False means use the native fallback. */
	@FunctionalInterface public interface DistantCapture { boolean start(Request request, Consumer<Outcome> completion); }
	private record Pending(Request request, Outcome fallback, long branchEpoch) {}

	private final InteractionToken token;
	private final Object level;
	private final int period;
	private final Supplier<Optional<Inputs>> inputs;
	private final BooleanSupplier current;
	private final NativeCapture nativeCapture;
	private final DistantCapture distantCapture;
	private final Consumer<Published> publisher;
	private final Map<PreciseTargetType, Slot> slots = new EnumMap<>(PreciseTargetType.class);
	private boolean active, ended;
	private long nextTick, generation, revision, branchEpoch, locationGeneration;
	private Inputs latestInputs;
	private Pending inFlight, queued;

	public PreciseCaptureRefresh(InteractionToken token, Object level, int period,
		Supplier<Optional<Inputs>> inputs, BooleanSupplier current, NativeCapture nativeCapture,
		DistantCapture distantCapture, Consumer<Published> publisher) {
		this.token = Objects.requireNonNull(token); this.level = Objects.requireNonNull(level);
		if (period < 1 || period > 50) throw new IllegalArgumentException("invalid capture period");
		this.period = period; this.inputs = Objects.requireNonNull(inputs); this.current = Objects.requireNonNull(current);
		this.nativeCapture = Objects.requireNonNull(nativeCapture); this.distantCapture = Objects.requireNonNull(distantCapture);
		this.publisher = Objects.requireNonNull(publisher);
	}

	/** Enter captures immediately; frames never count as periodic ticks. */
	public void enter(long tick) {
		if (ended || active || !current.getAsBoolean()) return;
		active = true; branchEpoch++;
		capture(tick);
	}
	public void tick(long tick) { if (active && !ended && tick >= nextTick) capture(tick); }
	/** Logical fence only; do not pretend to cancel an optional provider's physical request. */
	public void leave() { if (active) { active = false; branchEpoch++; queued = null; } }
	public void end() { ended = true; leave(); queued = null; inFlight = null; slots.clear(); }
	public boolean active() { return active && !ended; }

	private boolean owned(Inputs value) {
		return value.token() == token && value.level() == level && current.getAsBoolean();
	}
	private void capture(long tick) {
		nextTick = tick + period;
		if (!current.getAsBoolean()) { end(); return; }
		var supplied = inputs.get();
		if (supplied.isEmpty() || !owned(supplied.get())) return;
		var request = new Request(++generation, supplied.get());
		long epoch = branchEpoch;
		Scan scan;
		try { scan = Objects.requireNonNull(nativeCapture.capture(request)); }
		catch (RuntimeException | LinkageError unavailable) { scan = Scan.incomplete(); }
		if (!active || ended || epoch != branchEpoch || request.generation() != generation || !owned(request.inputs())) return;
		latestInputs = request.inputs();
		for (var entry : scan.nativeSlots().entrySet()) slots.put(entry.getKey(), new Slot(generation, latestInputs, entry.getValue()));
		if (!scan.needsDistant()) {
			locationGeneration = generation;
			slots.put(PreciseTargetType.LOCATION, new Slot(generation, latestInputs, scan.location()));
			queued = null;
		} else slots.putIfAbsent(PreciseTargetType.LOCATION, new Slot(generation, latestInputs, Outcome.incomplete()));
		publish();
		if (!active || ended || epoch != branchEpoch || request.generation() != generation || !owned(request.inputs()) || !scan.needsDistant()) return;
		var pending = new Pending(request, scan.location(), epoch);
		if (inFlight == null) start(pending);
		else queued = pending;
	}
	private void publish() { publisher.accept(new Published(++revision, generation, latestInputs, slots)); }
	private void start(Pending pending) {
		inFlight = pending;
		boolean scheduled;
		try { scheduled = distantCapture.start(pending.request(), outcome -> complete(pending, outcome)); }
		catch (RuntimeException | LinkageError unavailable) { scheduled = false; }
		if (!scheduled) complete(pending, pending.fallback());
	}
	private void complete(Pending pending, Outcome outcome) {
		if (inFlight != pending) return; // duplicates, ended requests and out-of-order callbacks
		inFlight = null;
		Pending successor = queued; queued = null;
		if (active && !ended && pending.branchEpoch() == branchEpoch && owned(pending.request().inputs())
			&& pending.request().generation() > locationGeneration) {
			if (outcome == null || outcome.candidate().filter(value -> !PreciseTargetType.LOCATION.matches(value.resolvedTarget())).isPresent())
				outcome = Outcome.incomplete();
			locationGeneration = pending.request().generation();
			slots.put(PreciseTargetType.LOCATION, new Slot(locationGeneration, pending.request().inputs(), Objects.requireNonNull(outcome)));
			publish(); // accept this completed generation even while newer native rays are pending; no DH starvation
		}
		if (successor != null && inFlight == null && active && !ended && successor.branchEpoch() == branchEpoch
			&& successor.request().generation() == generation && owned(successor.request().inputs())) start(successor);
	}
}
