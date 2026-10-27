package nx.pingwheel.common.presentation.inventory;

import java.util.Map;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;

/** A normal authority/publication tick with its physical-slot allowance already spent. */
@NotNullByDefault
public final class InventoryPublicationTicks {
	private InventoryPublicationTicks() {}

	public static void withoutScanSlots(InventoryBackend backend, long now, InventorySettings settings) {
		try {
			var runtime = backend.runtime();
			runtime.advance(now, settings);
			var field = InventoryRuntime.class.getDeclaredField("physical");
			field.setAccessible(true);
			var physical = (CostLedger) field.get(runtime);
			// Model competing work consuming this tick's shared slots, not a bypass
			// of source authority or publication budgets. Metadata probes still run.
			long remaining = physical.remaining(InventorySourceAccess.PHYSICAL);
			if (remaining > 0) try (var spent = physical.tryReserve(Map.of(InventorySourceAccess.PHYSICAL, remaining)).orElseThrow()) {
				spent.commit(Map.of(InventorySourceAccess.PHYSICAL, remaining));
			}
			backend.tick(now, settings);
		} catch (ReflectiveOperationException failure) { throw new AssertionError("cannot spend the fixture's physical-slot allowance", failure); }
	}
}
