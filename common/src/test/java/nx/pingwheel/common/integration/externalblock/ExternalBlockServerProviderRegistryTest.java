package nx.pingwheel.common.integration.externalblock;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.domain.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockServerProviderRegistryTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void registrationKeepsExplicitOrderAndFirstDuplicate() {
		ExternalBlockServerProviderRegistry registry = new ExternalBlockServerProviderRegistry();
		FakeProvider first = new FakeProvider("provider:first");
		FakeProvider duplicate = new FakeProvider("provider:first");
		FakeProvider second = new FakeProvider("provider:second");

		registry.register(first);
		registry.register(duplicate);
		registry.register(second);

		assertEquals(List.of(first, second), registry.providers());
		assertEquals(first, registry.find("provider:first"));
	}

	@Test
	void missingLevelAndCorruptCandidateAreFailSoft() {
		ExternalBlockServerProviderRegistry registry = new ExternalBlockServerProviderRegistry();
		registry.register(new FakeProvider("provider:test"));
		Target.ExternalBlockTarget corrupt = Target.ExternalBlockTarget.candidate(
			"minecraft:overworld", "provider:test", "minecraft:stone", "not-a-provider-locator", false);

		assertTrue(registry.validate(null, corrupt) instanceof ExternalBlockServerProvider.ValidationResult.Invalid);
		assertTrue(registry.materialize(null, corrupt) instanceof ExternalBlockServerProvider.MaterializationResult.Invalid);
	}

	@Test
	void observationDefaultsToUnavailableForExistingProviderImplementations() {
		FakeProvider provider = new FakeProvider("provider:test");
		Target.ExternalBlockTarget committed = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", provider.id, "stable", "minecraft:stone", "opaque", false);

		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable.class,
			provider.observeBlock(null, committed));
		assertTrue(provider.resolvePreviewReadSource(null, committed).isEmpty());
		assertTrue(provider.resolveCommittedReadSource(null, committed).isEmpty());
	}

	@Test
	void contentDispatchIsUnavailableForUnknownOrLegacyProvidersWithoutMaterialization() {
		var candidate = Target.ExternalBlockTarget.candidate("minecraft:overworld", "provider:test", "minecraft:stone", "opaque", false);
		var target = committed("minecraft:stone");
		var legacy = new FakeProvider("provider:test");
		assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(null, null, candidate, true).isEmpty());
		assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(legacy, null, candidate, true).isEmpty());
		assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(legacy, null, target, false).isEmpty());
		assertEquals(0, legacy.materializationCalls.get());
		assertEquals(0, legacy.references.size(), "preview cannot allocate a provider reference via materialization");
		assertEquals(0, legacy.observationCalls.get());
	}

	@Test
	void contentDispatchSelectsOnlyItsOwnCandidateOrCommittedMethodAndFailsSoft() {
		var provider = new FakeProvider("provider:test");
		var candidate = Target.ExternalBlockTarget.candidate("minecraft:overworld", "provider:test", "minecraft:stone", "opaque", false);
		provider.contentFailure = new LinkageError("new Content API drift");
		assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(provider, null, candidate, true).isEmpty());
		assertEquals(1, provider.previewCalls.get());
		assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(provider, null, candidate, false).isEmpty());
		assertEquals(0, provider.committedCalls.get());
		assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(provider, null, committed("minecraft:stone"), false).isEmpty());
		assertEquals(1, provider.committedCalls.get());
		assertTrue(ExternalBlockServerProviderRegistry.invokeReadSource(new FakeProvider("other"), null, candidate, true).isEmpty());
		assertEquals(0, provider.materializationCalls.get());
		assertEquals(0, provider.references.size());
	}

	@Test
	void observationDispatchFailsSoftForMissingProviderAndProviderFailures() {
		ExternalBlockServerProviderRegistry registry = new ExternalBlockServerProviderRegistry();
		Target.ExternalBlockTarget committed = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "provider:test", "stable", "minecraft:stone", "opaque", false);
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			registry.observeBlock(null, null, committed));

		FakeProvider provider = new FakeProvider("provider:test");
		registry.register(provider);
		provider.observationFailure = new IllegalStateException("provider failure");
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable.class,
			ExternalBlockServerProviderRegistry.invokeObservation(provider, null, committed));
		provider.observationLinkageFailure = new LinkageError("API drift");
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable.class,
			ExternalBlockServerProviderRegistry.invokeObservation(provider, null, committed));
		provider.observationFailure = null;
		provider.observationLinkageFailure = null;
		provider.observationResult = new ExternalBlockServerProvider.ObservationResult.Invalid();
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			ExternalBlockServerProviderRegistry.invokeObservation(provider, null, committed));
	}

	@Test
	void productionObservationFlowValidatesLoadedLiveIdentityAndProvidedState() {
		ExternalBlockServerProviderRegistry registry = new ExternalBlockServerProviderRegistry();
		FakeProvider provider = new FakeProvider("provider:test");
		registry.register(provider);
		FakeObservationAccess access = new FakeObservationAccess();
		Target.ExternalBlockTarget committed = committed("minecraft:stone");
		BlockPos position = new BlockPos(4, 65, -2);
		provider.observationResult = available(access.level, position, access.liveState);

		ExternalBlockServerProvider.ObservationResult result = observe(
			registry, access, provider, committed);

		var observed = assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Available.class, result);
		assertEquals(position, observed.observation().position());
		assertEquals(access.liveState, observed.observation().state());
		assertEquals(1, access.loadedCalls);
		assertEquals(1, access.stateCalls);
		assertEquals(1, provider.observationCalls.get());

		access.loaded = false;
		access.stateCalls = 0;
		provider.observationResult = available(access.level, position, access.liveState);
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, committed));
		assertEquals(0, access.stateCalls, "unloaded positions must not read block state");

		access.loaded = true;
		access.liveState = Blocks.AIR.defaultBlockState();
		provider.observationResult = available(access.level, position, access.liveState);
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, committed));

		access.liveState = Blocks.STONE.defaultBlockState();
		provider.observationResult = available(access.level, position, Blocks.CHEST.defaultBlockState());
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, committed));

		provider.observationResult = available(access.level, position, access.liveState);
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, committed("minecraft:furnace")));

		provider.observationResult = available(access.otherLevel, position, access.liveState);
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, committed));
	}

	@Test
	void productionObservationFlowRejectsWrongThreadDimensionAndCandidateBeforeProviderOrRead() {
		ExternalBlockServerProviderRegistry registry = new ExternalBlockServerProviderRegistry();
		FakeProvider provider = new FakeProvider("provider:test");
		registry.register(provider);
		FakeObservationAccess access = new FakeObservationAccess();
		BlockPos position = new BlockPos(1, 64, 1);
		provider.observationResult = available(access.level, position, access.liveState);

		access.serverThread = false;
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, committed("minecraft:stone")));
		access.serverThread = true;
		access.sameDimension = false;
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, committed("minecraft:stone")));
		access.sameDimension = true;
		access.sameServer = false;
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, committed("minecraft:stone")));
		assertEquals(0, access.stateCalls);

		Target.ExternalBlockTarget candidate = Target.ExternalBlockTarget.candidate(
			"minecraft:overworld", provider.id, "minecraft:stone", "opaque", false);
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.Invalid.class,
			observe(registry, access, provider, candidate));
		access.sameServer = true;
		Target.ExternalBlockTarget unknownProviderTarget = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "provider:unknown", "stable", "minecraft:stone", "opaque", false);
		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable.class,
			registry.observeBlockWithAccess(access.server, access.level, unknownProviderTarget,
				new FakeProvider("provider:unknown"), (level, target) -> {
					return new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
				}, access));
	}

	@Test
	void missingProviderGateIsTemporarilyUnavailableWithoutInvocationOrWorldReads() {
		ExternalBlockServerProviderRegistry registry = new ExternalBlockServerProviderRegistry();
		FakeObservationAccess access = new FakeObservationAccess();
		AtomicInteger invocationCalls = new AtomicInteger();

		ExternalBlockServerProvider.ObservationResult result = registry.observeBlockWithAccess(
			access.server, access.level, committed("minecraft:stone"), null,
			(levelToken, ignored) -> {
				invocationCalls.incrementAndGet();
				throw new AssertionError("missing provider must not invoke observation");
			}, access);

		assertInstanceOf(ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable.class, result);
		assertEquals(0, invocationCalls.get(), "missing provider must not invoke observation");
		assertEquals(0, access.loadedCalls, "missing provider must not read world state");
		assertEquals(0, access.stateCalls, "missing provider must not read world state");
	}

	private static Target.ExternalBlockTarget committed(String expectedRegistryId) {
		return Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "provider:test", "stable", expectedRegistryId, "opaque", false);
	}

	private static ExternalBlockServerProvider.ObservationResult.Available available(
		Object level, BlockPos position, BlockState state) {
		return new ExternalBlockServerProvider.ObservationResult.Available(
			new ExternalBlockServerProvider.BlockObservation(level, position, state));
	}

	private static ExternalBlockServerProvider.ObservationResult observe(
		ExternalBlockServerProviderRegistry registry, FakeObservationAccess access,
		FakeProvider provider, Target.ExternalBlockTarget target) {
		return registry.observeBlockWithAccess(access.server, access.level, target, provider,
			(level, ignored) -> {
				provider.observationCalls.incrementAndGet();
				return provider.observationResult;
			}, access);
	}

	private static final class FakeObservationAccess implements ExternalBlockServerProviderRegistry.ObservationWorldAccess {
		private final Object server = new Object();
		private final Object level = new Object();
		private final Object otherLevel = new Object();
		private boolean serverThread = true;
		private boolean sameServer = true;
		private boolean sameDimension = true;
		private boolean loaded = true;
		private BlockState liveState = Blocks.STONE.defaultBlockState();
		private int loadedCalls;
		private int stateCalls;

		@Override
		public boolean sameServer(Object serverToken, Object levelToken) {
			return sameServer && serverToken == server && levelToken == level;
		}

		@Override
		public boolean sameDimension(Object levelToken, String expectedDimension) {
			return sameDimension && levelToken == level && "minecraft:overworld".equals(expectedDimension);
		}

		@Override
		public boolean isServerThread(Object serverToken) {
			return serverThread && serverToken == server;
		}

		@Override
		public boolean isLoaded(Object levelToken, BlockPos position) {
			loadedCalls++;
			return loaded && levelToken == level;
		}

		@Override
		public BlockState state(Object levelToken, BlockPos position) {
			stateCalls++;
			return liveState;
		}
	}

	private static final class FakeProvider implements ExternalBlockServerProvider {
		private final String id;
		private RuntimeException observationFailure;
		private LinkageError observationLinkageFailure;
		private ExternalBlockServerProvider.ObservationResult observationResult =
			new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
		private final AtomicInteger observationCalls = new AtomicInteger();
		private final AtomicInteger materializationCalls = new AtomicInteger();
		private final ExternalBlockReferenceIndex references = new ExternalBlockReferenceIndex();
		private final AtomicInteger previewCalls = new AtomicInteger(), committedCalls = new AtomicInteger();
		private LinkageError contentFailure;

		private FakeProvider(String id) {
			this.id = id;
		}

		@Override
		public String providerId() {
			return id;
		}

		@Override
		public ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) {
			return new ValidationResult.Invalid();
		}

		@Override
		public MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget candidate) {
			materializationCalls.incrementAndGet();
			references.commit(references.prepare(new ExternalBlockReferenceIndex.LocatorKey(id,
				candidate.providerLocator(), candidate.expectedBlockRegistryId(), candidate.hasBlockEntity()), () -> "allocated"));
			return new MaterializationResult.Invalid();
		}

		@Override public Optional<ResolvedBlockReadSource> resolvePreviewReadSource(ServerLevel level, Target.ExternalBlockTarget candidate) {
			previewCalls.incrementAndGet();
			if (contentFailure != null) throw contentFailure;
			return ExternalBlockServerProvider.super.resolvePreviewReadSource(level, candidate);
		}
		@Override public Optional<ResolvedBlockReadSource> resolveCommittedReadSource(ServerLevel level, Target.ExternalBlockTarget committed) {
			committedCalls.incrementAndGet();
			if (contentFailure != null) throw contentFailure;
			return ExternalBlockServerProvider.super.resolveCommittedReadSource(level, committed);
		}

		@Override
		public RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
			return new RefreshResult.Invalid();
		}

		@Override
		public ObservationResult observeBlock(ServerLevel level, Target.ExternalBlockTarget committed) {
			if (observationLinkageFailure != null) throw observationLinkageFailure;
			if (observationFailure != null) throw observationFailure;
			return observationResult;
		}

		@Override
		public Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget committed) {
			return Optional.empty();
		}

		@Override
		public void release(MinecraftServer server, Target.ExternalBlockTarget committed) {
		}
	}
}
