package nx.pingwheel.common.presentation.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Backend to Publisher to wire to ClientInventory coverage for a terminal
 * Preview invalidation that is still deferred when a same-epoch presentation
 * view is rebuilt. The rebuilt context must keep the terminal INVALID control
 * until handoff instead of turning the request into INCOMPLETE/loading state.
 */
@NotNullByDefault
class InventoryTerminalPreviewResetTest {
	private static final UUID A = InventoryBackendTest.A;
	private static final UUID B = InventoryBackendTest.B;
	private static final String TARGET_TYPE = "entity_block";
	private static final @Unmodifiable List<UUID> FILLERS = List.of(
		new UUID(10, 1), new UUID(10, 2), new UUID(10, 3), new UUID(10, 4),
		new UUID(10, 5), new UUID(10, 6), new UUID(10, 7), new UUID(10, 8),
		new UUID(10, 9), new UUID(10, 10), new UUID(10, 11), new UUID(10, 12),
		new UUID(10, 13), new UUID(10, 14), new UUID(10, 15), new UUID(10, 16));

	private static InventoryBackend.Policy policy(long view) {
		return new InventoryBackend.Policy(100, view, Set.of(TARGET_TYPE));
	}

	private static void configureActivePreview(Fixture fixture) {
		// InventoryLimits.MAX_PERIOD_TICKS is 10. All transport/reset paths stay in
		// period zero; the byte-budget case waits through later full periods.
		fixture.settings.getPreview().setPeriodTicks(InventoryLimits.MAX_PERIOD_TICKS);
		fixture.settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
	}

	@Test
	void transportDeferredInvalidSurvivesRepeatedSameEpochResetsAndNormalTickRetry() {
		try (Fixture f = new Fixture(4)) {
			configureActivePreview(f);
			Endpoint endpoint = new Endpoint(A);
			f.hello(A, 0);
			endpoint.drain(f.host);
			long request = endpoint.open();
			f.backend.handle(A, endpoint.last(InventoryC2SPacket.Kind.OPEN), 0, f.settings);
			f.tick(0);
			endpoint.drain(f.host);

			assertFalse(endpoint.client.preview(request).entries().isEmpty(), "the real Preview row must be displayed");
			assertFalse(endpoint.client.selectable(request).isEmpty(), "the displayed row must be selectable");

			f.sourceValid.set(false);
			f.host.failInvalidPreviewSend = true;
			f.tick(1);
			endpoint.drain(f.host);
			assertFalse(endpoint.client.selectable(request).isEmpty(), "a deferred INVALID leaves the old rows visible");
			int reads = f.reads.get();
			int validations = f.validations.get();

			// Rebuild the same presentation epoch twice while the terminal control
			// is still waiting for transport. The retired source must not be probed.
			for (long view : new long[] {2, 3}) {
				f.host.policies.put(A, policy(view));
				endpoint.reset(view);
				f.hello(A, view);
				endpoint.drain(f.host);
			}

			f.host.failInvalidPreviewSend = false;
			f.tick(4);
			endpoint.drain(f.host);

			var rebuilt = endpoint.client.preview(request);
			assertTrue(rebuilt.invalid(), "the rebuilt terminal request must receive INVALID, not INCOMPLETE");
			assertEquals(InventoryS2CPacket.Status.INVALID, rebuilt.status());
			assertTrue(endpoint.client.selectable(request).isEmpty(), "stale retained rows are no longer selectable");
			assertEquals(reads, f.reads.get(), "terminal retry performs no new source read");
			assertEquals(validations, f.validations.get(), "terminal retry performs no new source validation");

			InventoryC2SPacket close = endpoint.close(request);
			f.backend.handle(A, close, 5, f.settings);
			assertFalse(endpoint.client.hasPreview(request));
			assertEquals(0, f.backend.runtime().memory().reserved());
			assertTrue(f.closes.get() > 0, "closing the terminal request releases its source");
		}
	}

