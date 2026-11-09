package nx.pingwheel.common.presentation.inventory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Backend fan-out: one detached projection encode per identical
 * recipient value inside one publication operation. The provisional seed and
 * the completed cut are separate operations, so each is observed after it is
 * actually published instead of by guessing a tick.
 */
@NotNullByDefault
class InventoryFanoutEncodingReuseTest {
	private static final UUID A = InventoryPublicationBackendFixture.A;
	private static final UUID B = InventoryPublicationBackendFixture.B;
	/** Far more slots than one physical-slot tick, so seed and completion are distinct objects. */
	private static final int SLOTS = 64;
	private static final int SEED_LIMIT = 40, COMPLETE_LIMIT = 240;

	@BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test void sameTrackingProjectionEncodesOnceForTwoRecipients() {
		var settings = settings();
		var reads = new AtomicInteger();
		InventoryRuntime.SourceResolver resolver = (input, cap) ->
			Optional.of(new InventoryRuntimeTest.Source(InventoryRuntimeTest.input(A), reads, SLOTS));
		try (var f = new InventoryPublicationBackendFixture(resolver, settings,
			TargetKey.from(InventoryRuntimeTest.TARGET))) {
			MarkerId marker = select(f, settings);
			var encodes = new AtomicInteger();
			f.backend.encodedFragmentProbe(ignored -> encodes.incrementAndGet());
			int tick = 2;
			// The first actual tracking publication is the provisional selected seed.
			while (tick < SEED_LIMIT && encodes.get() == 0) f.backend.tick(tick++, settings);
			assertEquals(1, encodes.get(), "both recipients share one detached provisional encoding");
			for (UUID recipient : new UUID[] {A, B}) {
				var seed = f.clients.get(recipient).tracking(marker);
				assertFalse(seed.complete());
				assertEquals(1, seed.entries().size());
				assertEquals(7L, seed.entries().getFirst().count());
			}
			encodes.set(0);
			// The completed cut is a different publication revision and must encode once too.
			while (tick < COMPLETE_LIMIT && !complete(f, marker)) f.backend.tick(tick++, settings);
			assertComplete(f, marker);
			assertEquals(1, encodes.get(), "both recipients share one detached encoding of the completed cut");
		}
	}

	@Test void differentFoldProjectionUsesASeparateEncoding() throws ReflectiveOperationException {
		var settings = settings();
		var reads = new AtomicInteger();
		InventoryRuntime.SourceResolver resolver = (input, cap) ->
			Optional.of(new InventoryRuntimeTest.Source(InventoryRuntimeTest.input(A), reads, SLOTS));
		try (var f = new InventoryPublicationBackendFixture(resolver, settings,
			TargetKey.from(InventoryRuntimeTest.TARGET))) {
			MarkerId marker = select(f, settings);
			setRecipientFolded(f.backend, marker, B);
			var encodes = new AtomicInteger();
			f.backend.encodedFragmentProbe(ignored -> encodes.incrementAndGet());
			int tick = 2;
			while (tick < SEED_LIMIT && encodes.get() < 2) f.backend.tick(tick++, settings);
			assertEquals(2, encodes.get(), "exact and aggregate seed projections must not share one encoding");
			encodes.set(0);
			while (tick < COMPLETE_LIMIT && !complete(f.clients.get(A), marker)) f.backend.tick(tick++, settings);
			assertTrue(complete(f.clients.get(A), marker), "the exact recipient completes its own cut");
			// B's forced aggregate lane is only an encoding/fold contrast here: its
			// whole aggregate handoff is not asserted as a client completion.
			assertEquals(2, encodes.get(), "exact and aggregate completed projections must not share one encoding");
		}
	}

