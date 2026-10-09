package nx.pingwheel.common.marker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.PresentationPropertyPingTypes;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * C2b handoff coverage: the production {@link InventoryBackend} plus the real
 * {@link MarkerCreationService} dedicated admission run over a provider-shaped
 * external fixture. The fixture mirrors the provider's candidate-locator and
 * committed-current-point resolution so the committed input binding, live
 * witness, annotation tags and tracking baseline are exercised end to end.
 */
class SableInventoryHandoffTest {
	private static final UUID A = new UUID(1, 1), B = new UUID(2, 2);
	private static final String DIMENSION = "minecraft:overworld";
	private static final String PROVIDER = "sable";

	private static final class Route {
		final String subLevel, registry;
		final Set<String> tags;
		final int x, y, z;
		boolean active = true;
		long count;
		final AtomicInteger reads = new AtomicInteger();
		Route(String subLevel, String registry, int x, int y, int z, long count, Set<String> tags) {
			this.subLevel = subLevel; this.registry = registry; this.x = x; this.y = y; this.z = z;
			this.count = count; this.tags = tags;
		}
	}

	private record Binding(Route route, BlockReadSource descriptor) {}

	/** Host-binding tampering used to prove the exact committed-binding guard. */
	private enum Tamper { NONE, FACE, OWNER, CANDIDATE, OTHER_STABLE, SAME_LOCATOR_OTHER_IDENTITY, CHANGED_LOCATOR }

	private static final class Fixture implements AutoCloseable {
		final Map<String, Route> candidates = new HashMap<>(), points = new HashMap<>();
		final AtomicInteger resolutions = new AtomicInteger();
		final InventorySettings settings = InventorySettings.serverDefaults();
		final InventoryRuntime runtime = new InventoryRuntime(this::resolve, 16_000_000);
		final TestHost host = new TestHost(this, runtime);
		final InventoryBackend backend = new InventoryBackend(host, runtime, 1);
		Fixture() { host.hello(A); host.hello(B); }

		Route route(String locator, String subLevel, int x, int y, int z, String registry, long count) {
			Route route = new Route(subLevel, registry, x, y, z, count, Set.of("c:chests"));
			candidates.put(locator, route); return route;
		}
		Target.ExternalBlockTarget candidate(String locator) {
			Route route = candidates.get(locator);
			return Target.ExternalBlockTarget.candidate(DIMENSION, PROVIDER, route.registry, locator, true);
		}
		String stableId(Route route) { return "stable/" + route.subLevel + "/" + route.x + "," + route.y + "," + route.z; }

		/** Provider-shaped current binding: candidates by locator, committed targets by current stable point. */
		Binding binding(InventorySourceInput input) {
			resolutions.incrementAndGet();
			if (!(input.target() instanceof Target.ExternalBlockTarget external)) return null;
			Route route = external.isCandidate() ? candidates.get(external.providerLocator()) : points.get(external.stableTargetId());
			if (route == null || !route.active) return null;
			try {
				return new Binding(route, new BlockReadSource(external, PROVIDER, route.subLevel,
					new Target.BlockTarget(DIMENSION, route.x, route.y, route.z, route.registry),
					new MarkerAnchor(900, 901, 902)));
			} catch (IllegalArgumentException mismatch) { return null; }
		}
		Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input) {
			Binding binding = binding(input);
			return binding == null ? Optional.empty() : Optional.of(new Source(input, binding.route));
		}

