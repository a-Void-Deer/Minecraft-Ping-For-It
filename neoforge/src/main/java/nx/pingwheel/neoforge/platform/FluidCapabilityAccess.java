package nx.pingwheel.neoforge.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
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
		void require(BlockPos position);
		Block block(BlockPos position);
		@Nullable IFluidHandler capability(BlockPos position, Direction face);
		String dimension();
		boolean createAvailable();
		/** Final held-object safety check without resolving a new mapping or reading contents. */
		default boolean held(Member member) { require(member.position()); return true; }
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
		boolean matches();
		/** Reject non-stock capability modes (for example a stale Create boiler cache). No contents. */
		default boolean accepts(IFluidHandler handler) { return true; }
		InventoryValidationBudget.Cost checkCost();
	}

	/* The interface admits the whole finite resolution before any callback. This covers the maximum
	 * member DTO/copy/hash-set bookkeeping, 4096 boxed indices, bounded identity text, fixed Create
	 * declaration discovery, and all initial/post-capability checks. No content is part of this cost. */
	public static final InventoryValidationBudget.Cost RESOLUTION_COST =
		new InventoryValidationBudget.Cost(3145728, 1048576, 1048576);
	private static final InventoryValidationBudget.Cost CAPABILITY_METADATA =
		new InventoryValidationBudget.Cost(512, 512, 64);
	private static final InventoryValidationBudget.Cost RESIDENT_HANDOFF =
		new InventoryValidationBudget.Cost(256, 256, 32);
	private static final InventoryValidationBudget.Cost ADDRESS_METADATA =
		new InventoryValidationBudget.Cost(64, 64, 16);

	private final Route route;
	private final IFluidHandler handler;
	private final int count;
	private final int @Unmodifiable [] capacities;
	private final InventorySnapshotLayout layout;
	private final InventoryValidationBudget.Cost validationCost;
	private final InventoryValidationBudget.Cost readCost;

	private FluidCapabilityAccess(Route route, IFluidHandler handler, int @Unmodifiable [] capacities) {
		this.route = route; this.handler = handler; this.count = capacities.length; this.capacities = capacities;
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
		// Two full route passes plus one held-only post-capability pass. Capacities use only the
		// capability member's current gate; no full topology scan is multiplied by the tank count.
		validationCost = route.checkCost().times(3).plus(CAPABILITY_METADATA).plus(ADDRESS_METADATA.times(2L * count + 2));
		readCost = validationCost.times(2).plus(RESIDENT_HANDOFF);
	}

	public static Optional<IPlatformFluidService.Access> find(Route route) {
		Objects.requireNonNull(route);
		requireRoute(route);
		@Nullable IFluidHandler handler = route.world().capability(route.capabilityPosition(), route.face());
		requireHeld(route);
		if (handler == null) return Optional.empty();
		if (!route.accepts(handler)) throw new InventorySourceUnavailableException("fluid capability mode unavailable");
		route.world().require(route.capabilityPosition());
		int count = handler.getTanks();
		if (count < 0 || count > InventorySourceAccess.MAX_SLOTS)
			throw new InventorySourceUnavailableException("fluid capability address bound");
		int[] capacities = new int[count];
		for (int index = 0; index < count; index++) {
			route.world().require(route.capabilityPosition());
			int capacity = handler.getTankCapacity(index);
			if (capacity < 0) throw new InventorySourceUnavailableException("negative fluid address capacity");
			capacities[index] = capacity;
		}
		requireRoute(route);
		requireCapacities(route, handler, capacities);
		requireCountAndHeld(route, handler, count);
		return Optional.of(new FluidCapabilityAccess(route, handler, capacities));
	}

	private static void requireRoute(Route route) {
		if (!route.matches()) throw new InventorySourceUnavailableException("fluid capability route changed");
		// The final metadata callback cannot authorize a now-denied sibling or controller.
		requireHeld(route);
	}
	private static void requireHeld(Route route) {
		for (Member member : route.members())
			if (!route.world().held(member)) throw new InventorySourceUnavailableException("fluid held member replaced");
	}
	private static void requireCapacities(Route route, IFluidHandler handler, int[] expected) {
		// Capacities are public address metadata, not contents or a claim of opaque provider internals.
		// Only the capability owner is accessed per address; do not rescan every structural member for
		// every tank. Full route/mode and all-member checks surround the bounded sequence.
		for (int index = 0; index < expected.length; index++) {
			route.world().require(route.capabilityPosition());
			if (handler.getTankCapacity(index) != expected[index])
				throw new InventorySourceUnavailableException("fluid capability address mapping changed");
		}
	}
	private static void requireCountAndHeld(Route route, IFluidHandler handler, int expected) {
		requireHeld(route);
		route.world().require(route.capabilityPosition());
		if (handler.getTanks() != expected) throw new InventorySourceUnavailableException("fluid capability layout changed");
		// Count follows the last state/entity/route callback. The closing pass rechecks current
		// authority only, without another route/state getter that could hide a later count change.
		for (Member member : route.members()) route.world().require(member.position());
	}

	@Override public InventorySnapshotLayout layout() { return layout; }
	@Override public Optional<String> alias() { return route.alias(); }
	@Override public boolean stableCursor() { return true; }
	@Override public int slots() { return count; }
	@Override public InventoryValidationBudget.Cost validationCost() { return validationCost; }
	@Override public InventoryValidationBudget.Cost readCost() { return readCost; }
	@Override public InventoryValidationBudget.Cost observationCost(int limit) {
		if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
		return validationCost.times(2).plus(readCost.times(limit));
	}

	private void requireCurrent() {
		requireRoute(route);
		@Nullable IFluidHandler current = route.world().capability(route.capabilityPosition(), route.face());
		requireHeld(route);
		// A matching total is not a route witness: retain the exact capability object and root/member
		// objects as well as the ordered indices. Replacement starts a fresh observation, not a remap.
		if (current == null || current != handler || !route.accepts(current)) throw new InventorySourceUnavailableException("fluid capability replaced");
		route.world().require(route.capabilityPosition());
		int currentCount = current.getTanks();
		if (currentCount != count) throw new InventorySourceUnavailableException("fluid capability layout changed");
		requireCapacities(route, current, capacities);
		requireRoute(route);
		// The post-capacity route callbacks may themselves change public addressing. Check that
		// ordered witness again, then check count AFTER the last capacity/route callback as well.
		requireCapacities(route, current, capacities);
		requireCountAndHeld(route, current, count);
	}

	@Override public boolean valid() {
		try { requireCurrent(); return true; }
		catch (InventorySourceUnavailableException unavailable) { return false; }
	}

	@Override public boolean consume(int slot, IPlatformFluidService.Visitor visitor) {
		Objects.requireNonNull(visitor); Objects.checkIndex(slot, count);
		requireCurrent();
		// No callback, including authority or provider metadata, is allowed between this getter and
		// synchronous detachment. FluidStack is final; its native getters supply the borrowed patch.
		@Nullable FluidStack stack = handler.getFluidInTank(slot);
		boolean more = visitor.visit(borrow(stack));
		requireCurrent();
		return more;
	}

	@Override public IPlatformFluidService.Visit observe(int limit, IPlatformFluidService.Visitor visitor) {
		if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
		Objects.requireNonNull(visitor);
		requireCurrent();
		var result = IPlatformFluidService.Access.super.observe(limit, visitor);
		requireCurrent(); // even a zero-tank/zero-limit pass needs current metadata before completion
		return result;
	}

	private static IPlatformFluidService.Entry borrow(@Nullable FluidStack stack) {
		if (stack == null) throw new InventorySourceUnavailableException("fluid capability returned null");
		long amount = InventoryQuantities.fromMillibuckets(stack.getAmount());
		if (amount == 0) return IPlatformFluidService.Entry.empty();
		var fluid = Objects.requireNonNull(stack.getFluid(), "fluid capability identity");
		if (fluid == Fluids.EMPTY) throw new InventorySourceUnavailableException("positive fluid amount without identity");
		return new IPlatformFluidService.Entry(new IPlatformFluidService.Components(fluid, stack.getComponentsPatch()), amount);
	}
}