	@Test void revokedRecipientDoesNotBorrowTheOtherRecipientsProofOrAdmission() {
		var settings = settings();
		var reads = new AtomicInteger();
		var source = new MutableSource(InventoryRuntimeTest.input(A), reads, SLOTS, 7L);
		InventoryRuntime.SourceResolver resolver = (input, cap) -> Optional.of(source);
		try (var f = new InventoryPublicationBackendFixture(resolver, settings,
			TargetKey.from(InventoryRuntimeTest.TARGET))) {
			MarkerId marker = select(f, settings);
			var encodes = new AtomicInteger();
			f.backend.encodedFragmentProbe(ignored -> encodes.incrementAndGet());
			int tick = 2;
			while (tick < COMPLETE_LIMIT && !complete(f, marker)) f.backend.tick(tick++, settings);
			assertComplete(f, marker);
			assertEquals(7L, count(f.clients.get(A), marker));
			assertEquals(7L, count(f.clients.get(B), marker));
			// Refuse only B at the final publication admission, before this new cut
			// is delivered; A's source grant and the already closed B display stay.
			f.blocked = (context, packet) -> context.recipient().equals(B)
				&& InventorySyncPublisher.requiresSource(packet);
			source.amount = 9L;
			encodes.set(0);
			while (tick < 2 * COMPLETE_LIMIT && count(f.clients.get(A), marker) == 7L)
				f.backend.tick(tick++, settings);
			assertEquals(9L, count(f.clients.get(A), marker), "authorized recipient completes the new cut");
			assertEquals(7L, count(f.clients.get(B), marker), "blocked recipient keeps its previously delivered closed value");
			assertTrue(f.clients.get(B).tracking(marker).complete(),
				"an old closed display is retained, not withdrawn by a new-cut refusal");
			assertEquals(1, encodes.get(), "the shared bytes do not substitute for B's proof or delivery admission");
		}
	}

	private static long count(ClientInventory client, MarkerId marker) {
		return client.tracking(marker).entries().getFirst().count();
	}

	private static MarkerId select(InventoryPublicationBackendFixture fixture, InventorySettings settings) {
		long request = fixture.open(A, InventoryRuntimeTest.TARGET, BlockFace.NORTH, 0);
		fixture.backend.tick(1, settings);
		MarkerId marker = fixture.select(A, request, 1);
		fixture.closePreview(A, request, 1);
		return marker;
	}

	private static boolean complete(InventoryPublicationBackendFixture fixture, MarkerId marker) {
		return complete(fixture.clients.get(A), marker) && complete(fixture.clients.get(B), marker);
	}

	private static boolean complete(ClientInventory client, MarkerId marker) {
		return client.tracking(marker).complete();
	}

	private static void assertComplete(InventoryPublicationBackendFixture fixture, MarkerId marker) {
		assertTrue(complete(fixture.clients.get(A), marker));
		assertTrue(complete(fixture.clients.get(B), marker));
	}

	@SuppressWarnings("unchecked")
	private static void setRecipientFolded(InventoryBackend backend, MarkerId marker, UUID recipient)
		throws ReflectiveOperationException {
		var tracking = InventoryBackend.class.getDeclaredField("tracking"); tracking.setAccessible(true);
		Object lease = java.util.Objects.requireNonNull(
			((java.util.Map<?, ?>) tracking.get(backend)).get(marker));
		var recipients = lease.getClass().getDeclaredField("recipients"); recipients.setAccessible(true);
		Object state = java.util.Objects.requireNonNull(
			((java.util.Map<?, ?>) recipients.get(lease)).get(recipient));
		var folded = state.getClass().getDeclaredField("folded"); folded.setAccessible(true);
		folded.setBoolean(state, true);
	}

	/** Fixture source with one mutable reported quantity, so two completed cuts are distinguishable. */
	@NotNullByDefault
	private static final class MutableSource implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		private final AtomicInteger reads;
		private final int size;
		private long amount;

		MutableSource(InventorySourceInput input, AtomicInteger reads, int size, long amount) {
			this.input = input; this.reads = reads; this.size = size; this.amount = amount;
		}

		@Override public SourceKey key() { return new SourceKey("test", "inventory", "same-container", input.viewKey()); }
		@Override public boolean valid() { return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return size; }
		@Override public int displayBytes() { return InventoryDomainCodec.MAX_DISPLAY_BYTES; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.@Nullable Item read(int slot) {
			reads.incrementAndGet();
			return slot == 0 ? InventoryRuntimeTest.item(amount) : null;
		}
	}

	private static InventorySettings settings() {
		var settings = InventorySettings.serverDefaults();
		settings.setPendingMemoryMiB(256);
		settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		settings.getPreview().setPeriodTicks(1);
		settings.getTracking().setPeriodTicks(10);
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(SLOTS));
		settings.getPreview().setMaxSlotsServer(IntLimit.finite(SLOTS));
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(SLOTS));
		settings.getTracking().setMaxSlotsServer(IntLimit.finite(SLOTS));
		settings.validate();
		return settings;
	}
}
