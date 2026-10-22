package nx.pingwheel.neoforge.integration.create.presentation;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreatePreviewReceiptStateTest {
	private static CompoundTag speed(float value) { var tag = new CompoundTag(); tag.putFloat("Speed", value); return tag; }
	@Test void constructorAndAbsentFieldsAreNotObservedZero() {
		var state = new CreatePreviewReceiptState(); assertFalse(state.received());
		state.accept(new CompoundTag(), true, true, false, false, 1); assertFalse(state.received());
		state.accept(speed(0), true, true, false, false, 2); assertTrue(state.received()); assertEquals(2, state.tick());
		state.clear(); assertFalse(state.received());
	}
	@Test void onlyClientAppliedExactFieldShapeAndNonVirtualNonMovedSourceIsEvidence() {
		var state = new CreatePreviewReceiptState(); var tag = speed(23);
		state.accept(tag, false, true, false, false, 1); assertFalse(state.received());
		state.accept(tag, true, false, false, false, 1); assertFalse(state.received());
		state.accept(tag, true, true, true, false, 1); assertFalse(state.received());
		state.accept(tag, true, true, false, true, 1); assertFalse(state.received());
		CompoundTag network = new CompoundTag(); network.putLong("Id", 12); tag.put("Network", network);
		state.accept(tag, true, true, false, false, 3); assertFalse(state.received());
		network.putFloat("Stress", 5); network.putFloat("Capacity", 10);
		state.accept(tag, true, true, false, false, 4); assertTrue(state.received());
		state.accept(new CompoundTag(), true, true, false, false, 5); assertFalse(state.received(), "failed/empty update invalidates old evidence");
	}
}
