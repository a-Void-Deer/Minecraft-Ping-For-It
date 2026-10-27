package nx.pingwheel.common.presentation.inventory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime oracles: the authorized unlimited mode keeps a finite 4 GiB guard
 * and its off-slider finite value; shared-source accounting requires live
 * charges to survive a cap reduction and admission to precede source callbacks.
 * Large tickets model retained cost only: no correspondingly large data exists.
 */
@NotNullByDefault
class InventoryMemoryBudgetIntegrationTest {

	private static final long MIB = 1024L * 1024L;
	private static final long GIB = 1024L * MIB;
	private static final Target.BlockTarget TARGET =
		new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");

	@Test
	void unlimitedAdvanceAdmitsBeyondSignedIntButNotBeyondGuardAndRestoresFiniteValue() {
		var settings = InventorySettings.serverDefaults();
		settings.setPendingMemoryMiB(IntLimit.unlimited(65));
		settings.validate();

		try (var runtime = new InventoryRuntime(input -> {
			throw new AssertionError("memory-only admission must not resolve a source");
		}, 16L * MIB)) {
			runtime.advance(0, settings);
			var memory = runtime.memory();
			assertEquals(4L * GIB, memory.cap(), "advance must install the unlimited guard, not the saved finite cap");
			assertTrue(Objects.requireNonNull(settings.getPendingMemoryMiB()).isUnlimited());
			assertEquals(65, Objects.requireNonNull(settings.getPendingMemoryMiB()).getValue());

			try (var retained = memory.tryReserve(3L * GIB).orElseThrow()) {
				assertEquals(3L * GIB, memory.reserved(), "reservation accounting must not narrow to int");
				retained.commit(3L * GIB);
				assertEquals(3L * GIB, memory.retained());
				assertEquals(0L, memory.reserved());
				assertEquals(GIB, memory.remaining());

				try (var remainder = memory.tryReserve(GIB).orElseThrow()) {
					assertEquals(0L, memory.remaining(), "committed and outstanding costs share the same finite guard");
					assertTrue(memory.tryReserve(1L).isEmpty(), "unlimited cannot admit one byte beyond its internal guard");
					assertEquals(3L * GIB, memory.retained());
					assertEquals(GIB, memory.reserved(), "a refused reservation must not change outstanding cost");
				}
				assertEquals(GIB, memory.remaining());
			}
			assertEquals(0L, memory.retained());
			assertEquals(0L, memory.reserved());

			Objects.requireNonNull(settings.getPendingMemoryMiB()).setUnlimited(false);
			settings.validate();
			runtime.advance(0, settings);
			assertSame(memory, runtime.memory());
			assertEquals(65, Objects.requireNonNull(settings.getPendingMemoryMiB()).getValue(),
				"mode changes must not snap a legal non-slider finite value");
			assertFalse(Objects.requireNonNull(settings.getPendingMemoryMiB()).isUnlimited());
			assertEquals(65L * MIB, memory.cap(), "the saved finite value must affect admission even within the same tick");
			try (var finite = memory.tryReserve(65L * MIB).orElseThrow()) {
				assertEquals(0L, memory.remaining());
				assertTrue(memory.tryReserve(1L).isEmpty());
			}
			assertEquals(65L * MIB, memory.remaining());
		}
	}

