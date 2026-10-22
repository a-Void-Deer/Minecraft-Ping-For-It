package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalGeometryClassSafetyTest {
	@Test
	void commonGeometryCoreLoadsWhenOptionalClassesAreAbsent() {
		assertThrows(ClassNotFoundException.class,
			() -> Class.forName("dev.engine_room.flywheel.api.model.Model"));
		assertThrows(ClassNotFoundException.class,
			() -> Class.forName("com.simibubi.create.content.kinetics.base.RotatingInstance"));
		assertDoesNotThrow(() -> Class.forName(EntityBlockGeometryContext.class.getName()));
		assertDoesNotThrow(() -> Class.forName(FlywheelSilhouetteMask.class.getName()));
	}

	@Test
	void commonOutcomeMappingDistinguishesEmptyAndEmittedGeometry() {
		assertEquals(
			EntityBlockGeometryOutcome.EMPTY,
			EntityBlockGeometryOutcome.fromEmittedVertices(0));
		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			EntityBlockGeometryOutcome.fromEmittedVertices(1));
		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			EntityBlockGeometryOutcome.fromEmittedVertices(Integer.MAX_VALUE));
	}

	@Test
	void optionalAdapterDeclaresIndirectInstancerApiWithoutVisibilityMutation() throws IOException {
		Path source = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateFlywheelGeometryAdapter.java"));
		assertTrue(Files.isRegularFile(source), "NeoForge Flywheel adapter source must be present");

		String adapter = Files.readString(source, StandardCharsets.UTF_8);
		assertTrue(adapter.contains("IndirectInstancer.fromState("));
		assertFalse(adapter.contains("isVisible("));
		assertFalse(adapter.contains("setVisible("));
		assertFalse(adapter.contains("setDeleted("));
	}

	@Test
	void flywheelAdapterDeclaresMainOriginAndCommonTransformPolicyWiring() throws IOException {
		Path adapterPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateFlywheelGeometryAdapter.java"));
		String adapter = Files.readString(adapterPath, StandardCharsets.UTF_8);

		// Executable common tests cover the transform and policy outcomes; this
		// static seam only guards the optional Flywheel adapter's wiring to them.
		assertTrue(adapter.contains("manager.renderOrigin()"));
		assertTrue(adapter.contains("cameraRelativeEnvironmentVertex("));
		assertTrue(adapter.contains("FlywheelEnvironmentPolicy.accepts("));
	}

	@Test
	void createEntityAdapterDeclaresReflectiveProbeAndBoundedOutlineRoute() throws IOException {
		Path adapterPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateEntityOutlineAdapter.java"));
		Path neoClientPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge", "NeoClient.java"));
		assertTrue(Files.isRegularFile(adapterPath), "NeoForge entity adapter source must be present");
		assertTrue(Files.isRegularFile(neoClientPath), "NeoForge client source must be present");

		String adapter = Files.readString(adapterPath, StandardCharsets.UTF_8);
		String neoClient = Files.readString(neoClientPath, StandardCharsets.UTF_8);

		assertTrue(adapter.contains("pingforit:create_entity_outline"));
		assertTrue(adapter.contains("SuperGlueEntity"));
		assertTrue(adapter.contains("AbstractContraptionEntity"));
		assertTrue(adapter.contains("PackageEntity"));
		assertTrue(adapter.contains("\"dev.engine_room.flywheel.api.visualization.VisualizationManager\""));
		assertTrue(adapter.contains("\"supportsVisualization\""));
		assertTrue(adapter.contains("Class.forName("));
		assertTrue(Pattern.compile("getMethod\\s*\\(\\s*SUPPORTS_VISUALIZATION_METHOD\\s*,"
			+ "\\s*LevelAccessor\\.class\\s*\\)").matcher(adapter).find());
		assertTrue(adapter.contains(".invoke("));
		assertTrue(adapter.contains("InvocationTargetException"));
		assertFalse(adapterContainsDirectFlywheelLink(adapter));
		// The integration owner fixes the six-quad/24-vertex mask and 262144-
		// vertex cap. Their declarations and guards are structural evidence only,
		// not proof of a complete live dispatch or a once-only render call.
		assertTrue(declaresIntConstant(adapter, "EXPECTED_QUADS", "6"));
		assertTrue(declaresIntConstant(adapter, "EXPECTED_VERTICES", "24"));
		assertTrue(declaresIntConstant(adapter, "MAX_RENDER_VERTICES", "262_?144"));
		assertTrue(Pattern.compile("\\.quads\\(\\)\\.size\\(\\)\\s*!=\\s*EXPECTED_QUADS\\b")
			.matcher(adapter).find());
		assertTrue(Pattern.compile("\\.count\\s*!=\\s*EXPECTED_VERTICES\\b")
			.matcher(adapter).find());
		assertTrue(Pattern.compile("try\\s*\\(\\s*CreateEntityOutlineMaskScope\\.Scope\\b[^;]*"
			+ "CreateEntityOutlineMaskScope\\.enter\\(\\)\\s*\\)", Pattern.DOTALL)
			.matcher(adapter).find());
		assertTrue(Pattern.compile("new\\s+OutlineOnlyBufferSource\\s*\\([^;]*\\bMAX_RENDER_VERTICES\\s*\\)",
			Pattern.DOTALL).matcher(adapter).find());
		assertTrue(adapter.contains("EntityRenderDispatcher"));
		assertTrue(adapter.contains("dispatcher.render("));
		assertTrue(adapter.contains("TextureAtlas.LOCATION_BLOCKS"));
		assertTrue(adapter.contains("getPackedLightCoords("));
		assertTrue(adapter.contains("AabbOutlineMask.cameraRelative("));
		assertTrue(adapter.contains("textures/special/glue.png"));

		String code = normalizeJavaSource(adapter);
		assertFalse(code.contains("endOutlineBatch("));
		assertFalse(code.contains("setVisible("));
		assertFalse(code.contains("setDeleted("));
		assertFalse(code.contains("delete("));
		assertFalse(code.contains("setGlowing("));
		assertFalse(code.contains("renderBuffers()"));

		assertTrue(Pattern.compile("CREATE_ENTITY_ADAPTER\\s*=\\s*\""
			+ Pattern.quote("nx.pingwheel.neoforge.integration.create.CreateEntityOutlineAdapter")
			+ "\"").matcher(neoClient).find());
		assertTrue(neoClient.contains("ModList.get().isLoaded(\"create\")"));
		assertTrue(neoClient.contains("Class.forName("));
		assertTrue(Pattern.compile("if\\s*\\(\\s*createDetected\\s*\\)\\s*\\{[^{}]*"
			+ "registerOptionalAdapter\\s*\\(\\s*CREATE_ENTITY_ADAPTER\\b", Pattern.DOTALL)
			.matcher(neoClient).find());
	}

	@Test
	void optionalVisualizationMixinDeclaresExactTargetAndInjectionAbi() throws IOException {
		Path mixinPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "common", "mixin",
			"CreateVisualizationManagerMixin.java"));
		Path configPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "resources", "pingforit.mixins.json"));
		Path adapterPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateEntityOutlineAdapter.java"));
		assertTrue(Files.isRegularFile(mixinPath));
		assertTrue(Files.isRegularFile(configPath));
		assertTrue(Files.isRegularFile(adapterPath));

		String mixin = Files.readString(mixinPath, StandardCharsets.UTF_8);
		String config = Files.readString(configPath, StandardCharsets.UTF_8);
		String adapter = Files.readString(adapterPath, StandardCharsets.UTF_8);
		String normalizedMixin = normalizeJavaSource(mixin);

		assertTrue(normalizedMixin.contains("@Pseudo"));
		assertTrue(normalizedMixin.contains(
			"public interface CreateVisualizationManagerMixin"),
			"the optional target is an interface and must use an interface mixin");
		assertTrue(normalizedMixin.contains(
			"@Mixin(targets = \"dev.engine_room.flywheel.api.visualization.VisualizationManager\", remap = false)"),
			"the Flywheel target must remain a string target with remapping disabled");
		String injectBlock = extractAnnotationBlock(normalizedMixin, "@Inject(");
		String visualizationMethodDescriptor =
			"supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z";
		assertTrue(!injectBlock.isEmpty(), "the optional mixin must declare an @Inject block");
		assertTrue(injectBlock.contains("method = \"" + visualizationMethodDescriptor + "\""),
			"the @Inject must target the exact supportsVisualization descriptor");
		assertTrue(normalizedMixin.contains("at = @At(\"HEAD\")"));
		assertTrue(normalizedMixin.contains("cancellable = true"));
		assertTrue(normalizedMixin.contains("require = 0"));
		assertTrue(injectBlock.contains("remap = false"),
			"the supportsVisualization injection must disable remapping at injection level");
		assertTrue(normalizedMixin.contains("CallbackInfoReturnable<Boolean>"),
			"the handler must receive the boolean returnable callback");
		assertTrue(Pattern.compile("\\bstatic\\s+void\\s+\\w+\\s*\\(\\s*LevelAccessor\\s+\\w+\\s*,"
			+ "\\s*CallbackInfoReturnable<Boolean>\\s+\\w+\\s*\\)")
			.matcher(normalizedMixin).find(), "the optional mixin handler must be static with the host callback ABI");
		assertTrue(mixin.contains("CreateEntityOutlineMaskScope.active()"));
		assertFalse(adapterContainsDirectFlywheelLink(adapter));
		assertTrue(config.contains("\"CreateVisualizationManagerMixin\""));
	}

	private static String normalizeJavaSource(String source) {
		return source
			.replaceAll("(?s)/\\*.*?\\*/", "")
			.replaceAll("(?m)//[^\\r\\n]*", "")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private static boolean adapterContainsDirectFlywheelLink(String adapter) {
		return adapter.contains("import dev.engine_room.flywheel")
			|| adapter.contains("VisualizationManager.class")
			|| adapter.contains("VisualizationManager.");
	}

	private static boolean declaresIntConstant(String source, String name, String valuePattern) {
		return Pattern.compile("\\bint\\s+" + name + "\\s*=\\s*" + valuePattern + "\\s*;")
			.matcher(source).find();
	}

	private static String extractAnnotationBlock(String normalizedSource, String annotation) {
		int annotationStart = normalizedSource.indexOf(annotation);
		if (annotationStart < 0) {
			return "";
		}

		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int index = annotationStart + annotation.length() - 1;
			index < normalizedSource.length();
			index++) {
			char character = normalizedSource.charAt(index);
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (character == '\\') {
					escaped = true;
				} else if (character == '"') {
					inString = false;
				}
				continue;
			}

			if (character == '"') {
				inString = true;
			} else if (character == '(') {
				depth++;
			} else if (character == ')' && --depth == 0) {
				return normalizedSource.substring(annotationStart, index + 1);
			}
		}
		return "";
	}

	private static Path findRepositoryFile(Path relativePath) {
		Path directory = Path.of("").toAbsolutePath();
		while (directory != null) {
			Path candidate = directory.resolve(relativePath);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
			directory = directory.getParent();
		}
		return Path.of("__missing_repository_file__").resolve(relativePath);
	}
}
