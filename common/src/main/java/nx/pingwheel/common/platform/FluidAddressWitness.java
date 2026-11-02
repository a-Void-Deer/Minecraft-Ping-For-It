package nx.pingwheel.common.platform;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Objects;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.OperationProfile;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessMatch;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessUse;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Bounded, non-owning historical address identity. No referent, live access, input array or
 * provider callback escapes through this value. This is observation evidence, not a target key,
 * permission result, source generation or pooling certificate.
 */
@NotNullByDefault
public final class FluidAddressWitness {
	public static final int MAX_REFERENCES = 4097;

	/** Code-fixed vector interpretations; these are not wire or persistent schema identifiers. */
	@NotNullByDefault
	public enum Schema {
		/** Storage, then each ordered terminal underlying address; an unindexed route has only storage. */
		FABRIC_TRANSFER_V1,
		/** BlockState, LazyOptional, handler. Ordered capacities belong to the detached layout. */
		FORGE_CAPABILITY_V1,
		/** Handler. Ordered capacities and structural members belong to the detached layout. */
		NEOFORGE_CAPABILITY_V1,
		/** Common source's ordered block-entity identities, separate from its position/registry metadata. */
		COMMON_MEMBERS_V1
	}

	private final Schema schema;
	private final WeakReference<Object> @Unmodifiable [] identities;

	private FluidAddressWitness(Schema schema, Object[] current) {
		this.schema = schema;
		@SuppressWarnings("unchecked")
		WeakReference<Object>[] captured = (WeakReference<Object>[]) new WeakReference<?>[current.length];
		for (int index = 0; index < current.length; index++)
			captured[index] = new WeakReference<>(Objects.requireNonNull(current[index], "fluid address identity"));
		identities = captured;
	}

	/**
	 * Pure helper bound, including the caller's one temporary strong Object[] vector. The adapter
	 * must enter its composed witness profile and check this owned cost before allocating that vector.
	 * These helpers debit this cost themselves: do not debit it a second time in the adapter.
	 * Additional adapter work must be profiled and debited separately; this helper invokes no gates
	 * or native/provider callbacks and never allocates a comparison weak-reference vector.
	 */
	public static OperationProfile profile(WitnessUse use, int references) {
		Objects.requireNonNull(use); requireCount(references);
		if (use == WitnessUse.CAPTURE) {
			long bytes = 768L + 128L * references;
			return new OperationProfile(new InventoryValidationBudget.Cost(bytes, bytes, 64L + 8L * references),
				0, 0, retainedBytes(references));
		}
		long bytes = 256L + 8L * references;
		return new OperationProfile(new InventoryValidationBudget.Cost(bytes, bytes, 64L + 8L * references), 0, 0, 0);
	}

	/** Weak objects, private vector and fixed scalar/schema ownership; caller owns the retained ticket. */
	public static long retainedBytes(int references) {
		requireCount(references); return 256L + 112L * references;
	}

	/**
	 * Capture under an already held operation grant. Never retains the input array or a strong
	 * identity. The caller transfers the CAPTURE retained reservation into source/evidence ownership;
	 * closing a live Access must not clear this independent witness.
	 */
	public static FluidAddressWitness capture(Schema schema, Object[] current, Operation operation) {
		Objects.requireNonNull(schema); Objects.requireNonNull(current);
		Objects.requireNonNull(operation).allowance().debit(profile(WitnessUse.CAPTURE, current.length).ownedCost());
		try { return new FluidAddressWitness(schema, current); }
		finally { Reference.reachabilityFence(current); }
	}

	/**
	 * Compare an operation-local, freshly validated access's vector with this historical evidence.
	 * Schema, length or a positively different live identity means CHANGED. A cleared identity alone
	 * means UNVERIFIABLE, never proven remapping, SAME, completion, zero or a permanent budget defer.
	 * No equals, hashCode, referent getter, closure or provider callback participates.
	 */
	public WitnessMatch compare(Schema currentSchema, Object[] current, Operation operation) {
		Objects.requireNonNull(currentSchema); Objects.requireNonNull(current);
		Objects.requireNonNull(operation).allowance().debit(profile(WitnessUse.COMPARE, current.length).ownedCost());
		try {
			if (schema != currentSchema || identities.length != current.length) return WitnessMatch.CHANGED;
			boolean unverifiable = false;
			for (int index = 0; index < identities.length; index++) {
				Object identity = Objects.requireNonNull(current[index], "fluid address identity");
				WeakReference<Object> reference = identities[index];
				if (reference.refersTo(identity)) continue;
				// Check clearing AFTER a failed identity match: concurrent GC cannot masquerade as remapping.
				if (reference.refersTo(null)) unverifiable = true;
				else return WitnessMatch.CHANGED;
			}
			return unverifiable ? WitnessMatch.UNVERIFIABLE : WitnessMatch.SAME;
		} finally { Reference.reachabilityFence(current); }
	}

	private static void requireCount(int references) {
		if (references < 1 || references > MAX_REFERENCES) throw new IllegalArgumentException("fluid witness reference bound");
	}

	/** Deterministic package-local test seam; exposes no reference or referent. */
	void clearForTesting(int index) { identities[Objects.checkIndex(index, identities.length)].clear(); }
}
