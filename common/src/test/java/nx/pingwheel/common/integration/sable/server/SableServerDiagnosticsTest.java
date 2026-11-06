package nx.pingwheel.common.integration.sable.server;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.integration.sable.SableDiagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableServerDiagnosticsTest {

	@Test
	void liveLinkageErrorIsEmittedOnceAtTheOperationBoundary() {
		SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		LinkageError failure = new NoClassDefFoundError("sable-provider-payload");

		SableExternalBlockServerProvider.logLiveResult(
			diagnostics,
			"validate",
			null,
			new SableExternalBlockServerProvider.LiveResult.Invalid("linkage-error", failure));

		assertEquals(1, diagnostics.events().size());
		assertEquals(SableDiagnostics.SERVER_PREFIX, diagnostics.events().get(0).prefix());
		assertEquals("live-resolution", diagnostics.events().get(0).stage());
		assertEquals("linkage-error", diagnostics.events().get(0).reason());
		assertSame(failure, diagnostics.events().get(0).throwable());
	}

	@Test
	void discoveredApiKeepsItsCompleteTwelveDescriptorListWithoutEagerFormatting() throws ReflectiveOperationException {
		Method[] methods = new Method[12];
		for (int index = 0; index < methods.length; index++) {
			methods[index] = DescriptorFixture.class.getDeclaredMethod("method" + index);
		}
		Class<?> apiType = Class.forName(SableExternalBlockServerProvider.class.getName() + "$ReflectiveApi");
		var constructor = apiType.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		Object api = constructor.newInstance((Object[]) methods);
		Method signatures = apiType.getDeclaredMethod("signatures");
		signatures.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<String> descriptors = (List<String>) signatures.invoke(api);

		assertEquals(12, descriptors.size());
		assertTrue(descriptors.stream().allMatch(value -> value != null && !value.isBlank()));
	}

	public static final class DescriptorFixture {
		public static void method0() {}
		public static void method1() {}
		public static void method2() {}
		public static void method3() {}
		public static void method4() {}
		public static void method5() {}
		public static void method6() {}
		public static void method7() {}
		public static void method8() {}
		public static void method9() {}
		public static void method10() {}
		public static void method11() {}
	}
}
