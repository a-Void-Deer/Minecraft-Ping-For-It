package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual common source + Runtime admission/freezer/journal path; no loader or live game claim. */
@NotNullByDefault
public class InventoryFluidWorldTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@NotNullByDefault
	public static final class Fixture implements AutoCloseable, InventoryFluidSources.World, IPlatformFluidService {
		public final InventorySettings settings = InventorySettings.serverDefaults();
		public final InventoryRuntime runtime;
		public final World level = DoubleChestFixture.allocate(World.class);
		public final Map<BlockPos, Chest> entities = new HashMap<>();
		public final BlockPos root = new BlockPos(1, 64, 3);
		public final Target target;
		public final InventorySourceInput input;
		public final int tanks, memberCount;
		public String scope = "one";
		public String layoutToken = "stable";
		public boolean authorized = true, loaded = true, inside, unknown, failFinish, failClose;
		public boolean refuseAdditional;
		public boolean cursorless;
		public boolean unsupportedWitness;
		public Object routeIdentity = new Object();
		public final List<Object> addresses = new ArrayList<>();
		public final List<FluidAddressWitness> witnesses = new ArrayList<>();
		public ItemStack held = ItemStack.EMPTY;
		public int reads, probes, closes, liveAccess, maxLiveAccess, binds, notices, witnessCaptures, witnessCompares;
		public long amount = 81;
		public java.util.function.IntFunction<Entry> entryAt = slot -> amount == 0 ? Entry.empty() : new Entry(new Nbt(Fluids.WATER, null), amount);
		public Runnable afterRead = () -> {};
		public InventorySourceAccess.@Nullable Source lastSource;
		public Fixture(int tanks, int members, boolean external) {
			this.tanks = tanks; memberCount = members; level.server = DoubleChestFixture.allocate(Server.class);
			for (int i = 0; i < tanks; i++) addresses.add(new Object());
			for (int i = 0; i < members; i++) { BlockPos pos = root.offset(i, 0, 0); var chest = new Chest(pos); chest.setLevel(level); entities.put(pos, chest); }
			target = external ? Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "lease", "minecraft:chest", "locator", true)
				: new Target.BlockTarget("minecraft:overworld", root.getX(), root.getY(), root.getZ(), "minecraft:chest");
			input = new InventorySourceInput(target, new UUID(1, 2), BlockFace.NORTH, InventoryResourceKind.FLUID);
			runtime = new InventoryRuntime(new InventoryRuntime.SourceResolver() {
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted resolution"); }
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
					var source = InventoryFluidSources.resolve(Fixture.this, Fixture.this, input, cap, allowance);
					lastSource = source.orElse(null); return source;
				}
			}, settings.pendingMemoryBytes());
		}
		@Override public boolean onServerThread() { return true; }
		@Override public InventoryFluidSources.@Nullable Binding bind(InventorySourceInput input, InventoryValidationBudget.Allowance allowance) {
			binds++; if (!loaded || unknown || inside && target instanceof Target.BlockTarget) return null;
			var block = new Target.BlockTarget("minecraft:overworld", root.getX(), root.getY(), root.getZ(), "minecraft:chest");
			var descriptor = new BlockReadSource(target, target instanceof Target.ExternalBlockTarget ? "sable" : "minecraft",
				target instanceof Target.ExternalBlockTarget ? scope : "", block, new MarkerAnchor(1, 64, 3));
			return new InventoryFluidSources.Binding(level, new ResolvedBlockReadSource(level, descriptor, pos -> loaded && entities.containsKey(pos)), root);
		}
		@Override public @Nullable BlockEntity existingEntity(ServerLevel level, BlockPos pos) { return entities.get(pos); }
		@Override public BlockState blockState(ServerLevel level, BlockPos pos) { return Blocks.CHEST.defaultBlockState(); }
		@Override public boolean loaded(ServerLevel level, BlockPos pos) { return loaded; }
		@Override public @Nullable ItemStack ownerKey(InventorySourceInput input) { return authorized ? held : null; }
		@Override public net.minecraft.core.HolderLookup.Provider registries(ServerLevel level) { return RegistryAccess.EMPTY; }
		@Override public void used(RouteInfo route) { notices++; }
		@Override public OperationProfile probeProfile() { return profile(1024, 1024, 256, 2, 0); }
		private static OperationProfile profile(long allocation, long workspace, long work, long gates, long retained) {
			return new OperationProfile(new InventoryValidationBudget.Cost(allocation, workspace, work), gates, gates, retained);
		}
		@Override public Optional<Probe> probe(Context context, Operation operation) {
			if (refuseAdditional) operation.requireAdditional(new InventoryValidationBudget.Cost(1_000_000_000L, 1_000_000_000L, 1));
			operation.allowance().debit(probeProfile().ownedCost()); context.require(root, operation); probes++;
			return Optional.of(new Probe() {
				boolean done;
				@Override public Shape shape() { return new Shape(memberCount, OptionalInt.of(cursorless ? 0 : tanks), !cursorless); }
				@Override public OperationProfile finishProfile() { return profile(8192 + memberCount * 512L + tanks * 128L,
					8192 + memberCount * 512L + tanks * 128L, 4096 + 512L * memberCount + 32L * tanks, memberCount, 8192 + 1024L * memberCount + 256L * tanks); }
				@Override public Optional<Access> finish(Admission admission) {
					if (done) throw new IllegalStateException("probe reused"); done = true;
					try (var finish = admission.enter(finishProfile())) {
						finish.allowance().debit(finishProfile().ownedCost());
						for (int i = 0; i < memberCount; i++) context.require(root.offset(i, 0, 0), finish);
						if (failFinish) throw new IllegalStateException("metadata failed");
						var members = new ArrayList<InventorySnapshotLayout.Member>();
						var mapping = new ArrayList<Integer>(); for (int i = 0; i < (cursorless ? 0 : tanks); i++) mapping.add(i);
						for (int i = 0; i < memberCount; i++) members.add(new InventorySnapshotLayout.Member(root.offset(i, 0, 0),
							"minecraft:chest", "minecraft:chest", i == 0 ? "controller" : "member", i == 0 ? List.of(
							new InventorySnapshotLayout.Segment("tanks", ADDRESS_SCHEMA, cursorless ? 0 : tanks, mapping)) : List.of()));
						var layout = new InventorySnapshotLayout("test:fluid", layoutToken, root, members);
						Object route = routeIdentity; var ordered = List.copyOf(addresses);
						liveAccess++; maxLiveAccess = Math.max(maxLiveAccess, liveAccess);
						return Optional.of(new Access() {
							boolean closed;
							@Override public InventorySnapshotLayout layout() { return layout; }
							@Override public Optional<String> alias() { return Optional.empty(); }
							@Override public boolean stableCursor() { return !cursorless; }
							@Override public int slots() { return cursorless ? 0 : tanks; }
							@Override public RouteInfo routeInfo() { return new RouteInfo(memberCount > 1 ? TopologyEvidence.EXPLICIT_STRUCTURE : TopologyEvidence.PUBLIC_ADDRESS_ONLY, "fixture"); }
							@Override public OperationProfile witnessProfile(WitnessUse use) {
								if (unsupportedWitness) return Access.super.witnessProfile(use);
								return FluidAddressWitness.profile(use, cursorless ? 1 : ordered.size() + 1);
							}
							private Object[] vector(Operation operation, WitnessUse use) {
								operation.allowance().requireAvailable(witnessProfile(use).ownedCost());
								Object[] vector = new Object[cursorless ? 1 : ordered.size() + 1]; vector[0] = route;
								if (!cursorless) for (int index = 0; index < ordered.size(); index++) vector[index + 1] = ordered.get(index);
								return vector;
							}
							@Override public FluidAddressWitness captureWitness(Operation operation) {
								witnessCaptures++;
								var witness = FluidAddressWitness.capture(FluidAddressWitness.Schema.FABRIC_TRANSFER_V1, vector(operation, WitnessUse.CAPTURE), operation);
								witnesses.add(witness); return witness;
							}
							@Override public WitnessMatch compareWitness(FluidAddressWitness expected, Operation operation) {
								witnessCompares++; return expected.compare(FluidAddressWitness.Schema.FABRIC_TRANSFER_V1, vector(operation, WitnessUse.COMPARE), operation);
							}
							@Override public OperationProfile validationProfile() { return profile(1024, 1024, 256, 1, 0); }
							@Override public OperationProfile readProfile() { return profile(512, 512, 128, 2, 0); }
							@Override public boolean valid(Operation operation) {
								operation.allowance().debit(validationProfile().ownedCost()); context.require(root, operation);
								if (closed || route != routeIdentity || !layoutToken.equals(layout.layoutData())) return false;
								if (!cursorless) for (int index = 0; index < ordered.size(); index++) if (ordered.get(index) != addresses.get(index)) return false;
								return true;
							}
							@Override public boolean consume(int slot, Visitor visitor, Operation operation) {
								operation.allowance().debit(readProfile().ownedCost()); context.require(root, operation); reads++;
								boolean more = visitor.visit(entryAt.apply(slot));
								afterRead.run(); context.require(root, operation); return more;
							}
							@Override public Visit observe(int limit, Visitor visitor, Operation operation) {
								if (!cursorless) return Access.super.observe(limit, visitor, operation);
								for (int slot = 0; slot < Math.min(limit, tanks); slot++) if (!consume(slot, visitor, operation)) return new Visit(slot + 1, false);
								return new Visit(Math.min(limit, tanks), tanks <= limit);
							}
							@Override public void close() {
								if (!closed) { closed = true; liveAccess--; closes++; if (failClose) throw new IllegalStateException("external close failed"); }
							}
						});
					}
				}
				@Override public void close() { done = true; }
			});
		}
		public InventoryRuntime.Consumer attach() {
			runtime.advance(0, settings); return runtime.attach(input, new InventoryRuntime.TrackingSubject(nx.pingwheel.common.marker.TargetKey.from(target))).orElseThrow();
		}
		public void replaceMember(BlockPos pos) { var chest = new Chest(pos); chest.setLevel(level); entities.put(pos, chest); }
		public void clearMemberWitness() { clearWitness((FluidAddressWitness) field(Objects.requireNonNull(lastSource), "memberWitness"), 0); }
		@Override public void close() { runtime.close(); assertEquals(0, liveAccess); assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained()); }
	}
	@Test void stableNativeHandlerWith257CompleteFluidKeysAnd129NbtEncodingsEndsExplicitlyIncomplete() {
		try (var f = new Fixture(257, 1, false)) {
			f.entryAt = slot -> {
				var tag = new net.minecraft.nbt.CompoundTag(); tag.putInt("variant", slot / 2);
				return new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt((slot & 1) == 0 ? Fluids.WATER : Fluids.LAVA, tag), slot + 1L);
			};
			var consumer = f.attach(); var values = new ArrayList<@Nullable InventoryResourceValue>();
			InventoryRuntime.@Nullable Observation terminal = null;
			for (int tick = 0; tick < 100; tick++) {
				f.runtime.advance(tick, f.settings);
				var next = assertDoesNotThrow(() -> f.runtime.step(consumer));
				if (next.isEmpty()) continue;
				values.addAll(next.get().slots());
				if (next.get().result().completeness() != CaptureResult.Completeness.CONTINUE) { terminal = next.get(); break; }
			}
			assertNotNull(terminal); assertEquals(CaptureResult.Availability.READABLE, terminal.result().availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, terminal.result().completeness());
			assertEquals(257, f.reads); assertEquals(256, values.size()); assertEquals(256, terminal.result().coverage().scanned());
			assertEquals(257, terminal.result().coverage().expected().orElseThrow());
			assertEquals(32_896, values.stream().mapToLong(value -> java.util.Objects.requireNonNull(value).amount()).sum());
			assertTrue(values.stream().allMatch(value -> value != null && !value.aggregateRequired()));
			assertThrows(IllegalStateException.class, consumer::retainCompleted);
			assertEquals(0, f.runtime.memory().reserved());
		}
	}
	@Test void defaultOneEightAndCreate108MemberSourcesFinishWithActualShapeAndSingleSlotAdmission() {
		for (int[] shape : new int[][] {{1, 1}, {8, 1}, {1, 108}}) try (var f = new Fixture(shape[0], shape[1], false)) {
			var consumer = f.attach(); InventoryRuntime.@Nullable Observation last = null;
			for (int tick = 0; tick < 20; tick++) { f.runtime.advance(tick, f.settings); var next = f.runtime.step(consumer); if (next.isPresent()) last = next.get();
				if (last != null && last.result().completeness() == CaptureResult.Completeness.COMPLETE) break; }
			assertNotNull(last); assertEquals(CaptureResult.Completeness.COMPLETE, last.result().completeness()); assertEquals(shape[0], f.reads);
			assertEquals(shape[0], last.slots().size()); assertEquals(1, f.maxLiveAccess);
		}
	}
	@Test void externalAccessNeverSurvivesOperationAndScopeTransitionRequiresFreshAuthority() {
		try (var f = new Fixture(2, 1, true)) {
			var consumer = f.attach(); assertEquals(CaptureResult.Completeness.CONTINUE, f.runtime.step(consumer, 1).orElseThrow().result().completeness());
			assertEquals(0, f.liveAccess); f.scope = "two"; f.runtime.advance(1, f.settings);
			assertEquals(Optional.of(false), f.runtime.probe(consumer)); assertTrue(consumer.scopeTransition()); assertEquals(0, f.liveAccess);
			consumer.recomputeScope(); f.runtime.advance(2, f.settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, f.runtime.step(consumer).orElseThrow().result().completeness()); assertEquals(3, f.reads);
		}
		try (var f = new Fixture(2, 1, true)) {
			var consumer = f.attach(); f.runtime.step(consumer, 1).orElseThrow(); f.scope = "two"; f.authorized = false;
			f.runtime.advance(1, f.settings); assertEquals(Optional.of(false), f.runtime.probe(consumer)); assertFalse(consumer.scopeTransition());
		}
	}
	@Test void ordinaryEnteringSublevelAndUnknownMembershipInvalidateRatherThanChangingTargetType() {
		for (boolean unknown : new boolean[] {false, true}) try (var f = new Fixture(2, 1, false)) {
			var consumer = f.attach(); f.runtime.step(consumer, 1).orElseThrow(); f.inside = !unknown; f.unknown = unknown;
			assertEquals(Optional.of(false), f.runtime.probe(consumer)); assertFalse(consumer.scopeTransition()); assertInstanceOf(Target.BlockTarget.class, consumer.input().target());
		}
	}
	@Test void finalCallbackRevocationDropsProvisionalValueAndFailedFinishReleasesMetadata() {
		try (var f = new Fixture(1, 1, true)) {
			f.afterRead = () -> f.authorized = false; var consumer = f.attach(); var result = f.runtime.step(consumer).orElseThrow();
			assertNotEquals(CaptureResult.Availability.READABLE, result.result().availability()); assertTrue(result.slots().isEmpty()); assertEquals(0, f.liveAccess);
		}
		try (var f = new Fixture(1, 1, true)) {
			f.failFinish = true; var consumer = f.attach(); f.runtime.step(consumer); assertEquals(0, f.liveAccess); consumer.close();
			assertEquals(0, f.runtime.memory().reserved());
		}
	}
	@Test void nonemptyLocksGetRealAdditionalGrantsAndRevokeWithoutCachingOrLeakingTickets() {
		try (var f = new Fixture(1, 1, false)) {
			f.held = new ItemStack(Items.STICK); f.held.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("key"));
			f.entities.get(f.root).lock = new LockCode("key"); var consumer = f.attach();
			assertEquals(CaptureResult.Completeness.COMPLETE, f.runtime.step(consumer).orElseThrow().result().completeness());
			f.held.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("revoked"));
			assertEquals(Optional.of(false), f.runtime.probe(consumer)); assertEquals(1, f.reads);
		}
	}
	@Test void allocationAdmissionRefusalReadsNothingAndLoweredMemoryCapReleasesSourceOwnershipOnClose() {
		try (var f = new Fixture(1, 1, true)) {
			f.refuseAdditional = true; var consumer = f.attach(); assertTrue(f.runtime.step(consumer).isEmpty());
			assertEquals(0, f.reads); assertEquals(0, f.liveAccess); assertEquals(0, f.runtime.memory().reserved());
		}
		try (var f = new Fixture(1, 1, true)) {
			var consumer = f.attach(); f.runtime.step(consumer).orElseThrow(); f.runtime.memory().setCap(0);
			assertTrue(f.runtime.probe(consumer).isEmpty()); consumer.close(); assertEquals(0, f.runtime.memory().reserved());
			// The period's logical-quota bookkeeping is Runtime-owned, not source metadata.
			assertEquals(0, f.liveAccess); f.runtime.close(); assertEquals(0, f.runtime.memory().retained());
		}
	}
	@Test void pendingBlockEntityNbtIsNeverPromotedBeforeSafetyGates() {
		var world = DoubleChestFixture.allocate(PendingWorld.class); var cache = DoubleChestFixture.allocate(PendingCache.class);
		var chunk = DoubleChestFixture.allocate(PendingChunk.class); chunk.entities = new HashMap<>(); world.cache = cache; cache.chunk = chunk;
		var pos = new BlockPos(1, 64, 3); assertNull(InventoryFluidSources.existingEntity(world, pos));
		var chest = new Chest(pos); chunk.entities.put(pos, chest); assertSame(chest, InventoryFluidSources.existingEntity(world, pos));
		cache.chunk = null; assertNull(InventoryFluidSources.existingEntity(world, pos));
	}
	@Test void cursorlessReadAdmissionDefersWithoutInventingCompletionAndEmptyPassDoesNotIssueReadNotice() {
		try (var f = new Fixture(1, 1, true)) {
			f.cursorless = true; var consumer = f.attach(); f.refuseAdditional = true;
			assertTrue(f.runtime.step(consumer).isEmpty()); assertEquals(0, f.reads); assertEquals(0, f.notices);
			f.refuseAdditional = false; f.runtime.advance(1, f.settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, f.runtime.step(consumer).orElseThrow().result().completeness());
			assertEquals(1, f.reads); assertEquals(1, f.notices);
		}
		try (var f = new Fixture(0, 1, true)) {
			f.cursorless = true; var consumer = f.attach();
			assertEquals(CaptureResult.Completeness.COMPLETE, f.runtime.step(consumer).orElseThrow().result().completeness());
			assertEquals(0, f.reads); assertEquals(0, f.notices);
		}
	}
	@Test void failingExternalCloseBeforeOwnershipTransferReleasesAllSourceMetadata() {
		try (var f = new Fixture(1, 1, true)) {
			f.failClose = true; var consumer = f.attach(); var result = f.runtime.step(consumer);
			assertTrue(result.isEmpty() || result.orElseThrow().result().availability() != CaptureResult.Availability.READABLE);
			assertEquals(0, f.liveAccess); assertEquals(0, f.runtime.memory().reserved());
			consumer.close(); f.runtime.close(); assertEquals(0, f.runtime.memory().retained());
		}
	}
	@Test void sameDetachedLayoutCannotAuthorizeReacquiredRouteOrReorderedEqualQuantityAddresses() {
		for (int change = 0; change < 3; change++) try (var f = new Fixture(2, 1, true)) {
			var consumer = f.attach(); var prefix = f.runtime.step(consumer, 1).orElseThrow();
			assertEquals(CaptureResult.Completeness.CONTINUE, prefix.result().completeness());
			assertEquals(1, f.witnessCaptures); assertEquals(0, f.liveAccess);
			Object oldRoute = f.routeIdentity; Object first = f.addresses.getFirst(), second = f.addresses.get(1);
			if (change == 0) f.routeIdentity = new Object();
			else if (change == 1) f.addresses.set(1, new Object());
			else java.util.Collections.swap(f.addresses, 0, 1);
			f.runtime.advance(1, f.settings); assertEquals(Optional.of(false), f.runtime.probe(consumer));
			assertFalse(consumer.scopeTransition()); assertTrue(consumer.invalidated()); assertEquals(1, f.reads);
			assertEquals(1, f.witnessCaptures, "a current Access must not replace the historical witness");
			consumer.restart(); f.runtime.advance(2, f.settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, f.runtime.step(consumer).orElseThrow().result().completeness());
			assertEquals(3, f.reads); assertEquals(2, f.witnessCaptures);
			java.lang.ref.Reference.reachabilityFence(oldRoute); java.lang.ref.Reference.reachabilityFence(first); java.lang.ref.Reference.reachabilityFence(second);
		}
	}
	@Test void deterministicallyClearedRouteOrMemberEvidenceRetiresAndRecoversWithoutPermanentDeferralOrZero() {
		for (boolean member : new boolean[] {false, true}) try (var f = new Fixture(2, 1, true)) {
			var consumer = f.attach(); f.runtime.step(consumer, 1).orElseThrow();
			if (member) f.clearMemberWitness(); else clearWitness(f.witnesses.getFirst(), 0);
			f.runtime.advance(1, f.settings); assertEquals(Optional.of(false), f.runtime.probe(consumer));
			assertTrue(consumer.invalidated()); assertFalse(consumer.scopeTransition()); assertEquals(1, f.reads);
			consumer.restart(); f.runtime.advance(2, f.settings); var rebuilt = f.runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Availability.READABLE, rebuilt.result().availability());
			assertEquals(CaptureResult.Completeness.COMPLETE, rebuilt.result().completeness());
			assertEquals(2, rebuilt.slots().size()); assertTrue(rebuilt.slots().stream().allMatch(value -> value != null && value.amount() == 81));
			assertEquals(2, f.witnessCaptures); assertEquals(0, f.liveAccess);
		}
	}
	@Test void historicalPublicationPinsOriginalWeakProofAcrossConsumerReleaseAndFreshSourceConstruction() {
		try (var f = new Fixture(2, 1, true)) {
			var consumer = f.attach(); f.runtime.step(consumer).orElseThrow();
			try (var old = f.runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.SCANNING).orElseThrow()) {
				consumer.close(); long pinned = f.runtime.memory().retained();
				assertTrue(pinned >= FluidAddressWitness.retainedBytes(3) + FluidAddressWitness.retainedBytes(1));
				assertEquals(0, f.liveAccess); assertEquals(InventorySharedRead.Check.VALID, publicationCheck(f.runtime, old));
				assertEquals(1, f.witnessCaptures, "publication compares, never recaptures");
				Object oldRoute = f.routeIdentity; f.routeIdentity = new Object();
				var fresh = f.attach(); f.runtime.advance(1, f.settings); f.runtime.step(fresh).orElseThrow();
				assertEquals(2, f.witnessCaptures);
				assertEquals(InventorySharedRead.Check.UNAVAILABLE, publicationCheck(f.runtime, old));
				assertEquals(2, f.witnessCaptures, "the new source's proof cannot authorize the old cut");
				fresh.close(); assertTrue(f.runtime.memory().retained() >= pinned);
				java.lang.ref.Reference.reachabilityFence(oldRoute);
			}
			f.runtime.close(); assertEquals(0, f.runtime.memory().retained());
		}
	}
	private static InventorySharedRead.Check publicationCheck(InventoryRuntime runtime, InventoryPublicationEvidence evidence) {
		try (var grant = runtime.reservePublication(evidence).orElseThrow()) { return grant.validate(evidence); }
	}
	@Test void scopeExceptionRequiresSameRouteAddressesAndMemberIdentityAndNeverAcceptsClearedProof() {
		for (int change = 0; change < 4; change++) try (var f = new Fixture(2, 1, true)) {
			var consumer = f.attach(); f.runtime.step(consumer, 1).orElseThrow(); f.scope = "two";
			Object oldRoute = f.routeIdentity; Chest oldMember = f.entities.get(f.root);
			if (change == 0) f.routeIdentity = new Object();
			else if (change == 1) java.util.Collections.swap(f.addresses, 0, 1);
			else if (change == 2) f.replaceMember(f.root);
			else f.clearMemberWitness();
			f.runtime.advance(1, f.settings); assertEquals(Optional.of(false), f.runtime.probe(consumer));
			assertFalse(consumer.scopeTransition()); assertEquals(1, f.reads);
			java.lang.ref.Reference.reachabilityFence(oldRoute); java.lang.ref.Reference.reachabilityFence(oldMember);
		}
	}
	@Test void authorizationDenialPrecedesWitnessAndUnsupportedWitnessCannotBecomeAnEmptySuccessfulRoute() {
		try (var f = new Fixture(1, 1, true)) {
			f.authorized = false; var consumer = f.attach(); f.runtime.step(consumer);
			assertEquals(0, f.witnessCaptures); assertEquals(0, f.witnessCompares); assertEquals(0, f.reads);
		}
		try (var f = new Fixture(1, 1, true)) {
			var consumer = f.attach(); f.runtime.step(consumer).orElseThrow(); int compares = f.witnessCompares;
			f.authorized = false; assertEquals(Optional.of(false), f.runtime.probe(consumer));
			assertEquals(compares, f.witnessCompares); assertEquals(1, f.reads);
		}
		try (var f = new Fixture(1, 1, true)) {
			f.unsupportedWitness = true; var consumer = f.attach(); var result = f.runtime.step(consumer);
			assertTrue(result.isEmpty() || result.orElseThrow().result().availability() != CaptureResult.Availability.READABLE);
			assertEquals(0, f.witnessCaptures); assertEquals(0, f.reads); assertEquals(0, f.liveAccess);
			consumer.close(); f.runtime.close(); assertEquals(0, f.runtime.memory().reserved()); assertEquals(0, f.runtime.memory().retained());
		}
	}
	@Test void lateCallbackRouteOrMemberRebindDiscardsProvisionalValueEvenWithUnchangedLayoutAndQuantity() {
		for (boolean member : new boolean[] {false, true}) try (var f = new Fixture(1, 1, true)) {
			f.afterRead = () -> { if (member) f.replaceMember(f.root); else f.routeIdentity = new Object(); };
			var consumer = f.attach(); var result = f.runtime.step(consumer).orElseThrow();
			assertNotEquals(CaptureResult.Availability.READABLE, result.result().availability()); assertTrue(result.slots().isEmpty());
			assertEquals(1, f.reads); assertEquals(1, f.witnessCaptures); assertEquals(0, f.liveAccess);
		}
	}
	@Test void detachedExternalIndexHasNoStrongNativeMemberAndAdditionalWeakOwnershipFitsDefaultShapes() {
		for (int[] shape : new int[][] {{1, 1}, {8, 1}, {1, 108}}) try (var f = new Fixture(shape[0], shape[1], true)) {
			var consumer = f.attach(); InventoryRuntime.@Nullable Observation last = null;
			for (int tick = 0; tick < 20; tick++) {
				f.runtime.advance(tick, f.settings); var next = f.runtime.step(consumer); if (next.isPresent()) last = next.get();
				if (last != null && last.result().completeness() == CaptureResult.Completeness.COMPLETE) break;
			}
			assertNotNull(last); assertEquals(CaptureResult.Completeness.COMPLETE, last.result().completeness());
			assertEquals(shape[0], f.reads); assertEquals(0, f.liveAccess); assertEquals(1, f.maxLiveAccess);
			var source = Objects.requireNonNull(f.lastSource);
			assertNull(field(source, "ordinaryAccess")); assertNull(field(source, "ordinaryMembers"));
			Object[] members = (Object[]) Objects.requireNonNull(field(source, "members")); assertEquals(shape[1], members.length);
			for (var component : members[0].getClass().getRecordComponents())
				assertTrue(component.getType() == BlockPos.class || component.getType() == String.class);
			var ownership = (InventoryFluidAdmission.Ownership) field(source, "ownership");
			assertNotNull(ownership); assertTrue(ownership.bytes() >= FluidAddressWitness.retainedBytes(shape[1]) + FluidAddressWitness.retainedBytes(shape[0] + 1));
			assertEquals(1, f.witnessCaptures); assertTrue(f.witnessCompares > 1);
		}
	}
	private static @Nullable Object field(Object value, String name) {
		try { var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static void clearWitness(FluidAddressWitness witness, int index) {
		try { var method = FluidAddressWitness.class.getDeclaredMethod("clearForTesting", int.class); method.setAccessible(true); method.invoke(witness, index); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	@NotNullByDefault
	private static final class PendingWorld extends ServerLevel {
		private @Nullable PendingCache cache;
		private PendingWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public net.minecraft.server.level.ServerChunkCache getChunkSource() { return Objects.requireNonNull(cache); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("pending NBT promotion"); }
	}
	@NotNullByDefault
	private static final class PendingCache extends net.minecraft.server.level.ServerChunkCache {
		private @Nullable PendingChunk chunk;
		private PendingCache() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
		@Override public net.minecraft.world.level.chunk.@Nullable LevelChunk getChunkNow(int x, int z) { return chunk; }
	}
	@NotNullByDefault
	private static final class PendingChunk extends net.minecraft.world.level.chunk.LevelChunk {
		private @Nullable Map<BlockPos, BlockEntity> entities;
		private PendingChunk() { super(null, null); }
		@Override public Map<BlockPos, BlockEntity> getBlockEntities() { return Objects.requireNonNull(entities); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("pending NBT constructor"); }
	}
	@NotNullByDefault
	public static final class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		public LockCode lock = LockCode.NO_LOCK;
		Chest(BlockPos pos) { super(pos, Blocks.CHEST.defaultBlockState()); }
		@Override public LockCode pingforit$inventoryLock() { return lock; }
	}
	@NotNullByDefault
	public static final class World extends ServerLevel {
		private @Nullable Server server;
		private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public net.minecraft.server.MinecraftServer getServer() { return Objects.requireNonNull(server); }
	}
	@NotNullByDefault
	public static final class Server extends DedicatedServer {
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
	}
}
