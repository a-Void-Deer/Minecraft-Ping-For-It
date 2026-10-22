package nx.pingwheel.common.presentation.preview;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

/** Client-thread, one-interaction provisional store. It never mutates a marker. */
public final class ClientPresentationPreview {
	@FunctionalInterface public interface AccessSource {
		Optional<PresentationPreviewAccess> current(String targetTypeId);
	}
	/** Supplies detached marker sections with their identity, never the retained store itself. */
	@FunctionalInterface public interface RetainedSource {
		Optional<RetainedSnapshot> observed(Target target, String type);
	}
	public record RetainedSnapshot(Target target, String targetTypeId, long observedAtTick,
		Map<String, PresentationSection> sections) {
		public RetainedSnapshot {
			Objects.requireNonNull(target); Objects.requireNonNull(targetTypeId);
			if (observedAtTick < 0 || sections.size() > 32) throw new IllegalArgumentException("retained preview bounds");
			sections = Map.copyOf(sections);
		}
	}
	/** Lifecycle input only. Neither the world identity nor the caller's token is exported. */
	public static final class Binding {
		private final Object token, levelIdentity;
		private final Target target;
		private final String targetTypeId;
		public Binding(Object token, Target target, String targetTypeId, Object levelIdentity) {
			this.token = Objects.requireNonNull(token); this.target = Objects.requireNonNull(target);
			this.targetTypeId = Objects.requireNonNull(targetTypeId); this.levelIdentity = Objects.requireNonNull(levelIdentity);
		}
	}
	/** Detached UI values and an opaque interaction identity, with no lifecycle/world handle. */
	public record Projection(UUID interactionId, Target target, String targetTypeId, long epoch, long view,
		Map<PresentationPropertyRef, PreviewFieldAccess.Outcome> fields, Set<PresentationPropertyRef> childBlack) {
		public Projection {
			Objects.requireNonNull(interactionId); Objects.requireNonNull(target); Objects.requireNonNull(targetTypeId);
			fields = Map.copyOf(fields);
			childBlack = Set.copyOf(childBlack);
		}
		public Projection(UUID interactionId, Target target, String targetTypeId, long epoch, long view,
			Map<PresentationPropertyRef, PreviewFieldAccess.Outcome> fields) {
			this(interactionId, target, targetTypeId, epoch, view, fields, Set.of());
		}
		public Optional<PreviewObservation> property(PresentationPropertyRef ref) {
			if (ref == null || childBlack.contains(ref)) return Optional.empty();
			var root = fields.get(PresentationPropertyRef.root(ref.adapterId(), ref.fieldId()));
			if (!(root instanceof PreviewFieldAccess.Observed observed)) return Optional.empty();
			PresentationValue value = ref.resolve(new PresentationSection(ref.adapterId(), 1,
				Map.of(ref.fieldId(), observed.observation().value()), false));
			return value == null ? Optional.empty() : Optional.of(new PreviewObservation(value,
				observed.observation().origin(), observed.observation().observedAtTick(), observed.observation().stale()));
		}
	}
	private final AccessSource accessSource;
	private final Supplier<? extends PreviewFieldAccess.ReadContext> contextSource;
	private final Map<String, PreviewFieldAccess> readers;
	private final RetainedSource retained;
	private final Consumer<PresentationPreviewC2SPacket> sender;
	private final Map<PresentationPropertyRef, PreviewFieldAccess.Outcome> fields = new LinkedHashMap<>();
	private final Map<PresentationPropertyRef, PreviewFieldAccess.Missing> attempted = new LinkedHashMap<>();
	private Binding binding;
	private UUID interactionId;
	private PresentationPreviewAccess access;
	private PresentationPreviewC2SPacket pending;
	private long deadline, nextRequest = 1, nextSendTick, lastTick;

