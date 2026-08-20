package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalGeometryClassSafetyTest {
	@Test
	void commonGeometryCoreLoadsWhenOptionalClassesAreAbsent() {
		assertThrows(ClassNotFoundException.class,
			() -> Class.forName("dev.engine_room.flywheel.api.model.Model"));
		assertThrows(ClassNotFoundException.class,
			() -> Class.forName("com.simibubi.create.content.kinetics.base.RotatingInstance"));
		assertDoesNotThrow(() -> Class.forName(EntityBlockGeometryContext.class.getName()));
		assertDoesNotThrow(() -> Class.forName(FlywheelSilhouetteMask.class.getName()));
	}

	@Test
	void optionalAdapterUsesIndirectStateResolutionWithoutVisibilityMutation() throws IOException {
		Path source = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateFlywheelGeometryAdapter.java"));
		assertTrue(Files.isRegularFile(source), "NeoForge Flywheel adapter source must be present");

		String adapter = Files.readString(source, StandardCharsets.UTF_8);
		assertTrue(adapter.contains("resolveLiveInstancer"));
		assertTrue(adapter.contains("IndirectInstancer.fromState(state)"));
		assertFalse(adapter.contains("isVisible("));
		assertFalse(adapter.contains("setVisible("));
		assertFalse(adapter.contains("setDeleted("));
	}

	private static Path findRepositoryFile(Path relativePath) {
		Path directory = Path.of("").toAbsolutePath();
		while (directory != null) {
			Path candidate = directory.resolve(relativePath);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
			directory = directory.getParent();
		}
		return Path.of("__missing_repository_file__").resolve(relativePath);
	}
}
