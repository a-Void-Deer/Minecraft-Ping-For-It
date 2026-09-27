package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
	void backendDeclaresFabricWorldAwareHostApiRoute() throws IOException {
		String source = readFabricSource(ADAPTER_SOURCE);

		// Static host-API linkage, not proof of emitted geometry or pose values.
		assertTrue(source.contains("createPoseStack("));
		assertTrue(source.contains("buffer.getBuffer("));
		assertTrue(source.contains("renderBatched("));
		assertTrue(source.contains("getSeed("));
		assertTrue(source.contains("RandomSource.create("));
		assertFalse(source.contains(".getOffset("));
		assertFalse(source.contains("renderSingleBlock("));
		assertFalse(source.contains("endBatch("));
		assertFalse(source.contains("flush("));
	}

	@Test
	void clientInitializerDeclaresUnconditionalGenericBackendRegistration() throws IOException {
		String adapterSource = readFabricSource(ADAPTER_SOURCE);
		String clientSource = readFabricSource(CLIENT_SOURCE);

		assertFalse(adapterSource.toLowerCase().contains("refinedstorage"));
		assertTrue(adapterSource.contains("WorldAwareBlockModelOutlineAdapterRegistry.INSTANCE.register("));
		assertTrue(Pattern.compile("onInitializeClient\\s*\\(\\s*\\)\\s*\\{\\s*"
			+ "FabricWorldAwareBlockModelOutlineAdapter\\.register\\s*\\(\\s*\\)")
			.matcher(clientSource).find());
	}

	private static String readFabricSource(String source) throws IOException {
		Path fromRoot = Path.of(source);
		Path fromCommonProject = Path.of("..", source);
		Path path = Files.exists(fromRoot) ? fromRoot : fromCommonProject;
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
