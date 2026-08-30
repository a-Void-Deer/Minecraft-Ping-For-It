package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeWorldAwareBlockModelOutlineAdapterContractTest {
	private static final String ADAPTER_SOURCE =
		"forge/src/main/java/nx/pingwheel/forge/integration/"
			+ "ForgeWorldAwareBlockModelOutlineAdapter.java";
	private static final String CLIENT_SOURCE =
		"forge/src/main/java/nx/pingwheel/forge/ForgeClient.java";

	@Test
	void backendUsesForgeModelDataAndOriginalRenderTypes() throws IOException {
		String source = readSource(ADAPTER_SOURCE);

		assertTrue(source.contains("level.getModelDataManager().getAt(pos)"));
		assertTrue(source.contains("modelData == null"));
		assertTrue(source.contains("ModelData.EMPTY"));
		assertTrue(source.contains("model.getModelData(level, pos, state, modelData)"));
		assertTrue(source.contains("model.getRenderTypes(state, renderTypesRandom, modelData)"));
		assertTrue(source.contains("buffer.getBuffer(originalRenderType)"));
		assertTrue(source.contains("originalRenderType);"));
		assertTrue(source.contains("\n\t\t\t\t\tfalse,"));
		assertTrue(source.contains("poseStack.pushPose()"));
		assertTrue(source.contains("poseStack.popPose()"));
		assertTrue(source.contains("finally"));
		assertTrue(source.contains("state.getSeed(pos)"));
		assertTrue(source.contains("RandomSource.create(seed)"));
	}

	@Test
	void backendDoesNotOwnBufferLifecycleOrApplyAnOffset() throws IOException {
		String source = readSource(ADAPTER_SOURCE);

		assertFalse(source.toLowerCase().contains("refinedstorage"));
		assertFalse(source.contains("getOffset("));
		assertFalse(source.contains("renderSingleBlock"));
		assertFalse(source.contains("flush"));
		assertFalse(source.contains("endBatch"));
		assertFalse(source.contains("buffer.close"));
		assertFalse(source.contains("buffer.retain"));
	}

	@Test
	void clientInitializationAlwaysRegistersTheProcessLifetimeBackend() throws IOException {
		String adapterSource = readSource(ADAPTER_SOURCE);
		String clientSource = readSource(CLIENT_SOURCE);

		assertTrue(adapterSource.contains("if (registration != null)"));
		assertTrue(adapterSource.contains("WorldAwareBlockModelOutlineAdapterRegistry.INSTANCE.register"));
		assertTrue(clientSource.contains("ForgeWorldAwareBlockModelOutlineAdapter.register()"));
		assertFalse(clientSource.contains("isModLoaded"));
		assertFalse(clientSource.contains("ClientPlayConnectionEvents"));
	}

	private static String readSource(String source) throws IOException {
		Path fromRoot = Path.of(source);
		Path fromCommonProject = Path.of("..", source);
		Path path = Files.exists(fromRoot) ? fromRoot : fromCommonProject;
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
