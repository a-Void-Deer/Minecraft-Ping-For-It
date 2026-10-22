package nx.pingwheel.common.client.outline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Structural contract coverage for the optional NeoForge/Create boundary.
 * Create is compile-only for this project, so loading its resolver in the
 * common test runtime would not be safe; source contracts pin the integration
 * without creating a hard test dependency.
 */
class CreateLargeWaterWheelPresentationResolverContractTest {
	private static final String RESOLVER_SOURCE =
		"src/main/java/nx/pingwheel/neoforge/integration/create/"
			+ "CreateLargeWaterWheelPresentationResolver.java";
	private static final String NEO_CLIENT_SOURCE =
		"src/main/java/nx/pingwheel/neoforge/NeoClient.java";

	@Test
	void resolverDeclaresCreateStructuralBlockHostBinding() throws IOException {
		String source = readProjectFile(RESOLVER_SOURCE);

		assertTrue(source.contains("instanceof WaterWheelStructuralBlock"));
		assertTrue(source.contains("instanceof LargeWaterWheelBlock"));
		assertTrue(source.contains("stillValid("));
		assertTrue(source.contains("WaterWheelStructuralBlock.getMaster("));
		assertTrue(source.contains("BuiltInRegistries.BLOCK.getKey("));
		assertTrue(source.contains("BlockPresentationRelation.PROXY_TO_OWNER"));
		assertTrue(source.contains("\"entity_block\""));
		assertTrue(source.contains("\"create:large_water_wheel\""));
		assertTrue(source.contains("BlockPresentationResolverRegistry.INSTANCE.register("));

		// Master selection must use Create's structural relation, not an
		// unrelated block entity, chunk, entity, or Flywheel visual search.
		assertFalse(source.contains("getBlockEntity("));
		assertFalse(source.contains("visualAtPos("));
		assertFalse(source.contains("getChunk("));
		assertFalse(source.contains("getEntities("));
	}

	@Test
	void createOnlyBranchDeclaresReflectiveWheelRegistrationWithoutFlywheelGate() throws IOException {
		String source = readProjectFile(NEO_CLIENT_SOURCE);

		assertTrue(Pattern.compile("CREATE_WATER_WHEEL_RESOLVER\\s*=\\s*\""
			+ Pattern.quote("nx.pingwheel.neoforge.integration.create.CreateLargeWaterWheelPresentationResolver")
			+ "\"").matcher(source).find());
		assertFalse(source.contains(
			"import nx.pingwheel.neoforge.integration.create.CreateLargeWaterWheelPresentationResolver"));
		assertTrue(Pattern.compile("createDetected\\s*=\\s*ModList\\.get\\(\\)\\.isLoaded"
			+ "\\(\"create\"\\)").matcher(source).find());
		String createOnly = ifBody(source, "createDetected");
		String createAndFlywheel = ifBody(source, "createDetected\\s*&&\\s*flywheelDetected");
		assertFalse(createOnly.isEmpty(), "Create-only registration gate must exist");
		assertFalse(createAndFlywheel.isEmpty(), "Flywheel-specific gate must be separate");
		assertTrue(Pattern.compile("registerOptionalResolver\\s*\\(\\s*CREATE_WATER_WHEEL_RESOLVER\\b")
			.matcher(createOnly).find());
		assertFalse(createAndFlywheel.contains("CREATE_WATER_WHEEL_RESOLVER"));
		assertTrue(source.contains("Class.forName("));
	}

	private static String ifBody(String source, String condition) {
		Matcher gate = Pattern.compile("if\\s*\\(\\s*" + condition + "\\s*\\)\\s*\\{").matcher(source);
		if (!gate.find()) {
			return "";
		}
		int openingBrace = gate.end() - 1;
		int depth = 0;
		for (int index = openingBrace; index < source.length(); index++) {
			if (source.charAt(index) == '{') {
				depth++;
			} else if (source.charAt(index) == '}' && --depth == 0) {
				return source.substring(openingBrace, index + 1);
			}
		}
		return "";
	}

	private static String readProjectFile(String relativePath) throws IOException {
		List<Path> candidates = List.of(
			Path.of(relativePath),
			Path.of("neoforge").resolve(relativePath),
			Path.of("..").resolve("neoforge").resolve(relativePath));
		for (Path candidate : candidates) {
			if (Files.exists(candidate)) {
				return Files.readString(candidate);
			}
		}
		throw new IOException("Unable to locate project source: " + relativePath);
	}
}
