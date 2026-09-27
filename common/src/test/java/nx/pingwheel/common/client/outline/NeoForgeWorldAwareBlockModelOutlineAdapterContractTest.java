package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

		assertFalse(source.contains("ModList"));
		assertFalse(source.contains("isLoaded(\"create\")"));
		assertFalse(source.toLowerCase().contains("refinedstorage"));
		assertFalse(source.toLowerCase().contains("rs2"));
		assertFalse(source.contains("getOffset("));
	}

	@Test
	void backendDeclaresNeoForgeWorldModelDataAndRenderTypeHostApis() throws IOException {
		String source = readSource(ADAPTER_SOURCE);

		// Unlike Forge's manager.getAt, NeoForge gets model data from the level.
		assertTrue(source.contains("level.getModelData("));
		assertTrue(source.contains("ModelData.EMPTY"));
		assertTrue(source.contains("model.getModelData("));
		assertTrue(source.contains("model.getRenderTypes("));
		assertTrue(source.contains("buffer.getBuffer("));
		assertTrue(source.contains("renderBatched("));
		assertTrue(source.contains("getSeed("));
		assertTrue(source.contains("RandomSource.create("));
		assertFalse(source.contains("renderSingleBlock("));
		assertFalse(source.contains("flush("));
		assertFalse(source.contains("endBatch("));
	}

	@Test
	void neoClientDeclaresGenericRegistrationOutsideCreateSessionHandlers() throws IOException {
		String source = readSource(NEO_CLIENT_SOURCE);
		String constructor = methodBody(source, "public\\s+NeoClient\\s*\\(");
		String createLoader = methodBody(source, "private\\s+static\\s+void\\s+loadCreateAdapters\\s*\\(");
		String teardown = methodBody(source, "private\\s+static\\s+void\\s+closeCreateAdapters\\s*\\(");

		assertFalse(constructor.isEmpty());
		assertFalse(createLoader.isEmpty());
		assertFalse(teardown.isEmpty());
		assertTrue(constructor.contains("NeoForgeWorldAwareBlockModelOutlineAdapter.register()"));
		assertFalse(createLoader.contains("NeoForgeWorldAwareBlockModelOutlineAdapter"));
		assertFalse(teardown.contains("NeoForgeWorldAwareBlockModelOutlineAdapter"));
	}

	private static String methodBody(String source, String declarationPattern) {
		Matcher declaration = Pattern.compile(declarationPattern).matcher(source);
		if (!declaration.find()) {
			return "";
		}
		int openingBrace = source.indexOf('{', declaration.end());
		if (openingBrace < 0) {
			return "";
		}
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

	private static String readSource(String relativePath) throws IOException {
		for (Path candidate : List.of(
			Path.of(relativePath),
			Path.of("..", relativePath))) {
			if (Files.isRegularFile(candidate)) {
				return Files.readString(candidate).replace("\r\n", "\n");
			}
		}
		throw new IOException("source file not found: " + relativePath);
	}
}
