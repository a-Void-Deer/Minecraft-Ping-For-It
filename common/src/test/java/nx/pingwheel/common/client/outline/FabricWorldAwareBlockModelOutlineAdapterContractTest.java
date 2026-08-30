package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricWorldAwareBlockModelOutlineAdapterContractTest {
	private static final String ADAPTER_SOURCE =
		"fabric/src/main/java/nx/pingwheel/fabric/integration/"
			+ "FabricWorldAwareBlockModelOutlineAdapter.java";
	private static final String CLIENT_SOURCE =
		"fabric/src/main/java/nx/pingwheel/fabric/FabricClient.java";

	@Test
	void backendUsesTheLiveWorldAwareBatchedRoute() throws IOException {
		String source = readFabricSource(ADAPTER_SOURCE);

		assertTrue(source.contains("context.level()"));
		assertTrue(source.contains("context.blockPos()"));
		assertTrue(source.contains("context.blockState()"));
		assertTrue(source.contains("context.cameraPosition()"));
		assertTrue(source.contains("context.transform()"));
		assertTrue(source.contains("createPoseStack"));
		assertTrue(source.contains("state.getOffset(level, pos)"));
		assertTrue(countOccurrences(source, "state.getOffset(level, pos)") == 1);
		assertTrue(source.contains("renderBatched"));
		assertTrue(source.contains("RandomSource.create(state.getSeed(pos))"));
		assertTrue(source.contains("\n\t\t\tfalse,"));
		assertFalse(source.contains("renderSingleBlock"));
		assertFalse(source.contains("VirtualBlockDisplay"));
		assertFalse(source.contains("VoxelShape"));
		assertFalse(source.contains("endBatch"));
		assertFalse(source.contains("flush"));
	}

	@Test
	void backendIsGenericAndStaysRegisteredAcrossConnections() throws IOException {
		String adapterSource = readFabricSource(ADAPTER_SOURCE);
		String clientSource = readFabricSource(CLIENT_SOURCE);
		String optionalModId = "refined" + "storage";

		assertFalse(adapterSource.toLowerCase().contains(optionalModId));
		assertFalse(clientSource.toLowerCase().contains(optionalModId));
		assertTrue(adapterSource.contains("if (registration != null)"));
		assertTrue(adapterSource.contains("WorldAwareBlockModelOutlineAdapterRegistry.INSTANCE.register"));
		assertTrue(clientSource.contains("FabricWorldAwareBlockModelOutlineAdapter.register()"));
		assertFalse(clientSource.contains("ClientPlayConnectionEvents"));
		assertFalse(clientSource.contains("closeAdapter"));
	}

	private static String readFabricSource(String source) throws IOException {
		Path fromRoot = Path.of(source);
		Path fromCommonProject = Path.of("..", source);
		Path path = Files.exists(fromRoot) ? fromRoot : fromCommonProject;
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	private static int countOccurrences(String source, String expected) {
		int count = 0;
		int offset = 0;
		while ((offset = source.indexOf(expected, offset)) >= 0) {
			count++;
			offset += expected.length();
		}
		return count;
	}
}