	public ClientPresentationPreview(AccessSource accessSource,
		Supplier<? extends PreviewFieldAccess.ReadContext> contextSource, List<PreviewFieldAccess> readers,
		RetainedSource retained, Consumer<PresentationPreviewC2SPacket> sender) {
		this.accessSource = Objects.requireNonNull(accessSource);
		this.contextSource = Objects.requireNonNull(contextSource);
		this.retained = Objects.requireNonNull(retained);
		this.sender = Objects.requireNonNull(sender);
		Map<String, PreviewFieldAccess> registered = new LinkedHashMap<>();
		for (var reader : readers) if (registered.putIfAbsent(reader.adapterId(), reader) != null)
			throw new IllegalArgumentException("duplicate preview reader");
		this.readers = Map.copyOf(registered);
	}
	public void begin(Binding next) {
		abort(); binding = Objects.requireNonNull(next); interactionId = UUID.randomUUID(); tick();
	}
	public void abort() {
		try { cancelPending(); }
		finally { binding = null; interactionId = null; access = null; fields.clear(); attempted.clear(); }
	}
	private void cancelPending() {
		if (pending != null) {
			var old = pending; pending = null;
			sender.accept(PresentationPreviewC2SPacket.cancel(old.epoch(), old.view(), old.requestId()));
		}
	}
	private PreviewFieldAccess.ReadContext synchronize() {
		if (binding == null) return null;
		var context = contextSource.get();
		if (context == null || context.levelIdentity() != binding.levelIdentity
			|| !binding.target.dimensionId().equals(context.dimensionId())) { abort(); return null; }
		if (context.tick() < 0) { abort(); return null; }
		lastTick = Math.max(lastTick, context.tick());
		var current = accessSource.current(binding.targetTypeId).filter(value -> value.targetTypeId().equals(binding.targetTypeId)).orElse(null);
		if (!Objects.equals(access, current)) {
			try { cancelPending(); }
			finally { fields.clear(); attempted.clear(); access = current; }
		}
		return context;
	}
	public void tick() {
		var context = synchronize();
		if (context == null || access == null) return;
		fields.entrySet().removeIf(entry -> entry.getValue() instanceof PreviewFieldAccess.Observed observed
			&& observed.observation().origin() == PreviewObservation.Origin.RETAINED_MARKER);
		for (var adapter : access.adapters().entrySet()) {
			Map<String, PreviewFieldAccess.Outcome> local = Map.of();
			var reader = readers.get(adapter.getKey());
			if (reader != null) try {
				local = reader.observe(binding.target, adapter.getValue().fields().keySet(), context);
				if (local == null) local = Map.of();
			} catch (RuntimeException | LinkageError unavailable) { local = Map.of(); }
			for (var field : adapter.getValue().fields().entrySet()) {
				var ref = PresentationPropertyRef.root(adapter.getKey(), field.getKey());
				var outcome = local.get(field.getKey());
				if (outcome instanceof PreviewFieldAccess.Observed observed) {
					if (observed.observation().origin() == PreviewObservation.Origin.CLIENT_SYNCED
						&& field.getValue().accepts(observed.observation().value())) fields.put(ref, outcome);
					else fields.remove(ref);
				} else if (outcome == PreviewFieldAccess.Missing.NOT_APPLICABLE) fields.put(ref, outcome);
				else {
					var previous = fields.get(ref);
					// A vanished local source cannot leave its previous getter value looking available.
					if (previous instanceof PreviewFieldAccess.Observed observed
						&& observed.observation().origin() == PreviewObservation.Origin.CLIENT_SYNCED) fields.remove(ref);
				}
			}
		}
		RetainedSnapshot cached;
		try { cached = retained.observed(binding.target, binding.targetTypeId).orElse(null); }
		catch (RuntimeException | LinkageError unavailable) { cached = null; }
		if (cached != null && binding.target.equals(cached.target()) && binding.targetTypeId.equals(cached.targetTypeId())
			// Empty external IDs deliberately alias candidates; they cannot identify a cached marker.
			&& !(binding.target instanceof Target.ExternalBlockTarget external && external.isCandidate())) {
			for (var adapter : access.adapters().entrySet()) {
				var section = cached.sections().get(adapter.getKey());
				if (section == null || !section.adapterId().equals(adapter.getKey())
					|| section.schema() != adapter.getValue().schema()) continue;
				for (var field : adapter.getValue().fields().entrySet()) {
					var ref = PresentationPropertyRef.root(adapter.getKey(), field.getKey());
					var value = section.fields().get(field.getKey());
					if (value != null && field.getValue().accepts(value) && !(fields.get(ref) instanceof PreviewFieldAccess.Observed)
						&& fields.get(ref) != PreviewFieldAccess.Missing.NOT_APPLICABLE)
						// No annotation survives this boundary, even from a non-stale marker section.
						fields.put(ref, new PreviewFieldAccess.Observed(new PreviewObservation(value,
							PreviewObservation.Origin.RETAINED_MARKER, cached.observedAtTick(), true)));
				}
			}
		}
		// Local/cache evidence may disappear after a terminal response. Keep the one-shot's
		// scheduling/availability result without repeating its source read.
		attempted.forEach(fields::putIfAbsent);
		if (pending != null && lastTick >= deadline) {
			var expired = pending; cancelPending(); finishMissing(expired, PreviewFieldAccess.Missing.UNAVAILABLE);
		}
		if (pending == null) requestMissing(lastTick);
	}
	private void requestMissing(long tick) {
		if (tick < nextSendTick) return;
		for (String adapter : access.adapters().keySet().stream().sorted().toList()) {
			Set<String> missing = new LinkedHashSet<>();
			for (String field : access.fields(adapter)) {
				var ref = PresentationPropertyRef.root(adapter, field);
				if (attempted.containsKey(ref)) continue;
				var outcome = fields.get(ref);
				if (outcome == PreviewFieldAccess.Missing.NOT_APPLICABLE) continue;
				if (outcome instanceof PreviewFieldAccess.Observed observed && !observed.observation().stale()) continue;
				missing.add(field);
			}
			if (missing.isEmpty()) continue;
			if (nextRequest == Long.MAX_VALUE) return;
			pending = PresentationPreviewC2SPacket.read(access.epoch(), access.view(), nextRequest++,
				binding.target, binding.targetTypeId, adapter, missing);
			for (String field : missing) attempted.put(PresentationPropertyRef.root(adapter, field), PreviewFieldAccess.Missing.PENDING);
			nextSendTick = tick > Long.MAX_VALUE - PresentationPreviewLimits.MIN_REQUEST_TICKS ? Long.MAX_VALUE : tick + PresentationPreviewLimits.MIN_REQUEST_TICKS;
			deadline = tick > Long.MAX_VALUE - PresentationPreviewLimits.REQUEST_TICKS ? Long.MAX_VALUE : tick + PresentationPreviewLimits.REQUEST_TICKS;
			for (String field : missing) fields.putIfAbsent(PresentationPropertyRef.root(adapter, field), PreviewFieldAccess.Missing.PENDING);
			try { sender.accept(pending); }
			catch (RuntimeException failure) { var failed = pending; pending = null; finishMissing(failed, PreviewFieldAccess.Missing.UNAVAILABLE); throw failure; }
			return;
		}
	}
	private void finishMissing(PresentationPreviewC2SPacket request, PreviewFieldAccess.Missing outcome) {
		for (String field : request.fields()) {
			var ref = PresentationPropertyRef.root(request.adapterId(), field);
			attempted.put(ref, outcome);
			if (!(fields.get(ref) instanceof PreviewFieldAccess.Observed) && fields.get(ref) != PreviewFieldAccess.Missing.NOT_APPLICABLE)
				fields.put(ref, outcome);
		}
	}
	public boolean accept(PresentationPreviewS2CPacket packet) {
		var context = synchronize();
		if (context == null || access == null || pending == null || packet == null || packet.isCorrupt()
			|| packet.epoch() != pending.epoch() || packet.view() != pending.view()
			|| packet.requestId() != pending.requestId() || !packet.adapterId().equals(pending.adapterId())) return false;
		var request = pending;
		if (lastTick >= deadline) { cancelPending(); finishMissing(request, PreviewFieldAccess.Missing.UNAVAILABLE); return false; }
		if (packet.schema() != access.adapters().get(request.adapterId()).schema()) return false;
		if (packet.status() == PresentationPreviewS2CPacket.Status.RESULT) {
			var section = packet.decodeSection(access.narrow(request.adapterId(), request.fields()));
			if (section == null) return false;
			section.fields().forEach((field, value) -> {
				var ref = PresentationPropertyRef.root(request.adapterId(), field);
				var previous = fields.get(ref);
				if (!(previous instanceof PreviewFieldAccess.Observed observed)
					|| observed.observation().origin() != PreviewObservation.Origin.CLIENT_SYNCED)
					fields.put(ref, new PreviewFieldAccess.Observed(new PreviewObservation(value,
						PreviewObservation.Origin.SERVER_PREVIEW, context.tick(), false)));
			});
		}
		pending = null;
		finishMissing(request, packet.status() == PresentationPreviewS2CPacket.Status.DEFERRED
			? PreviewFieldAccess.Missing.DEFERRED : PreviewFieldAccess.Missing.UNAVAILABLE);
		return true;
	}
	public Optional<Projection> projection() {
		if (synchronize() == null || access == null) return Optional.empty();
		// A projection after RESET must not remain blank until a later game tick.
		if (fields.isEmpty()) tick();
		return binding == null || access == null ? Optional.empty()
			: Optional.of(new Projection(interactionId, binding.target, binding.targetTypeId, access.epoch(), access.view(), fields, access.childBlack()));
	}
	/** Re-check access at dispatch; values are only claims and CREATE still recaptures. */
	public Optional<PresentationPropertyIntent> intent(Object token, PresentationPropertyRef ref, String pingType) {
		return projection().filter(view -> binding.token == token).flatMap(view -> view.property(ref))
			.map(observed -> new PresentationPropertyIntent(ref, observed.value(), pingType));
	}
}
