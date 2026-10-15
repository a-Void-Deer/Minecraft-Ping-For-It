package nx.pingwheel.common.presentation.minecraft;

import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Quantity;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;

/** One canonical sweep and one pinned, resumable adapter job. No overflow population or timer index. */
final class PresentationFairSampler {
	private static final int DISCOVERY_PER_TICK = 256;
	private long highWater = -1, after = -1, currentId = -1;
	private int adapterIndex;
	private boolean sweeping, sweepEligible;
	private boolean sweepWaiting;
	private boolean discoveredEnd;
	private PresentationServer.Lease current;
	private long reasonCycle;
	private int reasons;
	private boolean observedCycle;

	record Result(int captures, DelayState state) {}

	boolean hasParkedMiss(Map<Long, PresentationServer.Lease> cache) {
		return currentId >= 0 && !cache.containsKey(currentId);
	}

	boolean rememberInitial(Map<Long, PresentationServer.Lease> cache, PresentationServer.Lease lease) {
		if (hasParkedMiss(cache) || lease.sources.values().stream().noneMatch(source ->
			source.observation == PresentationServer.Source.Observation.SUCCESS || source.observation == PresentationServer.Source.Observation.FAILED))
			return false;
		return PresentationServer.rememberLease(cache, lease);
	}

	Result tick(ServerMarkerStore store, Map<Long, PresentationServer.Lease> cache,
		PresentationRegistry registry, PresentationSettings policy, long tick,
		PresentationAdapter.CaptureBudget work, int captureLimit,
		BiFunction<ServerMarker, PresentationAdapter, Set<String>> demands,
		BiFunction<ServerMarker, Set<String>, PresentationSection> basicCapture,
		Function<ServerMarker, String> ownerName, Consumer<PresentationServer.Lease> publish,
		BudgetDelaySink sink, long nowNanos) {
		if (sink == null) sink = BudgetDelaySink.NONE;
		cache.entrySet().removeIf(entry -> {
			ServerMarker marker = store.find(new MarkerId(entry.getKey())).orElse(null);
			if (marker == null || marker.expiresAtTick() <= tick) return true;
			entry.getValue().marker = marker;
			return false;
		});
		if (!sweeping) {
			highWater = store.highWaterId(); after = -1; sweeping = true; sweepEligible = false; sweepWaiting = false; discoveredEnd = false;
		}
		var adapters = registry.sectionAdapters();
		int inspected = 0, captures = 0;
		while (true) {
			if (currentId < 0) {
				if (discoveredEnd) return completed(captures);
				if (inspected == DISCOVERY_PER_TICK) return new Result(captures, DelayState.NEUTRAL);
				var page = store.pageAfter(after, highWater, 1);
				if (page.markers().isEmpty()) {
					return completed(captures);
				}
				ServerMarker marker = page.markers().get(0);
				discoveredEnd = page.complete();
				inspected++;
				currentId = marker.id().value(); adapterIndex = 0;
				current = cache.get(currentId);
				if (current == null) current = new PresentationServer.Lease(marker, ownerName.apply(marker));
				else current.marker = marker;
				resetReasons();
			}
			ServerMarker live = store.find(new MarkerId(currentId)).orElse(null);
			if (live == null || live.expiresAtTick() <= tick) { finishJob(); continue; }
			current.marker = live;
			while (adapterIndex < adapters.size()) {
				PresentationAdapter adapter = adapters.get(adapterIndex);
				Set<String> demand = demands.apply(live, adapter);
				PresentationServer.Source source = PresentationServer.sourceFor(current, adapter, policy, true);
				if (demand.isEmpty()) {
					PresentationServer.clearDemand(source, adapter);
					adapterIndex++; resetReasons(); continue;
				}
				if (tick < source.nextSample) { sweepWaiting = true; adapterIndex++; resetReasons(); continue; }
				sweepEligible = true;
				int refused = PresentationServer.captureRefusal(adapter, policy, work, captureLimit - captures);
				if (!cache.containsKey(currentId) && refused == 0 && !admit(cache, tick))
					refused |= Reason.CACHE_CAPACITY.bit();
				if (refused != 0) {
					observe(sink, live, adapter, tick, nowNanos, refused);
					publish.accept(current);
					return new Result(captures, DelayState.DELAYED);
				}
				PresentationServer.captureSource(current, adapter, policy, tick, work, demand,
					fields -> basicCapture.apply(live, fields));
				captures++;
				adapterIndex++; resetReasons();
			}
			publish.accept(current);
			finishJob();
		}
	}
	private Result completed(int captures) {
		sweeping = false;
		return new Result(captures, sweepEligible ? DelayState.HEALTHY : sweepWaiting ? DelayState.NEUTRAL : DelayState.IDLE);
	}

	private boolean admit(Map<Long, PresentationServer.Lease> cache, long tick) {
		if (PresentationServer.rememberLease(cache, current)) return true;
		// The cache is structurally bounded. A precise retained deadline is never
		// erased early, and the current job cannot be selected as its own victim.
		var iterator = cache.entrySet().iterator();
		int searched = 0;
		while (iterator.hasNext() && searched++ < 1024) {
			var entry = iterator.next();
			if (entry.getKey() == currentId) continue;
			if (entry.getValue().sources.values().stream().allMatch(source -> tick >= source.nextSample)) {
				iterator.remove();
				return PresentationServer.rememberLease(cache, current);
			}
		}
		return false;
	}

	private void finishJob() {
		after = currentId; currentId = -1; current = null; adapterIndex = 0; resetReasons();
	}
	private void resetReasons() { observedCycle = false; reasons = 0; }
	private void observe(BudgetDelaySink sink, ServerMarker marker, PresentationAdapter adapter,
		long cycle, long now, int refused) {
		if (!observedCycle || reasonCycle != cycle) { observedCycle = true; reasonCycle = cycle; reasons = 0; }
		int added = refused & ~reasons;
		if (added == 0) return;
		boolean first = reasons == 0;
		reasons |= added;
		try { sink.observe(new DelayEvent(Module.PRESENTATION, cycle, now,
			context(marker, adapter.adapterId()), first, added, Quantity.of(Unit.MARKER_ADAPTER, 1))); }
		catch (RuntimeException | LinkageError ignored) {}
	}
	private static WorkItem context(ServerMarker marker, String adapter) {
		String description = "marker=" + marker.id().value() + " adapter=" + adapter + " dimension=" + marker.target().dimensionId();
		Target target = marker.target();
		if (target instanceof Target.BlockTarget block)
			description += " registry=" + block.blockRegistryId() + " pos=" + block.x() + "," + block.y() + "," + block.z();
		else if (target instanceof Target.ExternalBlockTarget external)
			description += " provider=" + external.providerId() + " stable=" + external.stableTargetId();
		return new WorkItem("marker:" + marker.id().value() + ":" + adapter, description);
	}
}
