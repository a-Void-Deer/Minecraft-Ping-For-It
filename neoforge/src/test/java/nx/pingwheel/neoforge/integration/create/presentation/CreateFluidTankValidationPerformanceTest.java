package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.OperationProfile;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.neoforge.platform.FluidCapabilityAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static nx.pingwheel.neoforge.platform.PlatformFluidServiceTest.WORK;
import static nx.pingwheel.neoforge.platform.PlatformFluidServiceTest.assertReleasedAccess;
import static nx.pingwheel.neoforge.platform.PlatformFluidServiceTest.assertReleasedProbe;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reusable headless BEFORE/AFTER cost baseline for one Create fluid-tank Preview source, not a
 * latency-threshold test. The real {@link CreateFluidTankAccess} algorithm and the real
 * {@link FluidCapabilityAccess} probe/finish/valid/consume/observe sequence execute against detached
 * metadata ports, a production-shaped nested gate world, and the real default work/memory ledgers.
 * The fixture world, block entities and handler are deterministic headless stand-ins: this is not a
 * live Create install, a {@code ServerLevel}, or the common guarded source wrapper.
 */
@NotNullByDefault
final class CreateFluidTankValidationPerformanceTest {
	private static final Direction FACE = Direction.NORTH;
	private static final String TANK = "create:fluid_tank";
	// Production InventoryFluidSources ordinary gates: MEMBER + INDEX_LOOKUP (membership adds none)
	// and MEMBER + BIND for current authority. These are fixture preconditions for cost admission,
	// not expected call counts.
	private static final InventoryValidationBudget.Cost MEMBER_GATE =
		new InventoryValidationBudget.Cost(1024, 1024, 528);
	private static final InventoryValidationBudget.Cost AUTHORITY =
		new InventoryValidationBudget.Cost(9216, 9216, 2560);
	private static final @Unmodifiable List<Size> SIZES = List.of(new Size(3, 32), new Size(2, 256));

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void representativeCreateTankDiscoveryValidationConsumeAndObserveCosts() throws Exception {
		for (Size size : SIZES) measure(size.width(), size.height());
	}

