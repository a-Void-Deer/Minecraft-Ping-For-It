package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.neoforge.integration.create.presentation.VaultPerformanceFixture.Contents;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real segmented provider -> ordinary authority wrapper -> native runtime; never a synthetic alias source. */
@NotNullByDefault
class CreateVaultComposedSharingTest {
	private static final UUID OWNER = new UUID(84, 1), OTHER = new UUID(84, 2);
	private static final TargetKey.VaultKey KEY = new TargetKey.VaultKey("minecraft:overworld", 0, 0, 0, "create:item_vault");
	@BeforeAll static void bootstrap() throws Exception { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { CreateVaultTrackingPerformanceTest.restoreRegistries(); }
	private static InventorySourceInput input(BlockPos hit, UUID owner, BlockFace face) {
		return new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", hit.getX(), hit.getY(), hit.getZ(), "create:item_vault"), owner, face);
	}
	private static InventorySettings policy() {
		var settings = CreateVaultTrackingPerformanceTest.settings();
		settings.setPhysicalSlotsPerTick(IntLimit.finite(32));
		settings.getTracking().setPeriodTicks(1000); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(4000)); settings.getTracking().setMaxSlotsServer(IntLimit.finite(8000));
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(4000)); settings.getPreview().setMaxSlotsServer(IntLimit.finite(8000));
		settings.validate(); return settings;
	}
	private static int logical(InventoryRuntime runtime, InventoryRuntime.Subject subject) throws Exception {
		var field = InventoryRuntime.class.getDeclaredField("logicalUsed"); field.setAccessible(true);
		@Nullable Object value = ((Map<?, ?>) field.get(runtime)).get(subject);
		return value == null ? 0 : (Integer) value;
	}

	@Test void oppositeMemberHitsShareOne900GetterSweepAndPayTheVaultTrackingSubjectOnce() throws Exception {
		var world = new CreateVaultTrackingPerformanceTest.World(Contents.REPEATED); var settings = policy();
		var firstInput = input(BlockPos.ZERO, OWNER, BlockFace.NORTH);
		var otherInput = input(new BlockPos(4, 2, 2), OWNER, BlockFace.NORTH);
		var resolved = new ArrayList<InventorySourceInput>();
		InventoryRuntime.SourceResolver resolver = new InventoryRuntime.SourceResolver() {
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int ceiling) { throw new AssertionError("validation admission required"); }
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return world.resolutionValidationCost(input); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int ceiling, InventoryValidationBudget.Allowance allowance) {
				resolved.add(input); return world.resolve(input, ceiling, allowance);
			}
		};
		try (var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(KEY);
			var first = runtime.attach(firstInput, subject).orElseThrow(); var peer = runtime.attach(otherInput, subject).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(first)); assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(peer));
			assertTrue(resolved.containsAll(List.of(firstInput, otherInput)), "both original roots independently pass production metadata authority before sharing");
			assertEquals(0, world.vault.getters);
			int a = 0, b = 0, paid = 0; boolean ac = false, bc = false;
			for (int tick = 1; tick <= 128 && !(ac && bc); tick++) {
				runtime.advance(tick, settings);
				int before = logical(runtime, subject);
				if (!ac) { var page = runtime.step(first, 32).orElseThrow(); a += page.slots().size(); ac = page.result().completeness() == CaptureResult.Completeness.COMPLETE; }
				int afterFirst = logical(runtime, subject);
				if (!bc) { var page = runtime.step(peer, 32).orElseThrow(); b += page.slots().size(); bc = page.result().completeness() == CaptureResult.Completeness.COMPLETE; }
				assertEquals(afterFirst, logical(runtime, subject), "peer consumes the same paid subject prefix"); paid += logical(runtime, subject) - before;
			}
			assertTrue(ac && bc); assertEquals(900, a); assertEquals(900, b); assertEquals(900, world.vault.getters);
			assertEquals(900, paid); assertEquals(firstInput, first.input()); assertEquals(otherInput, peer.input());
		}
	}

	@Test void differentOwnersAndFrozenFacesEachNeedAnIndependent900GetterRound() throws Exception {
		for (boolean ownerChanges : new boolean[] {true, false}) {
			var world = new CreateVaultTrackingPerformanceTest.World(Contents.EMPTY); var settings = policy();
			try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
				runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(KEY);
				var first = runtime.attach(input(BlockPos.ZERO, OWNER, BlockFace.NORTH), subject).orElseThrow();
				var peer = runtime.attach(input(new BlockPos(4, 2, 2), ownerChanges ? OTHER : OWNER, ownerChanges ? BlockFace.NORTH : BlockFace.SOUTH), subject).orElseThrow();
				int a = 0, b = 0, paid = 0;
				for (int tick = 1; tick <= 160 && (a < 900 || b < 900); tick++) {
					runtime.advance(tick, settings);
					int before = logical(runtime, subject);
					if (a < 900) a += runtime.step(first, 16).map(p -> p.slots().size()).orElse(0);
					if (b < 900) b += runtime.step(peer, 16).map(p -> p.slots().size()).orElse(0);
					paid += logical(runtime, subject) - before;
				}
				assertEquals(900, a); assertEquals(900, b); assertEquals(1800, world.vault.getters); assertEquals(1800, paid);
			}
		}
	}

	@Test void latePreviewCannotJoinOldRoundButCanShareAReplacementStartedAfterAttachmentAndNewTrackingStartsFresh() {
		var world = new CreateVaultTrackingPerformanceTest.World(Contents.EMPTY); var settings = policy();
		try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var old = runtime.attach(input(BlockPos.ZERO, OWNER, BlockFace.NORTH), new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			runtime.advance(1, settings); assertEquals(16, runtime.step(old, 16).orElseThrow().slots().size());
			var late = runtime.attach(input(new BlockPos(4, 2, 2), OWNER, BlockFace.NORTH), new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			runtime.advance(2, settings); assertEquals(16, runtime.step(late, 16).orElseThrow().slots().size()); assertEquals(32, world.vault.getters);
			old.restart(); assertEquals(16, runtime.step(old, 16).orElseThrow().slots().size()); assertEquals(32, world.vault.getters, "replacement created after both attachment floors is shareable");
			var selected = runtime.attach(input(BlockPos.ZERO, OWNER, BlockFace.NORTH), new InventoryRuntime.TrackingSubject(KEY)).orElseThrow();
			runtime.advance(3, settings); var fresh = runtime.step(selected, 16).orElseThrow();
			assertEquals(16, fresh.result().coverage().scanned()); assertEquals(16, fresh.slots().size()); assertEquals(48, world.vault.getters, "new selection cannot reuse the preview prefix");
		}
	}
}