		void open(long requestId, Target target) {
			backend.handle(A, InventoryC2SPacket.open(host.epoch(A), 100, 1, requestId, target, BlockFace.NORTH), 0, settings);
		}
		void tick(int now) { backend.tick(now, settings); }
		InventoryS2CPacket preview(long requestId) {
			return host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.requestId() == requestId && !p.entries().isEmpty())
				.reduce((a, b) -> b).orElseThrow();
		}
		InventoryC2SPacket selectPacket(long requestId, long commitId, String pingType) {
			var preview = preview(requestId);
			return InventoryC2SPacket.select(host.epoch(A), 100, 1, commitId, requestId,
				preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), pingType);
		}
		void select(long requestId, long commitId, String pingType, int now) {
			backend.handle(A, selectPacket(requestId, commitId, pingType), now, settings);
		}
		InventoryS2CPacket snapshot(MarkerId marker) {
			return host.packets.stream().filter(p -> marker.equals(p.markerId()) && p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && !p.entries().isEmpty())
				.reduce((a, b) -> b).orElseThrow();
		}
		@Override public void close() { backend.close(); }
	}

	private static final class Source implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		private final Route route;
		Source(InventorySourceInput input, Route route) { this.input = input; this.route = route; }
		@Override public SourceKey key() {
			return new SourceKey(PROVIDER, "block_inventory", route.subLevel + "/" + route.x + "," + route.y + "," + route.z,
				input.viewKey());
		}
		@Override public boolean valid() { return route.active; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 1; }
		@Override public InventoryDomainCodec.Item read(int slot) {
			if (!route.active) throw new IllegalStateException("inactive provider source");
			route.reads.incrementAndGet();
			if (slot != 0) return null;
			return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), route.count, "stone", null, false);
		}
	}

	private static final class TestHost implements InventoryBackend.Host {
		final Fixture fixture; final InventoryRuntime runtime;
		final ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource());
		final List<InventoryS2CPacket> packets = new ArrayList<>();
		final List<UUID> recipients = new ArrayList<>();
		final Map<UUID, InventoryBackend.Policy> policies = new HashMap<>();
		final List<String> events = new ArrayList<>();
		final AtomicInteger materializeCalls = new AtomicInteger(), releaseCalls = new AtomicInteger();
		final MarkerCreationService service;
		Function<Target.ExternalBlockTarget, Route> materializeRoute;
		Tamper tamper = Tamper.NONE;
		Target.ExternalBlockTarget lastCommitted;
		InventorySourceInput boundInput;
		Route annotationRoute;

		TestHost(Fixture fixture, InventoryRuntime runtime) {
			this.fixture = fixture; this.runtime = runtime;
			materializeRoute = candidate -> fixture.candidates.get(candidate.providerLocator());
			var entityBlock = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
			service = new MarkerCreationService(store,
				(target, context) -> new ResolvedTarget(target, entityBlock),
				PingTypeCatalog.builtIn(),
				(owner, target) -> AuthoritativeTargetValidation.accepted(new ValidatedMarkerTarget(target,
					TargetMatchContext.blockEntityBlock(true), new MarkerAnchor(5, 6, 7), new TargetNameJson("{\"text\":\"Chest\"}"))));
			policies.put(A, new InventoryBackend.Policy(100, 1, Set.of("entity_block")));
			policies.put(B, policies.get(A));
		}
		void hello(UUID player) { fixture.backend.handle(player, InventoryC2SPacket.hello(), 0, fixture.settings); }
		long epoch(UUID player) {
			for (int i = packets.size() - 1; i >= 0; i--)
				if (recipients.get(i).equals(player) && packets.get(i).kind() == InventoryS2CPacket.Kind.OFFER) return packets.get(i).epoch();
			throw new AssertionError();
		}
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.ofNullable(policies.get(player)); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) {
			events.add("open");
			if (!(requested instanceof Target.ExternalBlockTarget external) || !external.isCandidate()) return Optional.empty();
			return Optional.of(new InventoryBackend.Opened(external, "entity_block", "attention"));
		}
		@Override public Optional<InventorySourceInput> bindCommitted(InventorySourceInput previewInput, Target committed) {
			events.add("bind");
			if (!(previewInput.target() instanceof Target.ExternalBlockTarget)
				|| !(committed instanceof Target.ExternalBlockTarget committedExternal))
				return InventoryBackend.Host.super.bindCommitted(previewInput, committed);
			if (tamper == Tamper.FACE) return Optional.of(new InventorySourceInput(committedExternal, previewInput.readOwner(), BlockFace.SOUTH));
			if (tamper == Tamper.OWNER) return Optional.of(new InventorySourceInput(committedExternal, B, previewInput.face()));
			if (tamper != Tamper.NONE) {
				Target.ExternalBlockTarget boundTarget = switch (tamper) {
					case CANDIDATE -> (Target.ExternalBlockTarget) previewInput.target();
					case OTHER_STABLE -> Target.ExternalBlockTarget.committed(DIMENSION, PROVIDER, "other-stable",
						committedExternal.expectedBlockRegistryId(), "other-locator", committedExternal.hasBlockEntity());
					case SAME_LOCATOR_OTHER_IDENTITY -> Target.ExternalBlockTarget.committed(DIMENSION, PROVIDER,
						"same-locator-other-identity", committedExternal.expectedBlockRegistryId(),
						committedExternal.providerLocator(), committedExternal.hasBlockEntity());
					case CHANGED_LOCATOR -> Target.ExternalBlockTarget.committed(DIMENSION, PROVIDER, committedExternal.stableTargetId(),
						committedExternal.expectedBlockRegistryId(), "changed-locator", committedExternal.hasBlockEntity());
					default -> throw new AssertionError(tamper);
				};
				return Optional.of(new InventorySourceInput(boundTarget, previewInput.readOwner(), previewInput.face()));
			}
			return runtime.preflight(() -> {
				Binding preview = fixture.binding(previewInput);
				Binding current = fixture.binding(new InventorySourceInput(committedExternal, previewInput.readOwner(), previewInput.face()));
				if (preview == null || current == null
					|| !InventoryBackend.correspondingPhysicalBinding(preview.descriptor(), current.descriptor()))
					return Optional.<InventorySourceInput>empty();
				boundInput = new InventorySourceInput(committedExternal, previewInput.readOwner(), previewInput.face());
				return Optional.of(boundInput);
			});
		}
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) {
			events.add("annotation");
			Binding binding = fixture.binding(input);
			if (binding == null) return false;
			annotationRoute = binding.route();
			return PresentationPropertyPingTypes.builtIn().allows(pingType, binding.route().registry, binding.route().tags);
		}
		@Override public boolean knows(UUID player, MarkerId marker) {
			return store.allMarkers().stream().anyMatch(m -> m.id().equals(marker) && m.recipients().contains(player));
		}
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			events.add("create");
			var outcome = service.createDedicatedWithExternalTransaction(new ExternalBlockTransaction() {
				@Override public ExternalBlockServerProvider.MaterializationResult materialize(Target.ExternalBlockTarget candidate) {
					materializeCalls.incrementAndGet();
					Route route = materializeRoute.apply(candidate);
					if (route == null) return new ExternalBlockServerProvider.MaterializationResult.Invalid();
					String stable = fixture.stableId(route);
					fixture.points.put(stable, route);
					lastCommitted = Target.ExternalBlockTarget.committed(DIMENSION, PROVIDER, stable, route.registry,
						"stale-locator-" + stable, true);
					return new ExternalBlockServerProvider.MaterializationResult.Materialized(
						new ExternalBlockServerProvider.MaterializedTarget(lastCommitted,
							TargetMatchContext.blockEntityBlock(true), new MarkerAnchor(5, 6, 7)));
				}
				@Override public void release(Target.ExternalBlockTarget committed) { releaseCalls.incrementAndGet(); }
			}, player, frozen.target(), frozen.defaultPingType(), 1, 80, List.of(A, B), (target, type, owner, audience, intents) -> {
				events.add("admission");
				var rejection = admission.prepare(target, type, audience);
				return rejection == null ? MarkerCreationService.AdmissionResult.accepted(List.of(), Map.of())
					: MarkerCreationService.AdmissionResult.rejected(rejection);
			});
			if (!outcome.isAccepted()) return new InventoryBackend.Created(null, outcome.rejectReason().orElseThrow());
			return new InventoryBackend.Created(outcome.creation().orElseThrow().marker(), null);
		}
		@Override public int encodedBytes(InventoryS2CPacket packet) {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256, InventoryS2CPacket.MAX_FRAME_BYTES));
			try { packet.write(buffer); return buffer.readableBytes(); }
			finally { buffer.release(); }
		}
		@Override public void send(UUID player, InventoryS2CPacket packet) {
			assertFalse(packet.isCorrupt(), packet.toString());
			packets.add(packet); recipients.add(player);
		}
	}

	@Test void previewCandidateUsesRequestScopedQuotaWithoutMarkerKeyOrProviderReference() {
		try (var f = new Fixture()) {
			Route route = f.route("a", "sublevel-a", 1, 2, 3, "minecraft:chest", 7);
			f.open(1, f.candidate("a")); f.tick(0);
			assertEquals(7, f.preview(1).entries().getFirst().count());
			assertEquals(1, route.reads.get());
			assertEquals(0, f.host.materializeCalls.get(), "preview allocates no provider tracking reference");
			assertEquals(0, f.host.releaseCalls.get());
			assertEquals(0, f.host.store.size());
		}
	}

	@Test void validSelectionMaterializesOnceBindsCurrentPhysicalRootWitnessesAndStoresCommittedTrackingWithFreshCount() {
		try (var f = new Fixture()) {
			Route route = f.route("a", "sublevel-a", 1, 2, 3, "minecraft:chest", 7);
			f.open(1, f.candidate("a")); f.tick(0);
			assertEquals(7, f.preview(1).entries().getFirst().count());
			route.count = 11;
			int reads = route.reads.get();
			f.select(1, 1, "request", 1);
			assertEquals(1, f.host.materializeCalls.get(), "one materialization, never one per retry");
			assertEquals(0, f.host.releaseCalls.get());
			assertEquals(1, f.host.store.size());
			var marker = f.host.store.allMarkers().getFirst();
			assertEquals(f.host.lastCommitted, marker.target());
			assertTrue(marker.target() instanceof Target.ExternalBlockTarget external && external.isCommitted());
			assertEquals("attention", marker.pingType().id());
			assertTrue(marker.properties().isEmpty());
			assertEquals(reads + 1, route.reads.get(), "exactly one live witness read, never the preview snapshot");
			assertNotNull(f.host.boundInput);
			assertEquals(A, f.host.boundInput.readOwner());
			assertEquals(BlockFace.NORTH, f.host.boundInput.face());
			assertEquals(route, f.host.annotationRoute, "annotation queries the resolved physical block, not a placeholder");
			assertEquals("stale-locator-" + f.stableId(route),
				((Target.ExternalBlockTarget) f.host.boundInput.target()).providerLocator(),
				"the committed target keeps its original identity while the current provider point authorizes reads");
			f.tick(2);
			var snapshot = f.snapshot(marker.id());
			assertEquals(11, snapshot.entries().getFirst().count(), "the first tracking capture is fresh, never the preview count");
			assertEquals("request", snapshot.entries().getFirst().itemPingType());
		}
	}

	@Test void wrongSubLevelRootRegistryOrInactiveSourceRejectsBeforeWitnessWithSingleReleaseAndNoStore() {
		for (String kind : List.of("sublevel", "root", "registry", "inactive")) {
			try (var f = new Fixture()) {
				Route candidate = f.route("a", "sublevel-a", 1, 2, 3, "minecraft:chest", 7);
				f.open(1, f.candidate("a")); f.tick(0);
				assertEquals(7, f.preview(1).entries().getFirst().count());
				int reads = candidate.reads.get();
				Route other = f.route("b", kind.equals("sublevel") ? "sublevel-b" : "sublevel-a",
					kind.equals("root") ? 4 : 1, 2, 3, kind.equals("registry") ? "minecraft:barrel" : "minecraft:chest", 9);
				if (kind.equals("inactive")) other.active = false;
				f.host.materializeRoute = ignored -> other;
				f.select(1, 1, "request", 1);
				assertEquals(1, f.host.materializeCalls.get(), kind);
				assertEquals(1, f.host.releaseCalls.get(), kind + ": the materialized reference is released exactly once");
				assertEquals(0, f.host.store.size(), kind);
				assertEquals(reads, candidate.reads.get(), kind + ": preview evidence cannot authorize a witness");
				assertFalse(f.host.events.contains("annotation"), kind);
			}
		}
	}

	@Test void tamperedOwnerOrFaceBindingRejectsBeforeValidationWitnessOrAnnotation() {
		for (boolean face : List.of(true, false)) {
			try (var f = new Fixture()) {
				Route route = f.route("a", "sublevel-a", 1, 2, 3, "minecraft:chest", 7);
				f.open(1, f.candidate("a")); f.tick(0);
				assertEquals(7, f.preview(1).entries().getFirst().count());
				int reads = route.reads.get(), resolutions = f.resolutions.get();
				f.host.tamper = face ? Tamper.FACE : Tamper.OWNER;
				f.select(1, 1, "request", 1);
				assertEquals(1, f.host.materializeCalls.get());
				assertEquals(1, f.host.releaseCalls.get());
				assertEquals(0, f.host.store.size());
				assertEquals(reads, route.reads.get(), "owner/face mismatch is rejected before validation or witness");
				assertEquals(resolutions, f.resolutions.get(), "owner/face mismatch performs no provider resolution");
				assertFalse(f.host.events.contains("annotation"));
			}
		}
	}

	@Test void hostReturnedCandidateOtherStableSameLocatorOrChangedLocatorRejectsBeforeValidationWitnessWithSingleRollback() {
		for (Tamper tamper : List.of(Tamper.CANDIDATE, Tamper.OTHER_STABLE, Tamper.SAME_LOCATOR_OTHER_IDENTITY, Tamper.CHANGED_LOCATOR)) {
			try (var f = new Fixture()) {
				Route route = f.route("a", "sublevel-a", 1, 2, 3, "minecraft:chest", 7);
				f.open(1, f.candidate("a")); f.tick(0);
				assertEquals(7, f.preview(1).entries().getFirst().count());
				int reads = route.reads.get(), resolutions = f.resolutions.get();
				f.host.tamper = tamper;
				f.select(1, 1, "request", 1);
				assertEquals(1, f.host.materializeCalls.get(), tamper.name());
				assertEquals(1, f.host.releaseCalls.get(), tamper.name() + ": materialization rolls back exactly once");
				assertEquals(0, f.host.store.size(), tamper.name());
				assertEquals(reads, route.reads.get(), tamper.name() + ": no witness after a bad committed binding");
				assertEquals(resolutions, f.resolutions.get(), tamper.name() + ": no provider validation after a bad committed binding");
				assertFalse(f.host.events.contains("annotation"), tamper.name());
			}
		}
	}

	@Test void openCandidateNormalizationFenceAllowsClassificationCorrectionAndRejectsExactBindingDrift() {
		var requested = Target.ExternalBlockTarget.candidate(DIMENSION, PROVIDER, "minecraft:chest", "locator-a", false);
		assertTrue(InventoryBackend.sameCandidateNormalization(requested,
			Target.ExternalBlockTarget.candidate(DIMENSION, PROVIDER, "minecraft:chest", "locator-a", true)),
			"server-side classification correction keeps the same candidate binding");
		assertFalse(InventoryBackend.sameCandidateNormalization(requested,
			Target.ExternalBlockTarget.candidate(DIMENSION, PROVIDER, "chest", "locator-a", false)),
			"an unqualified-to-canonical registry rewrite is not the requested exact binding");
		assertFalse(InventoryBackend.sameCandidateNormalization(requested,
			Target.ExternalBlockTarget.candidate(DIMENSION, PROVIDER, "minecraft:barrel", "locator-a", false)));
		assertFalse(InventoryBackend.sameCandidateNormalization(requested,
			Target.ExternalBlockTarget.candidate(DIMENSION, "other-provider", "minecraft:chest", "locator-a", false)));
		assertFalse(InventoryBackend.sameCandidateNormalization(requested,
			Target.ExternalBlockTarget.candidate("minecraft:the_nether", PROVIDER, "minecraft:chest", "locator-a", false)));
		assertFalse(InventoryBackend.sameCandidateNormalization(requested,
			Target.ExternalBlockTarget.candidate(DIMENSION, PROVIDER, "minecraft:chest", "locator-b", false)),
			"an opaque locator change is rejected before any source access");
		assertFalse(InventoryBackend.sameCandidateNormalization(requested,
			Target.ExternalBlockTarget.committed(DIMENSION, PROVIDER, "stable", "minecraft:chest", "locator-a", true)),
			"a committed normalization is never accepted by the candidate fence");
		assertFalse(InventoryBackend.sameCandidateNormalization(new Target.BlockTarget(DIMENSION, 1, 2, 3, "minecraft:chest"),
			new Target.BlockTarget(DIMENSION, 1, 2, 3, "minecraft:chest")),
			"ordinary OPEN normalization keeps its existing behavior and is not candidate-fenced");
	}

	@Test void physicalBindingCorrespondenceIsSharedWithProductionHostBindAndRejectsDrift() {
		var base = descriptor(PROVIDER, DIMENSION, "minecraft:chest", "sublevel-a", 1, 2, 3);
		assertTrue(InventoryBackend.correspondingPhysicalBinding(base,
			descriptor(PROVIDER, DIMENSION, "minecraft:chest", "sublevel-a", 1, 2, 3)));
		assertFalse(InventoryBackend.correspondingPhysicalBinding(base,
			descriptor("other-provider", DIMENSION, "minecraft:chest", "sublevel-a", 1, 2, 3)));
		assertFalse(InventoryBackend.correspondingPhysicalBinding(base,
			descriptor(PROVIDER, "minecraft:the_nether", "minecraft:chest", "sublevel-a", 1, 2, 3)));
		assertFalse(InventoryBackend.correspondingPhysicalBinding(base,
			descriptor(PROVIDER, DIMENSION, "minecraft:chest", "sublevel-b", 1, 2, 3)));
		assertFalse(InventoryBackend.correspondingPhysicalBinding(base,
			descriptor(PROVIDER, DIMENSION, "minecraft:chest", "sublevel-a", 4, 2, 3)));
		assertFalse(InventoryBackend.correspondingPhysicalBinding(base,
			descriptor(PROVIDER, DIMENSION, "minecraft:barrel", "sublevel-a", 1, 2, 3)));
		assertFalse(InventoryBackend.correspondingPhysicalBinding(base, null));
		assertFalse(InventoryBackend.correspondingPhysicalBinding(null, base));
	}

	private static BlockReadSource descriptor(String provider, String dimension, String registry, String subLevel,
		int x, int y, int z) {
		var candidate = Target.ExternalBlockTarget.candidate(dimension, provider, registry, "locator", true);
		return new BlockReadSource(candidate, provider, subLevel,
			new Target.BlockTarget(dimension, x, y, z, registry), new MarkerAnchor(900, 901, 902));
	}

	@Test void sameRegistryCandidatesDoNotCrossBindAcrossRequests() {
		try (var f = new Fixture()) {
			Route a = f.route("a", "sublevel", 1, 2, 3, "minecraft:chest", 3);
			Route b = f.route("b", "sublevel", 5, 2, 3, "minecraft:chest", 11);
			f.open(1, f.candidate("a")); f.open(2, f.candidate("b")); f.tick(0);
			assertEquals(3, f.preview(1).entries().getFirst().count());
			assertEquals(11, f.preview(2).entries().getFirst().count());
			f.host.materializeRoute = ignored -> b;
			f.select(1, 1, "request", 1);
			assertEquals(1, f.host.materializeCalls.get());
			assertEquals(1, f.host.releaseCalls.get());
			assertEquals(0, f.host.store.size(), "same-registry candidate a cannot bind to b's physical root");
			assertEquals(1, a.reads.get(), "the failed request never witnesses the other request's source");
			f.select(2, 2, "request", 2);
			assertEquals(2, f.host.materializeCalls.get());
			assertEquals(1, f.host.releaseCalls.get(), "the matching second request keeps its reference");
			assertEquals(1, f.host.store.size());
			var marker = f.host.store.allMarkers().getFirst();
			assertEquals(f.stableId(b), ((Target.ExternalBlockTarget) marker.target()).stableTargetId());
			f.tick(3);
			assertEquals(11, f.snapshot(marker.id()).entries().getFirst().count());
		}
	}

	@Test void selectionReplayResendsStoredResultWithoutSecondMaterializationWitnessOrReference() {
		try (var f = new Fixture()) {
			Route route = f.route("a", "sublevel-a", 1, 2, 3, "minecraft:chest", 7);
			f.open(1, f.candidate("a")); f.tick(0);
			var selection = f.selectPacket(1, 1, "request");
			f.backend.handle(A, selection, 1, f.settings); f.tick(2);
			int reads = route.reads.get(), start = f.host.packets.size();
			f.backend.handle(A, selection, 3, f.settings);
			assertEquals(1, f.host.materializeCalls.get());
			assertEquals(0, f.host.releaseCalls.get());
			assertEquals(1, f.host.store.size());
			assertEquals(reads, route.reads.get(), "a replay is never another witness or provider acquisition");
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream()
				.anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.SELECTED && p.commitId() == 1));
		}
	}

	@Test void invalidSourceBeforeFirstTrackingPublicationPublishesNoQuantityAndRecoveryStartsFreshBaseline() {
		try (var f = new Fixture()) {
			Route route = f.route("a", "sublevel-a", 1, 2, 3, "minecraft:chest", 7);
			f.open(1, f.candidate("a")); f.tick(0);
			route.count = 11;
			f.select(1, 1, "request", 1);
			var marker = f.host.store.allMarkers().getFirst();
			route.active = false;
			f.tick(2);
			var frames = f.host.packets.stream().filter(p -> marker.id().equals(p.markerId())).toList();
			assertTrue(frames.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.STATUS && p.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(frames.stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT || p.kind() == InventoryS2CPacket.Kind.STREAM),
				"an invalid source publishes no quantity before its first tracking baseline");
			assertEquals(1, f.host.store.size());
			route.active = true; route.count = 19;
			f.tick(5);
			assertEquals(19, f.snapshot(marker.id()).entries().getFirst().count(), "recovery starts a fresh authoritative baseline");
			assertEquals(1, f.host.store.size());
		}
	}
}
