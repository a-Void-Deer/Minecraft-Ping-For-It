package nx.pingwheel.common.integration.externalblock;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.diagnostics.DiagnosticBackend;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ExternalBlockRegistryDiagnosticsTest {
	@BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}
	private static final Target.ExternalBlockTarget CANDIDATE = Target.ExternalBlockTarget.candidate(
		"minecraft:overworld", "test", "minecraft:stone", "opaque", false);
	private static final Target.ExternalBlockTarget COMMITTED = Target.ExternalBlockTarget.committed(
		"minecraft:overworld", "test", "stable", "minecraft:stone", "opaque", false);
	private static final InventoryValidationBudget.Cost QUERY = new InventoryValidationBudget.Cost(64, 64, 4);

	@Test void failureAndNullResultsKeepDispatchOutcomesAndEmitOriginalCause() throws ReflectiveOperationException {
		ServerLevel level = token(ServerLevel.class);
		MinecraftServer server = (MinecraftServer) token(Class.forName("net.minecraft.server.dedicated.DedicatedServer"));
		for (boolean nullResult : List.of(false, true)) {
			try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
				var registry = new ExternalBlockServerProviderRegistry();
				var provider = new BrokenProvider(nullResult);
				registry.register(provider);
				assertInstanceOf(ExternalBlockServerProvider.ValidationResult.Invalid.class, registry.validate(level, CANDIDATE));
				assertInstanceOf(ExternalBlockServerProvider.MaterializationResult.Invalid.class, registry.materialize(level, CANDIDATE));
				assertInstanceOf(ExternalBlockServerProvider.RefreshResult.Invalid.class, registry.refresh(level, COMMITTED));
				assertTrue(registry.resolveName(level, COMMITTED).isEmpty());
				assertInstanceOf(ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable.class,
					ExternalBlockServerProviderRegistry.invokeObservation(provider, level, COMMITTED));
				assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(provider, level, CANDIDATE, true).isEmpty());
				assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(provider, level, COMMITTED, false).isEmpty());
				if (!nullResult) {
					registry.observeValidationDistance(level, COMMITTED,
						new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3), 4, true);
					registry.release(server, COMMITTED);
					registry.release(server, COMMITTED, "marker");
					registry.close(server);
				}
				assertEquals(nullResult ? 7 : 10, capture.events().size());
				for (LogEvent event : capture.events()) {
					String message = event.getMessage().getFormattedMessage();
					assertEquals(Level.WARN, event.getLevel());
					assertTrue(message.contains(BrokenProvider.class.getName()));
					assertTrue(message.contains(nullResult ? "null-result" : "original provider cause"));
					assertNull(event.getThrown());
				}
			}
		}
	}

	@Test void temporaryRefreshMissingProviderAndFatalFailuresRetainTheirContracts() throws ReflectiveOperationException {
		ServerLevel level = token(ServerLevel.class);
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
			var registry = new ExternalBlockServerProviderRegistry();
			assertInstanceOf(ExternalBlockServerProvider.RefreshResult.Invalid.class, registry.refresh(level, COMMITTED));
			var provider = new BrokenProvider(false);
			provider.temporary = true;
			registry.register(provider);
			assertInstanceOf(ExternalBlockServerProvider.RefreshResult.TemporarilyUnavailable.class, registry.refresh(level, COMMITTED));
			assertTrue(capture.events().isEmpty());
			provider.fatal = new AssertionError("must propagate");
			assertSame(provider.fatal, assertThrows(AssertionError.class, () -> registry.refresh(level, COMMITTED)));
			assertTrue(capture.events().isEmpty());
		}
	}

	@Test void cleanupFailureDoesNotPreventLaterProviderOrReferenceCleanup() throws ReflectiveOperationException {
		MinecraftServer server = (MinecraftServer) token(Class.forName("net.minecraft.server.dedicated.DedicatedServer"));
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
			var registry = new ExternalBlockServerProviderRegistry();
			registry.register(new BrokenProvider(false));
			var later = new BrokenProvider(true);
			later.id = "later";
			registry.register(later);
			registry.close(server);
			assertEquals(1, later.closed.get());
			var references = new ExternalBlockReferenceIndex();
			for (String id : List.of("first", "later")) {
				references.commit(references.prepare(new ExternalBlockReferenceIndex.LocatorKey("test", id, "minecraft:stone", false), () -> id));
			}
			AtomicInteger attempts = new AtomicInteger();
			references.close(id -> {
				attempts.incrementAndGet();
				if (id.equals("first")) throw new IllegalStateException("reference cleanup cause");
			});
			assertEquals(2, attempts.get());
			assertEquals(0, references.size());
			assertTrue(capture.events().stream().anyMatch(event ->
				event.getMessage().getFormattedMessage().contains("reference cleanup cause")));
		}
	}

	@Test void throwingMemberPredicateStillDeniesAccessAndReportsCause() throws ReflectiveOperationException {
		var descriptor = new BlockReadSource(CANDIDATE, "test", "sublevel",
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:stone"),
			new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3));
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
			var source = new ResolvedBlockReadSource(token(ServerLevel.class), descriptor,
				pos -> { throw new LinkageError("membership cause"); });
			assertFalse(source.containsMember(BlockPos.ZERO));
			assertFalse(source.containsMember(null));
			assertEquals(1, capture.events().size());
			assertTrue(capture.events().getFirst().getMessage().getFormattedMessage().contains("membership cause"));
			assertNull(capture.events().getFirst().getThrown());
		}
	}

	@Test void throwingMembershipKeepsUnknownAndReportsLazilyWithoutExtraProviderLookup() throws ReflectiveOperationException {
		ServerLevel level = token(ServerLevel.class);
		var position = new BlockPos(4, 5, 6);
		for (LoggingProfile profile : List.of(LoggingProfile.NORMAL, LoggingProfile.DEEP)) {
			try (var capture = new IntegrationDiagnosticCapture(Level.WARN, profile)) {
				var registry = new ExternalBlockServerProviderRegistry();
				var provider = new MembershipProvider("membership-test");
				provider.failure = new IllegalStateException("membership raw cause");
				registry.register(provider);
				assertEquals(ExternalBlockServerProvider.Membership.UNKNOWN,
					registry.membership(level, position, new InventoryValidationBudget.Allowance(QUERY)));
				assertEquals(1, provider.membershipCalls.get());
				assertEquals(1, provider.providerIdCalls.get());
				assertEquals(1, capture.events().size());
				var event = capture.events().getFirst();
				var message = event.getMessage().getFormattedMessage();
				assertEquals(Level.WARN, event.getLevel());
				assertTrue(message.contains("side=SERVER"));
				assertTrue(message.contains("site=external-block/membership"));
				assertTrue(message.contains("handling=membership-unavailable"));
				assertNull(event.getThrown());
				if (profile == LoggingProfile.NORMAL) {
					assertFalse(message.contains("membership raw cause"));
					assertTrue(message.contains("failure=invalid-state"));
				} else {
					assertTrue(message.contains(provider.getClass().getName()));
					assertTrue(message.contains("position=" + position));
					assertTrue(message.contains("membership raw cause"));
				}
			}
		}
	}

	@Test void ordinaryUnknownAndMembershipBudgetOrFatalOutcomesStayQuiet() throws ReflectiveOperationException {
		ServerLevel level = token(ServerLevel.class);
		var position = BlockPos.ZERO;
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN, LoggingProfile.DEEP)) {
			var registry = new ExternalBlockServerProviderRegistry();
			var provider = new MembershipProvider("ordinary-membership");
			registry.register(provider);
			assertEquals(ExternalBlockServerProvider.Membership.UNKNOWN,
				registry.membership(level, position, new InventoryValidationBudget.Allowance(QUERY)));
			assertTrue(capture.events().isEmpty());

			assertThrows(InventoryItemBudget.CaptureLimitException.class,
				() -> registry.membership(level, position, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
			assertTrue(capture.events().isEmpty());

			provider.fatal = new AssertionError("fatal membership");
			assertSame(provider.fatal, assertThrows(AssertionError.class,
				() -> registry.membership(level, position, new InventoryValidationBudget.Allowance(QUERY))));
			assertTrue(capture.events().isEmpty());
		}
	}

	@Test void blockingBackendDoesNotHoldRegistryMonitor() throws Exception {
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN, LoggingProfile.DEEP)) {
			// The scoped capture resets the membership channel for the first admission.
			var registry = new ExternalBlockServerProviderRegistry();
			var provider = new MembershipProvider("blocking-membership");
			provider.failure = new IllegalStateException("blocking membership");
			registry.register(provider);
			var entered = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			var previousBackend = swapBackend(new DiagnosticBackend() {
				@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return true; }
				@Override public void log(DiagnosticSide side, Level severity, String message) {
					entered.countDown();
					await(release);
				}
			});
			var executor = Executors.newFixedThreadPool(2);
			try {
				Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
				Future<ExternalBlockServerProvider.Membership> result = executor.submit(
					() -> registry.membership(token(ServerLevel.class), BlockPos.ZERO,
						new InventoryValidationBudget.Allowance(QUERY)));
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				Future<Boolean> monitorFree = executor.submit(registry::isEmpty);
				assertFalse(monitorFree.get(2, TimeUnit.SECONDS));
				release.countDown();
				assertEquals(ExternalBlockServerProvider.Membership.UNKNOWN, result.get(2, TimeUnit.SECONDS));
			} finally {
				release.countDown();
				executor.shutdownNow();
				swapBackend(previousBackend);
			}
		}
	}

	private static DiagnosticBackend swapBackend(DiagnosticBackend replacement) throws ReflectiveOperationException {
		var method = Diagnostics.class.getDeclaredMethod("swapBackendForTesting", DiagnosticBackend.class);
		method.setAccessible(true);
		return (DiagnosticBackend) method.invoke(null, replacement);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while blocking the test backend", failure);
		}
	}

	private static <T> T token(Class<T> type) throws ReflectiveOperationException {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		var field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
	}

	@NotNullByDefault
	private static final class MembershipProvider implements ExternalBlockServerProvider {
		private final String id;
		private final AtomicInteger providerIdCalls = new AtomicInteger();
		private final AtomicInteger membershipCalls = new AtomicInteger();
		private @Nullable RuntimeException failure;
		private @Nullable AssertionError fatal;
		private MembershipProvider(String id) { this.id = id; }
		@Override public String providerId() { providerIdCalls.incrementAndGet(); return id; }
		@Override public InventoryValidationBudget.Cost membershipCost() { return QUERY; }
		@Override public Membership membership(ServerLevel level, BlockPos position, InventoryValidationBudget.Allowance allowance) {
			membershipCalls.incrementAndGet();
			allowance.debit(QUERY);
			if (fatal != null) throw fatal;
			if (failure != null) throw failure;
			return Membership.UNKNOWN;
		}
		@Override public ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) {
			return new ValidationResult.Invalid();
		}
		@Override public MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget candidate) {
			return new MaterializationResult.Invalid();
		}
		@Override public RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
			return new RefreshResult.Invalid();
		}
		@Override public Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget committed) {
			return Optional.empty();
		}
		@Override public void release(MinecraftServer server, Target.ExternalBlockTarget committed) {}
		@Override public void close(MinecraftServer server) {}
	}

	@NotNullByDefault
	private static final class BrokenProvider implements ExternalBlockServerProvider {
		private final boolean nullResult;
		private final RuntimeException failure = new IllegalStateException("original provider cause");
		private final AtomicInteger closed = new AtomicInteger();
		private String id = "test";
		private boolean temporary;
		private @Nullable AssertionError fatal;
		private BrokenProvider(boolean nullResult) { this.nullResult = nullResult; }
		private <T> @Nullable T result() { if (!nullResult) throw failure; return null; }
		@Override public String providerId() { return id; }
		@Override public @Nullable ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) { return result(); }
		@Override public @Nullable MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget candidate) { return result(); }
		@Override public @Nullable RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
			if (fatal != null) throw fatal;
			return temporary ? new RefreshResult.TemporarilyUnavailable() : result();
		}
		@Override public @Nullable ObservationResult observeBlock(ServerLevel level, Target.ExternalBlockTarget committed) { return result(); }
		@Override public @Nullable Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget committed) { return result(); }
		@Override public @Nullable Optional<ResolvedBlockReadSource> resolvePreviewReadSource(ServerLevel level, Target.ExternalBlockTarget candidate) { return result(); }
		@Override public @Nullable Optional<ResolvedBlockReadSource> resolveCommittedReadSource(ServerLevel level, Target.ExternalBlockTarget committed) { return result(); }
		@Override public void release(MinecraftServer server, Target.ExternalBlockTarget committed) { result(); }
		@Override public void observeValidationDistance(ServerLevel level, Target.ExternalBlockTarget target,
			nx.pingwheel.common.marker.MarkerAnchor anchor, double distance, boolean withinRange) { result(); }
		@Override public void close(MinecraftServer server) { closed.incrementAndGet(); result(); }
	}
}
