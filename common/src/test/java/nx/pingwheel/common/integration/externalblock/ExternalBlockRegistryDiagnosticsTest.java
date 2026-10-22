package nx.pingwheel.common.integration.externalblock;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
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

	private static <T> T token(Class<T> type) throws ReflectiveOperationException {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		var field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
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
