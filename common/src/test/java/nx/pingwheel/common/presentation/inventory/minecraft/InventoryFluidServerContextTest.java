package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryPublicationEvidence;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production server facade and real source/publication ownership, without GC timing or a live server. */
@NotNullByDefault
class InventoryFluidServerContextTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@Test void sourceOwnsStableFacadeButHistoricalPublicationHasNoOwningPathToNativeContext() {
		try (var f = new Fixture()) {
			var consumer = f.attach(); var result = f.runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, result.result().completeness());
			assertEquals(2, f.nativeSource.reads); assertEquals(0, f.nativeSource.liveAccess);
			var source = Objects.requireNonNull(f.source); var facade = facade(source);
			assertEquals(Optional.of(true), f.runtime.probe(consumer));
			try (var old = f.runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.SCANNING).orElseThrow()) {
				consumer.close(); assertTrue(f.runtime.memory().retained() > 0);
				assertEquals(InventorySharedRead.Check.VALID, f.check(old));
				assertSame(facade, facade(source), "the source, not a temporary weak owner, keeps the non-native facade alive");
				assertNonOwningGraph(old);
				assertNonOwningGraph(source); assertSame(f.server, serverReference(facade).get());
				assertEquals(2, f.nativeSource.reads, "historical validation must remain metadata-only");
				assertEquals(1, f.nativeSource.witnessCaptures, "later operations compare the original proof");
				assertEquals(0, f.nativeSource.liveAccess);
			}
			f.runtime.close(); assertEquals(0, f.runtime.memory().retained()); assertEquals(0, f.runtime.memory().reserved());
			assertFalse(source.valid(new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
			assertNonOwningGraph(source);
		}
	}

	@Test void clearedServerContextRejectsGatesReacquisitionAndOldPublicationButFreshSourceCanRecover() {
		try (var f = new Fixture()) {
			var consumer = f.attach(); f.runtime.step(consumer, 1).orElseThrow();
			var facade = facade(Objects.requireNonNull(f.source));
			try (var old = f.runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.SCANNING).orElseThrow()) {
				serverReference(facade).clear(); int probes = f.nativeSource.probes;
				assertFalse(facade.onServerThread()); assertNull(facade.ownerKey(f.nativeSource.input));
				assertNull(facade.bind(f.nativeSource.input, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
				assertThrows(InventorySourceUnavailableException.class, () -> facade.used(
					new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.EXPLICIT_STRUCTURE, "fixture")));
				f.runtime.advance(1, f.nativeSource.settings);
				assertEquals(InventorySharedRead.Check.UNAVAILABLE, f.check(old));
				assertEquals(Optional.of(false), f.runtime.probe(consumer));
				assertTrue(consumer.invalidated()); assertFalse(consumer.scopeTransition());
				assertEquals(probes, f.nativeSource.probes, "lost context cannot reacquire a native access");
				assertEquals(1, f.nativeSource.reads); assertEquals(0, f.nativeSource.liveAccess);
				consumer.restart(); f.runtime.advance(2, f.nativeSource.settings);
				var fresh = f.runtime.step(consumer).orElseThrow();
				assertEquals(CaptureResult.Availability.READABLE, fresh.result().availability());
				assertEquals(CaptureResult.Completeness.COMPLETE, fresh.result().completeness());
				assertTrue(fresh.slots().stream().allMatch(value -> value != null && value.amount() == 81));
				assertEquals(2, fresh.slots().size());
				assertEquals(InventorySharedRead.Check.UNAVAILABLE, f.check(old), "fresh ownership cannot revive a cleared old cut");
				assertNonOwningGraph(old);
			}
		}
	}

	@Test void clearingContextInsideGetterFailsFinalGateAndDiscardsProvisionalValue() {
		try (var f = new Fixture()) {
			var consumer = f.attach(); f.runtime.step(consumer, 1).orElseThrow();
			var server = serverReference(facade(Objects.requireNonNull(f.source)));
			f.nativeSource.afterRead = server::clear; f.runtime.advance(1, f.nativeSource.settings);
			var rejected = f.runtime.step(consumer, 1).orElseThrow();
			assertNotEquals(CaptureResult.Availability.READABLE, rejected.result().availability());
			assertTrue(rejected.slots().isEmpty()); assertFalse(consumer.scopeTransition());
			assertEquals(2, f.nativeSource.reads); assertEquals(0, f.nativeSource.liveAccess);
			assertNonOwningGraph(Objects.requireNonNull(f.source));
		}
	}

	@Test void eachOperationResolvesCurrentServerLevelAndNeverFallsBackToPreviousBinding() {
		try (var f = new Fixture()) {
			var consumer = f.attach(); f.runtime.step(consumer).orElseThrow();
			try (var old = f.runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.SCANNING).orElseThrow()) {
				int probes = f.nativeSource.probes; f.server.level = null;
				assertEquals(InventorySharedRead.Check.UNAVAILABLE, f.check(old));
				assertEquals(Optional.of(false), f.runtime.probe(consumer));
				assertEquals(probes, f.nativeSource.probes); assertEquals(2, f.nativeSource.reads);
				assertEquals(0, f.nativeSource.liveAccess); assertNonOwningGraph(old);
			}
		}
	}
	@Test void liveServerFacadePreservesAuthorizedSableScopeOnlyRecompute() {
		try (var f = new Fixture()) {
			var consumer = f.attach(); f.runtime.step(consumer, 1).orElseThrow();
			f.nativeSource.scope = "two"; f.runtime.advance(1, f.nativeSource.settings);
			assertEquals(Optional.of(false), f.runtime.probe(consumer)); assertTrue(consumer.scopeTransition());
			assertEquals(0, f.nativeSource.liveAccess); consumer.recomputeScope();
			f.runtime.advance(2, f.nativeSource.settings); var fresh = f.runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Availability.READABLE, fresh.result().availability());
			assertEquals(CaptureResult.Completeness.COMPLETE, fresh.result().completeness());
			assertEquals(2, fresh.slots().size()); assertEquals(3, f.nativeSource.reads);
			assertNonOwningGraph(Objects.requireNonNull(f.source));
		}
	}

	private static InventoryFluidSources.World facade(InventorySourceAccess.Source source) {
		for (var field : source.getClass().getDeclaredFields()) if (field.getType() == InventoryFluidSources.World.class) {
			try { field.setAccessible(true); return (InventoryFluidSources.World) Objects.requireNonNull(field.get(source)); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		throw new AssertionError("source has no world facade");
	}
	private static WeakReference<?> serverReference(InventoryFluidSources.World facade) {
		@Nullable WeakReference<?> found = null;
		for (var field : facade.getClass().getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
			try {
				field.setAccessible(true);
				var reference = assertInstanceOf(WeakReference.class, field.get(facade), "production facade must not own a server, level or callback");
				assertNull(found, "one bounded weak server context, not a registry"); found = reference;
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		return Objects.requireNonNull(found);
	}
	/** Trace actual owning instance edges, including evidence owners/tickets/collections, but never weak referents. */
	private static void assertNonOwningGraph(Object root) {
		var pending = new ArrayDeque<Object>(); var visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()); pending.add(root);
		while (!pending.isEmpty()) {
			Object value = pending.removeFirst(); if (!visited.add(value)) continue;
			assertFalse(value instanceof MinecraftServer || value instanceof Level || value instanceof BlockEntity
				|| value instanceof IPlatformFluidService.Context || value instanceof IPlatformFluidService.Access
				|| value instanceof ResolvedBlockReadSource || value instanceof InventoryFluidSources.Binding
				|| value instanceof net.minecraft.core.HolderLookup.Provider,
				() -> "historical owner reaches live context: " + value.getClass().getName());
			assertFalse(value.getClass().isSynthetic(), () -> "historical owner retains callback/closure: " + value.getClass().getName());
			if (value instanceof Reference<?>) continue;
			if (value instanceof Optional<?> optional) { optional.ifPresent(pending::addLast); continue; }
			if (value instanceof Iterable<?> values) { for (@Nullable Object element : values) if (element != null) pending.addLast(element); continue; }
			if (value instanceof Map<?, ?> values) { values.forEach((key, entry) -> { if (key != null) pending.addLast(key); if (entry != null) pending.addLast(entry); }); continue; }
			if (value instanceof Object[] values) { for (@Nullable Object element : values) if (element != null) pending.addLast(element); continue; }
			if (!value.getClass().getPackageName().startsWith("nx.pingwheel.")) continue;
			for (@Nullable Class<?> type = value.getClass(); type != null && type.getPackageName().startsWith("nx.pingwheel."); type = type.getSuperclass()) for (var field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
				try { field.setAccessible(true); @Nullable Object next = field.get(value); if (next != null) pending.addLast(next); }
				catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			}
		}
	}
	private static InventoryFluidSources.World productionFacade(MinecraftServer server) {
		try {
			var type = Class.forName(InventoryFluidSources.class.getName() + "$ServerWorld");
			var constructor = type.getDeclaredConstructor(MinecraftServer.class); constructor.setAccessible(true);
			return (InventoryFluidSources.World) constructor.newInstance(server);
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final InventoryFluidWorldTest.Fixture nativeSource = new InventoryFluidWorldTest.Fixture(2, 1, true);
		final Server server = DoubleChestFixture.allocate(Server.class);
		final World level = DoubleChestFixture.allocate(World.class);
		final InventoryRuntime runtime;
		final @Unmodifiable List<ExternalBlockServerProvider> priorProviders = ExternalBlockServerProviders.registry().providers();
		InventorySourceAccess.@Nullable Source source;
		Fixture() {
			level.fixture = nativeSource; level.server = server; server.level = level;
			var cache = DoubleChestFixture.allocate(Cache.class); cache.fixture = nativeSource; level.cache = cache;
			var players = DoubleChestFixture.allocate(Players.class); var player = DoubleChestFixture.allocate(Player.class);
			player.fixture = nativeSource; players.player = player; server.players = players;
			nativeSource.entities.values().forEach(entity -> entity.setLevel(level));
			ExternalBlockServerProviders.registry().clear(); ExternalBlockServerProviders.registry().register(new Provider());
			var service = new WeakService(new WeakReference<>(nativeSource));
			runtime = new InventoryRuntime(new InventoryRuntime.SourceResolver() {
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted source"); }
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
					var result = InventoryFluidSources.resolve(productionFacade(server), service, input, cap, allowance); source = result.orElse(null); return result;
				}
			}, nativeSource.settings.pendingMemoryBytes());
		}
		InventoryRuntime.Consumer attach() {
			runtime.advance(0, nativeSource.settings);
			return runtime.attach(nativeSource.input, new InventoryRuntime.TrackingSubject(TargetKey.from(nativeSource.target))).orElseThrow();
		}
		InventorySharedRead.Check check(InventoryPublicationEvidence evidence) {
			try (var grant = runtime.reservePublication(evidence).orElseThrow()) { return grant.validate(evidence); }
		}
		@Override public void close() {
			try { runtime.close(); nativeSource.close(); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved()); }
			finally { ExternalBlockServerProviders.registry().clear(); priorProviders.forEach(ExternalBlockServerProviders.registry()::register); InventoryFluidRouteDiagnostics.clear(); }
		}
	}
	/** Test service discovery is non-owning too, so graph assertions isolate the production facade's real edges. */
	@NotNullByDefault
	private record WeakService(WeakReference<InventoryFluidWorldTest.Fixture> fixture) implements IPlatformFluidService {
		@Override public OperationProfile probeProfile() { return Objects.requireNonNull(fixture.get()).probeProfile(); }
		@Override public Optional<Probe> probe(Context context, Operation operation) { return Objects.requireNonNull(fixture.get()).probe(context, operation); }
	}
	@NotNullByDefault
	private static final class Provider implements ExternalBlockServerProvider {
		@Override public String providerId() { return "sable"; }
		@Override public Optional<ResolvedBlockReadSource> resolveCommittedReadSource(ServerLevel level, Target.ExternalBlockTarget target) {
			var fixture = Objects.requireNonNull(((World) level).fixture);
			var block = new Target.BlockTarget(target.dimensionId(), fixture.root.getX(), fixture.root.getY(), fixture.root.getZ(), "minecraft:chest");
			var descriptor = new BlockReadSource(target, providerId(), fixture.scope, block, new MarkerAnchor(1, 64, 3));
			return Optional.of(new ResolvedBlockReadSource(level, descriptor, pos -> fixture.loaded && fixture.entities.containsKey(pos)));
		}
		@Override public ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget target) { throw new AssertionError("no materialization"); }
		@Override public MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget target) { throw new AssertionError("no materialization"); }
		@Override public RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget target) { throw new AssertionError("no refresh"); }
		@Override public Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget target) { throw new AssertionError("no name read"); }
		@Override public void release(MinecraftServer server, Target.ExternalBlockTarget target) { throw new AssertionError("no lease mutation"); }
	}
	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable World level;
		private @Nullable Players players;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public @Nullable ServerLevel getLevel(ResourceKey<Level> dimension) { return dimension.equals(Level.OVERWORLD) ? level : null; }
		@Override public DedicatedPlayerList getPlayerList() { return Objects.requireNonNull(players); }
	}
	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private @Nullable Player player;
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return player; }
	}
	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private InventoryFluidWorldTest.@Nullable Fixture fixture;
		private Player() { super(null, null, null, null); }
		@Override public ItemStack getMainHandItem() { return Objects.requireNonNull(fixture).held; }
	}
	@NotNullByDefault
	private static final class World extends ServerLevel {
		private InventoryFluidWorldTest.@Nullable Fixture fixture;
		private @Nullable Server server;
		private @Nullable Cache cache;
		private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public MinecraftServer getServer() { return Objects.requireNonNull(server); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public boolean isLoaded(BlockPos pos) { return Objects.requireNonNull(fixture).loaded; }
		@Override public BlockState getBlockState(BlockPos pos) { return Objects.requireNonNull(fixture).blockState(this, pos); }
		@Override public ServerChunkCache getChunkSource() { return Objects.requireNonNull(cache); }
		@Override public RegistryAccess registryAccess() { return RegistryAccess.EMPTY; }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("must use loaded-existing map"); }
	}
	@NotNullByDefault
	private static final class Cache extends ServerChunkCache {
		private InventoryFluidWorldTest.@Nullable Fixture fixture;
		private Cache() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
		@Override public @Nullable LevelChunk getChunkNow(int x, int z) {
			var current = Objects.requireNonNull(fixture); if (!current.loaded) return null;
			var chunk = DoubleChestFixture.allocate(Chunk.class); chunk.fixture = current; return chunk;
		}
	}
	@NotNullByDefault
	private static final class Chunk extends LevelChunk {
		private InventoryFluidWorldTest.@Nullable Fixture fixture;
		private Chunk() { super(null, null); }
		@Override public Map<BlockPos, BlockEntity> getBlockEntities() { return new java.util.HashMap<>(Objects.requireNonNull(fixture).entities); }
	}
}