	private static void measure(int width, int height) throws Exception {
		int members = Math.multiplyExact(width, Math.multiplyExact(width, height));
		BlockPos hit = new BlockPos(width - 1, height - 1, width - 1);
		var settings = InventorySettings.serverDefaults();
		// Real engineering ITEM_WORK upper bound and default pending-memory formula, never an
		// unlimited test quota. A changed production default therefore moves this fixture with it.
		long workLimit = Math.multiplyExact((long) InventorySourceAccess.MAX_SLOTS,
			InventoryItemBudget.workUpperBound((int) settings.effectiveDisplayBytes()));
		var ledger = new CostLedger(Map.of(WORK, workLimit));
		var memory = new RetainedMemoryLedger(settings.pendingMemoryBytes());
		var world = new World(width, height, hit);
		System.out.printf(Locale.ROOT,
			"NEOFORGE_FLUID_BASELINE meta=fixture(headless-detached-metadata;not-live-create;not-guarded-source)"
				+ " m=%d n=1 width=%d height=%d face=%s workLimit=%d pendingMemory=%d memberGate=%s authority=%s%n",
			members, width, height, FACE.getName(), workLimit, settings.pendingMemoryBytes(), MEMBER_GATE, AUTHORITY);

		InventoryFluidAdmission.Ownership ownership;
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER_GATE, AUTHORITY)) {
			@Nullable IPlatformFluidService.Access access = null;
			try {
				world.reset();
				long before = 0, started = System.nanoTime();
				OperationProfile finishProfile;
				var admitted = new java.util.ArrayList<OperationProfile>();
				IPlatformFluidService.Admission recorder = profile -> { admitted.add(profile); return admission.enter(profile); };
				try (var op = admission.enter(FluidCapabilityAccess.PROBE_PROFILE)) {
					// Mirror PlatformFluidServiceImpl.probe: admit the fixed probe profile, gate the
					// root, read its block, gate again, then run the actual Create discovery.
					op.allowance().debit(FluidCapabilityAccess.PROBE_PROFILE.ownedCost());
					world.require(hit, op);
					var block = world.block(hit, op);
					world.require(hit, op);
					assertTrue(CreateFluidTankAccess.recognizes(block));
					assertTrue(world.createAvailable());
					try (var probe = CreateFluidTankAccess.probe(world, hit, FACE, block, world, op)) {
						assertEquals(members, probe.shape().members());
						finishProfile = probe.finishProfile();
						access = probe.finish(recorder).orElseThrow();
						assertReleasedProbe(probe);
					}
					before = op.allowance().attemptedWorkUnits();
				}
				long discoveryNanos = System.nanoTime() - started;
				var finished = Objects.requireNonNull(access);
				assertEquals(members, finished.layout().members().size());
				assertEquals(1, finished.slots());
				assertEquals("minecraft:overworld|fluid-tank|0, 0, 0|create:fluid_tank",
					finished.alias().orElseThrow());
				assertEquals(IPlatformFluidService.TopologyEvidence.EXPLICIT_STRUCTURE,
					finished.routeInfo().topology());
				var discovery = Snapshot.of(world);
				assertEquals(0, discovery.contentGet(), "discovery is metadata only");
				assertTrue(discovery.memberGate() > 0 && discovery.authority() > 0 && discovery.held() > 0);
				// The public probe profile plus the actual finish Admission profiles are the
				// independently admitted upper bounds for this discovery tree.
				long gateBound = Math.addExact(FluidCapabilityAccess.PROBE_PROFILE.memberGateCalls(),
					admitted.stream().mapToLong(OperationProfile::memberGateCalls).sum());
				assertTrue(discovery.memberGate() <= gateBound, () -> "discovery member gates " + discovery);
				assertTrue(discovery.authority() <= gateBound, () -> "discovery authority gates " + discovery);
				print(members, "discovery", discovery, before, finishProfile, discoveryNanos);

				world.reset();
				long validationWork;
				started = System.nanoTime();
				try (var op = admission.enter(finished.validationProfile())) {
					assertTrue(finished.valid(op));
					validationWork = op.allowance().attemptedWorkUnits() - before;
					before = op.allowance().attemptedWorkUnits();
				}
				long validationNanos = System.nanoTime() - started;
				var validation = Snapshot.of(world);
				assertEquals(0, validation.contentGet(), "valid must not read contents");
				assertTrue(validation.memberGate() > 0 && validation.authority() > 0);
				assertTrue(validation.capability() > 0 && validation.getTanks() > 0);
				assertTrue(validation.getTankCapacity() > 0);
				assertTrue(validation.memberGate() <= finished.validationProfile().memberGateCalls(),
					() -> "validation member gates " + validation);
				assertTrue(validation.authority() <= finished.validationProfile().currentAuthorityCalls(),
					() -> "validation authority gates " + validation);
				print(members, "valid", validation, validationWork, finished.validationProfile(), validationNanos);

				world.reset();
				long readWork;
				started = System.nanoTime();
				var visits = new AtomicInteger();
				try (var op = admission.enter(finished.readProfile())) {
					assertTrue(finished.consume(0, entry -> {
						assertNotNull(entry.resident(), "positive fluid must carry an identity");
						assertEquals(81_000, entry.amount());
						visits.incrementAndGet();
						return true;
					}, op));
					readWork = op.allowance().attemptedWorkUnits() - before;
					before = op.allowance().attemptedWorkUnits();
				}
				long readNanos = System.nanoTime() - started;
				assertEquals(1, visits.get());
				var read = Snapshot.of(world);
				assertEquals(1, read.contentGet(), "one address is exactly one content getter");
				assertTrue(read.memberGate() > 0 && read.authority() > 0 && read.capability() > 0);
				assertTrue(read.getTanks() > 0 && read.getTankCapacity() > 0);
				assertTrue(read.memberGate() <= finished.readProfile().memberGateCalls(),
					() -> "read member gates " + read);
				assertTrue(read.authority() <= finished.readProfile().currentAuthorityCalls(),
					() -> "read authority gates " + read);
				print(members, "consume", read, readWork, finished.readProfile(), readNanos);

				world.reset();
				long observeWork;
				started = System.nanoTime();
				visits.set(0);
				try (var op = admission.enter(finished.observationProfile(1))) {
					var visit = finished.observe(1, entry -> {
						assertNotNull(entry.resident(), "positive fluid must carry an identity");
						assertEquals(81_000, entry.amount());
						visits.incrementAndGet();
						return true;
					}, op);
					assertEquals(new IPlatformFluidService.Visit(1, true), visit);
					observeWork = op.allowance().attemptedWorkUnits() - before;
				}
				long observeNanos = System.nanoTime() - started;
				assertEquals(1, visits.get());
				var observation = Snapshot.of(world);
				assertEquals(1, observation.contentGet(), "one observed address is exactly one getter");
				assertTrue(observation.memberGate() > 0 && observation.authority() > 0 && observation.capability() > 0);
				assertTrue(observation.getTanks() > 0 && observation.getTankCapacity() > 0);
				assertTrue(observation.memberGate() <= finished.observationProfile(1).memberGateCalls(),
					() -> "observation member gates " + observation);
				assertTrue(observation.authority() <= finished.observationProfile(1).currentAuthorityCalls(),
					() -> "observation authority gates " + observation);
				print(members, "observe", observation, observeWork, finished.observationProfile(1), observeNanos);

				// Transfer exactly like the ordinary source, then assert both the access and the
				// retained metadata can be released with no leak.
				ownership = admission.retain();
				assertTrue(ownership.bytes() > 0);
				assertEquals(ownership.bytes(), memory.retained());
				finished.close();
				access = null;
				assertReleasedAccess(finished);
			} finally {
				if (access != null) access.close();
			}
		}
		long used = ledger.used(WORK);
		assertTrue(used > 0, "the admitted work ledger must record attempted work");
		assertTrue(used <= workLimit);
		assertEquals(ownership.bytes(), memory.retained());
		assertEquals(0, memory.reserved(), "scratch is released when admission closes");
		ownership.close();
		assertEquals(0, memory.retained(), "retained metadata ownership is released");
		assertEquals(0, memory.reserved());
		System.out.printf(Locale.ROOT,
			"NEOFORGE_FLUID_BASELINE m=%d n=1 phase=cleanup ownershipBytes=%d retained=%d reserved=%d"
				+ " ledgerUsed=%d workLimit=%d%n",
			members, ownership.bytes(), memory.retained(), memory.reserved(), used, workLimit);
	}

	private static void print(int members, String phase, Snapshot snapshot, long work,
		OperationProfile profile, long nanos) {
		System.out.printf(Locale.ROOT,
			"NEOFORGE_FLUID_BASELINE m=%d n=1 phase=%s worldRequire=%d blockReads=%d held=%d"
				+ " contextRequire=%d memberGate=%d authority=%d capability=%d getTanks=%d"
				+ " getTankCapacity=%d contentGet=%d attemptedWork=%d profileWork=%d profileGates=%d"
				+ " profileAuthority=%d elapsedNanos=%d%n",
			members, phase, snapshot.worldRequire(), snapshot.blockReads(), snapshot.held(),
			snapshot.contextRequire(), snapshot.memberGate(), snapshot.authority(), snapshot.capability(),
			snapshot.getTanks(), snapshot.getTankCapacity(), snapshot.contentGet(), work,
			profile.ownedCost().workUnits(), profile.memberGateCalls(), profile.currentAuthorityCalls(), nanos);
	}

	@NotNullByDefault
	private record Size(int width, int height) {}

	@NotNullByDefault
	private record Snapshot(long worldRequire, long blockReads, long held, long contextRequire,
		long memberGate, long authority, long capability, long getTanks, long getTankCapacity, long contentGet) {
		private static Snapshot of(World world) {
			return new Snapshot(world.requireCalls, world.blockCalls, world.heldCalls, world.contextRequires,
				world.memberGateCalls, world.authorityCalls, world.capabilityCalls, world.handler.tankCountReads,
				world.handler.capacityReads, world.handler.contentReads);
		}
	}

	/**
	 * Production-shaped nested gates. Each world.require is the MinecraftWorld double Context.require
	 * around the loaded check; each Context.require is the member gate followed by current authority.
	 * block() and held() also reproduce their production multi-gate counts. Chunk/state/entity
	 * lookups are deterministic stand-ins, so this fixture is not a live ServerLevel.
	 */
	@NotNullByDefault
	private static final class World implements FluidCapabilityAccess.World, CreateFluidTankAccess.Metadata {
		private final Map<BlockPos, FluidCapabilityAccess.Block> blocks = new HashMap<>();
		private final Map<BlockPos, Object> entities = new HashMap<>();
		private final Set<BlockPos> allowed = new HashSet<>(), loaded = new HashSet<>();
		private final Object blockType = new Object();
		private final BlockPos root;
		private final Handler handler = new Handler();
		private long requireCalls, blockCalls, heldCalls, contextRequires, loadedChecks, metadataCalls;
		private long memberGateCalls, authorityCalls, capabilityCalls, dimensionCalls, createChecks;

		private World(int width, int height, BlockPos root) {
			this.root = root;
			for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
				BlockPos position = new BlockPos(x, y, z);
				var member = new Member(position.equals(BlockPos.ZERO), width, height);
				blocks.put(position, new FluidCapabilityAccess.Block(blockType, member, TANK, TANK));
				entities.put(position, member);
				allowed.add(position);
				loaded.add(position);
			}
		}
		private void reset() {
			requireCalls = blockCalls = heldCalls = contextRequires = loadedChecks = metadataCalls = 0;
			memberGateCalls = authorityCalls = capabilityCalls = dimensionCalls = createChecks = 0;
			handler.tankCountReads = handler.capacityReads = handler.contentReads = 0;
		}

		@Override public void require(BlockPos position, Operation operation) {
			requireCalls++;
			contextRequire(position, operation);
			loadedChecks++;
			if (!loaded.contains(position)) throw unavailable("fluid member unloaded");
			contextRequire(position, operation);
		}
		private void contextRequire(BlockPos position, Operation operation) {
			contextRequires++;
			memberGateCalls++;
			operation.allowance().debit(MEMBER_GATE);
			if (!allowed.contains(position)) throw unavailable("fluid member denied");
			authorityCalls++;
			operation.allowance().debit(AUTHORITY);
		}
		@Override public FluidCapabilityAccess.Block block(BlockPos position, Operation operation) {
			blockCalls++;
			require(position, operation);
			require(position, operation);
			@Nullable var block = blocks.get(position);
			if (block == null) throw unavailable("missing tank member");
			require(position, operation);
			if (block.entity() == null) throw unavailable("missing tank entity");
			require(position, operation);
			require(position, operation);
			require(position, operation);
			return block;
		}
		@Override public @Nullable IFluidHandler capability(BlockPos position, Direction face, Operation operation) {
			require(position, operation);
			capabilityCalls++;
			if (!position.equals(BlockPos.ZERO)) throw new AssertionError("capability must target the verified controller");
			if (face != FACE) throw new AssertionError("capability must keep the frozen face");
			return handler;
		}
		@Override public String dimension(Operation operation) {
			dimensionCalls++;
			contextRequire(root, operation);
			return "minecraft:overworld";
		}
		@Override public boolean createAvailable() { createChecks++; return true; }
		@Override public boolean held(FluidCapabilityAccess.Member member, Operation operation) {
			heldCalls++;
			BlockPos position = member.position();
			require(position, operation);
			@Nullable var current = blocks.get(position);
			if (current == null) return false;
			require(position, operation);
			if (!member.block().same(current)) return false;
			require(position, operation);
			if (entities.get(position) != member.block().entity()) return false;
			require(position, operation);
			return true;
		}

		@Override public boolean ordinary(FluidCapabilityAccess.Block block) {
			metadataCalls++;
			return block.entity() instanceof Member;
		}
		@Override public boolean virtual(Object entity) { member(entity); return false; }
		@Override public @Nullable BlockPos lastPosition(Object entity) { member(entity); return null; }
		@Override public boolean activeBoiler(Object entity) { member(entity); return false; }
		@Override public @Nullable BlockPos controller(Object entity) { member(entity); return BlockPos.ZERO; }
		@Override public boolean isController(Object entity) { return member(entity).master(); }
		@Override public int width(Object entity) { return member(entity).width(); }
		@Override public int height(Object entity) { return member(entity).height(); }
		@Override public Direction.@Nullable Axis axis(Object entity) { member(entity); return Direction.Axis.Y; }
		@Override public boolean storedHandler(Object controller, IFluidHandler handler) {
			metadataCalls++;
			return handler == this.handler;
		}
		private Member member(Object entity) {
			metadataCalls++;
			return (Member) Objects.requireNonNull(entity, "Create fluid fixture entity");
		}
	}

	@NotNullByDefault
	private record Member(boolean master, int width, int height) {}

	@NotNullByDefault
	private static final class Handler implements IFluidHandler {
		private final FluidStack stack = new FluidStack(Fluids.WATER, 1_000);
		private long tankCountReads, capacityReads, contentReads;

		@Override public int getTanks() { tankCountReads++; return 1; }
		@Override public FluidStack getFluidInTank(int tank) {
			assertEquals(0, tank);
			contentReads++;
			return stack;
		}
		@Override public int getTankCapacity(int tank) { assertEquals(0, tank); capacityReads++; return 8_000; }
		@Override public boolean isFluidValid(int tank, FluidStack stack) { throw new AssertionError("no simulation"); }
		@Override public int fill(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(int amount, FluidAction action) { throw new AssertionError("read only"); }
	}

	private static InventorySourceUnavailableException unavailable(String message) {
		return new InventorySourceUnavailableException(message);
	}
}
