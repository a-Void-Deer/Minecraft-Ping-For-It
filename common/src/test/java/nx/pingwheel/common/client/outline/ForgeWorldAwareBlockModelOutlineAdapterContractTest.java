package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
	void backendDeclaresForgeModelDataAndRenderTypeHostApis() throws IOException {
		String source = readSource(ADAPTER_SOURCE);

		// Forge uses the manager's getAt API; this does not execute a live model.
		assertTrue(source.contains("getModelDataManager("));
		assertTrue(source.contains(".getAt("));
		assertTrue(source.contains("ModelData.EMPTY"));
		assertTrue(source.contains("model.getModelData("));
		assertTrue(source.contains("model.getRenderTypes("));
		assertTrue(source.contains("buffer.getBuffer("));
		assertTrue(source.contains("renderBatched("));
		assertTrue(source.contains("getSeed("));
		assertTrue(source.contains("RandomSource.create("));
	}

	@Test
	void backendDeclaresNoBufferLifecycleOrExtraOffsetCalls() throws IOException {
		String source = readSource(ADAPTER_SOURCE);

		assertFalse(source.toLowerCase().contains("refinedstorage"));
		assertFalse(source.contains("getOffset("));
		assertFalse(source.contains("renderSingleBlock("));
		assertFalse(source.contains("flush("));
		assertFalse(source.contains("endBatch("));
		assertFalse(source.contains("buffer.close("));
		assertFalse(source.contains("buffer.retain("));
	}

	@Test
	void clientConstructorDeclaresGenericBackendRegistration() throws IOException {
		String adapterSource = readSource(ADAPTER_SOURCE);
		String clientSource = readSource(CLIENT_SOURCE);

		assertTrue(adapterSource.contains("WorldAwareBlockModelOutlineAdapterRegistry.INSTANCE.register("));
		assertTrue(Pattern.compile("public\\s+ForgeClient\\s*\\(\\s*\\)\\s*\\{[^{}]*"
			+ "ForgeWorldAwareBlockModelOutlineAdapter\\.register\\s*\\(\\s*\\)", Pattern.DOTALL)
			.matcher(clientSource).find());
	}

	private static String readSource(String source) throws IOException {
		Path fromRoot = Path.of(source);
		Path fromCommonProject = Path.of("..", source);
		Path path = Files.exists(fromRoot) ? fromRoot : fromCommonProject;
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
