package nx.pingwheel.neoforge.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.OperationProfile;
import nx.pingwheel.common.presentation.inventory.InventoryQuantities;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** One capability read implementation, shared by ordinary blocks and the Create safety route. */
@NotNullByDefault
public final class FluidCapabilityAccess implements IPlatformFluidService.Access {

	/** Detached identities plus server-local object witnesses; never fluid contents. */
	@NotNullByDefault
	public record Block(Object block, @Nullable Object entity, String blockId, String entityId) {
		public Block {
			Objects.requireNonNull(block); Objects.requireNonNull(blockId); Objects.requireNonNull(entityId);
		}
		public boolean same(Block other) {
			return block == other.block && entity == other.entity
				&& blockId.equals(other.blockId) && entityId.equals(other.entityId);
		}
	}

	@NotNullByDefault
	public record Member(BlockPos position, Block block) {
		public Member { position = Objects.requireNonNull(position).immutable(); Objects.requireNonNull(block); }
	}

	/** Production uses Context.require; the headless port exercises this same route and getter order. */
	@NotNullByDefault
	public interface World {
		void require(BlockPos position, Operation operation);
		Block block(BlockPos position, Operation operation);
		@Nullable IFluidHandler capability(BlockPos position, Direction face, Operation operation);
		String dimension(Operation operation);
		boolean createAvailable();
		/** Final held-object safety check without resolving a new mapping or reading contents. */
		default boolean held(Member member, Operation operation) { require(member.position(), operation); return true; }
		// Counts cover the production MinecraftWorld, including its nested loaded/state/entity gates.
		default OperationProfile requireProfile() { return profile(128, 128, 8, 2, 0); }
		default OperationProfile blockProfile() { return profile(2048, 2048, 1024, 16, 0); }
		default OperationProfile heldProfile() { return profile(256, 256, 32, 8, 0); }
		default OperationProfile capabilityProfile() { return profile(128, 128, 8, 2, 0); }
		default OperationProfile dimensionProfile() { return profile(1024, 1024, 256, 1, 0); }
	}

	@NotNullByDefault
	public interface Route {
		World world();
		Direction face();
		BlockPos capabilityPosition();
		@Unmodifiable List<Member> members();
		Optional<String> alias();
		String description();
		/** Metadata only, checking original identities, current mode and exact ordered topology. */
		boolean matches(Operation operation);
		/** Reject non-stock capability modes (for example a stale Create boiler cache). No contents. */
		default boolean accepts(IFluidHandler handler, Operation operation) { return true; }
		default OperationProfile acceptanceProfile() { return OperationProfile.NONE; }
		OperationProfile checkProfile();
		IPlatformFluidService.TopologyEvidence topology();
	}

	/** Fixed root/controller discovery, including cold fixed reflection and exceptional paths.
	 * Probe-only references stay in synchronous scratch; the finish stages acquire round ownership. */
	public static final OperationProfile PROBE_PROFILE = profile(65536, 32768, 8192, 192, 0);
	private static final OperationProfile COUNT = profile(64, 64, 8, 0, 0);
	private static final OperationProfile CAPACITY = profile(64, 64, 16, 0, 0);
	// Only handler identity crosses operations here; ordered capacities and members remain in layout.
	private static final OperationProfile CAPTURE_WITNESS = FluidAddressWitness.profile(IPlatformFluidService.WitnessUse.CAPTURE, 1);
	private static final OperationProfile COMPARE_WITNESS = FluidAddressWitness.profile(IPlatformFluidService.WitnessUse.COMPARE, 1);
	// Includes cold fixed-field metadata bookkeeping, primitive amount access and failure wrapping.
	// VM reflection internals, like the native getter internals, remain external-trusted.
	private static final InventoryValidationBudget.Cost RESIDENT_HANDOFF =
		new InventoryValidationBudget.Cost(256, 256, 32);

	private @Nullable Route route;
	private @Nullable IFluidHandler handler;
	private final int count;
	private int @Unmodifiable [] capacities;
	private final InventorySnapshotLayout layout;
	private final Optional<String> alias;
	private final IPlatformFluidService.RouteInfo routeInfo;
	private final OperationProfile validationProfile, readProfile;
	private static final int @Unmodifiable [] NO_CAPACITIES = new int[0];

