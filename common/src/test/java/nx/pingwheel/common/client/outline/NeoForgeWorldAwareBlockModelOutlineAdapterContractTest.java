package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeWorldAwareBlockModelOutlineAdapterContractTest {
	private static final String ADAPTER_SOURCE =
		"neoforge/src/main/java/nx/pingwheel/neoforge/integration/"
			+ "NeoForgeWorldAwareBlockModelOutlineAdapter.java";
	private static final String NEO_CLIENT_SOURCE =
		"neoforge/src/main/java/nx/pingwheel/neoforge/NeoClient.java";

	@Test
	void genericBackendHasNoOptionalModOrRs2Gate() throws IOException {
		String source = readSource(ADAPTER_SOURCE);

		assertTrue(source.contains("public static final String SOURCE_ID = "
			+ "\"pingforit:neoforge_world_baked_model\""));
		assertFalse(source.contains("ModList"));
		assertFalse(source.contains("isLoaded(\"create\")"));
		assertFalse(source.toLowerCase().contains("refinedstorage"));
		assertFalse(source.toLowerCase().contains("rs2"));
		assertFalse(source.contains("getOffset"));
	}

	@Test
	void worldModelDataAndRenderTypeContractIsPreserved() throws IOException {
		String source = readSource(ADAPTER_SOURCE);

		assertTrue(source.contains("ModelData modelData = level.getModelData(pos);"));
		assertTrue(source.contains("modelData = ModelData.EMPTY;"));
		assertTrue(source.contains("modelData = model.getModelData(level, pos, state, modelData);"));
		assertTrue(source.contains("model.getRenderTypes(state, renderTypesRandom, modelData)"));
		assertTrue(source.contains("VertexConsumer consumer = buffer.getBuffer(originalRenderType);"));
		assertTrue(source.contains("modelData,\n\t\t\t\t\toriginalRenderType"));
		assertTrue(source.contains("\t\t\t\t\tfalse,"));
		assertTrue(source.contains("poseStack.pushPose();"));
		assertTrue(source.contains("} finally {\n\t\t\t\tposeStack.popPose();"));
		assertFalse(source.contains("renderSingleBlock"));
		assertFalse(source.contains("flush"));
		assertFalse(source.contains("endBatch"));
	}

	@Test
	void genericRegistrationIsNotOwnedByCreateSessionTeardown() throws IOException {
		String source = readSource(NEO_CLIENT_SOURCE);
		int genericRegistration = source.indexOf(
			"NeoForgeWorldAwareBlockModelOutlineAdapter.register();");
		int createLoading = source.indexOf("loadCreateAdapters();");
		int createTeardown = source.indexOf("closeCreateAdapters");

		assertTrue(genericRegistration >= 0);
		assertTrue(createLoading > genericRegistration);
		assertTrue(createTeardown >= 0);
		String teardown = source.substring(createTeardown);
		assertFalse(teardown.contains("NeoForgeWorldAwareBlockModelOutlineAdapter"));
	}

	private static String readSource(String relativePath) throws IOException {
		for (Path candidate : List.of(
			Path.of(relativePath),
			Path.of("..", relativePath))) {
			if (Files.isRegularFile(candidate)) {
				return Files.readString(candidate);
			}
		}
		throw new IOException("source file not found: " + relativePath);
	}
}
