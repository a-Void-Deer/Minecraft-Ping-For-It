package nx.pingwheel.common.client.outline;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelRendererOutlineRequestTest {

	@AfterEach
	void tearDown() {
		LevelRendererOutlineRequest.resetCache();
	}

	/** A fake renderer that exposes the {@code requestOutlineEffect()} hook. */
	static final class WithMethod {
		private final AtomicInteger calls = new AtomicInteger();

		public void requestOutlineEffect() {
			calls.incrementAndGet();
		}

		AtomicInteger calls() {
			return calls;
		}
	}

	/** A fake renderer without the hook (the normal vanilla case). */
	static final class WithoutMethod {
	}

	@Test
	void findMethodFindsThePublicHookWhenPresent() {
		Method method = LevelRendererOutlineRequest.findMethod(WithMethod.class);
		assertNotNull(method);
		assertEquals(LevelRendererOutlineRequest.METHOD_NAME, method.getName());
	}

	@Test
	void findMethodReturnsNullWhenAbsent() {
		assertNull(LevelRendererOutlineRequest.findMethod(WithoutMethod.class));
	}

	@Test
	void invokeSucceedsWhenTheHookIsPresent() {
		WithMethod target = new WithMethod();
		Method method = LevelRendererOutlineRequest.findMethod(WithMethod.class);

		assertTrue(LevelRendererOutlineRequest.invoke(target, method));
		assertEquals(1, target.calls().get());
	}

	@Test
	void invokeIsANormalNoOpWhenTheHookIsMissing() {
		WithoutMethod target = new WithoutMethod();
		assertFalse(LevelRendererOutlineRequest.invoke(target, null));
	}

	@Test
	void requestUsesTheCachedResolutionAndInvokesTheHook() {
		LevelRendererOutlineRequest.resetCache();
		WithMethod target = new WithMethod();

		assertTrue(LevelRendererOutlineRequest.request(target));
		assertTrue(LevelRendererOutlineRequest.request(target));
		assertEquals(2, target.calls().get());
	}

	@Test
	void requestWithNoHookResolvesAbsentAndReturnsFalse() {
		LevelRendererOutlineRequest.resetCache();
		WithoutMethod target = new WithoutMethod();

		assertFalse(LevelRendererOutlineRequest.request(target));
		assertFalse(LevelRendererOutlineRequest.request(target));
	}

	@Test
	void requestCachesPresentAndMissingPerRuntimeClass() {
		LevelRendererOutlineRequest.resetCache();
		WithoutMethod missing = new WithoutMethod();
		WithMethod present = new WithMethod();

		assertFalse(LevelRendererOutlineRequest.request(missing));
		assertTrue(LevelRendererOutlineRequest.request(present));
		assertTrue(LevelRendererOutlineRequest.request(present));
		assertFalse(LevelRendererOutlineRequest.request(missing));
		assertEquals(2, present.calls().get());
	}

	@Test
	void resolveMethodCachesEachClassIncludingMissingAndResetClearsBoth() {
		LevelRendererOutlineRequest.resetCache();
		AtomicInteger presentLookups = new AtomicInteger();
		AtomicInteger missingLookups = new AtomicInteger();

		java.util.function.Function<Class<?>, Method> finder = type -> {
			if (type == WithMethod.class) {
				presentLookups.incrementAndGet();
			} else if (type == WithoutMethod.class) {
				missingLookups.incrementAndGet();
			}
			return LevelRendererOutlineRequest.findMethod(type);
		};

		assertNotNull(LevelRendererOutlineRequest.resolveMethod(WithMethod.class, finder));
		assertNull(LevelRendererOutlineRequest.resolveMethod(WithoutMethod.class, finder));
		assertNotNull(LevelRendererOutlineRequest.resolveMethod(WithMethod.class, finder));
		assertNull(LevelRendererOutlineRequest.resolveMethod(WithoutMethod.class, finder));
		assertEquals(1, presentLookups.get());
		assertEquals(1, missingLookups.get());

		LevelRendererOutlineRequest.resetCache();
		assertNotNull(LevelRendererOutlineRequest.resolveMethod(WithMethod.class, finder));
		assertNull(LevelRendererOutlineRequest.resolveMethod(WithoutMethod.class, finder));
		assertEquals(2, presentLookups.get());
		assertEquals(2, missingLookups.get());
	}

}
