package nx.pingwheel.common.presentation.preview;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

/** Packet ingress only queues. The host drains with the remaining SECTION tick allowance. */
public final class PresentationPreviewServer {
	public interface Host {
		/** Fresh authorization intersected with the already-advertised epoch/view; no world reads. */
		Optional<PresentationPreviewAccess> access(UUID player, String targetTypeId);
		int intervalTicks(UUID player, String adapterId);
		int scanBudget();
		/** Validate target/type/range then observe only demand. Called after shared budget admission. */
		Capture capture(UUID player, PresentationPreviewC2SPacket request, PresentationPreviewAccess authorized,
			PresentationAdapter.CaptureBudget budget);
		/** Real transport publication or explicit refusal; refusal never causes a recapture. */
		boolean send(UUID player, PresentationPreviewS2CPacket packet);
	}
	public record Capture(PresentationSection section, PresentationPreviewS2CPacket.Status status) {
		public Capture {
			Objects.requireNonNull(status);
			if ((status == PresentationPreviewS2CPacket.Status.RESULT) != (section != null))
				throw new IllegalArgumentException("preview capture outcome");
		}
	}
	private record Queued(PresentationPreviewC2SPacket request, long tick, int schema) {}
	private static final class Connection {
		long epoch, highWater, nextRequestTick;
		final Map<String, Long> nextAdapterTick = new HashMap<>();
	}
	private final Host host;
	private final Map<UUID, Connection> connections = new HashMap<>();
	private final Map<UUID, Queued> queued = new LinkedHashMap<>();
	public PresentationPreviewServer(Host host) { this.host = Objects.requireNonNull(host); }
	public void disconnect(UUID player) { queued.remove(player); connections.remove(player); }
	public void reset() { queued.clear(); connections.clear(); }
	public int queuedCount() { return queued.size(); }
	private Optional<PresentationPreviewAccess> access(UUID player, PresentationPreviewC2SPacket request) {
		try {
			return host.access(player, request.targetTypeId()).filter(value -> value.epoch() == request.epoch()
				&& value.view() == request.view() && value.targetTypeId().equals(request.targetTypeId()));
		} catch (RuntimeException | LinkageError denied) { return Optional.empty(); }
	}
	public boolean handle(UUID player, PresentationPreviewC2SPacket request, long tick) {
		if (request == null || request.isCorrupt() || tick < 0) return false;
		Connection state = connections.get(player);
		if (request.kind() == PresentationPreviewC2SPacket.Kind.CANCEL) {
			var pending = queued.get(player);
			if (state == null || state.epoch != request.epoch()) return false;
			state.highWater = Math.max(state.highWater, request.requestId());
			if (pending != null && pending.request().requestId() == request.requestId()
				&& pending.request().view() == request.view()) queued.remove(player);
			return true;
		}
		var authorization = access(player, request).orElse(null);
		if (authorization == null) return false;
		var adapter = authorization.adapters().get(request.adapterId());
		if (adapter == null || !adapter.fields().keySet().containsAll(request.fields())) return false;
		if (state == null) {
			if (connections.size() >= PresentationPreviewLimits.MAX_CONNECTIONS) return false;
			state = new Connection(); state.epoch = request.epoch(); connections.put(player, state);
		}
		if (state.epoch != request.epoch()) {
			queued.remove(player); state.epoch = request.epoch(); state.highWater = 0;
			// A new view/epoch cannot reset the connection's read cadence.
		}
		if (request.requestId() <= state.highWater) return false;
		state.highWater = request.requestId();
		if (tick < state.nextRequestTick || queued.containsKey(player)
			|| queued.size() >= PresentationPreviewLimits.MAX_QUEUED) return false;
		state.nextRequestTick = add(tick, PresentationPreviewLimits.MIN_REQUEST_TICKS);
		queued.put(player, new Queued(request, tick, adapter.schema())); return true;
	}
	/** Returns consumed capture attempts; the supplied work is debited exactly once. */
	public int drain(long tick, PresentationAdapter.CaptureBudget work, int remainingCaptures) {
		if (tick < 0 || remainingCaptures < 0) throw new IllegalArgumentException("preview tick budget");
		int used = 0;
		Iterator<Map.Entry<UUID, Queued>> iterator = queued.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next(); var request = entry.getValue().request(); UUID player = entry.getKey();
			if (tick - entry.getValue().tick() >= PresentationPreviewLimits.REQUEST_TICKS) {
				iterator.remove(); reply(player, request, entry.getValue().schema(), PresentationPreviewS2CPacket.Status.DEFERRED); continue;
			}
			var authorized = access(player, request).orElse(null);
			var adapter = authorized == null ? null : authorized.adapters().get(request.adapterId());
			if (adapter == null || adapter.schema() != entry.getValue().schema()
				|| !adapter.fields().keySet().containsAll(request.fields())) { iterator.remove(); continue; }
			Connection state = connections.get(player);
			if (tick < state.nextAdapterTick.getOrDefault(request.adapterId(), 0L)) continue;
			// Request lookup overhead and collector work share the same configured per-call bound.
			int allowance = Math.min(Math.max(0, host.scanBudget()) - 1, work.remaining() - 1);
			if (used >= remainingCaptures || allowance < 1) continue;
			work.scan(); used++;
			if (state.nextAdapterTick.size() >= 32 && !state.nextAdapterTick.containsKey(request.adapterId())) {
				iterator.remove(); continue;
			}
			state.nextAdapterTick.put(request.adapterId(), add(tick, Math.max(1, host.intervalTicks(player, request.adapterId()))));
			var budget = new PresentationAdapter.CaptureBudget(allowance);
			Capture captured;
			try { captured = host.capture(player, request, authorized.narrow(request.adapterId(), request.fields()), budget); }
			catch (RuntimeException | LinkageError unavailable) { captured = null; }
			finally { for (int debit = allowance - budget.remaining(); debit > 0; debit--) work.scan(); }
			iterator.remove();
			var current = access(player, request).orElse(null);
			if (current == null || current.adapters().get(request.adapterId()) == null
				|| current.adapters().get(request.adapterId()).schema() != adapter.schema()
				|| !current.fields(request.adapterId()).containsAll(request.fields())) continue;
			if (captured == null) { reply(player, request, adapter.schema(), PresentationPreviewS2CPacket.Status.UNAVAILABLE); continue; }
			if (captured.status() != PresentationPreviewS2CPacket.Status.RESULT) {
				reply(player, request, adapter.schema(), captured.status()); continue;
			}
			var section = captured.section();
			if (!request.adapterId().equals(section.adapterId()) || adapter.schema() != section.schema()
				|| section.stale() || !section.annotations().isEmpty()) {
				reply(player, request, adapter.schema(), PresentationPreviewS2CPacket.Status.UNAVAILABLE); continue;
			}
			Map<String, PresentationValue> projected = new LinkedHashMap<>();
			section.fields().forEach((field, value) -> {
				var descriptor = current.adapters().get(request.adapterId()).fields().get(field);
				if (request.fields().contains(field) && descriptor != null && descriptor.accepts(value)) projected.put(field, value);
			});
			try { send(player, PresentationPreviewS2CPacket.result(request,
				new PresentationSection(section.adapterId(), section.schema(), projected, false))); }
			catch (RuntimeException | LinkageError unavailable) { reply(player, request, adapter.schema(), PresentationPreviewS2CPacket.Status.UNAVAILABLE); }
		}
		return used;
	}
	private void reply(UUID player, PresentationPreviewC2SPacket request, int schema, PresentationPreviewS2CPacket.Status status) {
		var current = access(player, request).orElse(null);
		var adapter = current == null ? null : current.adapters().get(request.adapterId());
		if (adapter != null && adapter.schema() == schema && adapter.fields().keySet().containsAll(request.fields()))
			send(player, PresentationPreviewS2CPacket.control(request, schema, status));
	}
	private boolean send(UUID player, PresentationPreviewS2CPacket packet) {
		try { return host.send(player, packet); }
		catch (RuntimeException | LinkageError unavailable) { return false; }
	}
	private static long add(long tick, long delay) { return tick > Long.MAX_VALUE - delay ? Long.MAX_VALUE : tick + delay; }
}
