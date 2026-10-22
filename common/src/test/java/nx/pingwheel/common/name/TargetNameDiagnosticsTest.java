package nx.pingwheel.common.name;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.testing.DiagnosticLogCapture;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class TargetNameDiagnosticsTest {

	@Test void throwingGetterUsesUnknownAndReportsBoundedRootButExpectedAbsenceDoesNotWarn() {
		try (var logs = new DiagnosticLogCapture()) {
			var target = new Target.EntityTarget("minecraft:overworld", UUID.randomUUID());
			var lookup = new Lookup();
			assertUnknown(ClientTargetNameResolver.resolve(target, lookup));
			assertTrue(logs.events().isEmpty());
			lookup.failing = true;
			assertUnknown(ClientTargetNameResolver.resolve(target, lookup));
			assertUnknown(ClientTargetNameResolver.resolve(target, lookup));
			assertEquals(1, logs.operation("target-name/client-lookup").size());
			var event = logs.operation("target-name/client-lookup").getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			String report = event.getMessage().getFormattedMessage();
			assertTrue(report.contains("getter-sentinel"));
			assertTrue(report.contains("getter-root-sentinel"));
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
}
