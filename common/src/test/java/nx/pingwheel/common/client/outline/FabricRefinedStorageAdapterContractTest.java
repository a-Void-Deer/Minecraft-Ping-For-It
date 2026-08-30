package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricRefinedStorageAdapterContractTest {
	private static final String ADAPTER_SOURCE =
		"fabric/src/main/java/nx/pingwheel/fabric/integration/refinedstorage/"
			+ "RefinedStorageWorldAwareBlockModelOutlineAdapter.java";
	private static final String LOADER_SOURCE =
		"fabric/src/main/java/nx/pingwheel/fabric/integration/refinedstorage/RefinedStorageClient.java";

	@Test
	void adapterUsesTheWorldAwareBatchedRouteWithoutOwningTheBuffer() throws IOException {
		String source = readFabricSource(ADAPTER_SOURCE);

		assertTrue(source.contains("RefinedStorageCableBlockMatcher.matches"));
		assertTrue(source.contains("context.level()"));
		assertTrue(source.contains("context.blockPos()"));
		assertTrue(source.contains("context.blockState()"));
		assertTrue(source.contains("context.cameraPosition()"));
		assertTrue(source.contains("renderBatched"));
		assertTrue(source.contains("RandomSource.create(state.getSeed(pos))"));
		assertFalse(source.contains("renderSingleBlock"));
		assertFalse(source.contains("endBatch"));
		assertFalse(source.contains("flush"));
	}

	@Test
	void loaderGatesResolutionOnTheFabricModCheckAndClosesOnDisconnect() throws IOException {
		String source = readFabricSource(LOADER_SOURCE);

		assertTrue(source.contains("FabricLoader.getInstance().isModLoaded(MOD_ID)"));
		assertTrue(source.contains("ClientPlayConnectionEvents.DISCONNECT"));
		assertTrue(source.contains("closeAdapter()"));
		assertTrue(source.contains("Class.forName(ADAPTER_CLASS, true"));
		assertFalse(source.contains("WorldAwareBlockModelOutlineAdapterRegistry.INSTANCE.register"));

		int modCheck = source.indexOf("FabricLoader.getInstance().isModLoaded(MOD_ID)");
		int adapterResolution = source.indexOf("Class.forName(ADAPTER_CLASS, true");
		assertTrue(modCheck >= 0 && modCheck < adapterResolution);
		assertTrue(source.substring(modCheck, adapterResolution).contains("return"));
	}

	private static String readFabricSource(String source) throws IOException {
		Path fromRoot = Path.of(source);
		Path fromCommonProject = Path.of("..", source);
		Path path = Files.exists(fromRoot) ? fromRoot : fromCommonProject;
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
