package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;
import nx.pingwheel.common.presentation.preview.client.MinecraftPreviewFieldAccess;

/** Lazily loaded NeoForge reader of positively received public kinetic getters. */
public final class CreateClientPreviewFieldAccess implements PreviewFieldAccess {
	@Override public String adapterId() { return CreatePresentationAdapter.ADAPTER_ID; }
	@Override public Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext context) {
		Map<String, Outcome> result = new LinkedHashMap<>();
		for (String field : demand) result.put(field, Missing.UNAVAILABLE);
		if (!CreatePresentationAvailability.available() || demand.isEmpty()) return Map.copyOf(result);
		if (!(target instanceof Target.BlockTarget block)) {
			if (!(target instanceof Target.ExternalBlockTarget)) demand.forEach(field -> result.put(field, Missing.NOT_APPLICABLE));
			return Map.copyOf(result);
		}
		if (!(context instanceof MinecraftPreviewFieldAccess.WorldContext world)
			|| !context.dimensionId().equals(target.dimensionId()) || world.blockState(block) == null) return Map.copyOf(result);
		var raw = world.blockEntity(block);
		if (!(raw instanceof KineticBlockEntity entity)) {
			if (raw != null) {
				for (String field : Set.of(CreatePresentationAdapter.SPEED, CreatePresentationAdapter.HAS_NETWORK,
					CreatePresentationAdapter.OVERSTRESSED, CreatePresentationAdapter.STRESS, CreatePresentationAdapter.CAPACITY))
					if (demand.contains(field)) result.put(field, Missing.NOT_APPLICABLE);
			}
			return Map.copyOf(result);
		}
		if (entity.isRemoved() || entity.isVirtual() || !(raw instanceof CreateClientPreviewReceipt receipt)
			|| !receipt.pingforit$receivedKinetics()) return Map.copyOf(result);
		if (demand.contains(CreatePresentationAdapter.SPEED)) put(result, CreatePresentationAdapter.SPEED, () -> {
			double effective = entity.getSpeed(), theoretical = entity.getTheoreticalSpeed();
			return new PresentationValue.RecordValue(Map.of("effective_rpm", new PresentationValue.NumberValue(effective),
				"theoretical_rpm", new PresentationValue.NumberValue(theoretical), "moving", new PresentationValue.Flag(effective != 0)));
		}, context.tick());
		if (demand.contains(CreatePresentationAdapter.HAS_NETWORK)) put(result, CreatePresentationAdapter.HAS_NETWORK,
			() -> new PresentationValue.Flag(entity.hasNetwork()), context.tick());
		if (demand.contains(CreatePresentationAdapter.OVERSTRESSED)) put(result, CreatePresentationAdapter.OVERSTRESSED,
			() -> new PresentationValue.Flag(entity.isOverStressed()), context.tick());
		// General network totals and container summaries have no current-quantity local guarantee.
		// They deliberately fall back to the authorized server path, not private extraction.
		return Map.copyOf(result);
	}
	private static void put(Map<String, Outcome> result, String field, java.util.function.Supplier<PresentationValue> getter, long tick) {
		try { result.put(field, new Observed(new PreviewObservation(getter.get(), PreviewObservation.Origin.CLIENT_SYNCED, tick, false))); }
		catch (RuntimeException | LinkageError unavailable) { result.put(field, Missing.UNAVAILABLE); }
	}
}
