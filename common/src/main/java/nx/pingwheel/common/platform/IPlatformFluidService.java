package nx.pingwheel.common.platform;

import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.presentation.inventory.InventoryQuantities;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread, read-only fluid capability boundary shared by all loaders. This port never exports a
 * loader FluidStack, maps fluid to ItemStack, mutates storage, opens a transaction, loads a chunk or
 * triggers loot. Missing registration is a normal unavailable route, not a startup failure.
 *
 * <p>Caller sequence: reserve ONE Runtime's work/workspace; find(context, allowance); retain the
 * detached layout and source-owned world/member witnesses; reserve validation/read profiles; validate;
 * consume an address (or one bounded cursorless pass); synchronously freeze in the visitor; validate
 * original root/member objects, current route/controller/mode and owner again before committing data.
 * Revalidate the exact historical evidence again before publication. No successful check is cached.
 *
 * <p>Adapters must gate the root, controller and EVERY member before state/entity/capability/content
 * or iterator metadata access, including already acquired objects. The frozen side never widens to
 * null, another face or an unsided handler. Create uses the same NeoForge IFluidHandler route with
 * tested-version, whole-structure, controller, creative-tank and active-boiler mode checks. Static
 * block capabilities only: cauldrons, moving/virtual sources and entity/private storage are unsupported.
 * No fluid route certifies cross-owner/face pooling or snapshot atomicity.
 * Native third-party capability/codec internals remain external-trusted: admission bounds invocation
 * count, not arbitrary callback time or allocation. Ping-owned metadata, copies, canonicalization,
 * hashing, retained values and publication still require their separately admitted finite guards.
 */
@NotNullByDefault
public interface IPlatformFluidService {
	/** Layout segments describe tank/view addresses, not item slots or an NBT save path. */
	String ADDRESS_SCHEMA = "pingforit:fluid.address.v1";
	IPlatformFluidService NONE = new IPlatformFluidService() {
		@Override public InventoryValidationBudget.Cost resolutionCost() { return InventoryValidationBudget.NONE; }
		@Override public Optional<Access> resolve(Context context) { return Optional.empty(); }
		@Override public Optional<Access> find(Context context, InventoryValidationBudget.Allowance allowance) {
			Objects.requireNonNull(context); Objects.requireNonNull(allowance); return Optional.empty();
		}
	};
	IPlatformFluidService INSTANCE = ServiceLoader.load(IPlatformFluidService.class).findFirst().orElse(NONE);

	/**
	 * Pure cached upper bound including find's wrapper/bookkeeping and all owned resolution work,
	 * before any callback. Source-owned authority callback costs are admitted separately by the source.
	 */
	InventoryValidationBudget.Cost resolutionCost();
	/**
	 * Implementation hook entered only under resolutionCost admission. Metadata only: no fluid getter,
	 * full save or eager snapshot. Bounded indexed addressing must not be implemented by a full scan.
	 * Exceptions propagate so budget deferral cannot be confused with source absence.
	 */
	Optional<Access> resolve(Context context);
	default Optional<Access> find(Context context, InventoryValidationBudget.Allowance allowance) {
		Objects.requireNonNull(context); Objects.requireNonNull(allowance).debit(resolutionCost());
		context.require(context.root());
		Optional<Access> result = resolve(context);
		context.require(context.root());
		return result.map(access -> new GuardedAccess(context, access));
	}

	/**
	 * Server-thread current scope. Suppliers must re-read authority/membership on every call; they must
	 * not capture a successful check, an allowance or an expired external resolution. External sources
	 * reacquire this context per operation; ordinary handles may retain it with live suppliers.
	 */
	@NotNullByDefault
	record Context(ServerLevel level, BlockPos root, Direction face, UUID readOwner,
		Predicate<BlockPos> memberGate, BooleanSupplier currentAuthority) {
		public Context {
			Objects.requireNonNull(level); root = Objects.requireNonNull(root).immutable();
			Objects.requireNonNull(face); Objects.requireNonNull(readOwner);
			Objects.requireNonNull(memberGate); Objects.requireNonNull(currentAuthority);
		}
		public void require(BlockPos member) {
			if (!level.getServer().isSameThread() || !memberGate.test(member) || !currentAuthority.getAsBoolean())
				throw new InventorySourceUnavailableException("fluid access outside current owner/member scope");
		}
	}

