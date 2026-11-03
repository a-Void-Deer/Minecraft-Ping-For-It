package nx.pingwheel.forge.platform;

import java.util.ServiceLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
import nx.pingwheel.common.platform.client.IPlatformFluidRenderService;
import nx.pingwheel.common.platform.client.FluidSprites;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;

/** Reflection keeps loader-only types off the existing vanilla-first test compile classpath. */
@NotNullByDefault
class FluidPresentationServiceTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); }
	@Test void bothServicesDiscoverAndRenderConstructorDoesNotRequireMinecraftOrAtlas() {
		var names = new PlatformFluidDescriptionServiceImpl();
		var sprites = new nx.pingwheel.forge.platform.client.PlatformFluidRenderServiceImpl();
		assertTrue(ServiceLoader.load(IPlatformFluidDescriptionService.class).stream().anyMatch(p -> p.type() == names.getClass()));
		assertTrue(ServiceLoader.load(IPlatformFluidRenderService.class).stream().anyMatch(p -> p.type() == sprites.getClass()));
	}
	@Test void realPatchedFluidTypeBaseDescriptionAndLiteralOverrideUseNoNativeStack() throws Exception {
		boolean patched;
		try { Fluid.class.getMethod("getFluidType"); patched = true; } catch (NoSuchMethodException vanillaFirst) { patched = false; }
		org.junit.jupiter.api.Assumptions.assumeTrue(patched, "Forge's ordinary test task is vanilla-first; native success runs on the patched integration classpath");
		var fixture = new NativeFluid(); var fluid = fixture.fluid;
		var names = new PlatformFluidDescriptionServiceImpl();
		fixture.name(Component.translatable("fluid.test.forge"));
		assertEquals(new IPlatformFluidDescriptionService.Translation("fluid.test.forge", null), names.describeBase(fluid));
		fixture.name(Component.literal("Native Forge literal"));
		assertEquals(new IPlatformFluidDescriptionService.Literal("Native Forge literal"), names.describeBase(fluid));
		var sprites = new nx.pingwheel.forge.platform.client.PlatformFluidRenderServiceImpl();
		assertNull(sprites.stillSprite(fluid), "default null texture requires no atlas lookup");
		assertNull(FluidSprites.stillSprite(fluid, sprites));
		var extensions = Class.forName("net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions");
		int[] calls = {0};
		Object extension = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {extensions}, (proxy, method, args) -> {
			assertEquals("getStillTexture", method.getName()); assertEquals(0, method.getParameterCount(), "base overload, not stack/amount/world"); calls[0]++;
			return net.minecraft.resources.ResourceLocation.parse("test:native_still");
		});
		var renderProperties = Class.forName("net.minecraftforge.fluids.FluidType").getDeclaredField("renderProperties"); renderProperties.setAccessible(true);
		renderProperties.set(fixture.nativeType, extension);
		assertNull(FluidSprites.stillSprite(fluid, sprites), "headless missing atlas is a soft draw-time failure"); assertEquals(1, calls[0]);
	}
	@NotNullByDefault
	private static final class NativeFluid extends ClassLoader {
		final Fluid fluid;
		final Object nativeType;
		NativeFluid() throws Exception {
			super(FluidPresentationServiceTest.class.getClassLoader());
			String typeName = getClass().getName() + "$Type"; String fluidName = getClass().getName() + "$Fluid";
			var writer = writer(typeName, "net/minecraftforge/fluids/FluidType");
			writer.visitField(Opcodes.ACC_PUBLIC, "name", Type.getDescriptor(Component.class), null, null).visitEnd();
			var name = writer.visitMethod(Opcodes.ACC_PUBLIC, "getDescription", "()" + Type.getDescriptor(Component.class), null, null); name.visitCode(); name.visitVarInsn(Opcodes.ALOAD, 0);
			name.visitFieldInsn(Opcodes.GETFIELD, typeName.replace('.', '/'), "name", Type.getDescriptor(Component.class)); name.visitInsn(Opcodes.ARETURN); name.visitMaxs(1, 1); name.visitEnd();
			writer.visitEnd(); byte[] bytes = writer.toByteArray(); nativeType = allocate(defineClass(typeName, bytes, 0, bytes.length));
			writer = writer(fluidName, Type.getInternalName(EmptyFluid.class));
			String descriptor = "Lnet/minecraftforge/fluids/FluidType;";
			writer.visitField(Opcodes.ACC_PUBLIC, "nativeType", descriptor, null, null).visitEnd();
			var getter = writer.visitMethod(Opcodes.ACC_PUBLIC, "getFluidType", "()" + descriptor, null, null); getter.visitCode(); getter.visitVarInsn(Opcodes.ALOAD, 0);
			getter.visitFieldInsn(Opcodes.GETFIELD, fluidName.replace('.', '/'), "nativeType", descriptor); getter.visitInsn(Opcodes.ARETURN); getter.visitMaxs(1, 1); getter.visitEnd(); writer.visitEnd();
			bytes = writer.toByteArray(); fluid = (Fluid) allocate(defineClass(fluidName, bytes, 0, bytes.length)); fluid.getClass().getField("nativeType").set(fluid, nativeType);
		}
		void name(Component value) throws Exception { nativeType.getClass().getField("name").set(nativeType, value); }
		private static ClassWriter writer(String name, String parent) {
			var writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, parent, null);
			writer.visitAnnotation(Type.getDescriptor(NotNullByDefault.class), false).visitEnd(); return writer;
		}
		private static Object allocate(Class<?> type) throws Exception {
			var unsafeType = Class.forName("sun.misc.Unsafe"); var field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return java.util.Objects.requireNonNull(unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
		}
	}
}
