package nx.pingwheel.common.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingCaptureCoordinatorTest {

	private static final String OVERWORLD = "minecraft:overworld";

	@Test
	void beginReturnsFreshCurrentTokenAndLogs() {
		ActiveInteraction interaction = new ActiveInteraction();
		RecordingCaptureLogger logger = new RecordingCaptureLogger();
		PingCaptureCoordinator coordinator = coordinator(interaction, logger);

		InteractionToken token = coordinator.begin();

		assertTrue(interaction.isCurrent(token));
		assertTrue(logger.messages().stream().anyMatch(m -> m.contains("capture begin: token=0")));
	}

	@Test
	void completeResolvesAndFreezesTargetType() {
		ActiveInteraction interaction = new ActiveInteraction();
		PingCaptureCoordinator coordinator = coordinator(interaction, new RecordingCaptureLogger());
		InteractionToken token = coordinator.begin();

		TargetSnapshot snapshot = TargetSnapshotFactory.entity(OVERWORLD, UUID.randomUUID(), "minecraft:item");

		Optional<CapturedPingContext> result = coordinator.complete(token, snapshot);

		assertTrue(result.isPresent());
		assertEquals("dropped_item", result.get().resolvedTarget().targetType().id());

		// the frozen capture is immutable: reading it again yields the same target type
		assertEquals("dropped_item", interaction.currentContext().orElseThrow().resolvedTarget().targetType().id());
	}

	@Test
	void completeResolvesExactlyOnce() {
		ActiveInteraction interaction = new ActiveInteraction();
		AtomicInteger resolveCount = new AtomicInteger();
		TargetResolver countingResolver = (target, context) -> {
			resolveCount.incrementAndGet();
			return new ResolvedTarget(target, TargetTypeCatalog.builtIn().findById("location").orElseThrow());
		};
		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(countingResolver, interaction, PingCaptureLogger.noop());
		InteractionToken token = coordinator.begin();

		coordinator.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));

		assertEquals(1, resolveCount.get());
	}

	@Test
	void staleAsyncResultACannotOverwriteNewerPressB() {
		ActiveInteraction interaction = new ActiveInteraction();
		PingCaptureCoordinator coordinator = coordinator(interaction, new RecordingCaptureLogger());

		InteractionToken a = coordinator.begin();
		InteractionToken b = coordinator.begin();

		// stale result A arrives after B began: rejected, no state mutation
		Optional<CapturedPingContext> staleA = coordinator.complete(
			a, TargetSnapshotFactory.block(OVERWORLD, 0, 0, 0, "minecraft:stone"));

		assertTrue(staleA.isEmpty());
		assertTrue(interaction.currentContext().isEmpty());

		// fresh result B is accepted
		Optional<CapturedPingContext> freshB = coordinator.complete(
			b, TargetSnapshotFactory.location(OVERWORLD, 10, 20, 30));

		assertTrue(freshB.isPresent());
		assertEquals("location", freshB.get().resolvedTarget().targetType().id());
		assertTrue(freshB.get().resolvedTarget().target() instanceof Target.LocationTarget);
	}

	@Test
	void callbackRaceWhereNewBeginOccursDuringResolverCallIsRejectedAtomically() {
		ActiveInteraction interaction = new ActiveInteraction();
		AtomicBoolean resolverStarted = new AtomicBoolean();
		TargetResolver slowResolver = (target, context) -> {
			resolverStarted.set(true);
			// simulate a new press arriving while the resolver runs
			interaction.begin();
			return new ResolvedTarget(target, TargetTypeCatalog.builtIn().findById("location").orElseThrow());
		};
		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(slowResolver, interaction, PingCaptureLogger.noop());
		InteractionToken original = coordinator.begin();

		Optional<CapturedPingContext> result = coordinator.complete(
			original, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));

		assertTrue(resolverStarted.get());
		assertTrue(result.isEmpty());
		assertTrue(interaction.currentContext().isEmpty());
	}

	@Test
	void resolverRuntimeExceptionIsContainedAndLeavesTokenCurrentUncompleted() {
		ActiveInteraction interaction = new ActiveInteraction();
		RecordingCaptureLogger logger = new RecordingCaptureLogger();
		TargetResolver failingResolver = (target, context) -> {
			throw new IllegalStateException("boom-sentinel");
		};
		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(failingResolver, interaction, logger);
		InteractionToken token = coordinator.begin();

		Optional<CapturedPingContext> result = coordinator.complete(
			token, TargetSnapshotFactory.entity(OVERWORLD, UUID.randomUUID(), "minecraft:item"));

		assertTrue(result.isEmpty(), "resolver failure must be contained as empty");
		assertTrue(interaction.isCurrent(token), "token must remain current after a contained failure");
		assertTrue(interaction.currentContext().isEmpty(), "no capture may be frozen after a contained failure");

		// A caller can retry on the still-current, still-uncompleted token.
		PingCaptureCoordinator retry = new PingCaptureCoordinator(
			DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()), interaction, logger);
		Optional<CapturedPingContext> retried = retry.complete(
			token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));

		assertTrue(retried.isPresent());
		assertEquals("location", retried.get().resolvedTarget().targetType().id());
	}

	@Test
	void resolverFailureLogIsUsefulAndPrivate() {
		ActiveInteraction interaction = new ActiveInteraction();
		RecordingCaptureLogger logger = new RecordingCaptureLogger();
		TargetResolver failingResolver = (target, context) -> {
			throw new IllegalStateException("secret-detail-do-not-log");
		};
		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(failingResolver, interaction, logger);
		UUID entityUuid = UUID.randomUUID();
		InteractionToken token = coordinator.begin();

		coordinator.complete(token, TargetSnapshotFactory.entity(OVERWORLD, entityUuid, "minecraft:item"));

		List<String> messages = logger.messages();

		assertTrue(messages.stream().anyMatch(m -> m.contains("capture reject: resolve failure")));

		String joined = String.join("|", messages);

		// useful: identifies token sequence, kind, dimension, and failure class
		assertTrue(joined.contains("token=0"));
		assertTrue(joined.contains("kind=ENTITY"));
		assertTrue(joined.contains("dimension=minecraft:overworld"));
		assertTrue(joined.contains("cause=IllegalStateException"));

		// private: never the exception message or target identity
		assertFalse(joined.contains("secret-detail-do-not-log"), "exception message must not be logged");
		assertFalse(joined.contains("boom"), "exception message must not be logged");
		assertFalse(joined.contains(entityUuid.toString()), "UUID must not be logged");
		assertFalse(joined.contains("minecraft:item"), "entity type id must not be logged");
	}

	@Test
	void multiThreadedStaleResolutionCannotOverwriteNewerPress() throws Exception {
		ActiveInteraction interaction = new ActiveInteraction();
		RecordingCaptureLogger logger = new RecordingCaptureLogger();

		CountDownLatch resolverEntered = new CountDownLatch(1);
		CountDownLatch releaseResolver = new CountDownLatch(1);

		TargetResolver blockingResolver = (target, context) -> {
			resolverEntered.countDown();
			awaitLatch(releaseResolver);
			return new ResolvedTarget(target, TargetTypeCatalog.builtIn().findById("location").orElseThrow());
		};

		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(blockingResolver, interaction, logger);
		InteractionToken a = coordinator.begin();

		ExecutorService executor = Executors.newSingleThreadExecutor();

		try {
			Future<Optional<CapturedPingContext>> resultA = executor.submit(() -> coordinator.complete(
				a, TargetSnapshotFactory.location(OVERWORLD, 1, 2, 3)));

			// the worker is now blocked inside the resolver
			awaitLatch(resolverEntered);

			// the game thread starts a newer interaction while A's resolver still runs
			InteractionToken b = coordinator.begin();

			releaseResolver.countDown();

			Optional<CapturedPingContext> staleA = resultA.get(5, TimeUnit.SECONDS);

			assertTrue(staleA.isEmpty(), "stale A resolution must be rejected");
			assertTrue(interaction.isCurrent(b), "B must remain the current token");
			assertTrue(interaction.currentContext().isEmpty(), "stale A must not mutate state");

			Optional<CapturedPingContext> freshB = coordinator.complete(
				b, TargetSnapshotFactory.location(OVERWORLD, 4, 5, 6));

			assertTrue(freshB.isPresent(), "B completion must succeed");
			assertEquals("location", freshB.get().resolvedTarget().targetType().id());
		} finally {
			releaseResolver.countDown(); // never leave the worker blocked
			executor.shutdownNow();
		}
	}

	@Test
	void firstCompletionWinsForSameToken() {
		ActiveInteraction interaction = new ActiveInteraction();
		PingCaptureCoordinator coordinator = coordinator(interaction, new RecordingCaptureLogger());
		InteractionToken token = coordinator.begin();

		Optional<CapturedPingContext> first = coordinator.complete(
			token, TargetSnapshotFactory.block(OVERWORLD, 1, 2, 3, "minecraft:stone"));
		Optional<CapturedPingContext> second = coordinator.complete(
			token, TargetSnapshotFactory.block(OVERWORLD, 4, 5, 6, "minecraft:dirt"));

		assertTrue(first.isPresent());
		assertTrue(second.isEmpty());

		CapturedPingContext frozen = interaction.currentContext().orElseThrow();
		assertTrue(frozen.resolvedTarget().target() instanceof Target.BlockTarget);
		assertEquals("minecraft:stone", ((Target.BlockTarget) frozen.resolvedTarget().target()).blockRegistryId());
	}

	@Test
	void completeRejectsNullArguments() {
		ActiveInteraction interaction = new ActiveInteraction();
		PingCaptureCoordinator coordinator = coordinator(interaction, new RecordingCaptureLogger());
		InteractionToken token = coordinator.begin();
		TargetSnapshot snapshot = TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0);

		assertThrows(NullPointerException.class, () -> coordinator.complete(null, snapshot));
		assertThrows(NullPointerException.class, () -> coordinator.complete(token, null));
	}

	@Test
	void debugLogsCoverBeginResolveAcceptAndRejectWithoutSensitiveValues() {
		ActiveInteraction interaction = new ActiveInteraction();
		RecordingCaptureLogger logger = new RecordingCaptureLogger();
		PingCaptureCoordinator coordinator = coordinator(interaction, logger);

		UUID entityUuid = UUID.randomUUID();

		InteractionToken token = coordinator.begin();
		coordinator.complete(token, TargetSnapshotFactory.entity(OVERWORLD, entityUuid, "minecraft:item"));

		// begin a second interaction so the first token goes stale, exercising reject
		coordinator.begin();
		coordinator.complete(token, TargetSnapshotFactory.block(OVERWORLD, 987654, -42, 123456, "minecraft:stone"));

		List<String> messages = logger.messages();

		assertTrue(messages.stream().anyMatch(m -> m.contains("capture begin: token=0")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("capture resolve start: token=0")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("capture accepted: token=0")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("capture reject: stale token=0")));

		String joined = String.join("|", messages);

		// sensitive values must never appear in logs
		assertFalse(joined.contains("minecraft:item"), "entity type id must not be logged");
		assertFalse(joined.contains("minecraft:stone"), "block registry id must not be logged");
		assertFalse(joined.contains("minecraft:dirt"), "block registry id must not be logged");
		assertFalse(joined.contains(entityUuid.toString()), "UUID must not be logged");
		assertFalse(joined.contains("987654"), "coordinates must not be logged");
		assertFalse(joined.contains("123456"), "coordinates must not be logged");
		assertFalse(joined.contains("-42"), "coordinates must not be logged");
	}

	private static PingCaptureCoordinator coordinator(ActiveInteraction interaction, PingCaptureLogger logger) {
		return new PingCaptureCoordinator(
			DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()), interaction, logger);
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for latch");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("interrupted while waiting for latch", e);
		}
	}

	private static final class RecordingCaptureLogger implements PingCaptureLogger {

		private final List<String> messages = new ArrayList<>();

		@Override
		public void debug(String message, Object... args) {
			messages.add(format(message, args));
		}

		List<String> messages() {
			return List.copyOf(messages);
		}

		private static String format(String message, Object... args) {
			StringBuilder sb = new StringBuilder();
			int from = 0;
			int argIndex = 0;
			int open;

			while ((open = message.indexOf("{}", from)) >= 0) {
				sb.append(message, from, open);
				sb.append(argIndex < args.length ? String.valueOf(args[argIndex++]) : "{}");
				from = open + 2;
			}

			sb.append(message.substring(from));
			return sb.toString();
		}
	}
}