	/** Borrowed native identity input valid ONLY inside the immediate visitor; never retain/hash/format it. */
	@NotNullByDefault
	sealed interface Resident permits Components, Nbt { Fluid fluid(); }
	/** Fabric and NeoForge actual explicit persistent components; never an effective/default map. */
	@NotNullByDefault
	record Components(Fluid fluid, DataComponentPatch patch) implements Resident {
		public Components { Objects.requireNonNull(fluid); Objects.requireNonNull(patch); }
	}
	/** Forge's actual native variant NBT; null is a genuinely absent tag, not failed encoding. */
	@NotNullByDefault
	record Nbt(Fluid fluid, @Nullable CompoundTag tag) implements Resident {
		public Nbt { Objects.requireNonNull(fluid); }
	}
	/** Amount is authoritative U, checked natively (mB * 81 on Forge/NeoForge) before handoff. */
	@NotNullByDefault
	record Entry(@Nullable Resident resident, long amount) {
		public Entry {
			InventoryQuantities.require(amount);
			if ((amount == 0) != (resident == null)) throw new IllegalArgumentException("fluid empty/amount mismatch");
		}
		public static Entry empty() { return new Entry(null, 0); }
	}
	@NotNullByDefault
	@FunctionalInterface
	interface Visitor {
		/** Freeze/detach BEFORE the provider's next metadata callback. False stops, without completing. */
		boolean visit(Entry entry);
	}
	@NotNullByDefault
	record Visit(int visited, boolean complete) {
		public Visit { if (visited < 0 || visited > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid visit bound"); }
	}

	@NotNullByDefault
	interface Access {
		/**
		 * Immutable bounded member/controller and ordered member-local address mapping; no contents.
		 * Segment schemaId is ADDRESS_SCHEMA, fieldPath is a provider-local segment identifier, localSlots
		 * and visibleSlots describe native tanks/views. Cursorless segments have zero indexed addresses;
		 * each bounded observe call still charges every visited view. This DTO alone proves no atomicity.
		 * Resolved once and retained unchanged; valid() compares current topology against THIS layout.
		 */
		InventorySnapshotLayout layout();
		/** Provider-established physical alias; empty retains exact physical root identity. */
		Optional<String> alias();
		/** False means fresh one-pass enumeration, never a synthetic cross-tick cursor. */
		boolean stableCursor();
		/** Stable visible address count; zero for cursorless. Empty addresses still cost visits. */
		int slots();
		/** Pure cached profile: current route/mode, original ordered layout and dynamic member checks. */
		InventoryValidationBudget.Cost validationCost();
		/** Pure cached profile for ONE attempted getter plus provider pre/post/member gates, excluding visitor. */
		InventoryValidationBudget.Cost readCost();
		/** Includes bounded iterator/metadata entry and closure even for a zero-entry pass. Pure cached arithmetic. */
		default InventoryValidationBudget.Cost observationCost(int limit) {
			if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
			return validationCost().plus(readCost().times(limit));
		}
		/**
		 * Fresh metadata-only check against original alias/member/controller/address topology and current
		 * provider/mode gates; equal totals alone are insufficient. Source also checks world/member object
		 * identity, registry and owner lock/loot permissions. A changed layout invalidates, never remaps.
		 */
		boolean valid();
		default boolean valid(InventoryValidationBudget.Allowance allowance) {
			Objects.requireNonNull(allowance).debit(validationCost()); return valid();
		}
		/**
		 * Exactly one indexed content getter, with synchronous visitor before any later provider callback.
		 * Visitor output is provisional until provider AND source postchecks pass. The caller charges an
		 * attempted physical visit even on throw; overflow/failure throws, never empty/All variants.
		 */
		boolean consume(int slot, Visitor visitor);
		default boolean consume(int slot, Visitor visitor, InventoryValidationBudget.Allowance allowance) {
			Objects.requireNonNull(allowance).debit(readCost()); return consume(slot, visitor);
		}
		/**
		 * Cursorless implementations gate iterator/hasNext/next internally and visit at most limit views,
		 * including empties. Each later call starts at zero. No retained iterator, snapshot-backed pseudo
		 * incrementality or hidden full scan. Caller admits observationCost(limit) and charges attempted prefixes;
		 * an opaque exception conservatively spends the whole grant. Visitor refusal is not completion.
		 */
		default Visit observe(int limit, Visitor visitor) {
			if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
			Objects.requireNonNull(visitor);
			if (!stableCursor()) throw new UnsupportedOperationException("cursorless fluid provider needs a bounded observer");
			int count = slots();
			if (count < 0 || count > InventorySourceAccess.MAX_SLOTS) throw new IllegalStateException("fluid address bound");
			for (int slot = 0; slot < Math.min(count, limit); slot++) if (!consume(slot, visitor)) return new Visit(slot + 1, false);
			return new Visit(Math.min(count, limit), count <= limit);
		}
		default Visit observe(int limit, Visitor visitor, InventoryValidationBudget.Allowance allowance) {
			Objects.requireNonNull(allowance).debit(observationCost(limit)); return observe(limit, visitor);
		}
	}

	/** Default wrapper does not forward certification or invent cursorless internal member gates. */
	@NotNullByDefault
	final class GuardedAccess implements Access {
		private final Context context;
		private final Access delegate;
		private GuardedAccess(Context context, Access delegate) { this.context = context; this.delegate = Objects.requireNonNull(delegate); }
		private void gate() { context.require(context.root()); }
		@Override public InventorySnapshotLayout layout() { gate(); var value = delegate.layout(); gate(); return value; }
		@Override public Optional<String> alias() { gate(); var value = delegate.alias(); gate(); return value; }
		@Override public boolean stableCursor() { gate(); boolean value = delegate.stableCursor(); gate(); return value; }
		@Override public int slots() { gate(); int value = delegate.slots(); gate(); return value; }
		@Override public InventoryValidationBudget.Cost validationCost() { return delegate.validationCost(); }
		@Override public InventoryValidationBudget.Cost readCost() { return delegate.readCost(); }
		@Override public InventoryValidationBudget.Cost observationCost(int limit) { return delegate.observationCost(limit); }
		@Override public boolean valid() { gate(); boolean value = delegate.valid(); gate(); return value; }
		@Override public boolean consume(int slot, Visitor visitor) { gate(); boolean more = delegate.consume(slot, visitor); gate(); return more; }
		@Override public Visit observe(int limit, Visitor visitor) {
			gate();
			if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
			// Providers already own per-member/per-iterator gates through the original Context.
			Visit result = delegate.observe(limit, Objects.requireNonNull(visitor)); gate();
			if (result.visited() > limit) throw new IllegalStateException("fluid provider exceeded observation bound");
			return result;
		}
	}
}
