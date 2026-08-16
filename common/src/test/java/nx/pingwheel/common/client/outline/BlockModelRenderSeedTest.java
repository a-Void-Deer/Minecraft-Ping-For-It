package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Headless-scope tests for {@link BlockModelRenderSeed}: no Minecraft
 * bootstrap, no assets; the utility is pure ThreadLocal state, so the exact
 * resolved long is deterministic and asserted directly. The live
 * {@code BlockState#getSeed(pos)} derivation itself needs a running game and
 * is intentionally not covered here.
 */
class BlockModelRenderSeedTest {

	private static final long FALLBACK = 42L;

	@Test
	void outsideScopeResolvesToFallback() {
		assertEquals(FALLBACK, BlockModelRenderSeed.resolve(FALLBACK));
	}

	@Test
	void activeScopeResolvesExactSeed() {
		AtomicLong resolved = new AtomicLong(-1L);

		BlockModelRenderSeed.runWithSeed(0x1122334455667788L, () -> resolved.set(BlockModelRenderSeed.resolve(FALLBACK)));

		assertEquals(0x1122334455667788L, resolved.get());
	}

	@Test
	void activeScopeResolvesZeroSeed() {
		// A seed of exactly 0 is a valid active value and must never be
		// conflated with "no active scope".
		AtomicLong resolved = new AtomicLong(-1L);

		BlockModelRenderSeed.runWithSeed(0L, () -> resolved.set(BlockModelRenderSeed.resolve(FALLBACK)));

		assertEquals(0L, resolved.get());
	}

	@Test
	void nestedScopesRestorePriorOnExit() {
		List<Long> seen = new ArrayList<>();

		BlockModelRenderSeed.runWithSeed(0xAABBL, () -> {
			seen.add(BlockModelRenderSeed.resolve(FALLBACK));
			BlockModelRenderSeed.runWithSeed(0xCCDDL, () -> seen.add(BlockModelRenderSeed.resolve(FALLBACK)));
			seen.add(BlockModelRenderSeed.resolve(FALLBACK));
		});

		assertEquals(List.of(0xAABBL, 0xCCDDL, 0xAABBL), seen);
		assertEquals(FALLBACK, BlockModelRenderSeed.resolve(FALLBACK));
	}

	@Test
	void exceptionRestoresScopeBeforePropagating() {
		assertThrows(IllegalStateException.class, () -> BlockModelRenderSeed.runWithSeed(0x1234L, () -> {
			assertEquals(0x1234L, BlockModelRenderSeed.resolve(FALLBACK));
			throw new IllegalStateException("boom");
		}));

		assertEquals(FALLBACK, BlockModelRenderSeed.resolve(FALLBACK));
	}

	@Test
	void sequentialScopesDoNotLeak() {
		AtomicLong resolved = new AtomicLong(-1L);

		BlockModelRenderSeed.runWithSeed(0x1111L, () -> resolved.set(BlockModelRenderSeed.resolve(FALLBACK)));
		assertEquals(0x1111L, resolved.get());

		BlockModelRenderSeed.runWithSeed(0x2222L, () -> resolved.set(BlockModelRenderSeed.resolve(FALLBACK)));
		assertEquals(0x2222L, resolved.get());

		assertEquals(FALLBACK, BlockModelRenderSeed.resolve(FALLBACK));
	}
}
