package nx.pingwheel.common.client.marker;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the displayed target name lifecycle of {@link MarkerView}: the
 * unknown default, in-place replacement from the name store, and preservation
 * of the current name across same-id payload replacement.
 */
class MarkerViewNameTest {

	private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

	private static MarkerView view(long id) {
		return new MarkerView(marker(id, 1L));
	}

	private static ClientMarker marker(long id, long arrivalTick) {
		var target = new Target.LocationTarget("minecraft:overworld", 0, 0, 0);
		var snapshot = new MarkerSnapshot(new MarkerId(id), OWNER, target, "block", "attention",
			new MarkerAnchor(0, 0, 0), arrivalTick, 100L);
		return ClientMarker.withLocalDuration(snapshot, 0L, arrivalTick, 100L);
	}

	private static String translatableKey(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents, "expected a translatable component");
		return ((TranslatableContents) component.getContents()).getKey();
	}

	@Test
	void defaultTargetNameIsUnknownTranslatable() {
		assertEquals("pingforit.target.unknown", translatableKey(view(1L).getTargetName()));
	}

	@Test
	void replaceTargetNameUpdatesDisplayedNameWithoutRebuildingTheView() {
		MarkerView view = view(1L);
		Component custom = Component.literal("Chest (Large Chest)");

		view.replaceTargetName(custom);

		assertSame(custom, view.getTargetName());
		assertEquals("Chest (Large Chest)", view.getTargetName().getString());
	}

	@Test
	void replaceTargetNameRejectsNull() {
		assertThrows(NullPointerException.class, () -> view(1L).replaceTargetName(null));
	}

	@Test
	void replacePayloadPreservesCurrentTargetName() {
		MarkerView view = view(1L);
		Component custom = Component.literal("Chest (Large Chest)");

		view.replaceTargetName(custom);
		view.replacePayload(marker(1L, 2L));

		assertSame(custom, view.getTargetName());
		assertEquals("Chest (Large Chest)", view.getTargetName().getString());
	}
}
