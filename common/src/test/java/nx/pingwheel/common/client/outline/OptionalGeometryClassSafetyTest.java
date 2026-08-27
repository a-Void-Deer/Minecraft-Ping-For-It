package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
	void commonSourceOutcomeContractRemainsIndependentOfOptionalClasses() {
		assertEquals(
			EntityBlockGeometryOutcome.EMPTY,
			EntityBlockGeometryOutcome.fromEmittedVertices(0));
		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			EntityBlockGeometryOutcome.fromEmittedVertices(1));
		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			EntityBlockGeometryOutcome.fromEmittedVertices(Integer.MAX_VALUE));
		assertEquals(
			List.of(
				EntityBlockGeometryOutcome.RENDERED,
				EntityBlockGeometryOutcome.EMPTY,
				EntityBlockGeometryOutcome.FAILED),
			List.of(EntityBlockGeometryOutcome.values()));
	}

	@Test
	void optionalAdapterUsesIndirectStateResolutionWithoutVisibilityMutation() throws IOException {
		Path source = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateFlywheelGeometryAdapter.java"));
		assertTrue(Files.isRegularFile(source), "NeoForge Flywheel adapter source must be present");

		String adapter = Files.readString(source, StandardCharsets.UTF_8);
		assertTrue(adapter.contains("resolveLiveInstancer"));
		assertTrue(adapter.contains("IndirectInstancer.fromState(state)"));
		assertFalse(adapter.contains("isVisible("));
		assertFalse(adapter.contains("setVisible("));
		assertFalse(adapter.contains("setDeleted("));
	}

	@Test
	void flywheelAdapterKeepsMainAndEmbeddedVertexContracts() throws IOException {
		Path adapterPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateFlywheelGeometryAdapter.java"));
		Path transformPath = findRepositoryFile(Path.of(
			"common", "src", "main", "java", "nx", "pingwheel", "common", "client",
			"outline", "EntityBlockGeometryTransform.java"));
		String adapter = Files.readString(adapterPath, StandardCharsets.UTF_8);
		String transform = Files.readString(transformPath, StandardCharsets.UTF_8);

		assertTrue(adapter.contains("instancer.environment == GlobalEnvironment.INSTANCE"));
		assertTrue(adapter.contains("manager.renderOrigin()"));
		assertTrue(adapter.contains("position.x() + originX"));
		assertTrue(adapter.contains("position.y() + originY"));
		assertTrue(adapter.contains("position.z() + originZ"));
		assertTrue(adapter.contains("context.transform().cameraRelativeEnvironmentVertex("));
		assertTrue(adapter.contains("entry.environmentOrigin()"));
		assertTrue(adapter.contains("FlywheelEnvironmentPolicy.accepts("));
		assertFalse(adapter.contains("Sable"));

		assertTrue(transform.contains("environmentOrigin.getX() + localVertex.x()"));
		String normalizedTransform = normalizeJavaSource(transform);
		int vectorConstructor = normalizedTransform.indexOf("new Vector3f(");
		assertTrue(vectorConstructor >= 0, "transform must construct the output vector");
		String firstArgument = normalizedTransform
			.substring(vectorConstructor + "new Vector3f(".length())
			.trim();
		assertTrue(firstArgument.startsWith("(float) worldPosition.x"),
			"worldPosition.x must be the first Vector3f argument");
		assertTrue(normalizedTransform.contains("(float) worldPosition.y"));
		assertTrue(normalizedTransform.contains("(float) worldPosition.z"));
	}

	@Test
	void createEntityAdapterIsReflectiveAndUsesTheCompleteFallbackRenderPass() throws IOException {
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
		assertTrue(adapter.contains("SUPPORTS_VISUALIZATION_METHOD"));
		assertTrue(adapter.contains("managerClass.getMethod(SUPPORTS_VISUALIZATION_METHOD, LevelAccessor.class)"));
		assertTrue(adapter.contains("method.invoke(null, level)"));
		assertTrue(adapter.contains("InvocationTargetException"));
		assertTrue(adapter.contains("cause instanceof RuntimeException"));
		assertTrue(adapter.contains("cause instanceof Error"));
		assertFalse(adapter.contains("import dev.engine_room.flywheel"));
		assertFalse(adapter.contains("VisualizationManager."));
		assertFalse(adapter.contains("VisualizationManager.class"));
		assertFalse(adapter.contains("invokestatic"));
		assertTrue(adapter.contains("private static final int EXPECTED_QUADS = 6;"));
		assertTrue(adapter.contains("private static final int EXPECTED_VERTICES = 24;"));
		assertTrue(adapter.contains("mask.quads().size() != EXPECTED_QUADS"));
		assertTrue(adapter.contains("counter.count != EXPECTED_VERTICES"));
		int addVertex = adapter.indexOf("VertexConsumer vertex = consumer.addVertex(x, y, z);");
		int countAfterPosition = adapter.indexOf("counter.count++;", addVertex);
		int attributesAfterCount = adapter.indexOf(".setColor(red(color), green(color), blue(color), 255)", countAfterPosition);
		assertTrue(addVertex >= 0, "SuperGlue must emit through addVertex");
		assertTrue(countAfterPosition > addVertex, "SuperGlue must count after addVertex commits");
		assertTrue(attributesAfterCount > countAfterPosition,
			"SuperGlue must count before color/UV attributes");
		assertTrue(adapter.contains("CreateEntityOutlineMaskScope.enter()"));
		assertTrue(adapter.contains("try (CreateEntityOutlineMaskScope.Scope"));
		assertTrue(adapter.contains("OutlineOnlyBufferSource"));
		assertTrue(adapter.contains("TextureAtlas.LOCATION_BLOCKS"));
		assertTrue(adapter.contains("MAX_RENDER_VERTICES = 262_144"));
		assertTrue(adapter.contains("getPackedLightCoords"));
		assertTrue(adapter.contains("AabbOutlineMask.cameraRelative"));
		assertTrue(adapter.contains("textures/special/glue.png"));
		assertTrue(adapter.contains("0.0F, 0.0F"));
		assertTrue(adapter.contains("1.0F, 0.0F"));
		assertTrue(adapter.contains("1.0F, 1.0F"));
		assertTrue(adapter.contains("0.0F, 1.0F"));
		assertTrue(adapter.contains("0xFF000000 | (context.spec().argbColor() & 0x00FFFFFF)"));
		assertTrue(adapter.contains("RENDERED"));
		assertTrue(adapter.contains("partial-render-exception"));
		assertTrue(adapter.contains("vertexCount"));
		assertTrue(adapter.contains("printStackTrace"));
		assertTrue(adapter.contains("MIN_LOG_INTERVAL_NANOS = 1_000_000_000L"));
		assertTrue(adapter.contains("HEARTBEAT_NANOS = 5_000_000_000L"));
		assertTrue(adapter.contains("observedSinceNanos"));
		assertEquals(1, occurrences(adapter, "dispatcher.render("),
			"the explicit compatibility pass must issue exactly one dispatcher render call");

		assertFalse(adapter.contains("endOutlineBatch"));
		assertFalse(adapter.contains("setVisible("));
		assertFalse(adapter.contains("setDeleted("));
		assertFalse(adapter.contains("delete("));
		assertFalse(adapter.contains("setGlowing("));
		assertFalse(adapter.contains("mainBuffer"));
		assertFalse(adapter.contains("renderBuffers()"));

		assertTrue(neoClient.contains("CREATE_ENTITY_ADAPTER"));
		assertTrue(neoClient.contains("ModList.get().isLoaded(\"create\")"));
		assertTrue(neoClient.contains("Class.forName(className"));
		assertTrue(neoClient.contains("registerOptionalAdapter(CREATE_ENTITY_ADAPTER"));
	}

	@Test
	void createMaskScopeAndOptionalVisualizationMixinAreStructurallyScoped() throws IOException {
		Path scopePath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateEntityOutlineMaskScope.java"));
		Path mixinPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "common", "mixin",
			"CreateVisualizationManagerMixin.java"));
		Path configPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "resources", "pingforit.mixins.json"));
		Path adapterPath = findRepositoryFile(Path.of(
			"neoforge", "src", "main", "java", "nx", "pingwheel", "neoforge",
			"integration", "create", "CreateEntityOutlineAdapter.java"));
		assertTrue(Files.isRegularFile(scopePath));
		assertTrue(Files.isRegularFile(mixinPath));
		assertTrue(Files.isRegularFile(configPath));
		assertTrue(Files.isRegularFile(adapterPath));

		String scope = Files.readString(scopePath, StandardCharsets.UTF_8);
		String mixin = Files.readString(mixinPath, StandardCharsets.UTF_8);
		String config = Files.readString(configPath, StandardCharsets.UTF_8);
		String adapter = Files.readString(adapterPath, StandardCharsets.UTF_8);
		String normalizedMixin = normalizeJavaSource(mixin);

		assertTrue(scope.contains("ThreadLocal<Integer>"));
		assertTrue(scope.contains("public static Scope enter()"));
		assertTrue(scope.contains("public static boolean active()"));
		assertTrue(scope.contains("DEPTH.remove()"));
		assertTrue(scope.contains("if (closed)"));

		assertTrue(normalizedMixin.contains("@Pseudo"));
		assertTrue(normalizedMixin.contains(
			"public interface CreateVisualizationManagerMixin"),
			"the optional target is an interface and must use an interface mixin");
		assertTrue(normalizedMixin.contains(
			"@Mixin(targets = \"dev.engine_room.flywheel.api.visualization.VisualizationManager\", remap = false)"),
			"the Flywheel target must remain a string target with remapping disabled");
		assertTrue(mixin.contains("dev.engine_room.flywheel.api.visualization.VisualizationManager"));
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
		assertTrue(normalizedMixin.contains("private static void pingForItDisableVisualization("),
			"the optional mixin handler must be static");
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

	private static int occurrences(String text, String needle) {
		int count = 0;
		int offset = 0;
		while ((offset = text.indexOf(needle, offset)) >= 0) {
			count++;
			offset += needle.length();
		}
		return count;
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