	@Test
	void byteBudgetDeferredInvalidSurvivesSameEpochViewResetAndRetriesWhenBudgetReopens() {
		try (Fixture f = new Fixture(4)) {
			configureActivePreview(f);
			f.settings.getPreview().setMaxTargetsPerClient(IntLimit.finite(4));
			// A 2x client cap keeps the publisher's data reserve (640 bytes) below
			// the 4 KiB minimum global budget; B's real 2.6 KiB display then leaves
			// only the control reserve for this preview period.
			f.settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(BigDecimal.valueOf(2)));
			f.settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.125")));

			Endpoint endpoint = new Endpoint(A);
			f.hello(A, 0);
			endpoint.drain(f.host);
			long request = endpoint.open();
			f.backend.handle(A, endpoint.last(InventoryC2SPacket.Kind.OPEN), 0, f.settings);
			f.tick(0);
			endpoint.drain(f.host);
			assertFalse(endpoint.client.selectable(request).isEmpty());

			// B's large retained display consumes almost the whole preview global
			// period budget. Its own frame must still be handed off.
			f.hello(B, 0);
			f.open(B, 1, 1);
			for (long now = 1; now <= 3 && !f.hasPreviewData(B, 1); now++) f.tick(now);
			assertTrue(f.hasPreviewData(B, 1), () -> "the fixture must consume the byte budget with a real Preview frame: "
				+ f.packetSummary(0));

			// Extra negotiation controls consume the remaining control reserve, so
			// the terminal INVALID cannot be written in this period.
			for (UUID filler : FILLERS) {
				f.host.policies.put(filler, policy(1));
				f.hello(filler, 3);
			}

			int beforeInvalid = f.host.packets.size();
			f.sourceValid.set(false);
			f.tick(4);
			assertFalse(f.hasPreviewStatus(A, request, InventoryS2CPacket.Status.INVALID, beforeInvalid),
				"the fixture must defer the terminal INVALID under the byte budget");
			endpoint.drain(f.host);
			assertFalse(endpoint.client.selectable(request).isEmpty(), "the byte-budget-deferred INVALID leaves the old rows selectable");
			int reads = f.reads.get();
			int validations = f.validations.get();

			f.host.policies.put(A, policy(2));
			endpoint.reset(2);
			f.hello(A, 5);
			endpoint.drain(f.host);

			// The preview period reopens the byte budget; the retained terminal
			// INVALID must be retried without a source probe or a fresh scan.
			f.tick(20);
			endpoint.drain(f.host);

			var rebuilt = endpoint.client.preview(request);
			assertTrue(rebuilt.invalid(), "the terminal request must remain INVALID after budget deferral");
			assertEquals(InventoryS2CPacket.Status.INVALID, rebuilt.status());
			assertTrue(endpoint.client.selectable(request).isEmpty());
			assertEquals(reads, f.reads.get(), "terminal retry performs no new source read");
			assertEquals(validations, f.validations.get(), "terminal retry performs no new source validation");
		}
	}

	@Test
	void retainedMemoryRegistrationRefusalRetriesTerminalInvalidAfterSameEpochResets() {
		try (Fixture f = new Fixture(4)) {
			configureActivePreview(f);
			Endpoint endpoint = new Endpoint(A);
			f.hello(A, 0);
			endpoint.drain(f.host);
			long request = endpoint.open();
			f.backend.handle(A, endpoint.last(InventoryC2SPacket.Kind.OPEN), 0, f.settings);
			f.tick(0);
			endpoint.drain(f.host);
			assertFalse(endpoint.client.selectable(request).isEmpty());

			var memory = f.backend.runtime().memory();
			long originalCap = memory.cap();
			f.sourceValid.set(false);
			// Reserve the source validation first, then leave only a control-sized
			// remainder while holding one real ledger padding ticket. The terminal
			// context registration must be refused on the invalidating tick and on
			// the later same-epoch rebaselines.
			f.validationHook.set(() -> {
				long free = memory.remaining();
				long keep = Math.max(0L, free - 512L * 1024L);
				var padding = memory.tryReserve(keep).orElseThrow();
				padding.commit(keep);
				f.paddings.add(padding);
				memory.setCap(0);
			});
			f.tick(1);
			assertTrue(memory.cap() < originalCap, "the validation hook must exhaust retained-memory headroom");
			assertFalse(f.hasPreviewStatus(A, request, InventoryS2CPacket.Status.INVALID, 0),
				"the fixture must make the initial terminal context registration fail");
			assertFalse(endpoint.client.selectable(request).isEmpty(), "the old rows remain until INVALID is handed off");
			int reads = f.reads.get();
			int validations = f.validations.get();

			// Invalidation closes part of the old retained state. Re-pin the remaining
			// headroom with a second real ledger ticket so the next advance still has
			// less than one publisher registration available.
			memory.setCap(originalCap);
			long free = memory.remaining();
			long keep = Math.max(0L, free - 512L * 1024L);
			var extra = memory.tryReserve(keep).orElseThrow();
			extra.commit(keep);
			f.paddings.add(extra);

			// The terminal branch retries the failed context registration on a normal
			// tick. A later advance restores the configured cap but the committed
			// padding still leaves less than one publisher registration available.
			f.tick(2);
			for (long view : new long[] {2, 3}) {
				f.host.policies.put(A, policy(view));
				endpoint.reset(view);
				f.hello(A, view + 1);
				endpoint.drain(f.host);
			}
			assertTrue(f.sink.sawProjectionMemoryDelay(), () -> "the retry must defer on retained-memory admission: "
				+ f.sink.events() + " cap=" + memory.cap() + " retained=" + memory.retained()
				+ " reserved=" + memory.reserved());
			assertFalse(f.hasPreviewStatus(A, request, InventoryS2CPacket.Status.INVALID, 0),
				"registration refusal must keep the terminal control pending across resets");

			for (RetainedMemoryLedger.Ticket padding : f.paddings) padding.close();
			f.paddings.clear();
			memory.setCap(originalCap);
			f.tick(5);
			endpoint.drain(f.host);

			var rebuilt = endpoint.client.preview(request);
			assertTrue(rebuilt.invalid(), "the retried terminal request must receive INVALID");
			assertEquals(InventoryS2CPacket.Status.INVALID, rebuilt.status());
			assertTrue(endpoint.client.selectable(request).isEmpty());
			assertEquals(reads, f.reads.get(), "registration retry performs no new source read");
			assertEquals(validations, f.validations.get(), "registration retry performs no new source validation");

			InventoryC2SPacket close = endpoint.close(request);
			f.backend.handle(A, close, 6, f.settings);
			assertEquals(0, f.backend.runtime().memory().reserved());
			assertTrue(f.closes.get() > 0);
		}
	}

	@Test
	void closeBeforeDeferredInvalidHandoffRetiresTheTerminalControl() {
		try (Fixture f = new Fixture(4)) {
			configureActivePreview(f);
			Endpoint endpoint = new Endpoint(A);
			f.hello(A, 0);
			endpoint.drain(f.host);
			long request = endpoint.open();
			f.backend.handle(A, endpoint.last(InventoryC2SPacket.Kind.OPEN), 0, f.settings);
			f.tick(0);
			endpoint.drain(f.host);

			f.sourceValid.set(false);
			f.host.failInvalidPreviewSend = true;
			f.tick(1);
			f.host.policies.put(A, policy(2));
			endpoint.reset(2);
			f.hello(A, 2);
			endpoint.drain(f.host);

			InventoryC2SPacket close = endpoint.close(request);
			f.backend.handle(A, close, 3, f.settings);
			int start = f.host.packets.size();
			f.host.failInvalidPreviewSend = false;
			f.tick(4);
			endpoint.drain(f.host);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
				&& packet.requestId() == request), "a closed request cannot emit its retired terminal control");
			assertEquals(0, f.backend.runtime().memory().reserved());
			assertTrue(f.closes.get() > 0);
		}
	}

	@Test
	void completedPreviewRebaselineKeepsTheClosedValueAndSelectableRow() {
		try (Fixture f = new Fixture(1)) {
			configureActivePreview(f);
			Endpoint endpoint = new Endpoint(A);
			f.hello(A, 0);
			endpoint.drain(f.host);
			long request = endpoint.open();
			f.backend.handle(A, endpoint.last(InventoryC2SPacket.Kind.OPEN), 0, f.settings);
			f.tick(0);
			endpoint.drain(f.host);

			var closed = endpoint.client.preview(request);
			assertTrue(closed.completeScan());
			assertFalse(closed.invalid());
			assertFalse(closed.entries().isEmpty());
			long closedCount = closed.entries().getFirst().count();

			f.host.policies.put(A, policy(2));
			endpoint.reset(2);
			f.hello(A, 1);
			endpoint.drain(f.host);
			f.tick(1);
			f.tick(2);
			endpoint.drain(f.host);

			var rebuilt = endpoint.client.preview(request);
			assertFalse(rebuilt.invalid(), "a non-terminal completed Preview stays valid across the view reset");
			assertNotEquals(InventoryS2CPacket.Status.INVALID, rebuilt.status());
			assertFalse(rebuilt.entries().isEmpty(), "the closed value is retained after rebaseline");
			assertEquals(closedCount, rebuilt.entries().getFirst().count());
			assertFalse(endpoint.client.selectable(request).isEmpty(), "the retained closed row stays selectable");
		}
	}

	@NotNullByDefault
	static final class Fixture implements AutoCloseable {
		final InventoryBackendTest.Host host = new InventoryBackendTest.Host();
		final InventorySettings settings = InventorySettings.serverDefaults();
		final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger validations = new AtomicInteger();
		final AtomicInteger closes = new AtomicInteger();
		final AtomicBoolean sourceValid = new AtomicBoolean(true);
		final java.util.concurrent.atomic.AtomicReference<Runnable> validationHook = new java.util.concurrent.atomic.AtomicReference<>();
		final List<RetainedMemoryLedger.Ticket> paddings = new ArrayList<>();
		final RecordingSink sink = new RecordingSink();
		final InventoryBackend backend;

		Fixture(int slots) {
			backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(
				new TestSource(input, reads, validations, closes, sourceValid, validationHook, slots)), 16_000_000), 1, sink);
		}

		void hello(UUID player, long now) {
			backend.handle(player, InventoryC2SPacket.hello(), now, settings);
		}

		void open(UUID player, long requestId, long now) {
			backend.handle(player, InventoryC2SPacket.open(host.epoch(player), 100, 1, requestId,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), now, settings);
		}

		void tick(long now) {
			backend.tick(now, settings);
		}

		boolean hasPreviewData(UUID player, long requestId) {
			for (int index = 0; index < host.packets.size(); index++) {
				InventoryS2CPacket packet = host.packets.get(index);
				if (host.recipients.get(index).equals(player) && packet.kind() == InventoryS2CPacket.Kind.PREVIEW
					&& packet.requestId() == requestId && !packet.entries().isEmpty()) return true;
			}
			return false;
		}

		boolean hasPreviewStatus(UUID player, long requestId, InventoryS2CPacket.Status status, int from) {
			for (int index = Math.max(0, from); index < host.packets.size(); index++) {
				InventoryS2CPacket packet = host.packets.get(index);
				if (host.recipients.get(index).equals(player) && packet.kind() == InventoryS2CPacket.Kind.PREVIEW
					&& packet.requestId() == requestId && packet.status() == status) return true;
			}
			return false;
		}

		String packetSummary(int from) {
			var rows = new ArrayList<String>();
			for (int index = Math.max(0, from); index < host.packets.size(); index++) {
				InventoryS2CPacket packet = host.packets.get(index);
				rows.add(host.recipients.get(index) + ":" + packet.kind() + ":req=" + packet.requestId() + ":entries=" + packet.entries().size()
					+ ":status=" + packet.status() + ":bytes=" + packet.encodedSize((int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes()));
			}
			return rows.toString();
		}



		@Override public void close() {
			backend.close();
		}
	}

	@NotNullByDefault
	static final class Endpoint {
		final List<IPacket> requests = new ArrayList<>();
		final ClientInventory client = new ClientInventory(requests::add);
		final UUID recipient;
		int cursor;

		Endpoint(UUID recipient) {
			this.recipient = recipient;
			client.presentationReset(100, 1);
		}

		void drain(InventoryBackendTest.Host host) {
			for (; cursor < host.packets.size(); cursor++) {
				if (host.recipients.get(cursor).equals(recipient)) client.accept(wire(host.packets.get(cursor)));
			}
		}

		void reset(long view) {
			client.presentationReset(100, view);
		}

		long open() {
			return client.open(InventoryRuntimeTest.TARGET, BlockFace.NORTH, TARGET_TYPE);
		}

		InventoryC2SPacket close(long requestId) {
			client.close(requestId);
			return last(InventoryC2SPacket.Kind.CLOSE);
		}

		InventoryC2SPacket last(InventoryC2SPacket.Kind kind) {
			for (int index = requests.size() - 1; index >= 0; index--) {
				if (requests.get(index) instanceof InventoryC2SPacket packet && packet.kind() == kind) return packet;
			}
			throw new AssertionError("missing " + kind + " request");
		}
	}

	@NotNullByDefault
	static final class RecordingSink implements BudgetDelaySink {
		private final AtomicBoolean projectionMemory = new AtomicBoolean();
		private final List<String> observed = new ArrayList<>();

		boolean sawProjectionMemoryDelay() {
			return projectionMemory.get();
		}

		List<String> events() {
			return List.copyOf(observed);
		}

		@Override public void observe(@Nullable DelayEvent event) {
			if (event == null) return;
			observed.add(event.module() + "/" + event.item().description() + "/" + event.newReasonBits());
			if (event.module() == Module.INVENTORY_PREVIEW
				&& (event.newReasonBits() & Reason.MEMORY.bit()) != 0
				&& event.item().description().contains("projection")) projectionMemory.set(true);
		}

		@Override public void state(@Nullable Module module, long cycleId, long serverTick, @Nullable DelayState state) {}

		@Override public void endCycle(@Nullable Module module, long cycleId, long serverTick) {}
	}

	@NotNullByDefault
	static final class TestSource extends InventoryRuntimeTest.Source {
		final AtomicInteger reads;
		final AtomicInteger validations;
		final AtomicInteger closes;
		final AtomicBoolean valid;
		final java.util.concurrent.atomic.AtomicReference<Runnable> validationHook;
		final int displayBytes;

		TestSource(InventorySourceInput input, AtomicInteger reads, AtomicInteger validations,
			AtomicInteger closes, AtomicBoolean valid,
			java.util.concurrent.atomic.AtomicReference<Runnable> validationHook, int slots) {
			super(input, reads, slots);
			this.reads = reads;
			this.validations = validations;
			this.closes = closes;
			this.valid = valid;
			this.validationHook = validationHook;
			this.displayBytes = input.readOwner().equals(B) ? 2000 : 96;
		}

		@Override public boolean valid() {
			validations.incrementAndGet();
			if (!valid.get()) {
				@org.jetbrains.annotations.Nullable Runnable hook = validationHook.getAndSet(null);
				if (hook != null) hook.run();
			}
			return valid.get();
		}

		@Override public InventoryDomainCodec.Item read(int slot) {
			reads.incrementAndGet();
			String display = "{\"id\":\"minecraft:stone\",\"pad\":\"" + "x".repeat(displayBytes) + "\"}";
			return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "slot-" + slot),
				7, "stone", display, false);
		}

		@Override public void close() {
			closes.incrementAndGet();
		}
	}

	private static InventoryS2CPacket wire(InventoryS2CPacket packet) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buffer);
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buffer);
			assertFalse(decoded.isCorrupt());
			assertFalse(buffer.isReadable());
			return decoded;
		} finally {
			buffer.release();
		}
	}
}
