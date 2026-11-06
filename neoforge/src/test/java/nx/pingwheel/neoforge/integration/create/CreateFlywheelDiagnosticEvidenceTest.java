package nx.pingwheel.neoforge.integration.create;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.client.outline.FlywheelDiagnosticReason;
import nx.pingwheel.common.client.outline.FlywheelDiagnostics;
import nx.pingwheel.common.client.outline.FlywheelModelBudget;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Executes compiled extraction/preflight and detail methods with recording optional API stand-ins, not a Flywheel runtime. */
@NotNullByDefault
class CreateFlywheelDiagnosticEvidenceTest {
	@Test void admittedDeepAndDebugDetailsNeverRepeatModelMaterialOrMeshGetters() throws Exception {
		for (LoggingProfile profile : LoggingProfile.values()) {
			for (String stage : List.of("meshes", "material", "preflight")) {
				Harness harness = new Harness();
				ModelFacts model = new ModelFacts(stage);
				FlywheelDiagnostics diagnostics = FlywheelDiagnostics.global();
				try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture(Level.ALL, profile)) {
					for (int frame = 0; frame < 2; frame++) {
						CreateFlywheelGeometryAttempt.run(attempt -> {
							@Nullable Throwable failure = harness.extract(model, attempt.evidence);
							assertNotNull(failure);
							FlywheelDiagnosticReason reason = harness.reason(failure);
							assertEquals(stage.equals("meshes") ? FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED
								: stage.equals("material") ? FlywheelDiagnosticReason.MATERIAL_INCOMPATIBLE
								: FlywheelDiagnosticReason.BUDGET_EXCEEDED, reason);
							return new CreateFlywheelGeometryAttempt.Result(EntityBlockGeometryOutcome.FAILED, reason,
								() -> "target", () -> harness.details(model), failure);
						}, result -> diagnostics.report("target", result.reason(), result.outcome(), result.category(),
							result.target(), result.details(), result.failure()));
					}
					assertEquals(2, model.meshReads, "one model-list read per failed business attempt under every profile");
					assertEquals(stage.equals("meshes") ? 0 : 2, model.material.textureReads);
					assertEquals(stage.equals("preflight") ? 2 : 0, model.mesh.vertexReads);
					assertEquals(stage.equals("preflight") ? 2 : 0, model.mesh.indexReads);
					assertEquals(stage.equals("meshes") ? 0 : stage.equals("material") ? 8 : 12, model.meshList.sizeReads,
						"evidence and admitted detail add no list size reads to either business failure path");
					assertEquals(1, capture.events.size(), "unchanged attempt is domain-suppressed");
					assertEquals(stage.equals("preflight") ? Level.WARN : Level.ERROR, capture.events.getFirst().getLevel());
					if (profile != LoggingProfile.NORMAL) {
						String detail = capture.events.getFirst().getMessage().getFormattedMessage();
						assertTrue(detail.contains("existing facts"), "detail must complete, not merely catch a forbidden live read");
						assertTrue(detail.contains("modelAttempts=1"));
						assertFalse(detail.contains("context unavailable"));
					}
					if (profile != LoggingProfile.NORMAL && !stage.equals("preflight")) {
						assertTrue(capture.events.getFirst().getMessage().getFormattedMessage().contains("original getter cause"));
					}
				}
			}
		}
	}

	@Test void disabledReportsStillPerformOnlyTheirBusinessReadsAndNoDiagnosticCollection() throws Exception {
		Harness harness = new Harness();
		ModelFacts model = new ModelFacts("material");
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture(Level.OFF, LoggingProfile.DEBUG)) {
			CreateFlywheelGeometryAttempt.run(attempt -> {
				Throwable failure = java.util.Objects.requireNonNull(harness.extract(model, attempt.evidence));
				return new CreateFlywheelGeometryAttempt.Result(EntityBlockGeometryOutcome.FAILED,
					FlywheelDiagnosticReason.MATERIAL_INCOMPATIBLE,
					() -> { throw new AssertionError("disabled target collection"); },
					() -> { throw new AssertionError("disabled diagnostic collection"); }, failure);
			}, result -> FlywheelDiagnostics.global().report("target", result.reason(), result.outcome(), result.category(),
				result.target(), result.details(), result.failure()));
			assertEquals(1, model.meshReads); assertEquals(1, model.material.textureReads);
			assertTrue(capture.events.isEmpty());
		}
	}

	@Test void emptyMeshListSkipsSizeAndReusesTheActualEmptyCacheEntryUnderEveryProfileAndDisabledBackend() throws Exception {
		for (LoggingProfile profile : LoggingProfile.values()) {
			for (Level level : List.of(Level.ALL, Level.OFF)) {
				Harness harness = new Harness();
				ModelFacts model = new ModelFacts("empty");
				Object empty = harness.emptyGeometry();
				FlywheelDiagnostics diagnostics = FlywheelDiagnostics.global();
				try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture(level, profile)) {
					for (int frame = 0; frame < 2; frame++) {
						int expectedCacheHits = frame;
						assertEquals(EntityBlockGeometryOutcome.EMPTY, CreateFlywheelGeometryAttempt.run(attempt -> {
							try {
								return attempt.models(List.of(model), requested -> {
									Object geometry = harness.geometry(requested, attempt.evidence);
									assertSame(empty, geometry, "the production cache must return its EMPTY singleton");
									assertEquals(expectedCacheHits, attempt.evidence.cacheHits);
									assertEquals(0, attempt.evidence.meshCount);
									return geometry;
								}, harness::triangles,
									(reason, detail) -> new CreateFlywheelGeometryAttempt.Result(EntityBlockGeometryOutcome.EMPTY,
										reason, () -> "target", () -> harness.details(model) + "; " + detail.get(), null),
									geometries -> { throw new AssertionError("empty geometry must not reach plan construction"); });
							} catch (InvocationTargetException failure) {
								throw new AssertionError("empty mesh list must stay a cacheable EMPTY result", failure.getCause());
							}
						}, result -> {
							assertEquals(FlywheelDiagnosticReason.NO_INSTANCES, result.reason());
							assertNull(result.failure());
							diagnostics.report("target", result.reason(), result.outcome(), result.category(),
								result.target(), result.details(), result.failure());
						}));
					}
					assertEquals(1, model.meshReads, "the second attempt reuses the actual cached EMPTY geometry");
					assertEquals(1, model.meshList.emptyReads);
					assertEquals(0, model.meshList.sizeReads, "isEmpty=true must never call the throwing size getter");
					assertEquals(0, model.meshList.getReads);
					assertEquals(0, model.material.textureReads + model.mesh.vertexReads + model.mesh.indexReads);
					assertEquals(level == Level.ALL && profile == LoggingProfile.DEBUG ? 1 : 0, capture.events.size());
					assertTrue(capture.events.stream().allMatch(event -> event.getLevel() == Level.DEBUG));
					if (!capture.events.isEmpty()) {
						assertTrue(capture.events.getFirst().getMessage().getFormattedMessage().contains("existing facts"));
					}
				}
			}
		}
	}

	@Test void nonEmptyBudgetCheckSuppliesEvidenceWithOneBusinessSizeReadPerAttemptAcrossProfilesAndDisabledBackend() throws Exception {
		for (LoggingProfile profile : LoggingProfile.values()) {
			for (Level level : List.of(Level.ALL, Level.OFF)) {
				Harness harness = new Harness();
				ModelFacts model = new ModelFacts("budget");
				FlywheelDiagnostics diagnostics = FlywheelDiagnostics.global();
				try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture(level, profile)) {
					for (int frame = 0; frame < 2; frame++) {
						CreateFlywheelGeometryAttempt.run(attempt -> {
							Throwable failure = java.util.Objects.requireNonNull(harness.extract(model, attempt.evidence));
							assertEquals(FlywheelDiagnosticReason.BUDGET_EXCEEDED, harness.reason(failure));
							assertEquals(FlywheelModelBudget.MAX_MESHES_PER_MODEL + 1, attempt.evidence.meshCount);
							return new CreateFlywheelGeometryAttempt.Result(EntityBlockGeometryOutcome.FAILED,
								FlywheelDiagnosticReason.BUDGET_EXCEEDED, () -> "target", () -> harness.details(model), failure);
						}, result -> diagnostics.report("target", result.reason(), result.outcome(), result.category(),
							result.target(), result.details(), result.failure()));
					}
					assertEquals(2, model.meshReads, "a real budget rejection still retries rather than caching failure");
					assertEquals(2, model.meshList.emptyReads);
					assertEquals(2, model.meshList.sizeReads, "evidence and rejection detail reuse the business budget read");
					assertEquals(0, model.meshList.getReads);
					assertEquals(level == Level.ALL ? 1 : 0, capture.events.size());
					assertTrue(capture.events.stream().allMatch(event -> event.getLevel() == Level.WARN));
					if (level == Level.ALL && profile != LoggingProfile.NORMAL) {
						assertTrue(capture.events.getFirst().getMessage().getFormattedMessage().contains("existing facts"));
					}
				}
			}
		}
	}

	@NotNullByDefault
	public static final class ModelFacts {
		private final String stage;
		private int meshReads;
		final MaterialFacts material;
		final MeshFacts mesh = new MeshFacts();
		final @Unmodifiable RecordingMeshes meshList;
		ModelFacts(String stage) {
			this.stage = stage; material = new MaterialFacts(stage);
			meshList = new RecordingMeshes(stage, new ConfiguredFacts(mesh, material));
		}
		public @Unmodifiable List<ConfiguredFacts> meshes() {
			meshReads++;
			if (stage.equals("meshes")) throw new IllegalStateException("original getter cause");
			return meshList;
		}
	}
	@NotNullByDefault
	private static final class RecordingMeshes extends AbstractList<ConfiguredFacts> {
		private final boolean empty;
		private final boolean overBudget;
		private final ConfiguredFacts configured;
		private int emptyReads, sizeReads, getReads;
		private RecordingMeshes(String stage, ConfiguredFacts configured) {
			empty = stage.equals("empty"); overBudget = stage.equals("budget"); this.configured = configured;
		}
		@Override public boolean isEmpty() { emptyReads++; return empty; }
		@Override public int size() {
			sizeReads++;
			if (empty) throw new IllegalStateException("empty mesh list does not expose size");
			return overBudget ? FlywheelModelBudget.MAX_MESHES_PER_MODEL + 1 : 1;
		}
		@Override public ConfiguredFacts get(int index) {
			getReads++;
			if (empty || index != 0) throw new IndexOutOfBoundsException(index);
			return configured;
		}
	}
	@NotNullByDefault
	public record ConfiguredFacts(MeshFacts mesh, MaterialFacts material) {}
	@NotNullByDefault
	public static final class MeshFacts {
		private int vertexReads;
		private int indexReads;
		public int vertexCount() { vertexReads++; return Integer.MAX_VALUE; }
		public int indexCount() { indexReads++; return 3; }
	}
	@NotNullByDefault
	public static final class MaterialFacts {
		private final String stage;
		private int textureReads;
		MaterialFacts(String stage) { this.stage = stage; }
		public ResourceLocation texture() {
			textureReads++;
			if (stage.equals("material")) throw new IllegalStateException("original getter cause");
			return ResourceLocation.parse("test:already_observed_texture");
		}
	}
	@NotNullByDefault
	public static final class EvidenceFacts {
		public int modelAttempts, cacheHits, meshCount, meshIndex, vertexCount, indexCount, extractedMeshes;
		public long extractedTriangles;
		public @Nullable Object model, material;
		public @Nullable ResourceLocation texture;
	}

	/** Substitutes only optional interface symbols; native allocation is forbidden and never reached by these fixtures. */
	@NotNullByDefault
	private static final class Harness extends ClassLoader {
		private static final String ADAPTER = "nx/pingwheel/neoforge/integration/create/CreateFlywheelGeometryAdapter";
		private static final String SOURCE = ADAPTER + "$CreateFlywheelGeometrySource";
		private static final String CACHE = SOURCE + "$ModelGeometryCache";
		private static final String DETAIL = "nx/pingwheel/neoforge/integration/create/FlywheelEvidenceDetailsHarness";
		private static final @Unmodifiable Map<String, String> TYPES = Map.of(
			"dev/engine_room/flywheel/api/model/Model$ConfiguredMesh", Type.getInternalName(ConfiguredFacts.class),
			"dev/engine_room/flywheel/api/model/Model", Type.getInternalName(ModelFacts.class),
			"dev/engine_room/flywheel/api/model/Mesh", Type.getInternalName(MeshFacts.class),
			"dev/engine_room/flywheel/api/material/Material", Type.getInternalName(MaterialFacts.class),
			"nx/pingwheel/neoforge/integration/create/CreateFlywheelGeometryAttempt$Evidence", Type.getInternalName(EvidenceFacts.class));
		private final Object cache;
		private final Method get;
		private final Method describe;
		Harness() throws Exception {
			super(CreateFlywheelDiagnosticEvidenceTest.class.getClassLoader());
			Class<?> cacheType = loadClass(CACHE.replace('/', '.'));
			cache = cacheType.getConstructor().newInstance();
			get = cacheType.getMethod("get", ModelFacts.class, EvidenceFacts.class);
			var source = read(SOURCE);
			var details = new ClassNode(); details.version = source.version; details.access = Opcodes.ACC_PUBLIC;
			details.name = DETAIL; details.superName = "java/lang/Object";
			for (var method : source.methods) {
				if (!List.of("diagnosticDetails", "targetDescription", "transformMode", "className", "identity").contains(method.name)) continue;
				method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
				for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(SOURCE)) call.owner = DETAIL;
				details.methods.add(method);
			}
			var writer = new ClassWriter(0); details.accept(writer);
			byte @Unmodifiable [] bytes = writer.toByteArray();
			describe = defineClass(DETAIL.replace('/', '.'), bytes, 0, bytes.length).getMethod("diagnosticDetails",
				EntityBlockGeometryContext.class, Object.class, Object.class, List.class, Supplier.class);
		}
		@Nullable Throwable extract(ModelFacts model, CreateFlywheelGeometryAttempt.Evidence evidence) {
			try { geometry(model, evidence); return null; }
			catch (InvocationTargetException failure) { return java.util.Objects.requireNonNull(failure.getCause()); }
		}
		Object geometry(ModelFacts model, CreateFlywheelGeometryAttempt.Evidence evidence) throws InvocationTargetException {
			var facts = new EvidenceFacts();
			try { return java.util.Objects.requireNonNull(get.invoke(cache, model, facts)); }
			catch (IllegalAccessException failure) { throw new AssertionError(failure); }
			finally {
				evidence.modelAttempts = facts.modelAttempts; evidence.cacheHits = facts.cacheHits; evidence.meshCount = facts.meshCount;
				evidence.meshIndex = facts.meshIndex; evidence.vertexCount = facts.vertexCount; evidence.indexCount = facts.indexCount;
				evidence.extractedMeshes = facts.extractedMeshes; evidence.extractedTriangles = facts.extractedTriangles;
				evidence.model = facts.model; evidence.material = facts.material; evidence.texture = facts.texture;
			}
		}
		Object emptyGeometry() throws ReflectiveOperationException {
			return java.util.Objects.requireNonNull(get.getReturnType().getField("EMPTY").get(null));
		}
		long triangles(Object geometry) {
			try { return ((Number) geometry.getClass().getMethod("triangleCount").invoke(geometry)).longValue(); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		FlywheelDiagnosticReason reason(Throwable failure) {
			try { return (FlywheelDiagnosticReason) failure.getClass().getMethod("reason").invoke(failure); }
			catch (ReflectiveOperationException reflectionFailure) { throw new AssertionError(reflectionFailure); }
		}
		String details(ModelFacts model) {
			try { return (String) describe.invoke(null, null, null, null, List.of(model), (Supplier<String>) () -> "existing facts"); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			// Include the private cache value types so the rewritten cache class and its
			// returned geometry share this loader and their made-public members.
			if (!name.startsWith(ADAPTER.replace('/', '.') + '$')) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				@Nullable Class<?> loaded = findLoadedClass(name);
				if (loaded == null) try {
					var node = read(name.replace('.', '/')); node.access = publicAccess(node.access); node.nestHostClass = null;
					node.nestMembers = null; node.innerClasses.clear();
					for (var field : node.fields) { field.access = publicAccess(field.access); field.desc = replace(field.desc); }
					for (var method : node.methods) {
						method.access = publicAccess(method.access); method.desc = replace(method.desc);
						if (method.name.equals("extractMesh")) {
							method.instructions.clear(); method.tryCatchBlocks.clear();
							if (method.localVariables != null) method.localVariables.clear();
							method.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError"); method.visitInsn(Opcodes.DUP);
							method.visitLdcInsn("native mesh extraction is outside this preflight fixture");
							method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false);
							method.visitInsn(Opcodes.ATHROW); method.maxStack = 3; continue;
						}
						for (var instruction : method.instructions) {
							if (instruction instanceof MethodInsnNode call) {
								if (TYPES.containsKey(call.owner)) { call.owner = TYPES.get(call.owner); call.itf = false; call.setOpcode(Opcodes.INVOKEVIRTUAL); }
								call.desc = replace(call.desc);
							} else if (instruction instanceof TypeInsnNode type) type.desc = replace(type.desc);
							else if (instruction instanceof FieldInsnNode field) { field.owner = replace(field.owner); field.desc = replace(field.desc); }
						}
						// Only reference types changed; recompute stack frames against the stand-ins.
						for (var iterator = method.instructions.iterator(); iterator.hasNext();) {
							if (iterator.next().getType() == org.objectweb.asm.tree.AbstractInsnNode.FRAME) iterator.remove();
						}
						if (method.localVariables != null) method.localVariables.clear();
					}
					var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
					node.accept(writer); byte @Unmodifiable [] bytes = writer.toByteArray();
					loaded = defineClass(name, bytes, 0, bytes.length);
				} catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
				if (resolve) resolveClass(loaded); return loaded;
			}
		}
		private static int publicAccess(int access) { return access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC; }
		private static String replace(String value) {
			// ConfiguredMesh must be replaced before its enclosing Model prefix.
			value = value.replace("dev/engine_room/flywheel/api/model/Model$ConfiguredMesh", Type.getInternalName(ConfiguredFacts.class));
			for (var entry : TYPES.entrySet()) value = value.replace(entry.getKey(), entry.getValue());
			return value;
		}
		private static ClassNode read(String name) throws Exception {
			try (@Nullable InputStream input = CreateFlywheelDiagnosticEvidenceTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
				assertNotNull(input); var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
			}
		}
	}
}
