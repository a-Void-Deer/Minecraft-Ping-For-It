package nx.pingwheel.common.name;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationDiagnosticCapture;
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
