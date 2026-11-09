package nx.pingwheel.neoforge.integration.create.presentation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.OperationProfile;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.neoforge.platform.FluidCapabilityAccess;
import nx.pingwheel.neoforge.platform.FluidSourceDiagnostics;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Create-only static structure/mode gate. Contents still use the ordinary NeoForge fluid capability. */
@NotNullByDefault
public final class CreateFluidTankAccess {
	private static final String TANK = "com.simibubi.create.content.fluids.tank.FluidTankBlockEntity";
	private static final String CREATIVE = "com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity";
	private static final String TANK_BLOCK = "com.simibubi.create.content.fluids.tank.FluidTankBlock";
	private static final String SMART = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity";
	private static final String BOILER = "com.simibubi.create.content.fluids.tank.BoilerData";
	private static final String ID = "create:fluid_tank";
	private CreateFluidTankAccess() {}

	/** Indexed lookup only: repeated safety checks must not enumerate every installed mod. */
	public static boolean available() {
		try {
			@Nullable net.neoforged.fml.ModList mods = net.neoforged.fml.ModList.get();
			if (mods == null) return false;
			var container = mods.getModContainerById("create");
			return container.isPresent() && CreatePresentationAvailability.testedVersion(container.get().getModInfo().getVersion().toString());
		} catch (RuntimeException | LinkageError unavailable) { return false; }
	}

	/** A claimed Create tank never recovers to the unrestricted generic route, even when unavailable. */
	public static boolean recognizes(FluidCapabilityAccess.Block block) {
		if (ID.equals(block.blockId()) || "create:creative_fluid_tank".equals(block.blockId())
			|| ID.equals(block.entityId()) || "create:creative_fluid_tank".equals(block.entityId())) return true;
		@Nullable Class<?> type = block.entity() == null ? null : block.entity().getClass();
		for (int depth = 0; type != null && depth < 32; depth++, type = type.getSuperclass())
			if (TANK.equals(type.getName()) || CREATIVE.equals(type.getName())) return true;
		return false;
	}

	public static IPlatformFluidService.Probe probe(FluidCapabilityAccess.World world, BlockPos root, Direction face,
		FluidCapabilityAccess.Block block, Operation operation) {
		if (!world.createAvailable()) throw unavailable("unsupported Create fluid route",
			() -> blockContext(root, face, block));
		return probe(world, root, face, block, ApiHolder.API, operation);
	}

	/** Fixed metadata seam: no handler, native tank, controller-BE lookup or content method is exposed. */
	@NotNullByDefault
	interface Metadata {
		boolean ordinary(FluidCapabilityAccess.Block block);
		boolean virtual(Object entity);
		@Nullable BlockPos lastPosition(Object entity);
		boolean activeBoiler(Object entity);
		@Nullable BlockPos controller(Object entity);
		boolean isController(Object entity);
		int width(Object entity);
		int height(Object entity);
		Direction.@Nullable Axis axis(Object entity);
		default boolean storedHandler(Object controller, IFluidHandler handler) { return true; }
	}

