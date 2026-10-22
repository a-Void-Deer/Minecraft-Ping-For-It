package nx.pingwheel.neoforge.integration.create.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CreatePresentationCollectorBudgetTest {
	private static final CreateSamplingLimits LIMITS = new CreateSamplingLimits(128, 128, 16, 16, 256, 2048);
	private static final BlockPos ORIGIN = new BlockPos(18, 64, -4);

	@ParameterizedTest
	@CsvSource({ "8,4,4", "4,8,4" })
	void rejectedLastMemberKeepsTheEntireVisitedPrefixPaid(int x, int y, int z) {
		CreateSamplingWork work = new CreateSamplingWork(130);
		assertTrue(work.take(2), "source and controller reads have already been paid");
		AtomicInteger gates = new AtomicInteger();
		AtomicInteger reads = new AtomicInteger();
		assertFalse(work.verifyShape(ORIGIN, x, y, z, LIMITS, part -> {
			int visited = gates.incrementAndGet();
			assertEquals(2 + visited, work.used(), "payment precedes the gate, even for the rejected member");
			return visited < 128;
		}, part -> {
			reads.incrementAndGet();
			return true;
		}));
		assertEquals(128, gates.get());
		assertEquals(127, reads.get());
		assertEquals(130, work.used(), "a failed shape does not refund its 128 attempted checks");
		assertEquals(0, work.remaining());
	}

	@ParameterizedTest
	@CsvSource({ "8,4,4", "4,8,4", "3,3,3", "1,1,1" })
	void successfulShapeChargesEachMemberExactlyOnce(int x, int y, int z) {
		int members = x * y * z;
		CreateSamplingWork work = new CreateSamplingWork(members + 3);
		assertTrue(work.take(2));
		Set<BlockPos> checked = new HashSet<>();
		AtomicInteger gates = new AtomicInteger();
		assertTrue(work.verifyShape(ORIGIN, x, y, z, LIMITS, part -> {
			assertEquals(2 + gates.incrementAndGet(), work.used());
			return true;
		}, part -> {
			assertTrue(checked.add(part), "a member is verified once");
			return true;
		}));
		assertEquals(members, gates.get());
		assertEquals(members, checked.size());
		assertTrue(checked.contains(ORIGIN));
		assertTrue(checked.contains(ORIGIN.offset(x - 1, y - 1, z - 1)));
		assertEquals(members + 2, work.used(), "no bulk success charge may double-charge member checks");
		assertEquals(1, work.remaining());
	}

	@ParameterizedTest
	@CsvSource({ "0,1,1,1", "127,8,4,4", "127,4,8,4", "128,1,1,129", "128,0,1,1" })
	void zeroInsufficientOrInvalidShapeBudgetPreventsEveryGateAndRead(int allowance, int x, int y, int z) {
		CreateSamplingWork work = new CreateSamplingWork(allowance);
		assertFalse(work.verifyShape(ORIGIN, x, y, z, LIMITS,
			part -> { throw new AssertionError("member gate ran without shape admission"); },
			part -> { throw new AssertionError("member read ran without shape admission"); }));
		assertEquals(0, work.used());
		assertEquals(allowance, work.remaining());
	}

	@Test
	void throwingMemberReadCannotRefundPreviouslyPaidChecksOrItsOwnCheck() {
		CreateSamplingWork work = new CreateSamplingWork(130);
		assertTrue(work.take(2));
		AtomicInteger reads = new AtomicInteger();
		IllegalStateException failure = new IllegalStateException("block entity lookup");
		assertSame(failure, assertThrows(IllegalStateException.class,
			() -> work.verifyShape(ORIGIN, 8, 4, 4, LIMITS, part -> true, part -> {
				int visited = reads.incrementAndGet();
				assertEquals(2 + visited, work.used());
				if (visited == 128) throw failure;
				return true;
			})));
		assertEquals(128, reads.get());
		assertEquals(130, work.used());
		assertEquals(0, work.remaining());
	}

	@Test
	void throwingMemberGateKeepsItsChargeAndNeverReadsThatMember() {
		for (boolean linkage : new boolean[] { false, true }) {
			CreateSamplingWork work = new CreateSamplingWork(3);
			AtomicInteger gates = new AtomicInteger();
			AtomicInteger reads = new AtomicInteger();
			assertFalse(work.verifyShape(ORIGIN, 3, 1, 1, LIMITS, part -> {
				if (gates.incrementAndGet() == 2) {
					if (linkage) throw new NoClassDefFoundError("member gate");
					throw new IllegalStateException("member gate");
				}
				return true;
			}, part -> {
				reads.incrementAndGet();
				return true;
			}));
			assertEquals(2, gates.get());
			assertEquals(1, reads.get());
			assertEquals(2, work.used());
			assertEquals(1, work.remaining());
		}
	}

	@Test
	void mismatchedOrUnloadedMemberCannotRefundThePaidPrefix() {
		CreateSamplingWork work = new CreateSamplingWork(4);
		AtomicInteger reads = new AtomicInteger();
		assertFalse(work.verifyShape(ORIGIN, 4, 1, 1, LIMITS, null,
			part -> reads.incrementAndGet() < 3));
		assertEquals(3, reads.get());
		assertEquals(3, work.used());
		assertEquals(1, work.remaining());
	}

	@Test
	void invalidOrOverflowingShapesCannotStartLoadedBlockChecks() {
		var limits = new CreateSamplingLimits(128, 128, 16, 16, 256, 2048);
		assertTrue(limits.permitsShape(3, 3, 3, 256));
		assertFalse(limits.permitsShape(0, 1, 1, 256));
		assertFalse(limits.permitsShape(-1, 1, 1, 256));
		assertFalse(limits.permitsShape(1, 1, 129, 256));
		assertFalse(limits.permitsShape(Integer.MAX_VALUE,
			Integer.MAX_VALUE, Integer.MAX_VALUE, 256));
		assertFalse(limits.permitsShape(6, 6, 6, 256));
		assertFalse(limits.permitsShape(3, 3, 3, 16));
	}

	@Test
	void suppliedLimitsHaveAbsoluteUpperBounds() {
		assertThrows(IllegalArgumentException.class,
			() -> new CreateSamplingLimits(0, 1, 1, 1, 1, 1));
		assertThrows(IllegalArgumentException.class,
			() -> new CreateSamplingLimits(128, 513, 1, 1, 256, 2048));
		assertThrows(IllegalArgumentException.class,
			() -> new CreateSamplingLimits(128, 128, 1, 1, 256, 8193));
	}

	@Test
	void versionGateRejectsAbsentOrUntestedApi() {
		assertTrue(CreatePresentationAvailability.testedVersion("6.0.10"));
		assertTrue(CreatePresentationAvailability.testedVersion("6.0.10-281"));
		assertFalse(CreatePresentationAvailability.testedVersion(null));
		assertFalse(CreatePresentationAvailability.testedVersion("6.0.10-282"));
	}
}
