package nx.pingwheel.common.client.outline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Structural coverage for the optional Simulated boundary. Simulated is not a
 * project dependency, so the resolver is intentionally verified without
 * loading NeoForge or Simulated classes in the common test runtime.
 */
class SimulatedDockingConnectorPresentationResolverContractTest {
	private static final String RESOLVER_SOURCE =
		"src/main/java/nx/pingwheel/neoforge/integration/simulated/"
			+ "SimulatedDockingConnectorPresentationResolver.java";
	private static final String NEO_CLIENT_SOURCE =
		"src/main/java/nx/pingwheel/neoforge/NeoClient.java";

	@Test
	void resolverDeclaresSimulatedRegistryIdsAndProductionLookupBoundary() throws IOException {
		String source = readProjectFile(RESOLVER_SOURCE);

		// The executable NeoForge stand-in tests cover facing, powered, owner
		// selection, and handled-empty outcomes; they bypass these real IDs.
		assertTrue(registryIdBinding(source, "PAIRED_BLOCK_ID", "simulated:paired_docking_connector"));
		assertTrue(registryIdBinding(source, "OWNER_BLOCK_ID", "simulated:docking_connector"));
		assertTrue(registryIdBinding(source, "OWNER_BLOCK_ENTITY_ID", "simulated:docking_connector"));
		assertTrue(source.contains("BuiltInRegistries.BLOCK.getOptional("));
		assertTrue(source.contains("BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional("));
		assertTrue(source.contains("BuiltInRegistries.BLOCK.getKey("));
		assertTrue(source.contains("BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey("));
		assertTrue(source.contains("BlockPresentationResolverRegistry.INSTANCE.register("));
	}

	@Test
	void neoClientDeclaresSimulatedGatedReflectiveRegistration() throws IOException {
		String source = readProjectFile(NEO_CLIENT_SOURCE);

		assertTrue(Pattern.compile("SIMULATED_DOCKING_CONNECTOR_RESOLVER\\s*=\\s*\""
			+ Pattern.quote("nx.pingwheel.neoforge.integration.simulated.SimulatedDockingConnectorPresentationResolver")
			+ "\"").matcher(source).find());
		assertTrue(Pattern.compile("if\\s*\\(\\s*ModList\\.get\\(\\)\\.isLoaded\\(\"simulated\"\\)"
			+ "\\s*\\)\\s*\\{\\s*registerOptionalResolver\\s*\\(\\s*"
			+ "SIMULATED_DOCKING_CONNECTOR_RESOLVER\\b").matcher(source).find());
		assertTrue(source.contains("Class.forName("));
		assertTrue(source.contains("getMethod(\"register\")"));
		assertFalse(source.contains(
			"import nx.pingwheel.neoforge.integration.simulated."));
	}

	private static boolean registryIdBinding(String source, String role, String id) {
		return Pattern.compile("\\b" + role + "\\s*=\\s*ResourceLocation\\.parse\\(\\s*\""
			+ id + "\"\\s*\\)").matcher(source).find();
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
