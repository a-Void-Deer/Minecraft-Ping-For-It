package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.diagnostics.RateLimitedDiagnosticsTestScope;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMinecraftDiagnosticsTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static InventorySourceInput input(String registry) {
		return new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 64, -2, registry), new UUID(8, 9), BlockFace.NORTH);
	}
	private static class Access implements IPlatformInventoryService.Access {
		final AtomicInteger reads = new AtomicInteger();
		@Override public boolean valid() { return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 1; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public IPlatformInventoryService.Entry read(int slot) { reads.incrementAndGet(); return IPlatformInventoryService.Entry.empty(); }
		@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed source"); }
	}
	@Test void throwingLayoutKeepsLiveFallbackAndReportsOriginalFailureOnceAcrossPositions() {
		var input = input("diagnostic:layout");
		var cause = new IllegalArgumentException("layout cause registry=othermod:machine");
		var failure = new IllegalStateException("layout callback failed", cause);
		failure.addSuppressed(new RuntimeException("layout suppressed detail"));
		var access = new Access() { @Override public Optional<InventorySnapshotLayout> snapshotLayout() { throw failure; } };
		try (var capture = new Capture()) {
			assertNull(InventoryMinecraftSources.providerLayout(input, new BlockPos(1, 64, -2), access));
			assertNull(InventoryMinecraftSources.providerLayout(input, new BlockPos(99, 64, -2), access));
			var view = new InventoryMinecraftSources.View("live-alias", List.of(), List.of(), access);
			try (var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view, entry -> null)) {
				assertTrue(source.snapshotPlan().isEmpty());
				assertNull(source.read(0));
			}
			assertEquals(1, access.reads.get(), "layout failure retains the original live reader");
			assertEquals(1, capture.events.size(), "positions must not bypass the stable provider fingerprint");
			String message = capture.message();
			assertTrue(message.contains("layout callback failed"));
			assertTrue(message.contains("layout cause registry=othermod:machine"));
			assertTrue(message.contains("layout suppressed detail"));
			assertTrue(message.contains("throwingLayoutKeepsLiveFallback"));
			assertTrue(message.contains("diagnostic:layout"));
			assertTrue(message.contains(input.readOwner().toString()));
			assertTrue(message.contains("face=NORTH"));
			assertTrue(message.contains(access.getClass().getName()));
			assertNull(capture.events.getFirst().getThrown(), "only the bounded report is handed to Log4j");
		}
	}
	@Test void missingAndUnregisteredLayoutsAreNormalUnsupportedResults() {
		var input = input("diagnostic:unsupported");
		var unknown = new InventorySnapshotLayout("diagnostic:unknown", "", BlockPos.ZERO,
			List.of(new InventorySnapshotLayout.Member(BlockPos.ZERO, "diagnostic:unsupported", "diagnostic:unsupported", "target",
				List.of(new InventorySnapshotLayout.Segment("Items", "diagnostic:unregistered", 1, List.of(0))))));
		try (var capture = new Capture()) {
			assertNull(InventoryMinecraftSources.providerLayout(input, BlockPos.ZERO, new Access()));
			assertNull(InventoryMinecraftSources.providerLayout(input, BlockPos.ZERO, new Access() {
				@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.of(unknown); }
			}));
			assertTrue(capture.events.isEmpty());
		}
	}
	@Test void unexpectedValidityExceptionIsNotMistakenForExpectedLifecycleSignal() {
		var input = input("diagnostic:validity");
		var view = new InventoryMinecraftSources.View("source-alias", List.of(), List.of(), new Access());
		RuntimeException[] failure = {new InventorySourceUnavailableException("source read denied")};
		try (var capture = new Capture(); var source = new InventoryMinecraftSources.Source(input, view, () -> true,
			() -> { throw failure[0]; }, entry -> null)) {
			assertFalse(source.valid()); assertTrue(capture.events.isEmpty());
			failure[0] = new IllegalStateException("source read denied", new RuntimeException("real provider bug"));
			assertFalse(source.valid()); assertEquals(1, capture.events.size());
			assertTrue(capture.message().contains("real provider bug"), "classification must not use a message whitelist");
		}
	}
	@Test void resolverFailureReportsCauseWhileAbsentOptionalBindingStaysSilent() {
		var input = new InventorySourceInput(Target.ExternalBlockTarget.candidate("minecraft:overworld", "diagnostic:provider", "diagnostic:block", "locator-detail", true), new UUID(3, 4), BlockFace.WEST);
		try (var capture = new Capture()) {
			assertTrue(InventoryMinecraftSources.resolveExternal(input, Optional::empty, () -> true, entry -> null).isEmpty());
			assertTrue(capture.events.isEmpty());
			assertTrue(InventoryMinecraftSources.resolveExternal(input, () -> {
				throw new IllegalStateException("binding callback failure", new LinkageError("provider linkage detail"));
			}, () -> true, entry -> null).isEmpty());
			assertEquals(1, capture.events.size());
			assertTrue(capture.message().contains("provider linkage detail"));
			assertTrue(capture.message().contains("locator-detail"));
			assertTrue(capture.message().contains("face=WEST"));
		}
	}
	@Test void throwingMembershipGateRemainsDeniedAndReportsOriginalFailure() {
		var target = (Target.BlockTarget) input("diagnostic:member").target();
		try (var capture = new Capture()) {
			var denied = new InventoryMinecraftSources.PhysicalRead(target, BlockFace.NORTH, pos -> false);
			assertFalse(denied.contains(BlockPos.ZERO)); assertTrue(capture.events.isEmpty());
			var broken = new InventoryMinecraftSources.PhysicalRead(target, BlockFace.NORTH, pos -> {
				throw new IllegalArgumentException("member callback failed", new RuntimeException("scope cause"));
			});
			assertFalse(broken.contains(BlockPos.ZERO)); assertEquals(1, capture.events.size());
			assertTrue(capture.message().contains("member callback failed"));
			assertTrue(capture.message().contains("scope cause"));
		}
	}
	@Test void externalLeaseLossIsSilentButThrowingCurrentBindingReportsCause() {
		var world = new InventoryMinecraftSourcesTest.ChestWorld();
		var input = new InventorySourceInput(Target.ExternalBlockTarget.candidate("minecraft:overworld", "diagnostic:current", "minecraft:chest", "current-locator", true), new UUID(2, 7), BlockFace.NORTH);
		var descriptor = new nx.pingwheel.common.integration.externalblock.BlockReadSource((Target.ExternalBlockTarget) input.target(), "diagnostic:current", "sub-level",
			world.input.ordinaryTarget().orElseThrow(), new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3));
		var binding = new InventoryMinecraftSources.ExternalBinding(world, descriptor, world::isLoaded, (pos, face) -> Optional.empty());
		boolean[] active = {true}; RuntimeException[] failure = {null};
		try (var capture = new Capture(); var source = InventoryMinecraftSources.resolveExternal(input, () -> {
			if (failure[0] != null) throw failure[0];
			return active[0] ? Optional.of(binding) : Optional.empty();
		}, () -> true, entry -> null).orElseThrow()) {
			active[0] = false; assertFalse(source.valid()); assertTrue(capture.events.isEmpty());
			failure[0] = new IllegalStateException("current binding callback failed", new RuntimeException("current binding cause"));
			assertFalse(source.valid()); assertEquals(1, capture.events.size());
			assertTrue(capture.message().contains("current binding cause")); assertTrue(capture.message().contains("sub-level"));
			assertEquals(0, world.reads(), "binding diagnostics must not read any item contents");
		}
	}
	@Test void defaultGuardKeepsOrdinaryDenialSilentAndReportsThrowingGateAndDelegate() {
		var gateFailure = new IllegalStateException("inventory access outside the read scope", new RuntimeException("throwing default member gate"));
		var delegateFailure = new IllegalStateException("inventory access outside the read scope", new RuntimeException("throwing default validity delegate"));
		try (var capture = new Capture()) {
			var denied = new Access().guardedBy(() -> false);
			assertFalse(denied.valid()); assertThrows(InventorySourceUnavailableException.class, denied::slots);
			assertFalse(new Access() { @Override public boolean valid() { return false; } }.guardedBy(() -> true).valid());
			assertTrue(capture.events.isEmpty(), "ordinary false and explicit denial are not callback failures");
			var brokenGate = new Access().guardedBy(() -> { throw gateFailure; });
			assertFalse(brokenGate.valid()); assertFalse(brokenGate.valid());
			assertFalse(new Access() { @Override public boolean valid() { throw delegateFailure; } }.guardedBy(() -> true).valid());
			assertEquals(2, capture.events.size(), "the repeated throwing gate is rate-controlled, not retried as a warning");
			String reports = capture.events.stream().map(event -> event.getMessage().getFormattedMessage()).collect(java.util.stream.Collectors.joining("\n"));
			assertTrue(reports.contains(gateFailure.getMessage())); assertTrue(reports.contains("throwing default member gate"));
			assertTrue(reports.contains("throwing default validity delegate")); assertTrue(reports.contains("operation=inventory-guarded-access-valid"));
			assertTrue(reports.contains("delegateClass=")); assertTrue(reports.contains("gateClass="));
			for (var event : capture.events) { assertEquals(Level.WARN, event.getLevel()); assertNull(event.getThrown()); }
			var fatal = new AssertionError("fatal default member gate");
			assertSame(fatal, assertThrows(AssertionError.class, new Access().guardedBy(() -> { throw fatal; })::valid));
		}
	}
	static final class Capture implements AutoCloseable {
		final List<LogEvent> events = new ArrayList<>();
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final org.apache.logging.log4j.core.config.Configuration configuration = logger.getContext().getConfiguration();
		private final LoggerConfig previous = configuration.getLoggers().get(logger.getName());
		private final AbstractAppender appender = new AbstractAppender("native-inventory-diagnostics", null, null, true, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
		};
		private final RateLimitedDiagnosticsTestScope diagnostics;
		Capture() {
			appender.start(); var config = new LoggerConfig(logger.getName(), Level.WARN, false);
			config.addAppender(appender, Level.ALL, null); configuration.removeLogger(logger.getName());
			configuration.addLogger(logger.getName(), config); logger.getContext().updateLoggers();
			// Isolate the shared limiter after the capture logger is in place so
			// the report still travels the real production API and Log4j sink.
			diagnostics = RateLimitedDiagnosticsTestScope.install();
		}
		String message() { assertEquals(Level.WARN, events.getFirst().getLevel()); return events.getFirst().getMessage().getFormattedMessage(); }
		@Override public void close() {
			diagnostics.close();
			configuration.removeLogger(logger.getName()); if (previous != null) configuration.addLogger(logger.getName(), previous);
			logger.getContext().updateLoggers(); appender.stop();
		}
	}
}
