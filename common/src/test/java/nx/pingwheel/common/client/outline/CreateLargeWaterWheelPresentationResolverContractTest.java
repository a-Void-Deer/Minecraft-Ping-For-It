package nx.pingwheel.common.client.outline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
	void resolverUsesCreateMasterResolutionWithoutDiscoveryFallbacks() throws IOException {
		String source = readProjectFile(RESOLVER_SOURCE);

		assertTrue(source.contains("WaterWheelStructuralBlock"));
		assertTrue(source.contains("LargeWaterWheelBlock"));
		assertTrue(source.contains("sourceState.getBlock() instanceof WaterWheelStructuralBlock"));
		assertTrue(source.contains("return BlockPresentationResolution.UNHANDLED"));
		assertTrue(source.contains("sourceBlock.stillValid"));
		assertTrue(source.contains("sourcePos, sourceState, false"));
		assertTrue(source.contains("WaterWheelStructuralBlock.getMaster"));
		assertTrue(source.contains("context.world(), sourcePos, sourceState"));
		assertTrue(source.indexOf("sourceBlock.stillValid")
			< source.indexOf("WaterWheelStructuralBlock.getMaster"));
		assertTrue(source.contains("BlockPresentationRelation.PROXY_TO_OWNER"));
		assertTrue(source.contains("\"entity_block\""));
		assertTrue(source.contains("\"create:large_water_wheel\""));
		assertTrue(source.contains("masterPos"));
		assertTrue(source.contains("masterState"));
		assertTrue(source.contains("LARGE_WATER_WHEEL_ID"));
		assertTrue(source.contains("BlockPresentationResolution.handled(List.of())"));
		assertTrue(source.contains("registration != null"));
		assertTrue(source.contains("BlockPresentationResolverRegistry.Registration registration"));
		assertTrue(source.indexOf("new BlockRenderSubject")
			== source.lastIndexOf("new BlockRenderSubject"));

		assertFalse(source.contains("getBlockEntity"));
		assertFalse(source.contains("visualAtPos"));
		assertFalse(source.contains("getChunk"));
		assertFalse(source.contains("getEntities"));
		assertFalse(source.contains("scan"));
	}

	@Test
	void createRegistrationIsReflectiveAndIndependentOfFlywheel() throws IOException {
		String source = readProjectFile(NEO_CLIENT_SOURCE);

		assertTrue(source.contains(
			"nx.pingwheel.neoforge.integration.create.CreateLargeWaterWheelPresentationResolver"));
		assertFalse(source.contains(
			"import nx.pingwheel.neoforge.integration.create.CreateLargeWaterWheelPresentationResolver"));
		int createBranch = source.indexOf("if (createDetected) {");
		int wheelRegistration = source.indexOf("CREATE_WATER_WHEEL_RESOLVER", createBranch);
		int flywheelBranch = source.indexOf("if (createDetected && flywheelDetected)", createBranch);
		assertNotEquals(-1, createBranch);
		assertTrue(wheelRegistration > createBranch);
		assertTrue(wheelRegistration < flywheelBranch);
		assertTrue(source.contains(
			"registerOptionalResolver(CREATE_WATER_WHEEL_RESOLVER, \"create-water-wheel-presentation\")"));
		assertTrue(source.contains("Class.forName(className, true, NeoClient.class.getClassLoader())"));
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