	static IPlatformFluidService.Probe probe(FluidCapabilityAccess.World world, BlockPos root, Direction face,
		FluidCapabilityAccess.Block block, Metadata metadata, Operation operation) {
		Objects.requireNonNull(face); world.require(root, operation);
		if (!world.createAvailable()) throw unavailable("unsupported Create fluid route",
			() -> blockContext(root, face, block));
		var original = new FluidCapabilityAccess.Member(root, block);
		requireStatic(world, metadata, original, operation);
		@Nullable BlockPos found = controller(world, metadata, original, operation);
		if (found == null) throw unavailable("Create fluid controller missing",
			() -> blockContext(root, face, block));
		BlockPos controller = found.immutable();
		// The controller is a target-level access subject: scope/loaded/readable are checked before
		// its metadata or capability is touched, without treating every prism member this way.
		world.requireController(controller, operation);
		var master = new FluidCapabilityAccess.Member(controller, world.block(controller, operation));
		requireStatic(world, metadata, master, operation);
		world.require(controller, operation);
		boolean controllerRole = metadata.isController(entity(master));
		if (!controllerRole) throw unavailable("Create fluid controller relation",
			() -> "controller=" + controller.toShortString() + ";isController=false;"
				+ blockContext(root, face, block));
		@Nullable BlockPos observedController = controller(world, metadata, master, operation);
		if (!controller.equals(observedController)) throw unavailable("Create fluid controller relation",
			() -> "controller=" + controller.toShortString() + ";observedController=" + pos(observedController)
				+ ";isController=true;" + blockContext(root, face, block));
		requireStoredFluids(world, metadata, master, operation);
		world.require(controller, operation); int width = metadata.width(entity(master));
		world.require(controller, operation); int height = metadata.height(entity(master));
		world.require(controller, operation); Direction.@Nullable Axis axis = metadata.axis(entity(master));
		// Preflight the WHOLE member table before allocating positions or probing any prism member.
		// The common layout guard is finite even when Create's configurable max height is very large.
		if (width < 1 || width > 3 || height < 1 || axis != Direction.Axis.Y)
			throw unavailable("Create fluid bounded static topology",
				() -> "reason=invalid-shape;controller=" + controller.toShortString() + ";width=" + width
					+ ";height=" + height + ";axis=" + axis(axis)
					+ ";maxMembers=" + InventorySnapshotLayout.MAX_MEMBERS + ";" + blockContext(root, face, block));
		long size = (long) width * width * height;
		boolean containsHit = contains(controller, root, width, height);
		if (size > InventorySnapshotLayout.MAX_MEMBERS)
			throw unavailable("Create fluid member limit exceeded (member-limit-exceeded)",
				() -> "reason=member-limit-exceeded;controller=" + controller.toShortString()
					+ ";width=" + width + ";height=" + height + ";members=" + size
					+ ";maxMembers=" + InventorySnapshotLayout.MAX_MEMBERS + ";" + blockContext(root, face, block));
		if (!containsHit)
			throw unavailable("Create fluid hit outside structure (hit-outside-structure)",
				() -> "reason=hit-outside-structure;controller=" + controller.toShortString()
					+ ";width=" + width + ";height=" + height + ";members=" + size + ";"
					+ blockContext(root, face, block));
		return new TankProbe(new Discovery(world, face, original, master, metadata, width, height), operation);
	}

