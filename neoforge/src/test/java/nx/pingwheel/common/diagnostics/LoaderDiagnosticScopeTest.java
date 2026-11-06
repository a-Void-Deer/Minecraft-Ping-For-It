package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class LoaderDiagnosticScopeTest {
	@Test void intervalStateIsScopedPerSideButDomainGatesAreNeverResetOrExecutedByTheFixture() throws Exception {
		Fixture.INTERVAL.admit(DiagnosticSide.CLIENT);
		Fixture.INTERVAL.admit(DiagnosticSide.CLIENT);
		LoggingProfile client = Diagnostics.profile(DiagnosticSide.CLIENT);
		LoggingProfile server = Diagnostics.profile(DiagnosticSide.SERVER);
		try (var scope = new LoaderDiagnosticScope(LoggingProfile.DEBUG, LoggingProfile.NORMAL)) {
			scope.isolateChannels(Fixture.class);
			assertTrue(Fixture.INTERVAL.admit(DiagnosticSide.CLIENT).allowed());
			assertTrue(Fixture.INTERVAL.admit(DiagnosticSide.SERVER).allowed());
			assertEquals(0, Fixture.DOMAIN_READS.get());
			assertTrue(Fixture.DOMAIN.admit(DiagnosticSide.CLIENT).allowed());
			assertEquals(1, Fixture.DOMAIN_READS.get());
		}
		assertEquals(client, Diagnostics.profile(DiagnosticSide.CLIENT));
		assertEquals(server, Diagnostics.profile(DiagnosticSide.SERVER));
		assertFalse(Fixture.INTERVAL.admit(DiagnosticSide.CLIENT).allowed(), "the original admitted state is restored");
		assertTrue(Fixture.INTERVAL.admit(DiagnosticSide.SERVER).allowed(), "the original untouched side is restored");
		Fixture.CLOCK.set(Duration.ofSeconds(10).toNanos());
		assertEquals(2, Fixture.INTERVAL.admit(DiagnosticSide.CLIENT).suppressed(), "the original suppressed count is restored, not discarded");
		assertEquals(1, Fixture.DOMAIN_READS.get(), "domain state is real, not fixture-owned");
	}

	@NotNullByDefault
	private static final class Fixture {
		private static final java.util.concurrent.atomic.AtomicLong CLOCK = new java.util.concurrent.atomic.AtomicLong();
		private static final DiagnosticChannel INTERVAL = DiagnosticChannel.interval("loader-fixture-interval", Duration.ofSeconds(10), CLOCK::get);
		private static final AtomicInteger DOMAIN_READS = new AtomicInteger();
		private static final DiagnosticChannel DOMAIN = DiagnosticChannel.domain("loader-fixture-domain", () -> { DOMAIN_READS.incrementAndGet(); return true; });
	}
}