	public static OperationProfile profile(long allocation, long workspace, long work, long gates, long retained) {
		return new OperationProfile(new InventoryValidationBudget.Cost(allocation, workspace, work), gates, gates, retained);
	}
	private static OperationProfile currentProfile(Route route, int count) {
		var world = route.world(); long members = route.members().size();
		// Two route passes each close with held(M); acquisition and final count add two more held(M).
		// The two count gates, closing authority(M), and two ordered capacity(N) passes are separate.
		return route.checkProfile().times(2).plus(world.heldProfile().times(4 * members))
			.plus(world.capabilityProfile()).plus(route.acceptanceProfile())
			.plus(world.requireProfile().times(2 + members)).plus(COUNT.times(2))
			.plus(world.requireProfile().plus(CAPACITY).times(2L * count));
	}
	/** Incremental N-sized allocation, only after an admitted count lookup. No maximum-slot reservation. */
	public static OperationProfile addressProfile(Route route, int count) {
		requireBound(count); long members = route.members().size();
		// Construction adds boxed indices/copies/seen(N), capacities(N), bounded decimal text (<=11N),
		// member DTOs and position-set(M). Retained bytes cover only the newly acquired detached graph;
		// the Create route/member witnesses themselves were already acquired by the M stage.
		return currentProfile(route, count).plus(profile(8192 + 2048 * members + 256L * count,
			4096 + 1024 * members + 128L * count, 1024 + 512 * members + 32L * count,
			0, 2048 + 1024 * members + 128L * count));
	}

	private FluidCapabilityAccess(Route route, IFluidHandler handler, int @Unmodifiable [] capacities) {
		this.route = route; this.handler = handler; this.count = capacities.length; this.capacities = capacities;
		alias = route.alias(); routeInfo = new IPlatformFluidService.RouteInfo(route.topology(), handler.getClass().getName());
		List<Integer> addresses = new ArrayList<>(count);
		for (int index = 0; index < count; index++) addresses.add(index);
		var segment = new InventorySnapshotLayout.Segment("capability.tanks", IPlatformFluidService.ADDRESS_SCHEMA,
			count, addresses);
		List<InventorySnapshotLayout.Member> members = new ArrayList<>(route.members().size());
		for (Member member : route.members()) {
			boolean controller = member.position().equals(route.capabilityPosition());
			members.add(new InventorySnapshotLayout.Member(member.position(), member.block().blockId(), member.block().entityId(),
				controller ? "controller" : "member", controller ? List.of(segment) : List.of()));
		}
		var description = new StringBuilder(route.description()).append(";capacities=");
		for (int index = 0; index < count; index++) { if (index > 0) description.append(','); description.append(capacities[index]); }
		layout = new InventorySnapshotLayout("neoforge:fluid_capability", description.toString(), route.capabilityPosition(), members);
		validationProfile = currentProfile(route, count);
		readProfile = validationProfile.times(2).plus(new OperationProfile(RESIDENT_HANDOFF, 0, 0, 0));
	}

	public static Optional<IPlatformFluidService.Probe> probe(Route route, Operation operation) {
		@Nullable IFluidHandler handler = acquire(route, operation);
		if (handler == null) return Optional.empty();
		route.world().require(route.capabilityPosition(), operation);
		int count = handler.getTanks();
		requireBound(count);
		return Optional.of(new CapabilityProbe(route, handler, count, operation));
	}
	public static @Nullable IFluidHandler acquire(Route route, Operation operation) {
		requireRoute(route, operation);
		@Nullable IFluidHandler handler = route.world().capability(route.capabilityPosition(), route.face(), operation);
		requireHeld(route, operation);
		if (handler != null && !route.accepts(handler, operation))
			throw new InventorySourceUnavailableException("fluid capability mode unavailable");
		return handler;
	}
	public static int tankCount(Route route, IFluidHandler handler, Operation operation) {
		route.world().require(route.capabilityPosition(), operation); int count = handler.getTanks(); requireBound(count); return count;
	}
	private static void requireBound(int count) {
		if (count < 0 || count > InventorySourceAccess.MAX_SLOTS) throw new InventorySourceUnavailableException("fluid capability address bound");
	}
	/** Caller entered addressProfile before this method; the expected route/count never widen. */
	public static IPlatformFluidService.Access finish(Route route, IFluidHandler handler, int count, Operation operation) {
		operation.allowance().debit(addressProfile(route, count).ownedCost());
		if (acquire(route, operation) != handler || tankCount(route, handler, operation) != count)
			throw new InventorySourceUnavailableException("fluid probe shape changed");
		int[] capacities = new int[count];
		for (int index = 0; index < count; index++) {
			route.world().require(route.capabilityPosition(), operation);
			int capacity = handler.getTankCapacity(index);
			if (capacity < 0) throw new InventorySourceUnavailableException("negative fluid address capacity");
			capacities[index] = capacity;
		}
		requireRoute(route, operation);
		requireCapacities(route, handler, capacities, operation);
		requireCountAndHeld(route, handler, count, operation);
		return new FluidCapabilityAccess(route, handler, capacities);
	}

