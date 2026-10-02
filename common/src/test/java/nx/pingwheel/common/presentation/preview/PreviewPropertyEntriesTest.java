package nx.pingwheel.common.presentation.preview;

import java.util.List;
import java.util.Map;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewPropertyEntriesTest {
	@Test void nestedLiteralKeysAndSequenceRootNeverSequenceIndexes() {
		var root = PresentationPropertyRef.root("test:source", "test:record");
		var observed = new PreviewObservation(new PresentationValue.RecordValue(Map.of("counts",
			new PresentationValue.RecordValue(Map.of("minecraft:iron_ingot", new PresentationValue.NumberValue(0))),
			"list", new PresentationValue.Sequence(List.of(new PresentationValue.Text("entry"))))), PreviewObservation.Origin.SERVER_PREVIEW, 1, false);
		var projection = new ClientPresentationPreview.Projection(java.util.UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 0, 0, 0), "block", 1, 1, Map.of(root, new PreviewFieldAccess.Observed(observed)));
		var refs = PreviewPropertyEntries.of(projection).stream().map(PreviewPropertyEntries.Entry::ref).toList();
		assertTrue(refs.contains(new PresentationPropertyRef(root.adapterId(), root.fieldId(), List.of("counts", "minecraft:iron_ingot"))));
		assertTrue(refs.contains(new PresentationPropertyRef(root.adapterId(), root.fieldId(), List.of("list"))));
		assertFalse(refs.stream().anyMatch(ref -> ref.recordPath().contains("0")));
		assertEquals(new PresentationValue.NumberValue(0), projection.property(new PresentationPropertyRef(root.adapterId(), root.fieldId(), List.of("counts", "minecraft:iron_ingot"))).orElseThrow().value());
	}
}