	private static OperationProfile memberProfile(FluidCapabilityAccess.World world) {
		// One existing-world acquisition, seven fresh metadata gates (world.block already owns
		// the removed pre-block entry fence), fixed zero-arg metadata and bounded IDs.
		return world.blockProfile().plus(world.requireProfile().times(7))
			.plus(FluidCapabilityAccess.profile(2048, 2048, 256, 0, 0));
	}
	@NotNullByDefault
	private record Discovery(FluidCapabilityAccess.World world, Direction face, FluidCapabilityAccess.Member root,
		FluidCapabilityAccess.Member master, Metadata metadata, int width, int height) {
		private int size() { return width * width * height; }
		private OperationProfile finishProfile() {
			// Root/controller recheck + M discovery + a fresh full route before the capability.
			// The member ownership includes newly copied IDs/positions/records; it is NOT charged again
			// as route ownership in the later N stage, which acquires only layout/address metadata.
			return memberProfile(world).times(2L * size() + 4).plus(world.heldProfile().times(2L * size()))
				.plus(world.requireProfile().times(8)).plus(world.capabilityProfile()).plus(world.dimensionProfile())
				.plus(FluidCapabilityAccess.profile(8192 + 256L * size(), 4096 + 128L * size(), 1024 + 16L * size(),
					0, 2048 + 2048L * size()));
		}
		private TankRoute route(Operation operation) {
			BlockPos rootPosition = root.position();
			String rootBlockId = root.block().blockId(), rootEntityId = root.block().entityId();
			BlockPos masterPosition = master.position();
			String masterBlockId = master.block().blockId(), masterEntityId = master.block().entityId();
			Direction frozenFace = face;
			int frozenWidth = width, frozenHeight = height;
			if (!world.createAvailable()) throw unavailable("unsupported Create fluid route",
				() -> "position=" + rootPosition.toShortString() + ";face=" + frozenFace.getName()
					+ ";width=" + frozenWidth + ";height=" + frozenHeight);
			// Compare frozen discovery before visiting ANY newly computed prism/member/controller.
			// world.block applies its entry gate; no callback lies between that predicate and it.
			var currentRoot = world.block(rootPosition, operation);
			if (!root.block().same(currentRoot)) throw unavailable("Create fluid probe root changed",
				() -> "position=" + rootPosition.toShortString() + ";face=" + frozenFace.getName()
					+ ";blockId=" + rootBlockId + ";entityId=" + rootEntityId
					+ ";observedBlock=" + currentRoot.blockId() + ";observedEntity=" + currentRoot.entityId());
			requireStatic(world, metadata, root, operation);
			@Nullable BlockPos rootController = controller(world, metadata, root, operation);
			if (!masterPosition.equals(rootController)) throw unavailable("Create fluid probe controller changed",
				() -> "position=" + rootPosition.toShortString() + ";face=" + frozenFace.getName()
					+ ";blockId=" + rootBlockId + ";entityId=" + rootEntityId
					+ ";expectedController=" + masterPosition.toShortString()
					+ ";observedController=" + pos(rootController));
			// The preceding controller callback ended before this callback-free block entry gate.
			world.requireController(masterPosition, operation);
			var currentMaster = world.block(masterPosition, operation);
			if (!master.block().same(currentMaster)) throw unavailable("Create fluid probe controller replaced",
				() -> "position=" + masterPosition.toShortString() + ";face=" + frozenFace.getName()
					+ ";blockId=" + masterBlockId + ";entityId=" + masterEntityId
					+ ";observedBlock=" + currentMaster.blockId() + ";observedEntity=" + currentMaster.entityId());
			requireStatic(world, metadata, master, operation);
			world.require(masterPosition, operation);
			boolean controllerRole = metadata.isController(entity(master));
			if (!controllerRole) throw unavailable("Create fluid probe controller role changed",
				() -> "position=" + masterPosition.toShortString() + ";blockId=" + masterBlockId
					+ ";entityId=" + masterEntityId + ";isController=false;expectedController="
					+ masterPosition.toShortString());
			@Nullable BlockPos observedMasterController = controller(world, metadata, master, operation);
			if (!masterPosition.equals(observedMasterController))
				throw unavailable("Create fluid probe controller role changed",
					() -> "position=" + masterPosition.toShortString() + ";blockId=" + masterBlockId
						+ ";entityId=" + masterEntityId + ";isController=true;expectedController="
						+ masterPosition.toShortString() + ";observedController=" + pos(observedMasterController));
			requireStoredFluids(world, metadata, master, operation);
			world.require(masterPosition, operation);
			int observedWidth = metadata.width(entity(master));
			if (observedWidth != frozenWidth) throw unavailable("Create fluid probe width changed",
				() -> "position=" + masterPosition.toShortString() + ";blockId=" + masterBlockId
					+ ";entityId=" + masterEntityId + ";expectedWidth=" + frozenWidth + ";observedWidth=" + observedWidth);
			world.require(masterPosition, operation);
			int observedHeight = metadata.height(entity(master));
			if (observedHeight != frozenHeight) throw unavailable("Create fluid probe height changed",
				() -> "position=" + masterPosition.toShortString() + ";blockId=" + masterBlockId
					+ ";entityId=" + masterEntityId + ";expectedHeight=" + frozenHeight + ";observedHeight=" + observedHeight);
			world.require(masterPosition, operation);
			Direction.@Nullable Axis observedAxis = metadata.axis(entity(master));
			if (observedAxis != Direction.Axis.Y) throw unavailable("Create fluid probe axis changed",
				() -> "position=" + masterPosition.toShortString() + ";blockId=" + masterBlockId
					+ ";entityId=" + masterEntityId + ";expectedAxis=Y;observedAxis=" + axis(observedAxis));
			List<FluidCapabilityAccess.Member> members = new ArrayList<>(size());
			int index = 0;
			for (int x = 0; x < frozenWidth; x++) for (int y = 0; y < frozenHeight; y++) for (int z = 0; z < frozenWidth; z++) {
				BlockPos position = masterPosition.offset(x, y, z);
				int memberIndex = index++;
				// world.block applies the entry gate; this is the callback-free predecessor interval.
				var member = new FluidCapabilityAccess.Member(position, world.block(position, operation));
				requireStatic(world, metadata, member, operation);
				@Nullable BlockPos memberController = controller(world, metadata, member, operation);
				if (!masterPosition.equals(memberController)) throw unavailable("Create fluid member controller",
					() -> memberContext(position, memberIndex, frozenWidth, frozenHeight)
						+ ";expectedController=" + masterPosition.toShortString()
						+ ";observedController=" + pos(memberController));
				world.require(position, operation);
				boolean memberRole = metadata.isController(entity(member));
				boolean expectedRole = position.equals(masterPosition);
				if (memberRole != expectedRole) throw unavailable("Create fluid member role",
					() -> memberContext(position, memberIndex, frozenWidth, frozenHeight)
						+ ";isController=" + memberRole + ";expectedController=" + expectedRole);
				world.require(position, operation);
				Direction.@Nullable Axis memberAxis = metadata.axis(entity(member));
				if (memberAxis != Direction.Axis.Y) throw unavailable("Create fluid member axis",
					() -> memberContext(position, memberIndex, frozenWidth, frozenHeight)
						+ ";axis=" + axis(memberAxis) + ";expectedAxis=Y");
				members.add(member);
			}
			String alias = world.dimension(operation) + "|fluid-tank|" + masterPosition.toShortString() + "|" + ID;
			return new TankRoute(world, frozenFace, masterPosition, List.copyOf(members), root, master, metadata,
				frozenWidth, frozenHeight, alias);
		}
	}
	@NotNullByDefault
	private static final class TankProbe implements IPlatformFluidService.Probe {
		private @Nullable Discovery discovery;
		private @Nullable Operation origin;
		private final IPlatformFluidService.Shape shape;
		private final OperationProfile finishProfile;
		private TankProbe(Discovery discovery, Operation origin) {
			this.discovery = discovery; shape = new IPlatformFluidService.Shape(discovery.size(), OptionalInt.empty(), true);
			finishProfile = discovery.finishProfile(); this.origin = origin;
		}
		@Override public IPlatformFluidService.Shape shape() { return shape; }
		@Override public OperationProfile finishProfile() { return finishProfile; }
		@Override public Optional<IPlatformFluidService.Access> finish(IPlatformFluidService.Admission admission) {
			if (discovery == null) throw new IllegalStateException("Create fluid probe consumed");
			var original = discovery; var source = Objects.requireNonNull(origin); close();
			try {
				var allowance = source.allowance();
				try (var operation = admission.enter(finishProfile)) {
					source.allowance();
					if (operation.allowance() != allowance) throw new IllegalStateException("Create fluid finish changed caller allowance");
					operation.allowance().debit(finishProfile.ownedCost());
					var route = original.route(operation);
					// Member cache initialization can call unguarded getControllerBE. Use only the fully
					// verified controller's public capability, still on the original non-null face.
					@Nullable IFluidHandler handler = FluidCapabilityAccess.acquire(route, operation);
					if (handler == null) { FluidSourceDiagnostics.createFinishAbsent(route); return Optional.empty(); }
					int count = FluidCapabilityAccess.tankCount(route, handler, operation);
					source.allowance();
					try (var addresses = admission.enter(FluidCapabilityAccess.addressProfile(route, count))) {
						source.allowance();
						if (addresses.allowance() != allowance) throw new IllegalStateException("Create fluid address stage changed caller allowance");
						return Optional.of(FluidCapabilityAccess.finish(route, handler, count, addresses));
					}
				}
			} finally { close(); }
		}
		@Override public void close() { discovery = null; origin = null; }
	}

