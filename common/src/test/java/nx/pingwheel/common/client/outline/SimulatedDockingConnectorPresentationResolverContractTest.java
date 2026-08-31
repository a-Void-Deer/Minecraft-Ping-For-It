package nx.pingwheel.common.client.outline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
	void resolverPinsPairedConnectorOwnerPresentationContract() throws IOException {
		String source = readProjectFile(RESOLVER_SOURCE);

		assertTrue(source.contains("simulated:paired_docking_connector"));
		assertTrue(source.contains("simulated:docking_connector"));
		assertTrue(source.contains("BlockStateProperties.FACING"));
		assertTrue(source.contains("pairedState.getValue(BlockStateProperties.FACING)"));
		assertTrue(source.contains("pairedPos.relative(towardOwner)"));
		assertTrue(source.contains("towardOwner.getOpposite()"));
		assertTrue(source.contains("BlockStateProperties.POWERED"));
		assertTrue(source.contains("getBlockEntity(ownerPos)"));
		assertTrue(source.contains("ownerBlockEntity.getType() != ownerBlockEntityType"));
		assertTrue(source.contains("BlockPresentationRelation.PROXY_TO_OWNER"));
		assertTrue(source.contains("\"entity_block\""));
		assertTrue(source.contains("return BlockPresentationResolution.UNHANDLED"));
		assertTrue(source.contains("return BlockPresentationResolution.handled(List.of())"));
		assertTrue(source.contains("new BlockRenderSubject"));
		assertTrue(source.contains("registration != null"));
		assertTrue(source.contains("BlockPresentationResolverRegistry.Registration registration"));

		assertFalse(source.contains("dev.simulated_team"));
		assertFalse(source.contains("EXTENDED"));
		assertFalse(source.contains("getEntities"));
		assertFalse(source.contains("getChunk"));
		assertFalse(source.contains("scan"));
		assertFalse(source.contains("reflection"));
	}

	@Test
	void neoClientUsesOnlyStringReflectiveOptionalLoadingAfterModCheck() throws IOException {
		String source = readProjectFile(NEO_CLIENT_SOURCE);

		assertTrue(source.contains(
			"nx.pingwheel.neoforge.integration.simulated.SimulatedDockingConnectorPresentationResolver"));
		assertTrue(source.contains("ModList.get().isLoaded(\"simulated\")"));
		assertTrue(source.contains("registerOptionalResolver("));
		assertTrue(source.contains("Class.forName(className, true, NeoClient.class.getClassLoader())"));
		assertFalse(source.contains(
			"import nx.pingwheel.neoforge.integration.simulated.SimulatedDockingConnectorPresentationResolver"));
		assertFalse(source.contains("Class<SimulatedDockingConnectorPresentationResolver>"));
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
