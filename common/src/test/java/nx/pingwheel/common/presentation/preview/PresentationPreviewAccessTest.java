package nx.pingwheel.common.presentation.preview;

import java.util.Map;
import java.util.Set;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.preview.client.PreviewLocalReaders;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationPreviewAccessTest {
	@Test void absentAuthorizationNeverPromotedAndDedicatedInventoryExcluded() {
		var field = new PresentationField("test:field", PresentationField.Kind.NUMBER, true, 0, "label");
		var adapter = new PresentationPreviewAccess.Adapter(1, Map.of(field.id(), field));
		var access = new PresentationPreviewAccess(1, 1, "block", Map.of("test:adapter", adapter));
		assertFalse(access.allows("test:adapter", "test:unknown"));
		assertTrue(access.narrow("test:adapter", Set.of("test:unknown")).fields("test:adapter").isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new PresentationPreviewAccess(0, 1, "block", Map.of()));
		assertThrows(IllegalArgumentException.class, () -> new PresentationPreviewAccess(1, 1, "unknown", Map.of()));
		assertThrows(IllegalArgumentException.class, () -> new PresentationPreviewAccess(1, 1, "block", Map.of("pingforit:inventory", adapter)));
	}
	@Test void absentNeoForgeCreateStillRegistersBasicWithoutLinkage() {
		var readers = PreviewLocalReaders.create();
		assertTrue(readers.stream().anyMatch(reader -> reader.adapterId().equals("minecraft:basic")));
	}
}
