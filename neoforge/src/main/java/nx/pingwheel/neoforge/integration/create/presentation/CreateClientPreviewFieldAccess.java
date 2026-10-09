package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;
import nx.pingwheel.common.presentation.preview.client.MinecraftPreviewFieldAccess;

/** Lazily loaded NeoForge reader of positively received public kinetic getters and cached totals. */
public final class CreateClientPreviewFieldAccess implements PreviewFieldAccess {
	@Override public String adapterId() { return CreatePresentationAdapter.ADAPTER_ID; }
	@Override public Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext context) {
		Map<String, Outcome> result = new LinkedHashMap<>();
		for (String field : demand) result.put(field, Missing.UNAVAILABLE);
		if (!CreatePresentationAvailability.available() || demand.isEmpty()) return Map.copyOf(result);
		if (!(context instanceof MinecraftPreviewFieldAccess.WorldContext world)
			|| !context.dimensionId().equals(target.dimensionId())) return Map.copyOf(result);
		if (!(target instanceof Target.BlockTarget) && !(target instanceof Target.ExternalBlockTarget)) {
			demand.forEach(field -> result.put(field, Missing.NOT_APPLICABLE));
			return Map.copyOf(result);
		}
		// An external candidate is accepted only through a provider-confirmed
		// source; its live kinetic state and receipt checks below are unchanged.
		var source = world.blockSource(target);
		if (source == null || !source.containsMember(new BlockPos(source.block().x(), source.block().y(), source.block().z()))) return Map.copyOf(result);
		var block = source.block();
		if (world.blockState(block) == null) return Map.copyOf(result);
		var raw = world.blockEntity(block);
		if (!(raw instanceof KineticBlockEntity entity)) {
			if (raw != null) {
				for (String field : Set.of(CreatePresentationAdapter.SPEED, CreatePresentationAdapter.HAS_NETWORK,
					CreatePresentationAdapter.OVERSTRESSED, CreatePresentationAdapter.STRESS,
					CreatePresentationAdapter.CAPACITY, CreatePresentationAdapter.AVAILABLE_CAPACITY))
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
		// Registry-ID summaries still have no local quantity guarantee and keep
		// using the authorized server preview; cached network totals are gated by
		// the same receipt plus network presence and cached-field accessor shape.
		result.putAll(CreateCachedKineticPreview.observe(
			new CachedTotalsSource(entity, receipt), demand, context.tick()));
		return Map.copyOf(result);
	}
	private static void put(Map<String, Outcome> result, String field, java.util.function.Supplier<PresentationValue> getter, long tick) {
		try { result.put(field, new Observed(new PreviewObservation(getter.get(), PreviewObservation.Origin.CLIENT_SYNCED, tick, false))); }
		catch (RuntimeException | LinkageError unavailable) { result.put(field, Missing.UNAVAILABLE); }
	}

	/** Live binding of the Create-free cached-totals projection seam. */
	private static final class CachedTotalsSource implements CreateCachedKineticPreview.Source {
		private final KineticBlockEntity entity;
		private final CreateClientPreviewReceipt receipt;

		private CachedTotalsSource(KineticBlockEntity entity, CreateClientPreviewReceipt receipt) {
			this.entity = entity;
			this.receipt = receipt;
		}

		@Override public boolean receivedKinetics() { return receipt.pingforit$receivedKinetics(); }
		@Override public boolean hasNetwork() { return entity.hasNetwork(); }
		@Override public boolean hasCachedTotals() { return entity instanceof CreateCachedNetworkView; }
		@Override public float cachedStress() {
			return ((CreateCachedNetworkView) entity).pingforit$cachedNetworkStress();
		}
		@Override public float cachedCapacity() {
			return ((CreateCachedNetworkView) entity).pingforit$cachedNetworkCapacity();
		}
	}
}