	private static boolean contains(BlockPos controller, BlockPos root, int width, int height) {
		long x = (long) root.getX() - controller.getX(), y = (long) root.getY() - controller.getY(), z = (long) root.getZ() - controller.getZ();
		return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < width;
	}
	private static Object entity(FluidCapabilityAccess.Member member) {
		return Objects.requireNonNull(member.block().entity(), "Create fluid entity");
	}
	private static @Nullable BlockPos controller(FluidCapabilityAccess.World world, Metadata metadata, FluidCapabilityAccess.Member member, Operation operation) {
		world.require(member.position(), operation); return metadata.controller(entity(member));
	}
	private static void requireStatic(FluidCapabilityAccess.World world, Metadata metadata, FluidCapabilityAccess.Member member, Operation operation) {
		BlockPos position = member.position();
		FluidCapabilityAccess.Block block = member.block();
		String blockId = block.blockId(), entityId = block.entityId();
		world.require(position, operation);
		boolean blockMatch = ID.equals(blockId), entityMatch = ID.equals(entityId);
		boolean ordinary = blockMatch && entityMatch && metadata.ordinary(block);
		if (!blockMatch || !entityMatch || !ordinary)
			throw unavailable("Create creative/foreign fluid tank",
				() -> "position=" + position.toShortString() + ";blockId=" + blockId + ";entityId=" + entityId
					+ ";expected=" + ID + ";ordinary=" + ordinary);
		world.require(position, operation);
		boolean virtual = metadata.virtual(entity(member));
		if (virtual) throw unavailable("Create virtual fluid tank",
			() -> "position=" + position.toShortString() + ";blockId=" + blockId + ";entityId=" + entityId + ";virtual=true");
		world.require(position, operation);
		@Nullable BlockPos last = metadata.lastPosition(entity(member));
		if (last != null && !last.equals(position)) throw unavailable("Create moved fluid tank",
			() -> "position=" + position.toShortString() + ";blockId=" + blockId + ";entityId=" + entityId
				+ ";lastPosition=" + last.toShortString());
	}
	private static void requireStoredFluids(FluidCapabilityAccess.World world, Metadata metadata, FluidCapabilityAccess.Member master, Operation operation) {
		// Only the verified current controller selects the capability mode. Former controllers can
		// keep stale active BoilerData after becoming members; Create does not clear it on that path.
		BlockPos position = master.position();
		String blockId = master.block().blockId(), entityId = master.block().entityId();
		world.require(position, operation);
		boolean active = metadata.activeBoiler(entity(master));
		if (active) throw unavailable("Create active boiler is not stored fluid",
			() -> "position=" + position.toShortString() + ";blockId=" + blockId
				+ ";entityId=" + entityId + ";active=true");
	}
	private static InventorySourceUnavailableException unavailable(String message) { return new InventorySourceUnavailableException(message); }
	private static InventorySourceUnavailableException unavailable(String message, Supplier<String> context) {
		return FluidSourceDiagnostics.unavailable(message, context);
	}
	private static InventorySourceUnavailableException unavailable(String message, Throwable cause) {
		var failure = unavailable(message); failure.initCause(cause); return failure;
	}
	private static String blockContext(BlockPos position, Direction face, FluidCapabilityAccess.Block block) {
		return "position=" + position.toShortString() + ";face=" + face.getName()
			+ ";blockId=" + block.blockId() + ";entityId=" + block.entityId();
	}
	private static String memberContext(BlockPos position, int index, int width, int height) {
		return "member=" + position.toShortString() + ";index=" + index + ";width=" + width
			+ ";height=" + height + ";members=" + ((long) width * width * height);
	}
	private static String pos(@Nullable BlockPos position) { return position == null ? "none" : position.toShortString(); }
	private static String axis(@Nullable Direction.Axis axis) { return axis == null ? "none" : axis.getName(); }