	@Test
	void loweringUnlimitedBudgetDefersPreviewAndTrackingBeforeSourceCallbacksAndReleaseResumesBoth() {
		var settings = InventorySettings.serverDefaults();
		settings.setPendingMemoryMiB(IntLimit.unlimited(65));
		settings.setPhysicalSlotsPerTick(IntLimit.finite(16));
		settings.setDisplayByteMultiplier(BigDecimal.ONE);
		Objects.requireNonNull(settings.getPreview()).setMaxSlotsPerClient(IntLimit.finite(16));
		Objects.requireNonNull(settings.getPreview()).setMaxSlotsServer(IntLimit.finite(16));
		Objects.requireNonNull(settings.getTracking()).setMaxSlotsPerTarget(IntLimit.finite(16));
		Objects.requireNonNull(settings.getTracking()).setMaxSlotsServer(IntLimit.finite(128));
		settings.validate();

		var owner = new UUID(97, 1);
		var input = new InventorySourceInput(TARGET, owner, BlockFace.NORTH);
		var source = new RecordingSource(input, Math.toIntExact(settings.effectiveDisplayBytes()));
		var runtime = new InventoryRuntime((resolvedInput, displayBytes) -> {
			source.resolutions++;
			return Optional.of(source);
		}, 16L * MIB);
		var memory = runtime.memory();

		try (runtime) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var tracking = runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			long consumerCost = memory.retained();
			assertTrue(consumerCost > 0L);

			try (var retained = memory.tryReserve(3L * GIB).orElseThrow()) {
				retained.commit(3L * GIB);
				long liveCost = consumerCost + 3L * GIB;
				settings.setPendingMemoryMiB(IntLimit.finite(16));
				settings.validate();
				runtime.advance(1, settings);

				assertSame(memory, runtime.memory(), "reconfiguration must update, not replace, the live ledger");
				assertEquals(16L * MIB, memory.cap());
				assertEquals(liveCost, memory.retained(), "lowering the cap must not discard existing charges");
				assertEquals(16L * MIB - liveCost, memory.remaining(), "over-cap remaining bytes must stay negative");
				assertTrue(runtime.step(preview, 1).isEmpty());
				assertTrue(runtime.step(tracking, 1).isEmpty());
				assertEquals(0, source.resolutions, "both consumer domains must defer before source resolution");
				assertEquals(0, source.validations, "memory pressure is not a source-validity probe");
				assertEquals(0, source.reads, "the source read preceding production domain encoding must not run");
				assertEquals(0, source.closes, "no source handle was acquired by a deferred attempt");
				assertFalse(preview.invalidated());
				assertFalse(tracking.invalidated());
				assertEquals(liveCost, memory.retained());
				assertEquals(0L, memory.reserved(), "denied attempts must leave no extra workspace reservation");
				assertEquals(16L * MIB - liveCost, memory.remaining());
			}

			assertEquals(consumerCost, memory.retained(), "closing the old ticket releases exactly its still-accounted cost");
			assertEquals(16L * MIB - consumerCost, memory.remaining());
			assertEquals(16L * MIB, memory.cap(), "recovery must not require raising the cap or replacing the runtime");
			assertObservedStone(runtime.step(tracking, 1).orElseThrow());
			assertEquals(1, source.resolutions);
			assertEquals(1, source.reads);
			assertTrue(source.validations > 0, "admitted work must still validate its source");
			assertObservedStone(runtime.step(preview, 1).orElseThrow());
			assertEquals(1, source.resolutions, "preview reuses the admitted same-binding tracking observation");
			assertEquals(1, source.reads, "release restores both consumers without duplicating the physical read");
			assertEquals(0L, memory.reserved());
		}

		assertEquals(1, source.closes, "the shared source closes once when its final consumer leaves");
		assertEquals(0L, memory.retained(), "consumer, round, page and logical-progress charges must all close");
		assertEquals(0L, memory.reserved());
	}

	private static void assertObservedStone(InventoryRuntime.Observation observation) {
		assertEquals(CaptureResult.Availability.READABLE, observation.result().availability());
		assertEquals(CaptureResult.Completeness.COMPLETE, observation.result().completeness());
		assertEquals(1, observation.slots().size());
		var item = Objects.requireNonNull(observation.slots().getFirst());
		assertEquals("minecraft:stone", item.key().itemId());
		assertEquals("plain", item.key().componentsKey());
		assertEquals(7L, item.count());
	}

	/** A single live slot with no source-owned catalogue, snapshot or parser input. */
	@NotNullByDefault
	private static final class RecordingSource implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		private final int displayBytes;
		private int resolutions;
		private int validations;
		private int reads;
		private int closes;

		private RecordingSource(InventorySourceInput input, int displayBytes) {
			this.input = input;
			this.displayBytes = displayBytes;
		}

		@Override public SourceKey key() {
			return new SourceKey("test", "inventory", "memory-budget", input.viewKey());
		}
		@Override public boolean valid() { validations++; return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 1; }
		@Override public int displayBytes() { return displayBytes; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.Item read(int slot) {
			assertEquals(0, slot);
			reads++;
			return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), 7L, "stone", null, false);
		}
		@Override public void close() { closes++; }
	}
}
