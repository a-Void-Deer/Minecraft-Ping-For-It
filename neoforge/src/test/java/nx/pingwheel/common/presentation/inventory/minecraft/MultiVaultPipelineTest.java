package nx.pingwheel.common.presentation.inventory.minecraft;

import io.netty.buffer.ByteBufUtil;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryDecision;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static nx.pingwheel.common.presentation.inventory.minecraft.CertifiedVault1620Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Oracle: inventory contract's independent physical graphs, non-starving paid progress,
 * complete-only totals, getter-free at-most-once SELECT and original marker expiry.
 * Three native 81-member Vaults, not three member hits of one shared Vault.
 * World/marker authority and in-memory transport are headless ports; source, pooling,
 * native freezer, Backend, Publisher, both wire codecs and ClientInventory are real.
 */
@NotNullByDefault
class MultiVaultPipelineTest {

	@BeforeAll static void bootstrap() throws ReflectiveOperationException { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { CreateVaultTrackingPerformanceTest.restoreRegistries(); }

	@ParameterizedTest
	@CsvSource({"DEFAULTS,false", "DEFAULTS,true", "MINIMUM_BYTES,false", "MINIMUM_BYTES,true"})
	void thirdIndependentVaultOpensBeforeOrAfterTwoContinuousTrackingScansAndAllThreeFinish(MultiVaultFixture.Budgets budgets, boolean afterTracking) {
		try (var f = new MultiVaultFixture(budgets)) {
			MarkerId first = startTracking(f, 0), second = startTracking(f, 1);
			assertNotEquals(f.markers.find(first).orElseThrow().targetKey(), f.markers.find(second).orElseThrow().targetKey());
			f.next();
			long firstRevision = f.revision(first), secondRevision = f.revision(second);
			if (!afterTracking) {
				// First ingress on a fresh authoritative tick, before Backend scans Tracking.
				f.tick++; f.millis += 50;
			} else {
				// Same tick as the two Tracking consumers, after their work and Publisher drain.
				long before = f.visits;
				f.await(() -> f.visits > before, 128, "existing Tracking must actually read before late OPEN");
			}
			long openTick = f.tick;
			var thirdPreview = f.open(2);
			long request = thirdPreview.requestId();
			assertTrue(request > 0);
			assertTrue(f.received.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.OPENED && packet.requestId() == request), f::evidence);
			assertTrue(f.received.stream().noneMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED && packet.requestId() == request), f::evidence);
			f.awaitRow(thirdPreview);
			assertTrue(f.tick - openTick <= 128);
			assertTrue(f.received.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE && packet.requestId() == request
				&& packet.condition() == InventoryS2CPacket.PreviewCondition.PROGRESS && packet.progressRevision() > 0),
				"real paid scan/delivery progress must reach the decoded client route");
			assertTrue(f.previewConsumer(request).progress().consumedSlots() > 0, "progress controls must correspond to actual consumed native addresses");
			MarkerId third = f.select(thirdPreview);
			f.assertDistinctPhysicalSources();
			assertEquals(3, f.createCalls); assertEquals(3, f.createdNotifications);
			// Liveness bound: one address per configured period per rotating consumer,
			// plus 128 delivery ticks. No-progress windows fail much earlier below.
			int bound = 3 * SLOTS * Math.max(f.settings.getPreview().getPeriodTicks(), f.settings.getTracking().getPeriodTicks()) + 128;
			var ids = List.of(first, second, third);
			var progress = ids.stream().map(f::consumer).map(consumer -> consumer.progress()).toList();
			var lastProgress = new java.util.HashMap<MarkerId, Long>();
			ids.forEach(marker -> lastProgress.put(marker, f.tick));
			for (int step = 0; step < bound && !(f.client.tracking(first).complete() && f.client.tracking(second).complete()
				&& f.client.tracking(third).complete() && f.revision(first) > firstRevision + 1 && f.revision(second) > secondRevision + 1); step++) {
				f.next();
				var current = ids.stream().map(f::consumer).map(consumer -> consumer.progress()).toList();
				for (int index = 0; index < ids.size(); index++) {
					MarkerId marker = ids.get(index);
					if (!current.get(index).equals(progress.get(index))) lastProgress.put(marker, f.tick);
					assertTrue(f.tick - lastProgress.get(marker) <= 128,
						() -> "no physical/replay cursor progress for 6.4 s: " + marker + " " + f.evidence());
				}
				progress = current;
			}
			assertTrue(f.revision(first) > firstRevision + 1 && f.revision(second) > secondRevision + 1,
				"both original Vaults must finish replacement observations as well as their initial cut: " + f.evidence());
			for (int vault = 0; vault < 3; vault++) f.assertCount(List.of(first, second, third).get(vault), expected(vault));
			assertTrue(f.visits >= 3L * SLOTS, "three complete physical sources require at least three full native sweeps");
			f.assertDistinctPhysicalSources();
			assertEquals(0, f.client.stats().invalidPackets()); assertTrue(f.measurements > 0);
			expireAndRelease(f, List.of(first, second, third));
		}
	}

	@Test void protectedInputPreflightSurvivesExhaustedUnprotectedProbeAllowanceThenDuePreviewAdvances() {
		try (var f = new MultiVaultFixture(MultiVaultFixture.Budgets.DEFAULTS)) {
			startTracking(f, 0); startTracking(f, 1);
			// Production entry used before normal Inventory tick: reserve its one input
			// opportunity from the SAME ledger, then consume every unprotected probe.
			f.backend.prepareInitialAdmission(++f.tick); f.millis += 50;
			int paid = exhaustProbes(f);
			assertTrue(paid > 0 && paid < 256, "the input opportunity must withhold real probe capacity");
			var preview = f.open(2);
			long request = preview.requestId();
			assertTrue(f.received.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.OPENED && packet.requestId() == request), f::evidence);
			f.awaitRow(preview);
			assertTrue(f.previewConsumer(request).progress().consumedSlots() > 0);
			f.assertDistinctPhysicalSources();
			assertTrue(f.received.stream().anyMatch(packet -> packet.requestId() == request && packet.kind() == InventoryS2CPacket.Kind.PREVIEW
				&& !packet.entries().isEmpty()), "a grant alone cannot pass: due Preview must deliver actual native values");
			preview.close();
		}
	}

	@Test void exhaustedProbeBudgetReportsOpenRejectedAndFiveSecondNewAttemptSucceedsAfterPressureEnds() {
		try (var f = new MultiVaultFixture(MultiVaultFixture.Budgets.MINIMUM_BYTES)) {
			startTracking(f, 0); startTracking(f, 1); f.next();
			// After Backend tick returns, its unused protected grants are closed.
			// Deliberately spend the remaining legal same-tick probe allowance.
			assertTrue(exhaustProbes(f) > 0);
			long before = physical(f.runtime);
			var preview = f.open(2);
			var rejected = f.sent.stream().filter(packet -> packet.kind() == InventoryC2SPacket.Kind.OPEN).toList().getLast();
			assertTrue(f.received.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED
				&& packet.requestId() == rejected.requestId() && packet.failureReason() == InventoryFailureReason.PROBE_LIMIT), f::evidence);
			assertTrue(preview.waiting()); assertEquals(InventoryFailureReason.PROBE_LIMIT, preview.failureReason());
			assertEquals(before, physical(f.runtime), "failed OPEN may not read a resident item");
			assertTrue(f.client.selectable(rejected.requestId()).isEmpty());
			assertTrue(f.client.preview(rejected.requestId()).entries().isEmpty(), "retired attempt owns no invented empty/zero row");
			long failedAt = f.millis;
			f.next(); // Releases the exhausted tick; Tracking stays live under the same legal settings.
			f.millis = failedAt + 4_999; f.client.tick(true);
			assertEquals(1, preview.attempts(), "retry must respect the full fixed five-second delay");
			f.millis = failedAt + 5_000; f.client.tick(true);
			assertEquals(2, preview.attempts()); assertTrue(preview.requestId() > rejected.requestId());
			assertFalse(preview.waiting());
			long replacement = preview.requestId();
			f.await(() -> !f.client.selectable(replacement).isEmpty(), 128, "released probe pressure must permit the new frozen-target attempt");
			assertTrue(f.received.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.OPENED && packet.requestId() == replacement));
			f.select(preview); assertEquals(3, f.markers.size()); assertEquals(3, f.createCalls);
			f.assertDistinctPhysicalSources();
		}
	}

	@Test void minimumBytesMaximumDisplaySingletonInThirdVaultRemainsServiceableAfterTwoTrackingVaultsAreReleased() {
		var stack = maximumNativeDisplay();
		try (var f = new MultiVaultFixture(MultiVaultFixture.Budgets.MINIMUM_BYTES, 2, stack)) {
			MarkerId first = startTracking(f, 0), second = startTracking(f, 1);
			f.next();
			// 16 MiB is a capacity boundary, not a requirement to admit three largest
			// workspaces concurrently. Release actual tracking ownership first.
			f.backend.remove(first); f.backend.remove(second);
			assertTrue(f.leases().isEmpty());
			f.settings.setDisplayByteMultiplier(nx.pingwheel.common.config.InventorySizeScale.DISPLAY.maximumMultiplier());
			f.settings.setFrameByteMultiplier(nx.pingwheel.common.config.InventorySizeScale.FRAME.maximumMultiplier());
			String configured = f.settings.toString(); f.settings.validate(); assertEquals(configured, f.settings.toString());
			f.next(); // Actual OFFER/reconfiguration boundary before the new maximum-display OPEN.
			var preview = f.open(2); f.awaitRow(preview);
			var row = f.client.preview(preview.requestId()).entries().getFirst();
			assertFalse(row.fallback()); assertEquals(7, row.count());
			assertEquals(InventoryLimits.MAX_DISPLAY_BYTES, ByteBufUtil.utf8Bytes(Objects.requireNonNull(row.displayJson())),
				"the real native freezer must preserve the maximum display, not pass by stripping it");
			MarkerId third = f.select(preview);
			f.await(() -> f.client.tracking(third).complete(), SLOTS * f.settings.getTracking().getPeriodTicks() + 128,
				"one admitted maximum-display singleton must finish with the minimum byte/memory settings");
			f.assertCount(third, 7);
			assertEquals(InventoryLimits.MAX_DISPLAY_BYTES,
				ByteBufUtil.utf8Bytes(Objects.requireNonNull(f.client.tracking(third).entries().getFirst().displayJson())));
			f.assertDistinctPhysicalSources();
		}
	}

	private static MarkerId startTracking(MultiVaultFixture fixture, int vault) {
		var preview = fixture.open(vault); fixture.awaitRow(preview); return fixture.select(preview);
	}
	private static int exhaustProbes(MultiVaultFixture fixture) {
		for (int admitted = 0; admitted <= 256; admitted++) {
			InventoryDecision<Boolean> outcome = fixture.runtime.preflightDecision(() -> Optional.of(true));
			if (outcome.accepted()) continue;
			assertTrue(outcome.deferred()); assertEquals(InventoryFailureReason.PROBE_LIMIT, outcome.reason()); return admitted;
		}
		throw new AssertionError("same-tick ingress illegally reset or bypassed the 256-probe ledger");
	}
	private static long expected(int vault) {
		// Independent arithmetic oracle for the native fixture's 1620 resident counts.
		return SLOTS * (3_000_003L + vault * 10_000L) + (long) SLOTS * (SLOTS - 1) / 2;
	}
	private static void expireAndRelease(MultiVaultFixture fixture, List<MarkerId> markers) {
		long retained = fixture.runtime.memory().retained();
		long expiry = markers.stream().mapToLong(marker -> fixture.originalExpiry.get(marker)).max().orElseThrow();
		fixture.tick = expiry - 1; fixture.next();
		assertTrue(fixture.leases().isEmpty(), "original hard expiry releases every Tracking consumer without extending lifetime");
		assertTrue(fixture.runtime.memory().retained() < retained, "expiry must release journal/catalogue/evidence ownership");
		assertEquals(0, fixture.runtime.memory().reserved());
		long visits = fixture.visits;
		long metadata = fixture.world.metadataCalls;
		for (int i = 0; i < 2 * fixture.settings.getTracking().getPeriodTicks(); i++) fixture.next();
		assertEquals(visits, fixture.visits, "expired Vaults cannot scan, probe recovery or recreate consumers");
		assertEquals(metadata, fixture.world.metadataCalls, "expiry stops source metadata and recovery checks too");
	}
	private static ItemStack maximumNativeDisplay() {
		var emptyTag = new CompoundTag(); emptyTag.putString("payload", "");
		var stack = new ItemStack(Items.STONE, 7); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(emptyTag));
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, InventoryLimits.MAX_DISPLAY_BYTES);
		String envelope = Objects.requireNonNull(catalog.encode(stack).display().displayJson());
		int extra = InventoryLimits.MAX_DISPLAY_BYTES - ByteBufUtil.utf8Bytes(envelope);
		assertTrue(extra > 0);
		var tag = new CompoundTag(); tag.putString("payload", "\0".repeat(extra / 6) + "x".repeat(extra % 6));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		var encoded = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, InventoryLimits.MAX_DISPLAY_BYTES).encode(stack);
		assertFalse(encoded.display().aggregateRequired(), "native identity precondition, not an aggregate substitution");
		assertEquals(InventoryLimits.MAX_DISPLAY_BYTES, ByteBufUtil.utf8Bytes(Objects.requireNonNull(encoded.display().displayJson())));
		return stack;
	}
}
