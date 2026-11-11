package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class DiagnosticPolicyTest {
	@Test
	void reentryStorageIsAbsentUntilARealReentryAndDetachesWithoutCopying() {
		DiagnosticLogger.Attempt attempt = new DiagnosticLogger.Attempt();
		assertNull(attempt.detach(), "Ordinary, denied and hint attempts have no reentry map");
		DiagnosticLogger.Reentry key = new DiagnosticLogger.Reentry(DiagnosticSide.CLIENT, "nested-site", LoggingProfile.NORMAL);
		attempt.record(key);
		attempt.record(key);
		@Nullable var pending = attempt.detach();
		assertNotNull(pending);
		assertEquals(2L, pending.get(key));
		assertNull(attempt.detach(), "Flush transfers ownership, leaving no map to copy or clear");
		attempt.record(key);
		assertEquals(2L, pending.get(key), "Writer reentry cannot mutate the detached batch");
		@Nullable var later = attempt.detach();
		assertNotNull(later);
		assertNotSame(pending, later);
		assertEquals(1L, later.get(key));
	}

	@Test
	void profilesAndAllSeveritiesAreIndependent() {
		try (Fixture fixture = new Fixture()) {
			for (LoggingProfile effective : LoggingProfile.values()) {
				Diagnostics.setProfile(DiagnosticSide.CLIENT, effective);
				for (LoggingProfile minimum : LoggingProfile.values()) {
					DiagnosticLogger logger = select(Diagnostics.client(), minimum);
					for (Level level : List.of(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
						int before = fixture.sink.events.size();
						assertEquals(effective.includes(minimum), logger.isEnabled(level));
						logger.event(level, "stable status");
						assertEquals(before + (effective.includes(minimum) ? 1 : 0), fixture.sink.events.size());
						if (effective.includes(minimum)) assertEquals(level, fixture.sink.events.getLast().level);
					}
				}
			}
		}
	}

	@Test
	void currentPolicyControlsDisclosureWhileMinimumOnlyControlsEligibility() {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger reads = new AtomicInteger();
			Throwable failure = hostile(reads);
			Supplier<String> detail = () -> { reads.incrementAndGet(); return "position=secret"; };
			Diagnostics.client().atNormal().failure(Level.DEBUG, "provider-read", "fallback", detail, failure);
			assertEquals(0, reads.get());
			String normal = fixture.sink.events.getLast().text;
			assertTrue(normal.contains("site=provider-read handling=fallback"));
			assertTrue(normal.contains("failure=type-"));
			assertFalse(normal.contains("DiagnosticPolicyTest"));
			assertFalse(normal.contains("secret"));
			Diagnostics.client().atDetailed().error(detail);
			Diagnostics.client().atDebug().error(detail);
			Diagnostics.client().atNormal().error(detail);
			assertEquals(0, reads.get());
			assertEquals(1, fixture.sink.events.size());
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			Diagnostics.client().atNormal().failure(Level.DEBUG, "provider-read", "fallback", detail, failure);
			String deep = fixture.sink.events.getLast().text;
			assertTrue(deep.contains("position=secret"));
			assertTrue(deep.contains("original-secret"));
			assertTrue(deep.contains("stack-secret"));
			assertEquals(2, fixture.sink.events.size(), "One report per terminal, no normal+deep duplication");
		}
	}

	@Test
	void controlledTemplatesOnlyInterpolateExplicitSafeScalars() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.client().warn("stage={} count={} active={}", SafeDiagnosticValue.code("fallback"),
				SafeDiagnosticValue.number(7), SafeDiagnosticValue.bool(true));
			assertTrue(fixture.sink.events.getLast().text.contains("stage=fallback count=7 active=true"));
			assertThrows(IllegalArgumentException.class, () -> SafeDiagnosticValue.code("raw reason\nsecret"));
			assertThrows(IllegalArgumentException.class, () -> Diagnostics.client().site("dynamic target name"));
		}
	}

	@Test
	void noChannelMeansNoClockAndExplicitChannelSharesHistoryAcrossSeverityAndProfile() {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger clockReads = new AtomicInteger();
			AtomicLong clock = new AtomicLong();
			DiagnosticChannel channel = DiagnosticChannel.interval("provider-retry", Duration.ofSeconds(10), () -> {
				clockReads.incrementAndGet(); return clock.get();
			});
			for (int count = 0; count < 100; count++) Diagnostics.client().info("unlimited");
			assertEquals(0, clockReads.get());
			DiagnosticLogger client = Diagnostics.client().rateLimited(channel);
			assertTrue(client.isEnabled(Level.DEBUG));
			assertEquals(0, clockReads.get(), "Hints do not consume admission");
			client.debug("first");
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			client.atDebug().error(() -> { throw new AssertionError("suppressed supplier"); });
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			client.warn("same-window");
			Diagnostics.server().rateLimited(channel).warn("independent-side");
			Diagnostics.client().rateLimited(DiagnosticChannel.interval("provider-retry", Duration.ofSeconds(10), clock::get))
				.warn("independent-object-identity");
			assertEquals(103, fixture.sink.events.size());
			assertEquals(4, clockReads.get(), "Each explicit terminal consumes at most one channel attempt");
			clock.set(Duration.ofSeconds(10).toNanos());
			client.error("recovered");
			assertTrue(fixture.sink.events.getLast().text.contains("suppressed=2"));
		}
	}

	@Test
	void disabledOrBelowMinimumTerminalsDoNotConsumeDomainGateOrReadDetails() {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger admissions = new AtomicInteger();
			DiagnosticChannel domain = DiagnosticChannel.domain("domain-owned", () -> { admissions.incrementAndGet(); return true; });
			DiagnosticLogger logger = Diagnostics.client().rateLimited(domain);
			logger.atDetailed().error(() -> { throw new AssertionError("profile suppressed"); });
			fixture.sink.enabled = false;
			logger.warn("disabled");
			assertFalse(logger.isEnabled(Level.WARN));
			assertEquals(0, admissions.get());
			fixture.sink.enabled = true;
			logger.warn("admitted");
			logger.error("also-admitted");
			assertEquals(2, admissions.get());
			assertEquals(2, fixture.sink.events.size());
		}
	}

	@Test
	void effectiveSnapshotIsNotRereadAfterBackendOrGateChangesProfile() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			DiagnosticChannel gate = DiagnosticChannel.domain("changes-policy", () -> {
				Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL); return true;
			});
			Diagnostics.client().rateLimited(gate).atNormal().warn("read", "fallback", new RuntimeException("permitted"), () -> "detail");
			assertTrue(fixture.sink.events.getLast().text.contains("permitted"));
			assertEquals(LoggingProfile.NORMAL, Diagnostics.profile(DiagnosticSide.CLIENT));
		}
	}

	@Test
	void domainBeforeBackendKeepsDeepSnapshotAcrossDowngradeAndConsumesDisabledGateWithoutDetail() throws Exception {
		for (boolean backendEnabled : List.of(true, false)) {
			try (Fixture fixture = new Fixture()) {
				Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
				fixture.sink.enabled = backendEnabled;
				AtomicInteger admissions = new AtomicInteger();
				AtomicInteger details = new AtomicInteger();
				CountDownLatch consumed = new CountDownLatch(1);
				CountDownLatch continueReport = new CountDownLatch(1);
				DiagnosticLogger logger = Diagnostics.client().atDetailed();
				try (var executor = Executors.newFixedThreadPool(2)) {
					var terminal = executor.submit(() -> logger.domainBeforeBackend(Level.WARN, "domain-downgrade", () -> {
						admissions.incrementAndGet();
						consumed.countDown();
						await(continueReport);
						return new DiagnosticLogger.DomainReport("fallback", new IllegalStateException("original-secret"),
							() -> { details.incrementAndGet(); return "deep-secret"; });
					}));
					try {
						assertTrue(consumed.await(5, TimeUnit.SECONDS));
						executor.submit(() -> Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL)).get(5, TimeUnit.SECONDS);
					} finally {
						continueReport.countDown();
					}
					terminal.get(5, TimeUnit.SECONDS);
				}
				assertEquals(1, admissions.get());
				assertEquals(1, fixture.sink.preparations.get());
				assertEquals(1, fixture.sink.enabledChecks.get());
				assertEquals(backendEnabled ? 1 : 0, details.get());
				assertEquals(backendEnabled ? 1 : 0, fixture.sink.events.size());
				if (backendEnabled) {
					assertTrue(fixture.sink.events.getFirst().text.contains("deep-secret"));
					assertTrue(fixture.sink.events.getFirst().text.contains("original-secret"));
				}
				logger.domainBeforeBackend(Level.WARN, "domain-downgrade", () -> {
					throw new AssertionError("Next NORMAL snapshot must reject a DEEP minimum before the domain gate");
				});
				assertEquals(1, admissions.get());
				assertEquals(1, fixture.sink.preparations.get());
				assertEquals(1, fixture.sink.enabledChecks.get());
			}
		}
	}

	@Test
	void domainBeforeBackendKeepsNormalDisclosureAcrossUpgradeAndOnlyNextReportReadsHighDetail() throws Exception {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger admissions = new AtomicInteger();
			AtomicInteger detailReads = new AtomicInteger();
			AtomicInteger throwableReads = new AtomicInteger();
			Throwable failure = hostile(throwableReads);
			CountDownLatch consumed = new CountDownLatch(1);
			CountDownLatch continueReport = new CountDownLatch(1);
			Supplier<String> detail = () -> { detailReads.incrementAndGet(); return "upgraded-secret"; };
			try (var executor = Executors.newFixedThreadPool(2)) {
				var terminal = executor.submit(() -> Diagnostics.client().atNormal().domainBeforeBackend(Level.ERROR, "domain-upgrade", () -> {
					admissions.incrementAndGet();
					consumed.countDown();
					await(continueReport);
					return new DiagnosticLogger.DomainReport("fallback", failure, detail);
				}));
				try {
					assertTrue(consumed.await(5, TimeUnit.SECONDS));
					executor.submit(() -> Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG)).get(5, TimeUnit.SECONDS);
				} finally {
					continueReport.countDown();
				}
				terminal.get(5, TimeUnit.SECONDS);
			}
			assertEquals(1, admissions.get());
			assertEquals(0, throwableReads.get());
			assertEquals(0, detailReads.get());
			assertTrue(fixture.sink.events.getFirst().text.contains("site=domain-upgrade handling=fallback"));
			assertFalse(fixture.sink.events.getFirst().text.contains("secret"));
			Diagnostics.client().atNormal().domainBeforeBackend(Level.ERROR, "domain-upgrade", () -> {
				admissions.incrementAndGet();
				return new DiagnosticLogger.DomainReport("fallback", failure, detail);
			});
			assertEquals(2, admissions.get());
			assertEquals(1, detailReads.get());
			assertTrue(throwableReads.get() > 0);
			assertEquals(2, fixture.sink.events.size());
			assertTrue(fixture.sink.events.getLast().text.contains("upgraded-secret"));
			assertTrue(fixture.sink.events.getLast().text.contains("original-secret"));
		}
	}

	@Test
	void domainBeforeBackendShortCircuitsMinimumAndNullWithoutTouchingBackend() {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger admissions = new AtomicInteger();
			Diagnostics.client().atDetailed().domainBeforeBackend(Level.ERROR, "domain-minimum", () -> {
				throw new AssertionError("Below minimum cannot consume domain admission");
			});
			Diagnostics.client().atNormal().domainBeforeBackend(Level.ERROR, "domain-denied", () -> {
				admissions.incrementAndGet();
				return null;
			});
			assertEquals(1, admissions.get());
			assertEquals(0, fixture.sink.preparations.get());
			assertEquals(0, fixture.sink.enabledChecks.get());
			assertTrue(fixture.sink.events.isEmpty());
			assertTrue(fixture.faults.observations().isEmpty());
		}
	}

	@Test
	void domainBeforeBackendCallbackAndHandlingFailuresAreAdmissionFaultsWithoutRollbackOrBackendCalls() {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger domainState = new AtomicInteger();
			for (boolean linkage : List.of(false, true)) {
				Diagnostics.client().domainBeforeBackend(Level.WARN, linkage ? "domain-linkage" : "domain-runtime", () -> {
					domainState.incrementAndGet();
					if (linkage) throw new NoClassDefFoundError("gate failed");
					throw new IllegalStateException("gate failed");
				});
			}
			Diagnostics.client().domainBeforeBackend(Level.WARN, "domain-handling", () -> {
				domainState.incrementAndGet();
				return new DiagnosticLogger.DomainReport("raw provider reason", new RuntimeException(),
					() -> { throw new AssertionError("Invalid handling never formats detail"); });
			});
			assertThrows(IllegalArgumentException.class, () -> Diagnostics.client().domainBeforeBackend(Level.WARN, "raw dynamic site", () -> {
				throw new AssertionError("Site must be controlled before admission");
			}));
			assertEquals(3, domainState.get(), "Reporting failures do not refund or repeat a consumed domain transition");
			assertEquals(0, fixture.sink.preparations.get());
			assertEquals(0, fixture.sink.enabledChecks.get());
			assertTrue(fixture.sink.events.isEmpty());
			assertEquals(3, fixture.faults.observations().size());
			assertTrue(fixture.faults.observations().stream().allMatch(value -> value.key().stage() == DiagnosticFaultReporter.Stage.ADMISSION));
			Diagnostics.client().warn("guard-restored");
			assertEquals(1, fixture.sink.events.size());
		}
	}

	@Test
	void domainBeforeBackendRejectsBothDomainAndIntervalChannelsBeforeAnyGateOrClock() {
		try (Fixture fixture = new Fixture()) {
			DiagnosticChannel domain = DiagnosticChannel.domain("second-domain", () -> {
				throw new AssertionError("No second domain gate");
			});
			DiagnosticChannel interval = DiagnosticChannel.interval("second-clock", Duration.ofSeconds(10), () -> {
				throw new AssertionError("No additional channel clock");
			});
			for (DiagnosticChannel channel : List.of(domain, interval)) {
				Diagnostics.client().rateLimited(channel).domainBeforeBackend(Level.WARN, channel == domain ? "reject-domain" : "reject-interval", () -> {
					throw new AssertionError("Cannot consume callback with an attached channel");
				});
			}
			assertEquals(0, fixture.sink.preparations.get());
			assertEquals(0, fixture.sink.enabledChecks.get());
			assertTrue(fixture.sink.events.isEmpty());
			assertEquals(2, fixture.faults.observations().size());
			assertTrue(fixture.faults.observations().stream().allMatch(value -> value.key().stage() == DiagnosticFaultReporter.Stage.ADMISSION));
		}
	}

	@Test
	void domainBeforeBackendConsumesOneGateOnSetupFailureAndDoesNotReplayDescriptor() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			fixture.sink.throwAt = DiagnosticFaultReporter.Stage.SETUP;
			AtomicInteger admissions = new AtomicInteger();
			AtomicInteger details = new AtomicInteger();
			Diagnostics.client().domainBeforeBackend(Level.WARN, "domain-setup", () -> {
				admissions.incrementAndGet();
				return new DiagnosticLogger.DomainReport("fallback", new RuntimeException("business"),
					() -> { details.incrementAndGet(); return "unread-detail"; });
			});
			assertEquals(1, admissions.get());
			assertEquals(0, details.get());
			assertEquals(1, fixture.sink.preparations.get());
			assertEquals(0, fixture.sink.enabledChecks.get());
			assertEquals(DiagnosticFaultReporter.Stage.SETUP, fixture.faults.observations().getFirst().key().stage());
			fixture.sink.throwAt = null;
			Diagnostics.client().domainBeforeBackend(Level.WARN, "domain-setup", () -> null);
			assertEquals(1, admissions.get());
			assertEquals(0, details.get());
			assertEquals(1, fixture.sink.preparations.get());
			assertTrue(fixture.sink.events.isEmpty());
		}
	}

	@Test
	void domainBeforeBackendRetainsReentryGuardAcrossAdmissionCallback() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.client().domainBeforeBackend(Level.WARN, "domain-outer", () -> {
				Diagnostics.client().domainBeforeBackend(Level.ERROR, "domain-nested", () -> {
					throw new AssertionError("Reentrant callback must not execute");
				});
				return new DiagnosticLogger.DomainReport("fallback", null,
					() -> { throw new AssertionError("NORMAL never reads detail"); });
			});
			assertEquals(1, fixture.sink.events.size());
			assertEquals(1, fixture.sink.preparations.get());
			assertEquals(1, fixture.sink.enabledChecks.get());
			assertEquals(1, fixture.faults.observations().size());
			assertEquals(DiagnosticFaultReporter.Stage.REENTRY, fixture.faults.observations().getFirst().key().stage());
		}
	}

	@Test
	void concurrentChannelDuplicatesAndClockWraparoundAdmitOnce() throws Exception {
		try (Fixture fixture = new Fixture()) {
			AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 5);
			DiagnosticChannel channel = DiagnosticChannel.interval("concurrent", Duration.ofNanos(10), clock::get);
			CountDownLatch start = new CountDownLatch(1);
			try (var executor = Executors.newFixedThreadPool(4)) {
				var jobs = new ArrayList<java.util.concurrent.Future<?>>();
				for (int index = 0; index < 32; index++) jobs.add(executor.submit(() -> {
					try { start.await(); } catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
					}
					Diagnostics.client().rateLimited(channel).warn("once");
				}));
				start.countDown();
				for (var job : jobs) job.get(5, TimeUnit.SECONDS);
			}
			assertEquals(1, fixture.sink.events.size());
			clock.addAndGet(10);
			Diagnostics.client().rateLimited(channel).error("next");
			assertEquals(2, fixture.sink.events.size());
			assertTrue(fixture.sink.events.getLast().text.contains("suppressed=31"));
		}
	}

	@Test
	void setupEnabledClockAdmissionContextAccessorFormatAndSinkFailuresAreObserved() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			fixture.sink.throwAt = DiagnosticFaultReporter.Stage.SETUP;
			Diagnostics.client().site("setup-site").warn("status");
			fixture.sink.throwAt = DiagnosticFaultReporter.Stage.ENABLED;
			Diagnostics.client().site("enabled-site").warn("status");
			fixture.sink.throwAt = null;
			Diagnostics.client().site("clock-site").rateLimited(DiagnosticChannel.interval("broken-clock", Duration.ofSeconds(1),
				() -> { throw new IllegalStateException("clock-secret"); })).warn("status");
			Diagnostics.client().site("admission-site").rateLimited(DiagnosticChannel.domain("broken-gate",
				() -> { throw new IllegalStateException("gate-secret"); })).warn("status");
			Diagnostics.client().warn("context-site", "fallback", new RuntimeException("business-original"),
				() -> { throw new IllegalStateException("context-secret"); });
			assertTrue(fixture.sink.events.getLast().text.contains("business-original"));
			assertTrue(fixture.sink.events.getLast().text.contains("context unavailable"));
			Diagnostics.client().warn("accessor-site", "fallback", new RuntimeException() {
				@Override public String getMessage() { throw new IllegalStateException("accessor-secret"); }
			}, null);
			assertTrue(fixture.sink.events.getLast().text.contains("message unavailable"));
			Diagnostics.ExceptionFormatter previousFormatter = Diagnostics.swapFormatterForTesting((context, failure, observer) -> {
				throw new IllegalStateException("formatter-secret");
			});
			try {
				Diagnostics.client().warn("format-site", "fallback", new RuntimeException("business"), null);
			} finally {
				Diagnostics.swapFormatterForTesting(previousFormatter);
			}
			fixture.sink.throwAt = DiagnosticFaultReporter.Stage.SINK;
			Diagnostics.client().site("sink-site").warn("status");
			assertEquals(java.util.EnumSet.of(DiagnosticFaultReporter.Stage.SETUP, DiagnosticFaultReporter.Stage.ENABLED,
				DiagnosticFaultReporter.Stage.CHANNEL_CLOCK, DiagnosticFaultReporter.Stage.ADMISSION, DiagnosticFaultReporter.Stage.CONTEXT,
				DiagnosticFaultReporter.Stage.ACCESSOR, DiagnosticFaultReporter.Stage.FORMAT, DiagnosticFaultReporter.Stage.SINK),
				java.util.EnumSet.copyOf(fixture.faults.observations().stream().map(observation -> observation.key().stage()).toList()));
		}
	}

	@Test
	void reportingReentryTerminatesAtEnabledSupplierDomainAndSinkBoundaries() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			fixture.sink.onEnabled = () -> Diagnostics.server().site("recursive-enabled").warn("nested");
			Diagnostics.client().warn(() -> { Diagnostics.client().site("recursive-context").warn("nested"); return "outer"; });
			fixture.sink.onEnabled = null;
			fixture.sink.onLog = () -> Diagnostics.client().site("recursive-sink").warn("nested");
			Diagnostics.client().rateLimited(DiagnosticChannel.domain("recursive-domain", () -> {
				Diagnostics.client().site("recursive-domain").warn("nested"); return true;
			})).warn("outer");
			assertEquals(2, fixture.sink.events.size());
			assertEquals(4, fixture.faults.observations().size());
			assertTrue(fixture.faults.observations().stream().allMatch(value -> value.key().stage() == DiagnosticFaultReporter.Stage.REENTRY));
		}
	}

	@Test
	void reentryFaultOutputIsDeferredUntilClockAndDomainCallbackLocksHaveBeenReleased() {
		try (Fixture fixture = new Fixture()) {
			Object callbackLock = new Object();
			AtomicInteger writes = new AtomicInteger();
			DiagnosticFaultReporter faults = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> new java.io.OutputStream() {
				@Override public void write(int value) { throw new AssertionError("Complete record required"); }
				@Override public void write(byte @Unmodifiable [] record) {
					assertFalse(Thread.holdsLock(callbackLock), "Descriptor output cannot hold clock/domain callback locks");
					writes.incrementAndGet();
				}
			}));
			DiagnosticFaultReporter previous = Diagnostics.swapFaultsForTesting(faults);
			try {
				DiagnosticChannel clock = DiagnosticChannel.interval("reentrant-clock", Duration.ofSeconds(1), () -> {
					synchronized (callbackLock) {
						Diagnostics.client().site("clock-reentry").warn("nested");
						return 0;
					}
				});
				Diagnostics.client().rateLimited(clock).warn("outer");
				Diagnostics.client().rateLimited(DiagnosticChannel.domain("reentrant-domain", () -> {
					synchronized (callbackLock) {
						Diagnostics.client().site("domain-reentry").warn("nested");
						return true;
					}
				})).warn("outer");
				assertEquals(2, writes.get());
				assertEquals(2, faults.observations().size());
			} finally {
				Diagnostics.swapFaultsForTesting(previous);
			}
		}
	}

	@Test
	void admittedSinkFailureConsumesChannelAndFormattingRemainsBounded() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			DiagnosticLogger logger = Diagnostics.client().rateLimited(DiagnosticChannel.interval("sink-consumed", Duration.ofDays(1)));
			fixture.sink.throwAt = DiagnosticFaultReporter.Stage.SINK;
			logger.warn(() -> "first");
			fixture.sink.throwAt = null;
			logger.warn(() -> { throw new AssertionError("failed sink does not replay or refund"); });
			assertTrue(fixture.sink.events.isEmpty());
			Diagnostics.client().warn("large-report", "fallback", new RuntimeException("x".repeat(30_000)), () -> "y".repeat(30_000));
			assertTrue(fixture.sink.events.getLast().text.length() <= SafeExceptionReport.MAX_CHARACTERS);
			assertTrue(fixture.sink.events.getLast().text.contains("truncated"));
		}
	}

	@Test
	void blockedContextDoesNotHoldChannelOrPolicyLocksAndFatalContextBoundaryIsPreserved() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			DiagnosticLogger logger = Diagnostics.client().rateLimited(DiagnosticChannel.interval("blocked-context", Duration.ofDays(1)));
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			try (var executor = Executors.newFixedThreadPool(2)) {
				var blocked = executor.submit(() -> logger.warn(() -> {
					entered.countDown();
					try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
					}
					return "first-context";
				}));
				try {
					assertTrue(entered.await(5, TimeUnit.SECONDS));
					executor.submit(() -> {
						logger.warn(() -> { throw new AssertionError("duplicate remains suppressed"); });
						Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
						Diagnostics.server().warn("unblocked");
					}).get(5, TimeUnit.SECONDS);
					assertEquals(1, fixture.sink.events.size());
				} finally {
					release.countDown();
				}
				blocked.get(5, TimeUnit.SECONDS);
			}
			assertEquals(2, fixture.sink.events.size());
			OutOfMemoryError fatal = new OutOfMemoryError("fatal context");
			assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> Diagnostics.client().warn(() -> { throw fatal; })));
			Diagnostics.client().warn("guard-restored-after-fatal");
			assertEquals(3, fixture.sink.events.size());
		}
	}

	@Test
	void reportByProfileDoesNotReadDetailAtNormalAndUsesTheFixedSummary() {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger reads = new AtomicInteger();
			assertTrue(Diagnostics.client().atNormal().reportByProfile(Level.WARN, "profile-normal", "deferred",
				summary("reason={} impact={} limitHit={}", SafeDiagnosticValue.code("request-capacity"),
					SafeDiagnosticValue.code("ping-unavailable"), SafeDiagnosticValue.bool(true)),
				profile -> { reads.incrementAndGet(); return "precision=" + profile.name(); },
				new RuntimeException("normal-secret")));
			assertEquals(0, reads.get());
			String text = fixture.sink.events.getLast().text;
			assertTrue(text.contains("site=profile-normal handling=deferred"));
			assertTrue(text.contains("reason=request-capacity impact=ping-unavailable limitHit=true"));
			assertTrue(text.contains("failure=runtime-failure"));
			assertFalse(text.contains("normal-secret"));
			assertThrows(IllegalArgumentException.class, () -> Diagnostics.client().reportByProfile(Level.WARN,
				"profile-normal", "raw handling", summary("reason={}", SafeDiagnosticValue.code("deferred")), null, null));
		}
	}

	@Test
	void reportByProfileUsesOneSnapshotAndSelectsDeepPrecision() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			AtomicInteger reads = new AtomicInteger();
			assertTrue(Diagnostics.client().atNormal().reportByProfile(Level.DEBUG, "profile-snapshot", "truncated",
				summary("reason={}", SafeDiagnosticValue.code("snapshot-limit")), profile -> {
					reads.incrementAndGet();
					Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
					return "precision=" + profile.name();
				}, null));
			assertEquals(1, reads.get());
			assertTrue(fixture.sink.events.getLast().text.contains("reason=snapshot-limit precision=DEEP"));
			assertEquals(LoggingProfile.NORMAL, Diagnostics.profile(DiagnosticSide.CLIENT),
				"The terminal must not re-snapshot after detail selection");
		}
	}

	@Test
	void reportByProfileBypassesOnlyIntervalChannelsAtDebugAndSharesThemBelowDebug() {
		try (Fixture fixture = new Fixture()) {
			AtomicLong clock = new AtomicLong();
			DiagnosticChannel channel = DiagnosticChannel.interval("profile-channel", Duration.ofSeconds(10), clock::get);
			DiagnosticLogger logger = Diagnostics.client().atNormal().rateLimited(channel);
			DiagnosticLogger.NormalSummary reportSummary = summary("reason={}", SafeDiagnosticValue.code("rejected"));
			AtomicInteger reads = new AtomicInteger();
			assertTrue(logger.reportByProfile(Level.DEBUG, "profile-channel", "rejected", reportSummary,
				profile -> { reads.incrementAndGet(); return "precision=" + profile.name(); }, null, true));
			assertFalse(logger.reportByProfile(Level.DEBUG, "profile-channel", "rejected", reportSummary,
				profile -> { reads.incrementAndGet(); return "precision=" + profile.name(); }, null, true));
			assertEquals(0, reads.get());
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			assertFalse(logger.reportByProfile(Level.DEBUG, "profile-channel", "rejected", reportSummary,
				profile -> { reads.incrementAndGet(); return "precision=" + profile.name(); }, null, true));
			clock.set(Duration.ofSeconds(10).toNanos());
			assertTrue(logger.reportByProfile(Level.DEBUG, "profile-channel", "rejected", reportSummary,
				profile -> { reads.incrementAndGet(); return "precision=" + profile.name(); }, null, true));
			assertEquals(1, reads.get());
			assertTrue(fixture.sink.events.getLast().text.contains("precision=DEEP"));
			assertTrue(fixture.sink.events.getLast().text.contains("suppressed="));
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			assertTrue(logger.reportByProfile(Level.DEBUG, "profile-channel", "rejected", reportSummary,
				profile -> { reads.incrementAndGet(); return "precision=" + profile.name(); }, null, true));
			assertTrue(logger.reportByProfile(Level.DEBUG, "profile-channel", "rejected", reportSummary,
				profile -> { reads.incrementAndGet(); return "precision=" + profile.name(); }, null, true));
			assertEquals(3, reads.get());
			assertEquals(4, fixture.sink.events.size());
			for (int index = 2; index < 4; index++) {
				String bypassed = fixture.sink.events.get(index).text;
				assertTrue(bypassed.contains("bypass=debug-every-occurrence"));
				assertFalse(bypassed.contains("suppressed="));
			}
		}
	}

	@Test
	void reportByProfileDefaultsToCadenceControlAtDebug() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			AtomicLong clock = new AtomicLong();
			DiagnosticChannel channel = DiagnosticChannel.interval("profile-default", Duration.ofSeconds(10), clock::get);
			DiagnosticLogger logger = Diagnostics.client().atNormal().rateLimited(channel);
			DiagnosticLogger.NormalSummary reportSummary = summary("reason={}", SafeDiagnosticValue.code("rejected"));
			assertTrue(logger.reportByProfile(Level.DEBUG, "profile-default", "rejected", reportSummary,
				profile -> "detail", null));
			assertFalse(logger.reportByProfile(Level.DEBUG, "profile-default", "rejected", reportSummary,
				profile -> "detail", null));
			assertEquals(1, fixture.sink.events.size());
			assertTrue(fixture.sink.events.getFirst().text.contains("suppressed=0"));
		}
	}

	@Test
	void reportByProfileUsesTheSharedBoundedFailureFormatterAtDeep() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			assertTrue(Diagnostics.client().atNormal().reportByProfile(Level.WARN, "profile-failure", "rejected",
				summary("reason={} impact={}", SafeDiagnosticValue.code("transport-failure"),
					SafeDiagnosticValue.code("degraded")),
				profile -> "detail", new RuntimeException("deep-secret")));
			String text = fixture.sink.events.getLast().text;
			assertTrue(text.contains("reason=transport-failure impact=degraded"));
			assertTrue(text.contains("detail"));
			assertTrue(text.contains("deep-secret"));
			assertTrue(text.contains("exception"));
		}
	}

	@Test
	void reportByProfileKeepsTheSummaryAndContainsDetailFailures() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			assertTrue(Diagnostics.client().atNormal().reportByProfile(Level.DEBUG, "profile-context", "fallback",
				summary("reason={} impact={} limitHit={}", SafeDiagnosticValue.code("snapshot-limit"),
					SafeDiagnosticValue.code("degraded"), SafeDiagnosticValue.bool(true)),
				profile -> { throw new IllegalStateException("detail-secret"); }, null));
			String text = fixture.sink.events.getLast().text;
			assertTrue(text.contains("reason=snapshot-limit impact=degraded limitHit=true"));
			assertTrue(text.contains("context unavailable"));
			assertFalse(text.contains("detail-secret"));
			assertEquals(1, fixture.faults.observations().size());
			assertEquals(DiagnosticFaultReporter.Stage.CONTEXT, fixture.faults.observations().getFirst().key().stage());
		}
	}

	@Test
	void reportByProfileDebugEveryOccurrenceNeverBypassesADomainChannelOrItsDetailBoundary() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			AtomicInteger admissions = new AtomicInteger();
			AtomicInteger reads = new AtomicInteger();
			AtomicBoolean allow = new AtomicBoolean();
			DiagnosticLogger logger = Diagnostics.client().rateLimited(DiagnosticChannel.domain("profile-domain", () -> {
				admissions.incrementAndGet();
				return allow.get();
			}));
			DiagnosticLogger.NormalSummary reportSummary =
				summary("reason={}", SafeDiagnosticValue.code("provider-failure"));
			assertFalse(logger.reportByProfile(Level.DEBUG, "profile-domain", "rejected", reportSummary,
				profile -> { reads.incrementAndGet(); return "detail"; }, null, true));
			assertEquals(1, admissions.get());
			assertEquals(0, reads.get());
			allow.set(true);
			assertTrue(logger.reportByProfile(Level.DEBUG, "profile-domain", "rejected", reportSummary,
				profile -> { reads.incrementAndGet(); return "detail"; }, null, true));
			assertEquals(2, admissions.get(), "Every DEBUG report runs the domain gate");
			assertEquals(1, reads.get(), "Detail remains behind domain admission");
			assertFalse(fixture.sink.events.getLast().text.contains("bypass="),
				"A domain channel has no every-occurrence bypass");
		}
	}

	@Test
	void reportByProfileDoesNotReachChannelOrDetailWhenBackendOrMinimumRejects() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			AtomicInteger clockReads = new AtomicInteger();
			AtomicInteger reads = new AtomicInteger();
			DiagnosticChannel channel = DiagnosticChannel.interval("profile-disabled", Duration.ofSeconds(1), () -> {
				clockReads.incrementAndGet();
				return 0;
			});
			DiagnosticLogger logger = Diagnostics.client().atNormal().rateLimited(channel);
			DiagnosticLogger.NormalSummary reportSummary = summary("reason={}", SafeDiagnosticValue.code("queue-limit"));
			fixture.sink.enabled = false;
			assertFalse(logger.reportByProfile(Level.DEBUG, "profile-disabled", "deferred", reportSummary,
				profile -> { reads.incrementAndGet(); return "detail"; }, null, true));
			assertEquals(0, clockReads.get());
			assertEquals(0, reads.get());
			fixture.sink.enabled = true;
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			assertFalse(Diagnostics.client().atDetailed().reportByProfile(Level.DEBUG, "profile-minimum", "deferred",
				reportSummary, profile -> { reads.incrementAndGet(); return "detail"; }, null, true));
			assertEquals(0, clockReads.get());
			assertEquals(0, reads.get());
			assertTrue(fixture.sink.events.isEmpty());
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

	private static DiagnosticLogger select(DiagnosticLogger logger, LoggingProfile profile) {
		return switch (profile) { case NORMAL -> logger.atNormal(); case DEEP -> logger.atDetailed(); case DEBUG -> logger.atDebug(); };
	}

	private static DiagnosticLogger.NormalSummary summary(String template,
		SafeDiagnosticValue @Unmodifiable ... values) {
		return new DiagnosticLogger.NormalSummary(template, values);
	}

	private static Throwable hostile(AtomicInteger reads) {
		return new RuntimeException() {
			@Override public String getMessage() { reads.incrementAndGet(); return "original-secret"; }
			@Override public StackTraceElement @Unmodifiable [] getStackTrace() {
				reads.incrementAndGet(); return new StackTraceElement @Unmodifiable [] { new StackTraceElement("stack-secret", "read", "Secret.java", 7) };
			}
			@Override public @Nullable Throwable getCause() { reads.incrementAndGet(); return null; }
			@Override public String toString() { throw new AssertionError("No throwable string conversion"); }
		};
	}

	@NotNullByDefault
	private record Event(DiagnosticSide side, Level level, String text) {}

	@NotNullByDefault
	private static final class Sink implements DiagnosticBackend {
		private boolean enabled = true;
		private @Nullable DiagnosticFaultReporter.Stage throwAt;
		private @Nullable Runnable onEnabled;
		private @Nullable Runnable onLog;
		private final List<Event> events = new ArrayList<>();
		private final AtomicInteger preparations = new AtomicInteger();
		private final AtomicInteger enabledChecks = new AtomicInteger();
		@Override public void prepare(DiagnosticSide side) {
			preparations.incrementAndGet();
			fail(DiagnosticFaultReporter.Stage.SETUP);
		}
		@Override public boolean isEnabled(DiagnosticSide side, Level severity) {
			enabledChecks.incrementAndGet();
			fail(DiagnosticFaultReporter.Stage.ENABLED);
			if (onEnabled != null) onEnabled.run();
			return enabled;
		}
		@Override public synchronized void log(DiagnosticSide side, Level severity, String text) {
			fail(DiagnosticFaultReporter.Stage.SINK);
			if (onLog != null) onLog.run();
			events.add(new Event(side, severity, text));
		}
		private void fail(DiagnosticFaultReporter.Stage stage) {
			if (throwAt == stage) throw new IllegalStateException("backend-secret");
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		private final Sink sink = new Sink();
		private final DiagnosticFaultReporter faults = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(ByteArrayOutputStream::new));
		private final DiagnosticBackend previous = Diagnostics.swapBackendForTesting(sink);
		private final DiagnosticFaultReporter previousFaults = Diagnostics.swapFaultsForTesting(faults);
		private final LoggingProfile client = Diagnostics.profile(DiagnosticSide.CLIENT);
		private final LoggingProfile server = Diagnostics.profile(DiagnosticSide.SERVER);
		private final LoggingProfile bootstrap = Diagnostics.profile(DiagnosticSide.BOOTSTRAP);
		private Fixture() {
			for (DiagnosticSide side : DiagnosticSide.values()) Diagnostics.setProfile(side, LoggingProfile.NORMAL);
		}
		@Override public void close() {
			Diagnostics.swapBackendForTesting(previous);
			Diagnostics.swapFaultsForTesting(previousFaults);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, client);
			Diagnostics.setProfile(DiagnosticSide.SERVER, server);
			Diagnostics.setProfile(DiagnosticSide.BOOTSTRAP, bootstrap);
		}
	}
}
