package nx.pingwheel.common.platform;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.UUID;

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
 * <p>Caller sequence: admit probeProfile; probe(context, operation); admit the discovered shape through
 * Probe.finish; retain detached layout and source-owned witnesses; admit validation/read profiles; validate;
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
		@Override public OperationProfile probeProfile() { return OperationProfile.NONE; }
		@Override public Optional<Probe> probe(Context context, Operation operation) {
			Objects.requireNonNull(context); Objects.requireNonNull(operation).allowance(); return Optional.empty();
		}
	};
	IPlatformFluidService INSTANCE = ServiceLoader.load(IPlatformFluidService.class).findFirst().orElse(NONE);

	/**
	 * Pure upper bounds include cold, denied, exceptional and closing paths; visitor work is separate.
	 * Provider execution debits only its ownedCost. Context callbacks debit their own composed cost.
	 * retainedBytes is incremental newly owned metadata, not the total repeated at each nested stage.
	 */
	@NotNullByDefault
	record OperationProfile(InventoryValidationBudget.Cost ownedCost, long memberGateCalls,
		long currentAuthorityCalls, long retainedBytes) {
		public static final OperationProfile NONE = new OperationProfile(InventoryValidationBudget.NONE, 0, 0, 0);
		public OperationProfile {
			Objects.requireNonNull(ownedCost);
			if (memberGateCalls < 0 || currentAuthorityCalls < 0 || retainedBytes < 0)
				throw new IllegalArgumentException("negative fluid operation profile");
		}
		public OperationProfile plus(OperationProfile other) {
			Objects.requireNonNull(other);
			return new OperationProfile(ownedCost.plus(other.ownedCost), Math.addExact(memberGateCalls, other.memberGateCalls),
				Math.addExact(currentAuthorityCalls, other.currentAuthorityCalls), Math.addExact(retainedBytes, other.retainedBytes));
		}
		public OperationProfile times(long count) {
			if (count < 0) throw new IllegalArgumentException("negative fluid operation count");
			return count == 0 ? NONE : new OperationProfile(ownedCost.times(count), Math.multiplyExact(memberGateCalls, count),
				Math.multiplyExact(currentAuthorityCalls, count), Math.multiplyExact(retainedBytes, count));
		}
		/** Source callbacks are independent; neither is hidden inside the provider's owned cost. */
		public InventoryValidationBudget.Cost cost(InventoryValidationBudget.Cost memberGate, InventoryValidationBudget.Cost authority) {
			Objects.requireNonNull(memberGate); Objects.requireNonNull(authority);
			var callbacks = (memberGateCalls == 0 ? InventoryValidationBudget.NONE : memberGate.times(memberGateCalls))
				.plus(currentAuthorityCalls == 0 ? InventoryValidationBudget.NONE : authority.times(currentAuthorityCalls));
			return ownedCost.overlap(callbacks);
		}
	}
	/** Probe metadata only. A stable route may not know N until finish has gated all M members. */
	@NotNullByDefault
	record Shape(int members, OptionalInt indexedSlots, boolean stableCursor) {
		public Shape {
			Objects.requireNonNull(indexedSlots);
			if (members < 1 || members > InventorySnapshotLayout.MAX_MEMBERS || indexedSlots.isPresent()
				&& (indexedSlots.getAsInt() < 0 || indexedSlots.getAsInt() > InventorySourceAccess.MAX_SLOTS
					|| !stableCursor && indexedSlots.getAsInt() != 0)) throw new IllegalArgumentException("fluid probe shape bound");
		}
	}
	@NotNullByDefault
	enum TopologyEvidence { PUBLIC_ADDRESS_ONLY, EXPLICIT_STRUCTURE }
	@NotNullByDefault
	enum WitnessUse { CAPTURE, COMPARE }
	@NotNullByDefault
	enum WitnessMatch { SAME, CHANGED, UNVERIFIABLE }
	@NotNullByDefault
	record RouteInfo(TopologyEvidence topology, String providerClass) {
		public RouteInfo {
			Objects.requireNonNull(topology); Objects.requireNonNull(providerClass);
			if (providerClass.isBlank() || providerClass.length() > 1024 || providerClass.chars().anyMatch(Character::isISOControl))
				throw new IllegalArgumentException("fluid provider class bound");
		}
	}
	@NotNullByDefault
	@FunctionalInterface
	interface Admission {
		/** Reserve before callbacks/allocation. Execution debits ownedCost once; source gates debit their own work. */
		Operation enter(OperationProfile profile);
	}
	@NotNullByDefault
	interface Operation extends AutoCloseable {
		/** Same cumulative caller allowance across nested stages; never a refill or a new per-member allowance. */
		InventoryValidationBudget.Allowance allowance();
		/** Additional owned work must get a genuine caller grant BEFORE execution/debit, or throw budget deferral. */
		void requireAdditional(InventoryValidationBudget.Cost cost);
		@Override void close();
	}
	@NotNullByDefault
	@FunctionalInterface
	interface MemberGate { boolean test(BlockPos member, Operation operation); }
	@NotNullByDefault
	@FunctionalInterface
	interface AuthorityGate { boolean test(Operation operation); }
	/** Pure cached profile, before the first provider/world callback. */
	OperationProfile probeProfile();
	/** Root/controller/count/size only: no full-member allocation, iterator/content getter or layout construction. */
	Optional<Probe> probe(Context context, Operation operation);
	@NotNullByDefault
	interface Probe extends AutoCloseable {
		/** Pure frozen metadata, with no gate, capability lookup or allocation proportional to M/N. */
		Shape shape();
		/** Pure incremental finish bound for the known shape, excluding separately admitted later N growth. */
		OperationProfile finishProfile();
		/**
		 * Single use, synchronous and never tick-retained. Enter finishProfile before work. Gate all M members
		 * before a controller capability; when N is then known, enter its incremental profile BEFORE allocating
		 * layout/underlying/capacity arrays. Share Admission/allowance, including failure/closing callbacks.
		 * Close partial state on every failure; successful Access owns its metadata, never the probe ticket.
		 */
		Optional<Access> finish(Admission admission);
		/** Idempotent release only; any provider closing callbacks run inside the admitted probe/finish operation. */
		@Override void close();
	}

	/**
	 * Server-thread current scope. Suppliers must re-read authority/membership on every call; they must
	 * not capture a successful check, an allowance or an expired external resolution. External sources
	 * reacquire this context per operation; ordinary handles may retain it with live suppliers.
	 */
	@NotNullByDefault
	record Context(ServerLevel level, BlockPos root, Direction face, UUID readOwner,
		MemberGate memberGate, AuthorityGate currentAuthority) {
		public Context {
			Objects.requireNonNull(level); root = Objects.requireNonNull(root).immutable();
			Objects.requireNonNull(face); Objects.requireNonNull(readOwner);
			Objects.requireNonNull(memberGate); Objects.requireNonNull(currentAuthority);
		}
		public void require(BlockPos member, Operation operation) {
			Objects.requireNonNull(member); Objects.requireNonNull(operation).allowance();
			if (!level.getServer().isSameThread() || !memberGate.test(member, operation) || !currentAuthority.test(operation))
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
	interface Access extends AutoCloseable {
		/**
		 * Immutable bounded member/controller and ordered member-local address mapping; no contents.
		 * Segment schemaId is ADDRESS_SCHEMA, fieldPath is a provider-local segment identifier, localSlots
		 * and visibleSlots describe native tanks/views. Cursorless segments have zero indexed addresses;
		 * each bounded observe call still charges every visited view. This DTO alone proves no atomicity.
		 * Pure frozen metadata, without gates or callbacks; valid(operation) compares against THIS layout.
		 */
		InventorySnapshotLayout layout();
		/** Provider-established physical alias; empty retains exact physical root identity. */
		Optional<String> alias();
		/** False means fresh one-pass enumeration, never a synthetic cross-tick cursor. */
		boolean stableCursor();
		/** Stable visible address count; zero for cursorless. Empty addresses still cost visits. */
		int slots();
		/** Pure frozen classification; public-address evidence does not promise hidden equal-capacity remap detection. */
		RouteInfo routeInfo();
		/**
		 * Pure bound for an operation-local weak-identity vector, including the helper's owned cost.
		 * CAPTURE pays independent retained witness ownership; COMPARE retains nothing and constructs
		 * no new weak vector. Neither operation invokes a native/provider callback or replaces valid,
		 * member, authority or pre/post read checks. Local strong vectors die with the invocation.
		 * The default is migration-only unsupported, never empty successful evidence.
		 */
		default OperationProfile witnessProfile(WitnessUse use) {
			Objects.requireNonNull(use); throw new UnsupportedOperationException("fluid address witness unsupported");
		}
		/** Capture frozen current address identities, never this Access/context or a live resolution. */
		default FluidAddressWitness captureWitness(Operation operation) {
			Objects.requireNonNull(operation).allowance(); throw new UnsupportedOperationException("fluid address witness unsupported");
		}
		/** Compare the historical witness against this freshly acquired/validated access's current vector. */
		default WitnessMatch compareWitness(FluidAddressWitness expected, Operation operation) {
			Objects.requireNonNull(expected); Objects.requireNonNull(operation).allowance();
			throw new UnsupportedOperationException("fluid address witness unsupported");
		}
		/** Pure cached profile: current route/mode, original ordered layout and dynamic member checks. */
		OperationProfile validationProfile();
		/** Pure cached profile for ONE attempted getter plus provider pre/post/member gates, excluding visitor. */
		OperationProfile readProfile();
		/** Includes bounded iterator/metadata entry and closure even for a zero-entry pass. Pure cached arithmetic. */
		default OperationProfile observationProfile(int limit) {
			if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
			return readProfile().times(limit).plus(new OperationProfile(observationLoopCost(limit), 0, 0, 0));
		}
		/**
		 * Fresh metadata-only check against original alias/member/controller/address topology and current
		 * provider/mode gates; equal totals alone are insufficient. Source also checks world/member object
		 * identity, registry and owner lock/loot permissions. A changed layout invalidates, never remaps.
		 */
		boolean valid(Operation operation);
		/**
		 * Exactly one indexed content getter, with synchronous visitor before any later provider callback.
		 * Visitor output is provisional until provider AND source postchecks pass. The caller charges an
		 * attempted physical visit even on throw; overflow/failure throws, never empty/All variants.
		 */
		boolean consume(int slot, Visitor visitor, Operation operation);
		/**
		 * Cursorless implementations gate iterator/hasNext/next internally and visit at most limit views,
		 * including empties. Each later call starts at zero. No retained iterator, snapshot-backed pseudo
		 * incrementality or hidden full scan. Caller admits observationProfile(limit) and charges attempted prefixes;
		 * an opaque exception conservatively spends the whole grant. Visitor refusal is not completion.
		 */
		default Visit observe(int limit, Visitor visitor, Operation operation) {
			if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
			Objects.requireNonNull(visitor); Objects.requireNonNull(operation).allowance().debit(observationLoopCost(limit));
			if (!stableCursor()) throw new UnsupportedOperationException("cursorless fluid provider needs a bounded observer");
			int count = slots();
			if (count < 0 || count > InventorySourceAccess.MAX_SLOTS) throw new IllegalStateException("fluid address bound");
			for (int slot = 0; slot < Math.min(count, limit); slot++) if (!consume(slot, visitor, operation)) return new Visit(slot + 1, false);
			return new Visit(Math.min(count, limit), count <= limit);
		}
		/** Release only, idempotent; no new provider callbacks or unadmitted traversal. */
		@Override void close();
	}
	private static InventoryValidationBudget.Cost observationLoopCost(int limit) {
		return new InventoryValidationBudget.Cost(256, 256, 8L + limit);
	}
	/** Keeps the former root entry/exit fences; metadata accessors are now pure, not authorization boundaries. */
	static Access guarded(Context context, Access delegate) { return new GuardedAccess(context, delegate); }
	@NotNullByDefault
	final class GuardedAccess implements Access {
		private static final OperationProfile WRAPPER = new OperationProfile(new InventoryValidationBudget.Cost(256, 256, 16), 2, 2, 0);
		private final Context context;
		private final Access delegate;
		private boolean closed;
		private GuardedAccess(Context context, Access delegate) {
			this.context = Objects.requireNonNull(context); this.delegate = Objects.requireNonNull(delegate);
		}
		private void before(Operation operation) {
			if (closed) throw new IllegalStateException("fluid access closed");
			operation.allowance().debit(WRAPPER.ownedCost()); context.require(context.root(), operation);
		}
		@Override public InventorySnapshotLayout layout() { return delegate.layout(); }
		@Override public Optional<String> alias() { return delegate.alias(); }
		@Override public boolean stableCursor() { return delegate.stableCursor(); }
		@Override public int slots() { return delegate.slots(); }
		@Override public RouteInfo routeInfo() { return delegate.routeInfo(); }
		@Override public OperationProfile witnessProfile(WitnessUse use) { return delegate.witnessProfile(use); }
		private void witnessOperation(Operation operation) {
			if (closed) throw new IllegalStateException("fluid access closed");
			Objects.requireNonNull(operation).allowance();
		}
		@Override public FluidAddressWitness captureWitness(Operation operation) {
			witnessOperation(operation); return delegate.captureWitness(operation);
		}
		@Override public WitnessMatch compareWitness(FluidAddressWitness expected, Operation operation) {
			Objects.requireNonNull(expected); witnessOperation(operation); return delegate.compareWitness(expected, operation);
		}
		@Override public OperationProfile validationProfile() { return WRAPPER.plus(delegate.validationProfile()); }
		@Override public OperationProfile readProfile() { return WRAPPER.plus(delegate.readProfile()); }
		@Override public OperationProfile observationProfile(int limit) { return WRAPPER.plus(delegate.observationProfile(limit)); }
		@Override public boolean valid(Operation operation) {
			before(operation); boolean value = delegate.valid(operation); context.require(context.root(), operation); return value;
		}
		@Override public boolean consume(int slot, Visitor visitor, Operation operation) {
			Objects.checkIndex(slot, slots()); Objects.requireNonNull(visitor);
			before(operation); boolean value = delegate.consume(slot, visitor, operation); context.require(context.root(), operation); return value;
		}
		@Override public Visit observe(int limit, Visitor visitor, Operation operation) {
			if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
			Objects.requireNonNull(visitor); before(operation);
			Visit value = delegate.observe(limit, visitor, operation); context.require(context.root(), operation);
			if (value.visited() > limit) throw new IllegalStateException("fluid provider exceeded observation bound");
			return value;
		}
		@Override public void close() { if (!closed) { closed = true; delegate.close(); } }
	}
}
