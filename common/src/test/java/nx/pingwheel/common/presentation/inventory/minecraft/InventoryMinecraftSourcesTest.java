package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout.Member;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout.Segment;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMinecraftSourcesTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static final class World {
		String mapping = "axis-X/2x2x3"; final AtomicInteger wrappers = new AtomicInteger(), reads = new AtomicInteger();
		InventoryMinecraftSources.View view() {
			String expected = mapping; wrappers.incrementAndGet();
			return new InventoryMinecraftSources.View("same-controller-alias", List.of(), List.of(), new IPlatformInventoryService.Access() {
				@Override public Optional<String> alias() { return Optional.of("same-controller-alias"); }
				@Override public boolean valid() { return mapping.equals(expected); }
				@Override public boolean stableCursor() { return true; }
				@Override public int slots() { return 12; }
				@Override public OptionalLong version() { return OptionalLong.empty(); }
				@Override public IPlatformInventoryService.Entry read(int slot) { reads.incrementAndGet(); return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), mapping.contains("axis-X") ? 7 : 9); }
				@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed provider"); }
			});
		}
		InventorySourceAccess.Source source(InventorySourceInput input) {
			return new InventoryMinecraftSources.Source(input, view(), () -> true, this::view, entry ->
				new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "Stone", null, false));
		}
	}
	static CostLedger grant(int slots) {
		return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) slots, InventorySourceAccess.PROBES, 8L, InventorySourceAccess.PROVIDER_WORK, 32768L));
	}
	static SourceAccess.Handle open(World world, InventorySourceInput input) {
		var access = new InventorySourceAccess(input, i -> Optional.of(world.source(i))); var scope = new SourceAccess.ReadScope(input.viewKey(), java.util.Set.of("pingforit:inventory.items"));
		var target = input.target(); var resolved = (SourceAccess.ResolveResult.Available) access.resolve(new PresentationAdapter.DetachedTarget(target.dimensionId(), "block", target.blockRegistryId(), target.x(), target.y(), target.z(), ""), scope, grant(0));
		return ((SourceAccess.OpenResult.Started) access.open(resolved.descriptor(), scope, grant(0))).handle();
	}
	@Test void productionSourceWrapperRejectsSameAliasAndCountTopologyChangeBeforeContinuingCursorAndFreshObservationRecovers() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "create:item_vault"), new UUID(1, 1), BlockFace.NORTH); var world = new World();
		try (var handle = open(world, input)) {
			var first = ((SourceAccess.StepOutcome.Captured) handle.step(grant(1))).result();
			assertEquals(CaptureResult.Completeness.CONTINUE, first.completeness()); assertEquals(1, world.reads.get()); assertTrue(world.wrappers.get() > 1, "same mapping can reacquire different wrapper objects");
			world.mapping = "axis-Z/2x2x3";
			var changed = ((SourceAccess.StepOutcome.Captured) handle.step(grant(1))).result();
			assertEquals(CaptureResult.Availability.INVALID, changed.availability()); assertEquals(CaptureResult.Completeness.INCOMPLETE, changed.completeness()); assertTrue(changed.payload().isEmpty()); assertEquals(1, world.reads.get(), "no L1-prefix/L2-tail mixed scan");
		}
		try (var fresh = open(world, input)) {
			var recovered = ((SourceAccess.StepOutcome.Captured) fresh.step(grant(12))).result(); assertEquals(CaptureResult.Completeness.COMPLETE, recovered.completeness());
			var entries = ((CaptureResult.OpaqueKeyedFragment) recovered.payload().orElseThrow()).entries(); assertEquals(12, entries.size());
			assertTrue(entries.values().stream().allMatch(v -> InventoryDomainCodec.decode(v).count() == 9));
		}
	}
	@Test void snapshotPlanIsLazyAndDoesNotReplaceTheLiveSelectionWitness() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), new UUID(2, 2), BlockFace.NORTH);
		AtomicInteger captures = new AtomicInteger();
		var layout = new InventorySnapshotLayout("same-controller-alias", "face=north", BlockPos.ZERO,
			List.of(new Member(BlockPos.ZERO, "minecraft:chest", "minecraft:chest", "target",
				List.of(new Segment("Items", InventorySnapshotSchemas.ITEMS, 12, java.util.stream.IntStream.range(0, 12).boxed().toList())))));
		var access = new IPlatformInventoryService.Access() {
			@Override public Optional<String> alias() { return Optional.of("same-controller-alias"); }
			@Override public boolean valid() { return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 12; }
			@Override public OptionalLong version() { return OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("SELECT witness should not be used by this capture-only fixture"); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed provider"); }
		};
		var snapshot = new InventorySourceAccess.InventorySnapshot() {
			@Override public int slots() { return 12; }
			@Override public InventoryDomainCodec.Item read(int index) { return null; }
			@Override public long retainedBytes() { return 1; }
			@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
		};
		var view = new InventoryMinecraftSources.View("same-controller-alias", List.of(), List.of(), access, layout, false);
		var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
			entry -> new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "Stone", null, false),
			() -> { captures.incrementAndGet(); return Optional.of(snapshot); });
		var plan = source.snapshotPlan().orElseThrow();
		assertEquals(0, captures.get(), "plan discovery must not capture or save NBT");
		assertSame(snapshot, plan.capture().orElseThrow());
		assertEquals(1, captures.get());
		assertEquals(12, snapshot.slots());
	}

	@Test void aggregateAliasWithoutExplicitMemberLayoutKeepsLiveRoute() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "create:item_vault"), new UUID(3, 3), BlockFace.NORTH);
		World world = new World();
		var aggregate = new IPlatformInventoryService.Access() {
			@Override public Optional<String> alias() { return Optional.of("same-controller-alias"); }
			@Override public boolean valid() { return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 12; }
			@Override public OptionalLong version() { return OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { return IPlatformInventoryService.Entry.empty(); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { return true; }
		};
		var view = new InventoryMinecraftSources.View("aggregate-alias", List.of(), List.of(), aggregate);
		var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
			entry -> new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "Stone", null, false));
		assertTrue(source.snapshotPlan().isEmpty(), "aggregate alias cannot be snapshotted as the hit controller alone");
	}
}
