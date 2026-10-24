package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.SourceAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Inventory-local contiguous observations, without a second encoded payload. */
@NotNullByDefault
public record InventoryStep(CaptureResult metadata, int firstSlot,
	@Unmodifiable List<InventoryDomainCodec.@Nullable Item> slots, SweepEvidence evidence) {
	/** Domain evidence is independent of generic atomicity/version consistency. */
	@NotNullByDefault
	public enum SweepEvidence { NONE, STABLE_SLOT_SWEEP }

	public InventoryStep {
		Objects.requireNonNull(metadata, "metadata"); Objects.requireNonNull(slots, "slots"); Objects.requireNonNull(evidence, "evidence");
		if (metadata.payload().isPresent()) throw new IllegalArgumentException("typed inventory metadata carries a payload");
		if (firstSlot < 0 || firstSlot > InventorySourceAccess.MAX_SLOTS || slots.size() > InventorySourceAccess.MAX_STEP
			|| slots.size() > InventorySourceAccess.MAX_SLOTS - firstSlot
			|| metadata.coverage().scanned() != firstSlot + slots.size()
			|| (metadata.availability() != CaptureResult.Availability.READABLE && !slots.isEmpty()))
			throw new IllegalArgumentException("typed inventory page bounds");
		slots = Collections.unmodifiableList(new ArrayList<>(slots)); // List.copyOf rejects observed empty slots.
	}

	@NotNullByDefault
	public sealed interface Outcome {
		@NotNullByDefault record Captured(InventoryStep step) implements Outcome {
			public Captured { Objects.requireNonNull(step, "step"); }
		}
		@NotNullByDefault record Deferred() implements Outcome {}
	}

	/** Compatibility only: generic consistency never manufactures domain sweep evidence. */
	public static Outcome fromLegacy(SourceAccess.StepOutcome outcome) {
		Objects.requireNonNull(outcome, "outcome");
		if (outcome instanceof SourceAccess.StepOutcome.Deferred) return new Outcome.Deferred();
		CaptureResult result = ((SourceAccess.StepOutcome.Captured) outcome).result();
		var slots = new ArrayList<InventoryDomainCodec.@Nullable Item>();
		int end = Math.toIntExact(result.coverage().scanned());
		int first = end;
		if (result.payload().isPresent()) {
			if (!(result.payload().get() instanceof CaptureResult.OpaqueKeyedFragment page) || !InventoryDomainCodec.ID.equals(page.codecId()))
				throw new IllegalArgumentException("not an inventory fragment");
			int size = page.entries().size();
			first = end - size;
			if (size > InventorySourceAccess.MAX_STEP || first < 0) throw new IllegalArgumentException("inventory fragment bounds");
			for (int slot = first; slot < end; slot++) {
				@Nullable CaptureResult.OpaqueValue value = page.entries().get(Integer.toString(slot));
				if (value == null) throw new IllegalArgumentException("noncontiguous inventory fragment");
				slots.add(InventoryDomainCodec.decode(value));
			}
		}
		return new Outcome.Captured(new InventoryStep(withPayload(result, Optional.empty()), first, slots, SweepEvidence.NONE));
	}

	/** Explicit encoded compatibility, requested only by a generic consumer. */
	public CaptureResult toLegacy() {
		if (slots.isEmpty()) return metadata;
		var entries = new LinkedHashMap<String, CaptureResult.OpaqueValue>();
		for (int index = 0; index < slots.size(); index++) entries.put(Integer.toString(firstSlot + index), InventoryDomainCodec.encode(slots.get(index)));
		return withPayload(metadata, Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, entries)));
	}

	private static CaptureResult withPayload(CaptureResult source, Optional<CaptureResult.CapturePayload> payload) {
		return new CaptureResult(payload, source.coverage(), source.availability(), source.completeness(), source.consistency(), source.sourceVersion(), source.nextCursor());
	}
}
