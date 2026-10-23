package nx.pingwheel.neoforge.integration.create.presentation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production kinetic method, without adding Create to the headless runtime. */
@NotNullByDefault
public class CreatePresentationKineticDiagnosticsTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@ParameterizedTest
	@ValueSource(strings = {"speed", "theoretical", "network", "overstress"})
	void publicGetterFailureDiscardsTheWholeKineticResultAndReportsAtTheConsumingBoundary(String failedGetter) throws Exception {
		Method read = productionRead();
		for (boolean linkage : new boolean[] {false, true}) {
			var entity = new GetterSource(failedGetter, linkage);
			try (var capture = new CreatePresentationDiagnosticCapture()) {
				for (int attempt = 0; attempt < 5; attempt++) {
					assertEquals(CreatePresentationCollector.Kinetic.unavailable(), invoke(read, entity));
				}
				assertEquals(1, capture.events().size());
				var event = capture.events().getFirst();
				assertEquals(Level.WARN, event.getLevel()); assertNull(event.getThrown());
				String report = event.getMessage().getFormattedMessage();
				assertTrue(report.contains("operation=create/presentation-kinetic"), report);
				assertTrue(report.contains("kinetic getter cause"), report);
				@Unmodifiable List<String> expected = switch (failedGetter) {
					case "speed" -> List.of("speed");
					case "theoretical" -> List.of("speed", "theoretical");
					case "network" -> List.of("speed", "theoretical", "network");
					default -> List.of("speed", "theoretical", "network", "stress", "capacity", "overstress");
				};
				assertEquals(expected.size() * 5, entity.reads.size());
				assertEquals(expected, entity.reads.subList(0, expected.size()));
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"stress", "capacity"})
	void cachedGetterFailureKeepsTheExistingSiblingIsolation(String failedGetter) throws Exception {
		Method read = productionRead();
		var entity = new GetterSource(failedGetter, false);
		try (var capture = new CreatePresentationDiagnosticCapture()) {
			var kinetic = invoke(read, entity);
			assertEquals(new CreatePresentationCollector.Kinetic(true, 16f, 32f, true, true, false,
				failedGetter.equals("stress") ? null : 3f, failedGetter.equals("capacity") ? null : 8f), kinetic);
			assertEquals(List.of("speed", "theoretical", "network", "stress", "capacity", "overstress"), entity.reads);
			assertEquals(1, capture.events().size());
			assertTrue(capture.messages().getFirst().contains("operation=create/presentation-cached-" + failedGetter));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"speed", "overstress", "stress"})
	void fatalGetterErrorsStillPropagateWithoutAnUnavailableResultOrLocalReport(String failedGetter) throws Exception {
		Method read = productionRead();
		var entity = new GetterSource(failedGetter, false); entity.fatal = true;
		try (var capture = new CreatePresentationDiagnosticCapture()) {
			assertThrows(OutOfMemoryError.class, () -> invoke(read, entity));
			assertTrue(capture.events().isEmpty());
		}
	}

	private static CreatePresentationCollector.Kinetic invoke(Method read, GetterSource entity) throws Exception {
		try { return (CreatePresentationCollector.Kinetic) read.invoke(null, entity, true, true, true, true, true); }
		catch (InvocationTargetException failure) {
			if (failure.getCause() instanceof Error error) throw error;
			throw failure;
		}
	}

	/**
	 * Copy the compiled production method and its diagnostic lambdas unchanged,
	 * substituting only the optional Create class symbol with a controllable BE.
	 * This executes the real catch/return/getter order, not a source snapshot or a
	 * separately implemented recovery algorithm. No production test API is added.
	 */
	private static Method productionRead() throws Exception {
		String owner = "nx/pingwheel/neoforge/integration/create/presentation/CreatePresentationCollector";
		String harness = owner + "$KineticDiagnosticHarness";
		var original = new ClassNode();
		try (@Nullable InputStream input = CreatePresentationKineticDiagnosticsTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
			if (input == null) throw new IOException("missing compiled collector");
			new ClassReader(input).accept(original, 0);
		}
		var copied = new ClassNode(); copied.version = original.version;
		copied.access = Opcodes.ACC_PUBLIC; copied.name = harness; copied.superName = "java/lang/Object";
		for (var method : original.methods) {
			if (!method.name.equals("readKinetic") && !method.name.startsWith("lambda$readKinetic$")) continue;
			if (method.name.equals("readKinetic")) method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
			for (var instruction : method.instructions) {
				if (instruction instanceof TypeInsnNode type && type.desc.equals("com/simibubi/create/content/kinetics/base/KineticBlockEntity"))
					type.desc = Type.getInternalName(GetterSource.class);
				if (instruction instanceof MethodInsnNode call && call.owner.equals("com/simibubi/create/content/kinetics/base/KineticBlockEntity"))
					call.owner = Type.getInternalName(GetterSource.class);
				if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					for (int index = 0; index < dynamic.bsmArgs.length; index++) {
						if (dynamic.bsmArgs[index] instanceof Handle handle && handle.getOwner().equals(owner))
							dynamic.bsmArgs[index] = new Handle(handle.getTag(), harness, handle.getName(), handle.getDesc(), handle.isInterface());
					}
				}
			}
			// Stack-map local types still name the optional class. Recompute frames
			// after that one type substitution; all other instructions stay intact.
			for (var iterator = method.instructions.iterator(); iterator.hasNext();) {
				if (iterator.next().getType() == org.objectweb.asm.tree.AbstractInsnNode.FRAME) iterator.remove();
			}
			copied.methods.add(method);
		}
		var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		copied.accept(writer);
		return new HarnessLoader().define(harness.replace('/', '.'), writer.toByteArray())
			.getMethod("readKinetic", BlockEntity.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
	}

	@NotNullByDefault
	private static final class HarnessLoader extends ClassLoader {
		private HarnessLoader() { super(CreatePresentationKineticDiagnosticsTest.class.getClassLoader()); }
		private Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
	}

	@NotNullByDefault
	public static final class GetterSource extends BlockEntity implements CreateCachedNetworkView {
		private final String failedGetter;
		private final boolean linkage;
		private boolean fatal;
		private final List<String> reads = new ArrayList<>();
		private GetterSource(String failedGetter, boolean linkage) {
			super(null, BlockPos.ZERO, Blocks.AIR.defaultBlockState());
			this.failedGetter = failedGetter; this.linkage = linkage;
		}
		private void reading(String getter) {
			reads.add(getter);
			if (!failedGetter.equals(getter)) return;
			if (fatal) throw new OutOfMemoryError("synthetic fatal kinetic getter");
			var cause = new RuntimeException("kinetic getter cause");
			if (linkage) throw new LinkageError("kinetic " + getter, cause);
			throw new IllegalStateException("kinetic " + getter, cause);
		}
		public float getSpeed() { reading("speed"); return 16; }
		public float getTheoreticalSpeed() { reading("theoretical"); return 32; }
		public boolean hasNetwork() { reading("network"); return true; }
		public boolean isOverStressed() { reading("overstress"); return false; }
		@Override public float pingforit$cachedNetworkStress() { reading("stress"); return 3; }
		@Override public float pingforit$cachedNetworkCapacity() { reading("capacity"); return 8; }
	}
}
