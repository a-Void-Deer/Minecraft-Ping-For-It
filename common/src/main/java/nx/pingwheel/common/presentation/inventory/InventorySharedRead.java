package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Inventory-local equivalence evidence, never a replacement for an exact read binding or permission. */
@NotNullByDefault
public final class InventorySharedRead {
	private InventorySharedRead() {}
	@NotNullByDefault
	public record Policy(boolean distinguishFaces, boolean distinguishPlayers) {}
	@NotNullByDefault
	public enum Check { VALID, AUTHORITY_DENIED, STRUCTURE_INVALID, UNAVAILABLE, DEFERRED }
	/** Closed audited address families. Neither a stable cursor nor in-place validation grants one. */
	@NotNullByDefault
	public enum Route { VANILLA_CHEST, VANILLA_TRAPPED_CHEST, CREATE_VAULT_LOCAL }
	/** The layout describes ordered members, segment boundaries and every ordered member-local slot. */
	@NotNullByDefault
	public record Graph(String provider, String dimension, String alias, Route route, InventorySnapshotLayout layout) {
		public Graph {
			Objects.requireNonNull(provider); Objects.requireNonNull(dimension); Objects.requireNonNull(alias);
			Objects.requireNonNull(route); Objects.requireNonNull(layout);
			if (provider.isBlank() || provider.length() > 256 || dimension.isBlank() || dimension.length() > 256
				|| alias.isBlank() || alias.length() > 256 || layout.members().size() > 81)
				throw new IllegalArgumentException("shared read graph bound");
		}
		public int slots() {
			int slots = 0;
			for (var member : layout.members()) for (var segment : member.segments()) slots += segment.visibleSlots().size();
			return slots;
		}
	}
	/** Identity equality, not user equals/hashCode or a hash-only certificate. The referent stays server-local. */
	@NotNullByDefault
	public static final class Witness {
		private final Object value;
		public Witness(Object value) { this.value = Objects.requireNonNull(value); }
		public boolean is(Object current) { return value == current; }
		@Override public boolean equals(@Nullable Object other) { return other instanceof Witness witness && value == witness.value; }
		@Override public int hashCode() { return System.identityHashCode(value); }
	}
	/** Code-owned route metadata, not an incidental block-state/property or hit-relative role label. */
	@NotNullByDefault
	public record RouteEvidence(Witness block, @Nullable ChestType chestType, @Nullable Direction pairedFacing) {}
	/** No owner, authority result, resident contents or content version is retained here. */
	@NotNullByDefault
	public record Proof(Graph graph, Witness world, Witness registries, @Unmodifiable List<Witness> members,
		@Unmodifiable List<RouteEvidence> routeEvidence) {
		public Proof {
			Objects.requireNonNull(graph); Objects.requireNonNull(world); Objects.requireNonNull(registries);
			members = List.copyOf(members); routeEvidence = List.copyOf(routeEvidence);
			if (members.size() != graph.layout().members().size() || members.size() > 81
				|| routeEvidence.size() != members.size())
				throw new IllegalArgumentException("shared read witness bound");
		}
	}
	/** Borrowed from the source/handle. It must not outlive that owner; no global proof cache exists. */
	@NotNullByDefault
	public interface Port {
		Proof proof();
		/** All metadata/profile queries are cached, callback-free and do not allocate. */
		long retainedBytes();
		InventoryValidationBudget.Cost validationCost();
		InventoryValidationBudget.Cost readValidationCost(int slots);
		InventoryValidationBudget.Cost catalogCreationCost();
		Check validate(Proof expected, InventoryValidationBudget.Allowance allowance);
		/** Caller retains/charges the returned catalogue independently of the source. */
		InventoryItemCodec.Catalog createCatalog(int displayCeiling, InventoryValidationBudget.Allowance allowance);
		/**
		 * One physical visit, independent of the legacy handle cursor. The caller holds the slot,
		 * root-item work/workspace, catalogue growth and catalogue-processing reservations; the
		 * supplied validation allowance pays the complete metadata sequence, including failed attempts.
		 * Refusal never commits a journal cursor. Catalogues are accepted only from an equal proof.
		 */
		InventoryDomainCodec.@Nullable Item read(int slot, Proof expected, InventoryItemCodec.Catalog sharedCatalog,
			InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance);
	}
	/** Non-budget source failure. Processing/capacity refusals keep their existing distinct exception types. */
	@NotNullByDefault
	public static final class ReadFailure extends IllegalStateException {
		private final Check check;
		public ReadFailure(Check check) { this(check, null); }
		public ReadFailure(Check check, @Nullable Throwable cause) {
			super("certified inventory read: " + check, cause);
			if (check == Check.VALID || check == Check.DEFERRED) throw new IllegalArgumentException("non-failure check");
			this.check = check;
		}
		public Check check() { return check; }
	}
}
