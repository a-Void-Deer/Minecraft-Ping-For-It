package nx.pingwheel.common.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the input anchors used by {@code KeyMappingMixin}.
 *
 * <p>{@code click}/{@code set} are cancelled at {@code HEAD} by AMEcs (bundled
 * with some modpacks through {@code backslot}), so a TAIL injection on those
 * methods never runs there. {@code setDown} is called by both vanilla and
 * AMEcs and must remain the compatibility anchor.</p>
 */
class KeyMappingMixinTest {

	@Test
	void setDownInjectionIsTheAmeCsCompatibilityAnchor() {
		List<String> annotationStrings = injectionAnnotationStrings(
			"pingforit$onSetDown",
			"(ZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");

		assertTrue(annotationStrings.contains("setDown"),
			"setDown must be the injected target: " + annotationStrings);
		assertTrue(annotationStrings.contains("TAIL"),
			"setDown must be observed after the down state is applied: " + annotationStrings);
	}

	@Test
	void vanillaClickAndSetPathsRemainRegistered() {
		assertTrue(injectionAnnotationStrings(
			"pingforit$onClick",
			"(Lcom/mojang/blaze3d/platform/InputConstants$Key;"
				+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
			.contains("click"));
		assertTrue(injectionAnnotationStrings(
			"pingforit$onSet",
			"(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z"
				+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
			.contains("set"));
	}

	private static List<String> injectionAnnotationStrings(String methodName, String descriptor) {
		List<String> values = new ArrayList<>();
		String resourceName = "nx/pingwheel/common/mixin/KeyMappingMixin.class";

		try (InputStream stream = KeyMappingMixinTest.class
			.getClassLoader().getResourceAsStream(resourceName)) {
			assertNotNull(stream, "compiled mixin class not found: " + resourceName);

			new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(
					int access, String name, String methodDescriptor, String signature, String[] exceptions
				) {
					if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) {
						return null;
					}

					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
							return "Lorg/spongepowered/asm/mixin/injection/Inject;"
								.equals(annotationDescriptor)
								? new StringValueCollector(values) : null;
						}
					};
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (IOException failure) {
			throw new AssertionError("unable to read " + resourceName, failure);
		}

		assertFalse(values.isEmpty(), "missing @Inject on " + methodName);
		return values;
	}

	private static final class StringValueCollector extends AnnotationVisitor {
		private final List<String> values;

		private StringValueCollector(List<String> values) {
			super(Opcodes.ASM9);
			this.values = values;
		}

		@Override
		public void visit(String name, Object value) {
			if (value instanceof String string) {
				this.values.add(string);
			}
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			return new AnnotationVisitor(Opcodes.ASM9) {
				@Override
				public void visit(String entryName, Object value) {
					if (value instanceof String string) {
						StringValueCollector.this.values.add(string);
					}
				}

				@Override
				public AnnotationVisitor visitAnnotation(String entryName, String descriptor) {
					return new StringValueCollector(StringValueCollector.this.values);
				}
			};
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String descriptor) {
			return new StringValueCollector(this.values);
		}
	}
}