	private static void requireRoute(Route route, Operation operation) {
		if (!route.matches(operation)) throw new InventorySourceUnavailableException("fluid capability route changed");
		// The final metadata callback cannot authorize a now-denied sibling or controller.
		requireHeld(route, operation);
	}
	private static void requireHeld(Route route, Operation operation) {
		for (Member member : route.members())
			if (!route.world().held(member, operation)) throw new InventorySourceUnavailableException("fluid held member replaced");
	}
	private static void requireCapacities(Route route, IFluidHandler handler, int[] expected, Operation operation) {
		// Capacities are public address metadata, not contents or a claim of opaque provider internals.
		// Only the capability owner is accessed per address; do not rescan every structural member for
		// every tank. Full route/mode and all-member checks surround the bounded sequence.
		for (int index = 0; index < expected.length; index++) {
			route.world().require(route.capabilityPosition(), operation);
			if (handler.getTankCapacity(index) != expected[index])
				throw new InventorySourceUnavailableException("fluid capability address mapping changed");
		}
	}
	private static void requireCountAndHeld(Route route, IFluidHandler handler, int expected, Operation operation) {
		requireHeld(route, operation);
		route.world().require(route.capabilityPosition(), operation);
		if (handler.getTanks() != expected) throw new InventorySourceUnavailableException("fluid capability layout changed");
		// Count follows the last state/entity/route callback. The closing pass rechecks current
		// authority only, without another route/state getter that could hide a later count change.
		for (Member member : route.members()) route.world().require(member.position(), operation);
	}

	@Override public InventorySnapshotLayout layout() { return layout; }
	@Override public Optional<String> alias() { return alias; }
	@Override public boolean stableCursor() { return true; }
	@Override public int slots() { return count; }
	@Override public IPlatformFluidService.RouteInfo routeInfo() { return routeInfo; }
	@Override public OperationProfile witnessProfile(IPlatformFluidService.WitnessUse use) {
		return Objects.requireNonNull(use) == IPlatformFluidService.WitnessUse.CAPTURE ? CAPTURE_WITNESS : COMPARE_WITNESS;
	}
	@Override public FluidAddressWitness captureWitness(Operation operation) {
		liveRoute();
		var current = Objects.requireNonNull(handler);
		// Preflight precedes the temporary strong vector; the helper performs the single owned debit.
		operation.allowance().requireAvailable(CAPTURE_WITNESS.ownedCost());
		return FluidAddressWitness.capture(FluidAddressWitness.Schema.NEOFORGE_CAPABILITY_V1, new Object[] {current}, operation);
	}
	@Override public IPlatformFluidService.WitnessMatch compareWitness(FluidAddressWitness expected, Operation operation) {
		Objects.requireNonNull(expected); liveRoute();
		var current = Objects.requireNonNull(handler);
		operation.allowance().requireAvailable(COMPARE_WITNESS.ownedCost());
		return expected.compare(FluidAddressWitness.Schema.NEOFORGE_CAPABILITY_V1, new Object[] {current}, operation);
	}
	@Override public OperationProfile validationProfile() { return validationProfile; }
	@Override public OperationProfile readProfile() { return readProfile; }
	@Override public OperationProfile observationProfile(int limit) {
		if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
		return validationProfile.times(2).plus(IPlatformFluidService.Access.super.observationProfile(limit));
	}

	private Route liveRoute() {
		if (route == null) throw new IllegalStateException("fluid access closed"); return route;
	}
	private void requireCurrent(Operation operation) {
		var route = liveRoute();
		@Nullable IFluidHandler current = acquire(route, operation);
		// A matching total is not a route witness: retain the exact capability object and root/member
		// objects as well as the ordered indices. Replacement starts a fresh observation, not a remap.
		if (current == null || current != handler) throw new InventorySourceUnavailableException("fluid capability replaced");
		route.world().require(route.capabilityPosition(), operation);
		int currentCount = current.getTanks();
		if (currentCount != count) throw new InventorySourceUnavailableException("fluid capability layout changed");
		requireCapacities(route, current, capacities, operation);
		requireRoute(route, operation);
		// The post-capacity route callbacks may themselves change public addressing. Check that
		// ordered witness again, then check count AFTER the last capacity/route callback as well.
		requireCapacities(route, current, capacities, operation);
		requireCountAndHeld(route, current, count, operation);
	}

	@Override public boolean valid(Operation operation) {
		liveRoute(); operation.allowance().debit(validationProfile.ownedCost());
		try { requireCurrent(operation); return true; }
		catch (InventorySourceUnavailableException unavailable) { return false; }
	}

