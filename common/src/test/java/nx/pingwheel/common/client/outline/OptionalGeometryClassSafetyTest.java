package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionalGeometryClassSafetyTest {
	@Test
	void commonGeometryCoreLoadsWhenOptionalClassesAreAbsent() {
		assertThrows(ClassNotFoundException.class,
			() -> Class.forName("dev.engine_room.flywheel.api.model.Model"));
		assertThrows(ClassNotFoundException.class,
			() -> Class.forName("com.simibubi.create.content.kinetics.base.RotatingInstance"));
		assertDoesNotThrow(() -> Class.forName(EntityBlockGeometryContext.class.getName()));
		assertDoesNotThrow(() -> Class.forName(DeferredEntityBlockGeometryState.class.getName()));
	}
}