	@NotNullByDefault
	private record TankRoute(FluidCapabilityAccess.World world, Direction face, BlockPos capabilityPosition,
		@Unmodifiable List<FluidCapabilityAccess.Member> members, FluidCapabilityAccess.Member root,
		FluidCapabilityAccess.Member master, Metadata metadata, int width, int height,
		String physicalAlias) implements FluidCapabilityAccess.Route {
		@Override public Optional<String> alias() { return Optional.of(physicalAlias); }
		@Override public boolean accepts(IFluidHandler handler, Operation operation) {
			world.require(capabilityPosition, operation);
			return metadata.storedHandler(entity(master), handler);
		}
		@Override public String description() { return "create:static-fluid;axis=Y;width=" + width + ";height=" + height + ";face=" + face.getName(); }
		@Override public OperationProfile acceptanceProfile() {
			return world.requireProfile().plus(FluidCapabilityAccess.profile(256, 256, 16, 0, 0));
		}
		@Override public IPlatformFluidService.TopologyEvidence topology() { return IPlatformFluidService.TopologyEvidence.EXPLICIT_STRUCTURE; }
		@Override public OperationProfile checkProfile() {
			return memberProfile(world).times(members.size()).plus(world.requireProfile().times(2))
				.plus(FluidCapabilityAccess.profile(2048, 2048, 128, 0, 0));
		}
		/**
		 * Current target applicability only. The verified structure's internal member layout and
		 * handler relationship stay provider-owned; only the original hit root, the current
		 * controller and its mode/identity are rechecked on every current validation.
		 */
		@Override public boolean targetMatches(Operation operation) {
			if (!world.createAvailable())
				throw unavailable("fluid capability route changed", () -> "reason=create-unavailable;" + description());
			var currentRoot = world.block(root.position(), operation);
			if (!root.block().same(currentRoot))
				throw unavailable("fluid capability route changed",
					() -> "reason=root-replaced;member=" + root.position().toShortString());
			requireStatic(world, metadata, root, operation);
			@Nullable BlockPos observedController = controller(world, metadata, root, operation);
			if (!capabilityPosition.equals(observedController))
				throw unavailable("fluid capability route changed",
					() -> "reason=controller-changed;member=" + root.position().toShortString()
						+ ";expectedController=" + capabilityPosition.toShortString()
						+ ";observedController=" + pos(observedController));
			world.requireController(capabilityPosition, operation);
			var currentMaster = world.block(capabilityPosition, operation);
			if (!master.block().same(currentMaster))
				throw unavailable("fluid capability route changed",
					() -> "reason=controller-replaced;member=" + capabilityPosition.toShortString());
			requireStatic(world, metadata, master, operation);
			world.require(capabilityPosition, operation);
			if (!metadata.isController(entity(master)))
				throw unavailable("fluid capability route changed",
					() -> "reason=controller-role;member=" + capabilityPosition.toShortString());
			@Nullable BlockPos observedMaster = controller(world, metadata, master, operation);
			if (!capabilityPosition.equals(observedMaster))
				throw unavailable("fluid capability route changed",
					() -> "reason=controller-changed;member=" + capabilityPosition.toShortString()
						+ ";observedController=" + pos(observedMaster));
			requireStoredFluids(world, metadata, master, operation);
			world.require(capabilityPosition, operation);
			if (metadata.width(entity(master)) != width)
				throw unavailable("fluid capability route changed",
					() -> "reason=width-changed;member=" + capabilityPosition.toShortString());
			world.require(capabilityPosition, operation);
			if (metadata.height(entity(master)) != height)
				throw unavailable("fluid capability route changed",
					() -> "reason=height-changed;member=" + capabilityPosition.toShortString());
			world.require(capabilityPosition, operation);
			if (metadata.axis(entity(master)) != Direction.Axis.Y)
				throw unavailable("fluid capability route changed",
					() -> "reason=axis-changed;member=" + capabilityPosition.toShortString());
			return true;
		}
		@Override public OperationProfile targetCheckProfile() {
			// Root/master metadata plus the explicit controller gate before master access.
			return memberProfile(world).times(2).plus(world.requireProfile().times(3));
		}
		/** No World.block calls: only the cached root/master identities, mode metadata and pure access gates. */
		@Override public boolean authorityMatches(Operation operation) {
			world.require(root.position(), operation);
			world.requireController(capabilityPosition, operation);
			if (!metadata.isController(entity(master))) return false;
			@Nullable BlockPos observed = controller(world, metadata, master, operation);
			if (!capabilityPosition.equals(observed)) return false;
			if (metadata.activeBoiler(entity(master))) return false;
			if (metadata.width(entity(master)) != width) return false;
			if (metadata.height(entity(master)) != height) return false;
			return metadata.axis(entity(master)) == Direction.Axis.Y;
		}
		@Override public OperationProfile authorityProfile() {
			return world.requireProfile().times(3).plus(FluidCapabilityAccess.profile(1024, 1024, 128, 0, 0));
		}
		@Override public boolean matches(Operation operation) {
			BlockPos controllerPosition = capabilityPosition;
			int frozenWidth = width, frozenHeight = height;
			if (!world.createAvailable()) throw unavailable("fluid capability route changed",
				() -> "reason=create-unavailable;" + description());
			int index = 0;
			for (FluidCapabilityAccess.Member member : members) {
				BlockPos position = member.position();
				int memberIndex = index++;
				String expectedBlockId = member.block().blockId(), expectedEntityId = member.block().entityId();
				// world.block applies its entry gate; no callback lies between the frozen identity read and it.
				var current = world.block(position, operation);
				if (!member.block().same(current)) {
					String observedBlockId = current.blockId(), observedEntityId = current.entityId();
					throw unavailable("fluid capability route changed",
						() -> "reason=member-replaced;member=" + position.toShortString() + ";index=" + memberIndex
							+ ";expectedBlock=" + expectedBlockId + ";observedBlock=" + observedBlockId
							+ ";expectedEntity=" + expectedEntityId + ";observedEntity=" + observedEntityId);
				}
				requireStatic(world, metadata, member, operation);
				@Nullable BlockPos memberController = controller(world, metadata, member, operation);
				if (!controllerPosition.equals(memberController)) throw unavailable("fluid capability route changed",
					() -> "reason=controller-changed;member=" + position.toShortString() + ";index=" + memberIndex
						+ ";expectedController=" + controllerPosition.toShortString() + ";observedController=" + pos(memberController));
				world.require(position, operation);
				boolean memberRole = metadata.isController(entity(member)), expectedRole = position.equals(controllerPosition);
				if (memberRole != expectedRole) throw unavailable("fluid capability route changed",
					() -> "reason=role-changed;member=" + position.toShortString() + ";index=" + memberIndex
						+ ";isController=" + memberRole + ";expectedController=" + expectedRole);
				world.require(position, operation);
				Direction.@Nullable Axis memberAxis = metadata.axis(entity(member));
				if (memberAxis != Direction.Axis.Y) throw unavailable("fluid capability route changed",
					() -> "reason=axis-changed;member=" + position.toShortString() + ";index=" + memberIndex
						+ ";axis=" + axis(memberAxis) + ";expectedAxis=Y");
				if (position.equals(controllerPosition)) {
					world.require(position, operation); int observedWidth = metadata.width(entity(member));
					if (observedWidth != frozenWidth) throw unavailable("fluid capability route changed",
						() -> "reason=width-changed;member=" + position.toShortString() + ";index=" + memberIndex
							+ ";expectedWidth=" + frozenWidth + ";observedWidth=" + observedWidth);
					world.require(position, operation); int observedHeight = metadata.height(entity(member));
					if (observedHeight != frozenHeight) throw unavailable("fluid capability route changed",
						() -> "reason=height-changed;member=" + position.toShortString() + ";index=" + memberIndex
							+ ";expectedHeight=" + frozenHeight + ";observedHeight=" + observedHeight);
					requireStoredFluids(world, metadata, member, operation);
				}
			}
			return true;
		}
	}