	@Override public boolean consume(int slot, IPlatformFluidService.Visitor visitor, Operation operation) {
		Objects.requireNonNull(visitor); Objects.checkIndex(slot, count);
		liveRoute(); operation.allowance().debit(readProfile.ownedCost()); requireCurrent(operation);
		// No callback, including authority or provider metadata, is allowed between this getter and
		// synchronous detachment. FluidStack is final; its native getters supply the borrowed patch.
		@Nullable FluidStack stack = Objects.requireNonNull(handler).getFluidInTank(slot);
		boolean more = visitor.visit(borrow(stack));
		requireCurrent(operation);
		return more;
	}

	@Override public IPlatformFluidService.Visit observe(int limit, IPlatformFluidService.Visitor visitor, Operation operation) {
		if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
		Objects.requireNonNull(visitor);
		liveRoute(); operation.allowance().debit(validationProfile.ownedCost().times(2));
		requireCurrent(operation);
		var result = IPlatformFluidService.Access.super.observe(limit, visitor, operation);
		requireCurrent(operation); // even a zero-tank/zero-limit pass needs current metadata before completion
		return result;
	}
	@Override public void close() { route = null; handler = null; capacities = NO_CAPACITIES; }

	@NotNullByDefault
	private static final class CapabilityProbe implements IPlatformFluidService.Probe {
		private @Nullable Route route;
		private @Nullable IFluidHandler handler;
		private @Nullable Operation origin;
		private final IPlatformFluidService.Shape shape;
		private final OperationProfile finishProfile;
		private CapabilityProbe(Route route, IFluidHandler handler, int count, Operation origin) {
			this.route = route; this.handler = handler; shape = new IPlatformFluidService.Shape(1, OptionalInt.of(count), true);
			finishProfile = addressProfile(route, count); this.origin = origin;
		}
		@Override public IPlatformFluidService.Shape shape() { return shape; }
		@Override public OperationProfile finishProfile() { return finishProfile; }
		@Override public Optional<IPlatformFluidService.Access> finish(IPlatformFluidService.Admission admission) {
			if (route == null) throw new IllegalStateException("fluid probe consumed");
			var original = route; var capability = Objects.requireNonNull(handler); var source = Objects.requireNonNull(origin); close();
			try {
				var allowance = source.allowance();
				try (var operation = admission.enter(finishProfile)) {
					source.allowance(); // Admission is itself a callback; it must not expire the probe's scope.
					if (operation.allowance() != allowance) throw new IllegalStateException("fluid finish changed caller allowance");
					return Optional.of(FluidCapabilityAccess.finish(original, capability, shape.indexedSlots().orElseThrow(), operation));
				}
			} finally { close(); }
		}
		@Override public void close() { route = null; handler = null; origin = null; }
	}

	private static IPlatformFluidService.Entry borrow(@Nullable FluidStack stack) {
		if (stack == null) throw new InventorySourceUnavailableException("fluid capability returned null");
		// Constructors/setAmount retain negatives even though the public getter normalizes them to zero.
		InventoryQuantities.require(retainedAmount(stack));
		long amount = InventoryQuantities.fromMillibuckets(stack.getAmount());
		if (amount == 0) return IPlatformFluidService.Entry.empty();
		var fluid = Objects.requireNonNull(stack.getFluid(), "fluid capability identity");
		if (fluid == Fluids.EMPTY) throw new InventorySourceUnavailableException("positive fluid amount without identity");
		return new IPlatformFluidService.Entry(new IPlatformFluidService.Components(fluid, stack.getComponentsPatch()), amount);
	}

	private static int retainedAmount(FluidStack stack) {
		var metadata = RetainedAmount.METADATA;
		@Nullable Field field = metadata.field();
		if (field == null) throw new IllegalStateException("native fluid amount field unavailable", metadata.failure());
		try { return field.getInt(stack); }
		catch (IllegalAccessException | RuntimeException | LinkageError unavailable) {
			throw new IllegalStateException("native fluid amount read unavailable", unavailable);
		}
	}
	/** One fixed native declaration/failure per loader; no stack, handler, world or amount is cached. */
	@NotNullByDefault
	private record RetainedAmount(@Nullable Field field, @Nullable Throwable failure) {
		private static final RetainedAmount METADATA = load();
		private static RetainedAmount load() {
			try {
				Field field = FluidStack.class.getDeclaredField("amount");
				if (field.getType() != int.class || Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible())
					throw new IllegalStateException("unsupported native fluid amount declaration");
				return new RetainedAmount(field, null);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
				// Do not poison class initialization or retry metadata discovery at every address.
				return new RetainedAmount(null, unavailable);
			}
		}
	}
}
