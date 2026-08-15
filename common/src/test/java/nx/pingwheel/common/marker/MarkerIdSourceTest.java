package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerIdSourceTest {

	@Test
	void startsAtZero() {
		assertEquals(0L, new MarkerIdSource().nextId().value());
	}

	@Test
	void generatesMonotonicSequence() {
		MarkerIdSource source = new MarkerIdSource();

		assertEquals(0L, source.nextId().value());
		assertEquals(1L, source.nextId().value());
		assertEquals(2L, source.nextId().value());
		assertEquals(3L, source.nextId().value());
	}

	@Test
	void initialValueTestSeamStartsAtGivenValue() {
		MarkerIdSource source = new MarkerIdSource(10L);

		assertEquals(10L, source.nextId().value());
		assertEquals(11L, source.nextId().value());
	}

	@Test
	void rejectsNegativeInitialValue() {
		assertThrows(IllegalArgumentException.class, () -> new MarkerIdSource(-1L));
	}

	@Test
	void failsClearlyAtLongMaxValueBeforeOverflow() {
		MarkerIdSource source = new MarkerIdSource(Long.MAX_VALUE - 1L);

		assertEquals(Long.MAX_VALUE - 1L, source.nextId().value());
		assertEquals(Long.MAX_VALUE, source.nextId().value());

		assertThrows(IllegalStateException.class, source::nextId);
	}

	@Test
	void exhaustionIsSticky() {
		MarkerIdSource source = new MarkerIdSource(Long.MAX_VALUE);

		assertEquals(Long.MAX_VALUE, source.nextId().value());
		assertThrows(IllegalStateException.class, source::nextId);
		assertThrows(IllegalStateException.class, source::nextId);
	}

	@Test
	void concurrentGenerationProducesUniqueIds() throws Exception {
		MarkerIdSource source = new MarkerIdSource();
		int threads = 8;
		int perThread = 1000;

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);

		List<Future<long[]>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				start.await();

				long[] ids = new long[perThread];
				for (int i = 0; i < perThread; i++) {
					ids[i] = source.nextId().value();
				}
				return ids;
			}));
		}

		ready.await();
		start.countDown();

		Set<Long> seen = new HashSet<>();
		for (Future<long[]> future : futures) {
			for (long id : future.get()) {
				assertTrue(seen.add(id), "duplicate id " + id);
			}
		}

		pool.shutdown();

		assertEquals(threads * perThread, seen.size());
	}
}
