package nx.pingwheel.common.platform;

import java.util.AbstractList;
import java.util.List;
import java.util.ServiceConfigurationError;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService.Literal;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService.Translation;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.CaptureLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class FluidDescriptionsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void nativeLeafTranslationAndLiteralBecomeDetachedUnstyledDtos() {
		var nativeName = Component.translatableWithFallback("fluid.create.tea", "Builder's Tea").withStyle(Style.EMPTY.withBold(true));
		assertEquals(new Translation("fluid.create.tea", "Builder's Tea"), FluidDescriptions.project(nativeName));
		nativeName.append("later mutation");
		assertNull(FluidDescriptions.project(nativeName));
		assertEquals(new Literal("建筑工茶饮"), FluidDescriptions.project(Component.literal("建筑工茶饮")));
		var event = new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/hostile");
		assertEquals(new Literal("plain"), FluidDescriptions.project(Component.literal("plain").withStyle(Style.EMPTY.withClickEvent(event))));
	}
	@Test void malformedOverlimitAndComplexTextIsRefusedWithoutFlattening() {
		for (String text : new String[] {"", "   ", "x".repeat(1025), "茶".repeat(342), "\ud800", "\udc00", "a\n", "§cTea", "a\u2028b"}) {
			assertNull(FluidDescriptions.project(Component.literal(text)));
			assertThrows(IllegalArgumentException.class, () -> new Literal(text));
		}
		assertNotNull(FluidDescriptions.project(Component.literal("茶".repeat(341))));
		assertNotNull(FluidDescriptions.project(Component.literal("😀".repeat(256))));
		for (String key : new String[] {"", "fluid tea", "x".repeat(257), "fluid.茶"})
			assertNull(FluidDescriptions.project(Component.translatable(key)));
		assertNull(FluidDescriptions.project(Component.translatable("fluid.test.tea", Component.literal("arg"))));
		for (String fallback : new String[] {"\ud800", "x".repeat(1025), "bad\ntext"})
			assertNull(FluidDescriptions.project(Component.translatableWithFallback("fluid.test.tea", fallback)));
		var cycle = Component.literal("tea"); cycle.append(cycle);
		assertNull(FluidDescriptions.project(cycle));
		Object[] args = new Object[1]; var cyclic = new TranslatableContents("fluid.test.tea", null, args); args[0] = cyclic;
		assertNull(FluidDescriptions.project(MutableComponent.create(cyclic)));
	}
	@Test void opaqueComponentContentsAndSiblingContainersCannotExecuteCallbacks() throws Exception {
		Component opaque = (Component) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Component.class},
			(proxy, method, args) -> { throw new AssertionError("opaque component callback: " + method); });
		assertNull(FluidDescriptions.project(opaque));
		ComponentContents contents = (ComponentContents) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ComponentContents.class},
			(proxy, method, args) -> { throw new AssertionError("opaque contents callback: " + method); });
		assertNull(FluidDescriptions.project(MutableComponent.create(contents)));
		var constructor = MutableComponent.class.getDeclaredConstructor(ComponentContents.class, List.class, Style.class); constructor.setAccessible(true);
		var nativeContents = Component.literal("tea").getContents();
		var dangerous = new AbstractList<Component>() {
			@Override public Component get(int index) { throw new AssertionError("sibling read"); }
			@Override public int size() { throw new AssertionError("sibling size"); }
		};
		assertNull(FluidDescriptions.project(constructor.newInstance(nativeContents, dangerous, Style.EMPTY)));
		assertNull(FluidDescriptions.project(MutableComponent.create(new TranslatableContents("fluid.test.tea", null, new Object[0]) {
			@Override public String getKey() { throw new AssertionError("subclass getter"); }
		})));
	}
	@SuppressWarnings("removal")
	@Test void optionalFailuresAreSoftButAdmissionAndVmFatalErrorsAreNotSwallowed() throws Exception {
		assertNull(FluidDescriptions.describeBase(Fluids.WATER, null));
		for (Throwable failure : new Throwable[] {new IllegalStateException("name hook"), new NoClassDefFoundError("optional"), new ServiceConfigurationError("broken")}) {
			int[] calls = {0};
			assertNull(FluidDescriptions.describeBase(Fluids.WATER, fluid -> { calls[0]++; return fail(failure); }));
			assertEquals(1, calls[0]);
		}
		var constructor = CaptureLimitException.class.getDeclaredConstructor(String.class); constructor.setAccessible(true);
		var admission = constructor.newInstance("admission");
		assertSame(admission, assertThrows(CaptureLimitException.class, () -> FluidDescriptions.describeBase(Fluids.WATER, fluid -> { throw admission; })));
		assertThrows(OutOfMemoryError.class, () -> FluidDescriptions.describeBase(Fluids.WATER, fluid -> { throw new OutOfMemoryError("test"); }));
		assertThrows(ThreadDeath.class, () -> FluidDescriptions.describeBase(Fluids.WATER, fluid -> { throw new ThreadDeath(); }));
	}
	@SuppressWarnings("removal")
	@Test void nestedStandardWrappersPropagateHardFailuresButOrdinaryNestedFailuresStayOptional() throws Exception {
		var constructor = CaptureLimitException.class.getDeclaredConstructor(String.class); constructor.setAccessible(true);
		for (Throwable hard : new Throwable[] {constructor.newInstance("admission"), new OutOfMemoryError("sentinel"), new ThreadDeath()}) {
			var wrapped = new ServiceConfigurationError("provider", new ExceptionInInitializerError(
				new java.lang.reflect.UndeclaredThrowableException(new java.lang.reflect.InvocationTargetException(hard))));
			int[] calls = {0};
			assertSame(hard, assertThrows(hard.getClass(), () -> FluidDescriptions.describeBase(Fluids.WATER,
				fluid -> { calls[0]++; throw wrapped; })));
			assertEquals(1, calls[0], "classification is not another native name attempt");
		}
		for (Throwable ordinary : new Throwable[] {new IllegalStateException("name"), new NoClassDefFoundError("optional")}) {
			assertNull(FluidDescriptions.describeBase(Fluids.WATER, fluid -> { throw new ServiceConfigurationError("provider",
				new ExceptionInInitializerError(new java.lang.reflect.InvocationTargetException(ordinary))); }));
		}
	}
	@Test void cyclicAndOverDepthWrapperChainsFailClosedAndNeverWalkOpaqueCauseCallbacks() throws Exception {
		var first = new ServiceConfigurationError("cycle"); var second = new RuntimeException("cycle");
		first.initCause(second); second.initCause(first);
		assertSame(first, assertThrows(ServiceConfigurationError.class, () -> FluidDescriptions.describeBase(Fluids.WATER, fluid -> { throw first; })));
		RuntimeException deep = new RuntimeException("leaf");
		for (int depth = 0; depth < 256; depth++) deep = new RuntimeException("wrapper", deep);
		var overDepth = deep;
		assertSame(overDepth, assertThrows(RuntimeException.class, () -> FluidDescriptions.describeBase(Fluids.WATER, fluid -> { throw overDepth; })));
		var constructor = CaptureLimitException.class.getDeclaredConstructor(String.class); constructor.setAccessible(true);
		var hard = constructor.newInstance("boundary"); Throwable exposed = hard;
		for (int depth = 0; depth < 16; depth++) exposed = new RuntimeException("wrapper", exposed);
		var boundary = (RuntimeException) exposed;
		assertSame(hard, assertThrows(CaptureLimitException.class, () -> FluidDescriptions.describeBase(Fluids.WATER, fluid -> { throw boundary; })),
			"a hard failure exposed at the boundary is inspected before the depth stop");
		assertNull(FluidDescriptions.describeBase(Fluids.WATER, fluid -> { throw new IllegalStateException("opaque") {
			@Override public synchronized @Nullable Throwable getCause() { throw new AssertionError("arbitrary cause callback"); }
		}; }));
	}
	private static @Nullable IPlatformFluidDescriptionService.Description fail(Throwable failure) {
		if (failure instanceof RuntimeException runtime) throw runtime;
		throw (Error) failure;
	}
}
