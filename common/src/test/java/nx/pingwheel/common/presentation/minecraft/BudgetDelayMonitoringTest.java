package nx.pingwheel.common.presentation.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.PresentationDiagnosticCapture;
import org.apache.logging.log4j.Level;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Quantity;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;

import static org.junit.jupiter.api.Assertions.*;

/** Production coordinator with narrow ports; never creates a Minecraft server or boots registries. */
class BudgetDelayMonitoringTest {

	private static final Module PRESENTATION = Module.PRESENTATION;

	@Test void productionDiagnosticsReportEveryAdjacentClosedCycleWithoutAnotherWindow() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var monitor = new nx.pingwheel.common.presentation.budget.BudgetDelayMonitor(BudgetDelayMonitoring::reportSummary, modules -> false);
			for (long cycle = 0; cycle < 2; cycle++) {
				monitor.observe(event(PRESENTATION, cycle, cycle, "pending", Quantity.of(Unit.REQUEST_ADAPTER, 1), Reason.SOURCE_WORK.bit()));
				monitor.state(PRESENTATION, cycle, cycle, DelayState.DELAYED);
				monitor.endCycle(PRESENTATION, cycle, cycle);
				monitor.endCycle(PRESENTATION, cycle, cycle);
			}
			assertEquals(2, capture.events().size());
			assertTrue(capture.events().stream().allMatch(event -> event.getLevel() == Level.WARN && event.getThrown() == null));
			assertTrue(capture.messages().get(0).contains("cycle=0")); assertTrue(capture.messages().get(1).contains("cycle=1"));
		}
	}

	private static final class Player implements BudgetDelayMonitoring.Recipient {
		final UUID id;
		int permission;
		int permissionChecks, sends;
		@Nullable Throwable failure;
		final List<Set<Module>> notices = new ArrayList<>();
		Player(UUID id, int permission) { this.id = id; this.permission = permission; }
		Player(int permission) { this(UUID.randomUUID(), permission); }
		@Override public UUID id() { return id; }
		@Override public boolean hasPermissions(int level) {
			assertEquals(3, level, "literal vanilla level 3, not settings hints or presentation permission providers");
			permissionChecks++;
			return permission >= level;
		}
		@Override public void sendSystemMessage(Set<Module> modules) {
			sends++;
			if (failure != null) failWith(failure);
			notices.add(Set.copyOf(modules));
		}
	}

	private static final class Server {
		boolean enabled = true, dedicated = true;
		@Nullable UUID host;
		long tick;
		int flagReads, hostReads, recipientReads;
		@Nullable Throwable flagFailure, recipientFailure;
		boolean failIterationAfterFirst;
		final List<Player> players = new ArrayList<>();
	}

	private static final class Fixture {
		@Nullable Throwable clockFailure, warningFailure;
		final List<String> warnings = new ArrayList<>();
		final BudgetDelayMonitoring.Coordinator<Server> coordinator = new BudgetDelayMonitoring.Coordinator<>(
			line -> { if (warningFailure != null) failWith(warningFailure); warnings.add(line); },
			new BudgetDelayMonitoring.ServerPort<>() {
				@Override public long serverTick(Server server) {
					if (clockFailure != null) failWith(clockFailure);
					return server.tick;
				}
				@Override public boolean notificationsEnabled(Server server) {
					server.flagReads++;
					if (server.flagFailure != null) failWith(server.flagFailure);
					return server.enabled;
				}
				@Override public boolean dedicated(Server server) { return server.dedicated; }
				@Override public @Nullable UUID hostId(Server server) { server.hostReads++; return server.host; }
				@Override public Iterable<? extends BudgetDelayMonitoring.Recipient> recipients(Server server) {
					server.recipientReads++;
					if (server.recipientFailure != null) failWith(server.recipientFailure);
					if (server.failIterationAfterFirst) return () -> new java.util.Iterator<BudgetDelayMonitoring.Recipient>() {
						int index;
						@Override public boolean hasNext() {
							if (index > 0) throw new IllegalStateException("recipient iteration");
							return !server.players.isEmpty();
						}
						@Override public BudgetDelayMonitoring.Recipient next() { return server.players.get(index++); }
					};
					return server.players;
				}
			});

		void tick(Server server, long cycle, long at) {
			server.tick = at;
			BudgetDelaySink sink = coordinator.sink(server);
			sink.observe(event(PRESENTATION, cycle, at, "marker/adapter", Quantity.unknown(), Reason.SOURCE_WORK.bit()));
			sink.state(PRESENTATION, cycle, at, DelayState.DELAYED);
			sink.endCycle(PRESENTATION, cycle, at);
			coordinator.finishTick(server);
		}
	}

	private static DelayEvent event(Module module, long cycle, long now, String identity, Quantity quantity, int reasons) {
		return new DelayEvent(module, cycle, now, new WorkItem(identity, "dimension=example:world pos=1,2,3"), true, reasons, quantity);
	}

	private static void failWith(Throwable failure) {
		if (failure instanceof RuntimeException runtime) throw runtime;
		if (failure instanceof LinkageError linkage) throw linkage;
		throw (Error) failure;
	}

	@Test void dedicatedLevelTwoIsExcludedAndLevelThreeOrFourGetOneVanillaHandoff() {
		Fixture f = new Fixture();
		Server server = new Server();
		Player hostWithNoPermission = new Player(0), level2 = new Player(2), level3 = new Player(3), level4 = new Player(4);
		server.host = hostWithNoPermission.id;
		server.players.addAll(List.of(hostWithNoPermission, level2, level3, level4));
		f.tick(server, 0, 0);
		f.tick(server, 1, 20);
		assertEquals(0, hostWithNoPermission.sends, "dedicated has no host exception");
		assertEquals(0, level2.sends);
		assertEquals(1, level3.sends);
		assertEquals(1, level4.sends);
		assertEquals(0, server.hostReads, "dedicated never even queries a host identity");
		assertEquals(1, level3.permissionChecks);
		assertEquals(List.of(Set.of(PRESENTATION)), level4.notices);
	}

	@Test void integratedAuthoritativeHostUuidQualifiesAndDuplicateEligibilitySendsOnce() {
		Fixture f = new Fixture();
		Server server = new Server();
		server.dedicated = false;
		UUID hostId = UUID.randomUUID();
		Player host = new Player(hostId, 0), unrelated = new Player(2), operator = new Player(3);
		server.host = hostId;
		server.players.addAll(List.of(host, unrelated, operator));
		f.tick(server, 0, 0);
		f.tick(server, 1, 20);
		assertEquals(1, host.sends);
		assertEquals(0, unrelated.sends);
		assertEquals(1, operator.sends);
		host.permission = 4;
		f.tick(server, 2, 220);
		assertEquals(2, host.sends, "host plus permission is one OR predicate, not two sends");
		assertEquals(2, operator.sends);
		assertEquals(2, server.hostReads);
	}

	@Test void liveConfigAndRepeatedActivationKeepSameLifetimeAndCooldown() {
		Fixture f = new Fixture();
		Server server = new Server();
		Player operator = new Player(3);
		server.players.add(operator);
		server.enabled = false;
		BudgetDelaySink first = f.coordinator.sink(server);
		f.tick(server, 0, 0);
		f.tick(server, 1, 20);
		assertSame(first, f.coordinator.sink(server));
		assertEquals(2, f.warnings.size());
		assertEquals(0, server.recipientReads);
		server.enabled = true;
		f.tick(server, 2, 21);
		assertEquals(1, operator.sends);
		server.enabled = false;
		f.tick(server, 3, 40);
		server.enabled = true;
		f.tick(server, 4, 41);
		f.tick(server, 5, 220);
		assertEquals(1, operator.sends);
		f.tick(server, 6, 221);
		assertEquals(2, operator.sends);
		assertSame(first, f.coordinator.sink(server));
		assertEquals(7, server.flagReads, "notification flag is read live at each final flush");
	}

	@Test void replacementStopAndExplicitResetInvalidateOldSinksWithoutFlushingOldWorld() {
		Fixture f = new Fixture();
		Server old = new Server(), replacement = new Server();
		Player operator = new Player(3);
		replacement.players.add(operator);
		BudgetDelaySink oldSink = f.coordinator.sink(old);
		oldSink.observe(event(PRESENTATION, 900, 0, "old world", Quantity.unknown(), Reason.MEMORY.bit()));
		BudgetDelaySink newSink = f.coordinator.sink(replacement);
		assertNotSame(oldSink, newSink);
		oldSink.endCycle(PRESENTATION, 900, 40);
		oldSink.observe(event(PRESENTATION, 901, 40, "stale caller", Quantity.unknown(), Reason.MEMORY.bit()));
		oldSink.endCycle(PRESENTATION, 901, 40);
		f.coordinator.finishTick(old);
		assertTrue(f.warnings.isEmpty());
		assertEquals(0, old.flagReads);
		f.tick(replacement, 0, 40);
		f.coordinator.stopped(old);
		assertSame(newSink, f.coordinator.sink(replacement), "late stop for old server cannot reset a replacement");
		f.tick(replacement, 1, 60);
		assertEquals(1, operator.sends);
		f.coordinator.stopped(replacement);
		f.coordinator.finishTick(replacement);
		BudgetDelaySink restarted = f.coordinator.sink(replacement);
		assertNotSame(newSink, restarted);
		newSink.observe(event(PRESENTATION, 2, 61, "stopped caller", Quantity.unknown(), Reason.MEMORY.bit()));
		newSink.endCycle(PRESENTATION, 2, 61);
		assertEquals(2, f.warnings.size());
		f.tick(replacement, 0, 61);
		f.tick(replacement, 1, 81);
		assertEquals(2, operator.sends, "stopped lifetime cooldown was discarded");
		f.coordinator.reset();
		assertNotSame(restarted, f.coordinator.sink(replacement));
		assertSame(BudgetDelaySink.NONE, f.coordinator.sink(null));
	}

	@Test void noRecipientAndFailedRecipientDoNotConsumeCooldownAndOtherRecipientsContinue() {
		Fixture f = new Fixture();
		Server server = new Server();
		Player ineligible = new Player(2), failed = new Player(3), successful = new Player(4);
		server.players.add(ineligible);
		f.tick(server, 0, 0);
		f.tick(server, 1, 20);
		failed.failure = new NoClassDefFoundError("send");
		server.players.add(failed);
		f.tick(server, 2, 21);
		assertEquals(1, failed.sends);
		server.players.add(successful);
		f.tick(server, 3, 22);
		assertEquals(2, failed.sends);
		assertEquals(1, successful.sends, "one failed player cannot prevent the next eligible handoff");
		f.tick(server, 4, 23);
		assertEquals(1, successful.sends, "successful recipient consumes the global cooldown");
		assertEquals(0, ineligible.sends);
	}

	@Test void consolidatedModulesHaveNoTargetDataAndRetainExactLocaleKeys() {
		Fixture f = new Fixture();
		Server server = new Server();
		Player operator = new Player(3);
		server.players.add(operator);
		BudgetDelaySink sink = f.coordinator.sink(server);
		for (Module module : Module.values()) sink.state(module, 0, 0, DelayState.DELAYED);
		f.coordinator.finishTick(server);
		server.tick = 20;
		for (Module module : Module.values()) sink.state(module, 0, server.tick, DelayState.DELAYED);
		f.coordinator.finishTick(server);
		assertEquals(1, operator.sends);
		assertEquals(Set.of(PRESENTATION, Module.INVENTORY_PREVIEW, Module.INVENTORY_TRACKING), operator.notices.getFirst());
		Component notice = BudgetDelayMonitoring.notice(operator.notices.getFirst());
		TranslatableContents contents = assertInstanceOf(TranslatableContents.class, notice.getContents());
		assertEquals("message.pingforit.budget_delay", contents.getKey());
		assertEquals(1, contents.getArgs().length);
		Component list = assertInstanceOf(Component.class, contents.getArgs()[0]);
		assertEquals(List.of("message.pingforit.budget_delay.module.presentation",
			"message.pingforit.budget_delay.module.inventory_preview", "message.pingforit.budget_delay.module.inventory_tracking"),
			list.getSiblings().stream().map(Component::getContents).filter(TranslatableContents.class::isInstance)
				.map(TranslatableContents.class::cast).map(TranslatableContents::getKey).toList());
	}

	@Test void successfulHandoffStillConsumesCooldownIfLaterRecipientIterationFails() {
		Fixture f = new Fixture();
		Server server = new Server();
		Player operator = new Player(3);
		server.players.add(operator);
		server.failIterationAfterFirst = true;
		f.tick(server, 0, 0);
		assertDoesNotThrow(() -> f.tick(server, 1, 20));
		assertEquals(1, operator.sends);
		server.failIterationAfterFirst = false;
		f.tick(server, 2, 21);
		assertEquals(1, operator.sends, "the earlier successful handoff must not be erased by an iteration failure");
	}

	@Test void formatterReportsObservedCountsStagesAndSeparateUnitsWithTenSampleOverflow() {
		for (int total : List.of(10, 11)) {
			Fixture f = new Fixture();
			Server server = new Server();
			BudgetDelaySink sink = f.coordinator.sink(server);
			for (int i = 0; i < total; i++) {
				Quantity quantity = i == 0 ? Quantity.of(Unit.PHYSICAL_SLOT, 7)
					: i == 1 ? Quantity.of(Unit.LOGICAL_SLOT, 4) : Quantity.unknown();
				sink.observe(event(PRESENTATION, 42, 0, "stage_" + i + "\n\u0000", quantity,
					Reason.PHYSICAL_SLOTS.bit() | Reason.LOGICAL_GLOBAL.bit()));
			}
			sink.endCycle(PRESENTATION, 42, 0);
			String line = f.warnings.getFirst();
			assertTrue(line.contains("module=PRESENTATION"));
			assertTrue(line.contains("cycle=42"));
			assertTrue(line.contains("observed work items=" + total));
			assertTrue(line.contains("physical/decode slots=7"));
			assertTrue(line.contains("subject-logical slots=4"));
			assertTrue(line.contains("unknown observations=" + (total - 2)));
			assertTrue(line.contains("PHYSICAL_SLOTS=" + total));
			assertTrue(line.contains("LOGICAL_GLOBAL=" + total));
			assertTrue(line.contains("stage_9:"));
			assertFalse(line.contains("stage_10:"));
			assertEquals(total == 11, line.contains("and 1 more work items"));
			assertFalse(line.contains("[PingForIt]"), "Global's production logger adds the prefix once");
			assertFalse(line.contains("\n"));
			assertFalse(line.contains("\u0000"));
			assertFalse(line.contains("distinct targets"));
			assertFalse(line.contains("backlog"));
		}
	}

	@Test void formatterMarksOverflowAsLowerBoundRatherThanAnExactDeferredAmount() {
		Fixture f = new Fixture();
		Server server = new Server();
		BudgetDelaySink sink = f.coordinator.sink(server);
		sink.observe(event(PRESENTATION, 1, 0, "first", Quantity.of(Unit.BYTE, Long.MAX_VALUE), Reason.WIRE_GLOBAL.bit()));
		sink.observe(event(PRESENTATION, 1, 0, "second", Quantity.of(Unit.BYTE, 1), Reason.WIRE_GLOBAL.bit()));
		sink.endCycle(PRESENTATION, 1, 0);
		assertTrue(f.warnings.getFirst().contains("BYTE=>=9223372036854775807"));
		assertTrue(f.warnings.getFirst().contains("observed work items=2"));
	}

	@Test void diagnosticsPortFailuresCannotBreakTickButFatalErrorsAreNotSwallowed() {
		for (Throwable failure : List.of(new IllegalStateException("port"), new NoClassDefFoundError("port"))) {
			Fixture f = new Fixture();
			Server server = new Server();
			Player operator = new Player(3);
			server.players.add(operator);
			f.warningFailure = failure;
			assertDoesNotThrow(() -> f.tick(server, 0, 0));
			f.warningFailure = null;
			server.flagFailure = failure;
			assertDoesNotThrow(() -> f.tick(server, 1, 20));
			assertEquals(0, operator.sends);
			server.flagFailure = null;
			server.recipientFailure = failure;
			assertDoesNotThrow(() -> f.tick(server, 2, 21));
			server.recipientFailure = null;
			f.clockFailure = failure;
			assertDoesNotThrow(() -> f.tick(server, 3, 22));
			f.clockFailure = null;
			f.tick(server, 4, 23);
			assertEquals(1, operator.sends, "no failed diagnostics port consumed a delivered cooldown");
		}
		OutOfMemoryError fatal = new OutOfMemoryError("test resource failure");
		Fixture f = new Fixture();
		Server server = new Server();
		f.warningFailure = fatal;
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> f.tick(server, 0, 0)));
		f.warningFailure = null;
		server.flagFailure = fatal;
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> f.coordinator.finishTick(server)));
		server.flagFailure = null;
		Player operator = new Player(3);
		operator.failure = fatal;
		server.players.add(operator);
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> f.tick(server, 1, 20)));
	}
}
