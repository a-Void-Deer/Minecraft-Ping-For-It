package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Lazy diagnostic context shared with consuming logging boundaries. */
@NotNullByDefault
class InventorySourceUnavailableExceptionTest {
	@Test void legacyConstructorHasNoContextAndNewContextIsNotEvaluatedEagerly() {
		var calls = new AtomicInteger();
		var failure = new InventorySourceUnavailableException("expected source loss",
			() -> { calls.incrementAndGet(); return "root=0,0,0 controller=creative-tank member=4"; });
		assertEquals(0, calls.get());
		assertEquals("expected source loss", failure.getMessage());
		assertEquals("expected source loss", failure.getLocalizedMessage());
		assertEquals(0, calls.get(), "message and localized-message access must not evaluate diagnostics");
		assertEquals("root=0,0,0 controller=creative-tank member=4", failure.diagnosticContext());
		assertEquals(1, calls.get());
		assertNull(new InventorySourceUnavailableException("legacy").diagnosticContext());
	}
}
