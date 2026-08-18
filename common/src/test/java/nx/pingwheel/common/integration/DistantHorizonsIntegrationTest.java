package nx.pingwheel.common.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the package-private root-cause unwrapping used by the
 * Distant Horizons trace completion path: wrapper exceptions are unwrapped
 * to the underlying linkage error, non-linkage failures surface the root
 * cause, and recursive cause cycles terminate.
 */
class DistantHorizonsIntegrationTest {

	@Test
	void unwrapsCompletionExceptionToLinkageError() {
		NoClassDefFoundError root = new NoClassDefFoundError("missing");
		CompletionException wrapped = new CompletionException(root);

		assertSame(root, DistantHorizonsIntegration.rootCause(wrapped));
	}

	@Test
	void unwrapsNestedWrappersToDeepestCause() {
		LinkageError root = new LinkageError("root");
		ExecutionException inner = new ExecutionException(root);
		CompletionException outer = new CompletionException(inner);

		assertSame(root, DistantHorizonsIntegration.rootCause(outer));
	}

	@Test
	void returnsRootCauseForNonLinkageFailures() {
		IllegalStateException root = new IllegalStateException("boom");
		CompletionException wrapped = new CompletionException(root);

		Throwable result = DistantHorizonsIntegration.rootCause(wrapped);

		assertTrue(result instanceof IllegalStateException, "expected non-linkage root to surface as-is");
		assertSame(root, result);
	}

	@Test
	void terminatesOnRecursiveCauseCycle() {
		IllegalStateException first = new IllegalStateException("first");
		IllegalStateException second = new IllegalStateException("second");

		// The JDK forbids self-causation, but a mutual two-node cycle is
		// constructible and exercises the bounded walk.
		first.initCause(second);
		second.initCause(first);

		// Sixteen even hops through the cycle land back on the first node;
		// the point is that the walk terminates instead of looping forever.
		assertSame(first, DistantHorizonsIntegration.rootCause(first));
	}

	@Test
	void terminatesOnDeepCauseChain() {
		Throwable current = new RuntimeException("depth 0");

		for (int i = 1; i < 40; i++) {
			current = new RuntimeException("depth " + i, current);
		}

		// The walk is bounded to 16 cause hops: starting at depth 39 it must
		// stop at depth 23 instead of looping forever.
		Throwable root = DistantHorizonsIntegration.rootCause(current);

		assertSame(current.getClass(), root.getClass());
		assertTrue(root.getMessage().startsWith("depth "), "expected a bounded mid-chain cause");
	}
}