	/** Only reached after mod/version admission; uses this defining loader, never the context loader. */
	@NotNullByDefault
	private static final class ApiHolder {
		private static final Metadata API = load();
		private static Metadata load() {
			try { return new Api(CreateFluidTankAccess.class.getClassLoader()); }
			catch (ReflectiveOperationException | LinkageError failure) { throw unavailable("Create fluid metadata unavailable", failure); }
		}
	}

	@NotNullByDefault
	private static final class Api implements Metadata {
		private final Class<?> tank, tankBlock, boilerClass, storedTank;
		private final Method virtual, last, active, controller, isController, width, height, axis, tankIdentity;
		private final Field boiler;
		private Api(ClassLoader loader) throws ReflectiveOperationException {
			tank = Class.forName(TANK, false, loader); tankBlock = Class.forName(TANK_BLOCK, false, loader);
			Class<?> smart = Class.forName(SMART, false, loader); boilerClass = Class.forName(BOILER, false, loader);
			storedTank = Class.forName("com.simibubi.create.foundation.fluid.SmartFluidTank", false, loader);
			if (!BlockEntity.class.isAssignableFrom(tank) || !smart.isAssignableFrom(tank)
				|| !Modifier.isPublic(tank.getModifiers()) || !Modifier.isPublic(tankBlock.getModifiers())
				|| !Modifier.isPublic(smart.getModifiers()) || !Modifier.isPublic(boilerClass.getModifiers())
				|| !IFluidHandler.class.isAssignableFrom(storedTank) || !Modifier.isPublic(storedTank.getModifiers()))
				throw new NoSuchMethodException("Create fluid hierarchy");
			virtual = method(smart, "isVirtual", boolean.class); last = method(tank, "getLastKnownPos", BlockPos.class);
			active = method(boilerClass, "isActive", boolean.class); controller = method(tank, "getController", BlockPos.class);
			isController = method(tank, "isController", boolean.class); width = method(tank, "getWidth", int.class);
			height = method(tank, "getHeight", int.class); axis = method(tank, "getMainConnectionAxis", Direction.Axis.class);
			tankIdentity = method(tank, "getTankInventory", net.neoforged.neoforge.fluids.capability.templates.FluidTank.class);
			boiler = tank.getDeclaredField("boiler");
			if (boiler.getDeclaringClass() != tank || boiler.getType() != boilerClass || boiler.getModifiers() != Modifier.PUBLIC)
				throw new NoSuchFieldException("Create fluid boiler metadata shape");
		}
		private static Method method(Class<?> owner, String name, Class<?> returns) throws ReflectiveOperationException {
			Method method = owner.getDeclaredMethod(name);
			if (method.getDeclaringClass() != owner || method.getReturnType() != returns || method.getModifiers() != Modifier.PUBLIC)
				throw new NoSuchMethodException("Create fluid metadata shape: " + name);
			return method;
		}
		private static @Nullable Object invoke(Method method, Object owner) {
			try { return method.invoke(owner); }
			catch (InvocationTargetException failure) {
				@Nullable Throwable cause = failure.getCause();
				if (cause instanceof RuntimeException runtime) throw runtime;
				if (cause instanceof Error error) throw error;
				throw unavailable("Create fluid metadata callback failed", failure);
			} catch (ReflectiveOperationException failure) { throw unavailable("Create fluid metadata inaccessible", failure); }
		}
		@Override public boolean ordinary(FluidCapabilityAccess.Block block) {
			return block.entity() != null && block.entity().getClass() == tank && block.block().getClass() == tankBlock;
		}
		@Override public boolean virtual(Object entity) { return (Boolean) Objects.requireNonNull(invoke(virtual, entity)); }
		@Override public @Nullable BlockPos lastPosition(Object entity) { return (BlockPos) invoke(last, entity); }
		@Override public boolean activeBoiler(Object entity) {
			try {
				@Nullable Object value = boiler.get(entity);
				if (value == null || value.getClass() != boilerClass) throw unavailable("Create fluid boiler ownership changed");
				return (Boolean) Objects.requireNonNull(invoke(active, value));
			} catch (IllegalAccessException failure) { throw unavailable("Create fluid boiler inaccessible", failure); }
		}
		@Override public @Nullable BlockPos controller(Object entity) { return (BlockPos) invoke(controller, entity); }
		@Override public boolean isController(Object entity) { return (Boolean) Objects.requireNonNull(invoke(isController, entity)); }
		@Override public int width(Object entity) { return (Integer) Objects.requireNonNull(invoke(width, entity)); }
		@Override public int height(Object entity) { return (Integer) Objects.requireNonNull(invoke(height, entity)); }
		@Override public Direction.@Nullable Axis axis(Object entity) { return (Direction.Axis) invoke(axis, entity); }
		@Override public boolean storedHandler(Object controller, IFluidHandler handler) {
			// An inactive mode can temporarily retain the old boiler capability until Create refreshes.
			// A newly promoted controller can likewise retain its old controller's SmartFluidTank. The
			// public getter is an identity-only route witness: its contents are NEVER queried. Fluid still
			// comes exclusively through IFluidHandler, not an internal native tank read or refresh call.
			return handler.getClass() == storedTank && invoke(tankIdentity, controller) == handler;
		}
	}
}
