package nx.pingwheel.common.name;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationDiagnosticCapture;
import nx.pingwheel.common.testing.DiagnosticLogCapture;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class TargetNameDiagnosticsTest {

	@Test void throwingGetterUsesUnknownAndReportsBoundedRootButExpectedAbsenceDoesNotWarn() {
		try (var logs = new PresentationDiagnosticCapture()) {
			var target = new Target.EntityTarget("minecraft:overworld", UUID.randomUUID());
			var lookup = new Lookup();
			assertUnknown(ClientTargetNameResolver.resolve(target, lookup));
			assertTrue(logs.events().isEmpty());
			lookup.failing = true;
			assertUnknown(ClientTargetNameResolver.resolve(target, lookup));
			assertUnknown(ClientTargetNameResolver.resolve(target, lookup));
			assertEquals(1, logs.events().size());
			var event = logs.events().getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			String report = event.getMessage().getFormattedMessage();
			assertTrue(report.contains("getter-sentinel"));
			assertTrue(report.contains("getter-root-sentinel"));
		}
	}

	@Test void normalThrowingLookupStaysSafeAndConsumesTheGetterOnce() {
		try (var logs = new DiagnosticLogCapture()) {
			var target = new Target.EntityTarget("minecraft:overworld", UUID.randomUUID());
			var lookup = new CountingLookup();
			assertUnknown(ClientTargetNameResolver.resolve(target, lookup));
			assertTrue(logs.events().isEmpty(), "expected absence stays quiet at NORMAL");
			int callsBeforeFailure = lookup.calls.get();
			lookup.failing = true;
			assertUnknown(ClientTargetNameResolver.resolve(target, lookup));
			assertEquals(callsBeforeFailure + 1, lookup.calls.get(), "a failed lookup is not retried for diagnostics");
			assertEquals(1, logs.operation("target-name/client-lookup").size());
			var event = logs.events().getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			String report = event.getMessage().getFormattedMessage();
			assertTrue(report.contains("handling=unknown-name"), report);
			assertTrue(report.contains("failure=invalid-state"), report);
			assertFalse(report.contains("getter-sentinel"));
			assertFalse(report.contains("getter-root-sentinel"));
		}
	}

	@Test void serverLookupFailureAtNormalKeepsUnknownNameSafe() {
		try (var logs = new DiagnosticLogCapture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			var target = new Target.EntityTarget("minecraft:overworld", UUID.randomUUID());
			MinecraftTargetNameResolver.reportLookupFailure(target,
				new IllegalStateException("server-getter-sentinel", new RuntimeException("server-getter-root")));
			assertEquals(1, logs.operation("target-name/server-lookup").size());
			var event = logs.events().getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			String report = event.getMessage().getFormattedMessage();
			assertTrue(report.contains("handling=unknown-name"), report);
			assertTrue(report.contains("failure=invalid-state"), report);
			assertFalse(report.contains("server-getter-sentinel"));
			assertFalse(report.contains("server-getter-root"));
		}
	}

	private static void assertUnknown(Component name) {
		assertEquals("pingforit.target.unknown", ((TranslatableContents) name.getContents()).getKey());
	}

	@NotNullByDefault
	private static final class Lookup implements ClientTargetNameResolver.Lookup {
		private boolean failing;
		@Override public Optional<Component> entity(Target.EntityTarget target) {
			if (failing) throw new IllegalStateException("getter-sentinel", new RuntimeException("getter-root-sentinel"));
			return Optional.empty();
		}
		@Override public Optional<Component> block(Target.BlockTarget target) { return Optional.empty(); }
	}

	@NotNullByDefault
	private static final class CountingLookup implements ClientTargetNameResolver.Lookup {
		private final AtomicInteger calls = new AtomicInteger();
		private boolean failing;
		@Override public Optional<Component> entity(Target.EntityTarget target) {
			calls.incrementAndGet();
			if (failing) throw new IllegalStateException("getter-sentinel", new RuntimeException("getter-root-sentinel"));
			return Optional.empty();
		}
		@Override public Optional<Component> block(Target.BlockTarget target) { return Optional.empty(); }
	}
}
