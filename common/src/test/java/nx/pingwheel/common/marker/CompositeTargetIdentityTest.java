package nx.pingwheel.common.marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.*;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.presentation.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CompositeTargetIdentityTest {
	private static final String DIM = "minecraft:overworld";
	private static final UUID A = new UUID(1, 1), B = new UUID(2, 2), C = new UUID(3, 3);
	private static final TargetType TYPE = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
	private static final Target.BlockTarget LEFT = new Target.BlockTarget(DIM, 1, 2, 3, "minecraft:chest");
	private static final Target.BlockTarget RIGHT = new Target.BlockTarget(DIM, 2, 2, 3, "minecraft:chest");
	private static final TargetKey.DoubleChestKey GROUP = new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 2, 2, 3, "minecraft:chest");
	private static final MarkerAnchor ANCHOR = new MarkerAnchor(2.5, 2.5, 3.5);
	private static final TargetNameJson NAME = new TargetNameJson("{\"text\":\"Chest\"}");

	@Test void exactDerivationRemainsPhysicalAndGroupIdentitiesCannotCollide() {
		assertNotEquals(TargetKey.from(LEFT), TargetKey.from(RIGHT));
		assertNotEquals(GROUP, TargetKey.from(LEFT));
		assertNotEquals(GROUP, new TargetKey.DoubleChestKey(DIM, 2, 2, 3, 3, 2, 3, "minecraft:chest"));
		assertNotEquals(GROUP, new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 2, 2, 3, "minecraft:trapped_chest"));
		assertNotEquals(GROUP, new TargetKey.DoubleChestKey("minecraft:the_nether", 1, 2, 3, 2, 2, 3, "minecraft:chest"));
		var vault = new TargetKey.VaultKey(DIM, 1, 2, 3, "create:item_vault");
		assertNotEquals(vault, new TargetKey.VaultKey(DIM, 2, 2, 3, "create:item_vault"));
		assertNotEquals(vault, new TargetKey.VaultKey("minecraft:the_nether", 1, 2, 3, "create:item_vault"));
		assertNotEquals(vault, new TargetKey.BlockKey(DIM, 1, 2, 3, "create:item_vault"));
		assertSame(GROUP, TargetKey.requireCompatible(LEFT, GROUP));
		assertSame(GROUP, TargetKey.requireCompatible(RIGHT, GROUP));
	}

	@Test void malformedNoncanonicalPairsAndWrongTargetKeyShapesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.DoubleChestKey(DIM, 2, 2, 3, 1, 2, 3, "minecraft:chest"));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 1, 2, 3, "minecraft:chest"));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 1, 3, 3, "minecraft:chest"));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 2, 2, 4, "minecraft:chest"));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.DoubleChestKey(DIM, Integer.MIN_VALUE, 2, 3, Integer.MAX_VALUE, 2, 3, "minecraft:chest"));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 2, 2, 3, "mod:chest"));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.VaultKey(DIM, 1, 2, 3, "minecraft:chest"));
		assertThrows(IllegalArgumentException.class, () -> snapshot(new Target.BlockTarget(DIM, 3, 2, 3, "minecraft:chest"), GROUP));
		assertThrows(IllegalArgumentException.class, () -> snapshot(new Target.BlockTarget(DIM, 1, 2, 3, "minecraft:trapped_chest"), GROUP));
		assertThrows(IllegalArgumentException.class, () -> snapshot(LEFT, TargetKey.from(RIGHT)));
		assertThrows(IllegalArgumentException.class, () -> snapshot(LEFT, new TargetKey.VaultKey(DIM, 1, 2, 3, "create:item_vault")));
		assertThrows(NullPointerException.class, () -> snapshot(LEFT, null));
	}

	private static MarkerSnapshot snapshot(Target target, @Nullable TargetKey key) {
		return new MarkerSnapshot(new MarkerId(1), A, target, key, TYPE.id(), "attention", ANCHOR, 1, 20);
	}

	@Test void recipientWinnersUseArrivalThenIdWhileLosersHaveIndependentLifetimeAndCleanupKeepsKey() {
		var store = new ServerMarkerStore(new MarkerIdSource());
		var first = store.create(A, LEFT, GROUP, TYPE, TYPE.defaultPingType(), ANCHOR, 5, 30, List.of(A, B, C)).marker();
		var laterId = store.create(B, RIGHT, GROUP, TYPE, TYPE.defaultPingType(), ANCHOR, 5, 20, List.of(A, B)).marker();
		var olderArrival = store.create(A, RIGHT, GROUP, TYPE, TYPE.defaultPingType(), ANCHOR, 4, 25, List.of(A)).marker();
		assertEquals(laterId.id(), store.winnerFor(GROUP, A).orElseThrow().id());
		assertEquals(first.id(), store.winnerFor(GROUP, C).orElseThrow().id());
		assertEquals(3, store.size());
		store.forgetRecipient(B);
		assertSame(GROUP, store.find(first.id()).orElseThrow().targetKey());
		assertEquals(List.of(A, C), store.find(first.id()).orElseThrow().recipients());
		assertEquals(GROUP, MarkerSnapshot.from(store.find(first.id()).orElseThrow()).targetKey());
		assertEquals(List.of(laterId.id()), store.expire(20).removals().stream().map(r -> r.marker().id()).toList());
		assertEquals(first.id(), store.winnerFor(GROUP, A).orElseThrow().id());
		assertTrue(store.find(olderArrival.id()).isPresent());
		store.expire(25); assertEquals(1, store.size());
		store.expire(30); assertEquals(0, store.size());
	}

	@Test void clientCopyReloadStaleFallbackAndIndependentDisplayKeepFrozenGroup() {
		var store = new ClientMarkerStore(0, 100);
		var first = new MarkerSnapshot(new MarkerId(1), A, LEFT, GROUP, TYPE.id(), "attention", ANCHOR, 1, 20);
		var second = new MarkerSnapshot(new MarkerId(2), B, RIGHT, GROUP, TYPE.id(), "danger", ANCHOR, 2, 10);
		store.onCreated(first, 0); store.onCreated(second, 1);
		store.onWinnerChanged(GROUP, java.util.Optional.of(second.id()));
		store.onCreated(second, 2);
		assertSame(GROUP, store.marker(second.id()).orElseThrow().targetKey());
		assertEquals(101, store.marker(second.id()).orElseThrow().displayExpiresAtLocalTick());
		store.expireFallback(10);
		assertEquals(first.id(), store.winnerId(GROUP).orElseThrow(), "lost removal temporarily falls back across physical halves");
		assertEquals(1, store.allMarkers().size());
		store.onRemoved(first.id(), MarkerRemovalReason.EXPIRED, 19);
		ClientMarker stale = store.marker(first.id()).orElseThrow();
		assertTrue(stale.isStale()); assertSame(GROUP, stale.targetKey());
		assertEquals(100, stale.displayExpiresAtLocalTick());
	}

	private static MarkerCreationService service(ServerMarkerStore store, TargetGroupingResolver grouping, List<String> events) {
		return new MarkerCreationService(store, (target, context) -> {
			events.add("classify"); return new ResolvedTarget(target, TYPE);
		}, PingTypeCatalog.builtIn(), (owner, target) -> {
			events.add("validate");
			return AuthoritativeTargetValidation.accepted(new ValidatedMarkerTarget(target, TargetMatchContext.none(), ANCHOR, NAME));
		}, MarkerCreationLogger.noop(), new ExternalBlockServerProviderRegistry(), null, grouping);
	}

	@Test void dedicatedAdmissionAndStoreShareOneResolutionEvenIfResolverChanges() {
		var events = new ArrayList<String>(); var count = new AtomicInteger();
		var store = new ServerMarkerStore(new MarkerIdSource());
		var service = service(store, (owner, target) -> {
			events.add("group"); assertEquals(A, owner); assertEquals(RIGHT, target);
			return count.incrementAndGet() == 1 ? GROUP : TargetKey.from(target);
		}, events);
		var outcome = service.createDedicated(null, A, RIGHT, "attention", 1, 20, List.of(A, B), (target, key, type, owner, audience) -> {
			events.add("dedicated"); assertSame(GROUP, key); assertEquals(0, store.size());
			assertEquals(RIGHT, target); assertEquals(List.of(A, B), audience);
			return MarkerCreationService.AdmissionResult.accepted(List.of(), Map.of());
		});
		assertTrue(outcome.isAccepted()); assertEquals(1, count.get());
		assertEquals(List.of("validate", "classify", "group", "dedicated"), events);
		assertSame(GROUP, outcome.creation().orElseThrow().marker().targetKey());
		assertEquals(RIGHT, outcome.creation().orElseThrow().marker().target());
		assertTrue(service.create(A, RIGHT, "attention", 2, 30, List.of(A)).isAccepted());
		assertEquals(2, count.get());
		assertSame(GROUP, store.find(outcome.creation().orElseThrow().marker().id()).orElseThrow().targetKey(), "later topology/resolver results never re-key an existing marker");
	}

	@Test void invalidPingAndRejectedPropertyPolicyNeverRunGrouping() {
		var events = new ArrayList<String>(); var store = new ServerMarkerStore(new MarkerIdSource());
		var service = service(store, (owner, target) -> { fail("rejected create cannot group"); return GROUP; }, events);
		assertFalse(service.create(A, LEFT, "not_a_ping", 1, 20, List.of(A)).isAccepted());
		var intent = PresentationPropertyIntent.observed(PresentationPropertyRef.root("minecraft:basic", "minecraft:target.name"), new PresentationValue.Text("Chest"));
		assertFalse(service.create(null, A, LEFT, "attention", 1, 20, List.of(A), List.of(intent),
			(target, type, owner, audience, intents) -> MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST)).isAccepted());
		assertEquals(0, store.size());
	}

	@Test void incompatibleGroupingRejectsBeforeDedicatedAdmissionAndStorage() {
		var store = new ServerMarkerStore(new MarkerIdSource());
		var service = service(store, (owner, target) -> TargetKey.from(RIGHT), new ArrayList<>());
		var result = service.createDedicated(null, A, LEFT, "attention", 1, 20, List.of(A), (target, key, type, owner, audience) -> {
			fail("invalid group cannot authorize a sidecar"); return MarkerCreationService.AdmissionResult.accepted(List.of(), Map.of());
		});
		assertEquals(MarkerRejectReason.INVALID_REQUEST, result.rejectReason().orElseThrow()); assertEquals(0, store.size());
	}

	@Test void groupedCancellationRevealsOtherHitAndVaultRecordsRemainIndependent() {
		var store = new ServerMarkerStore(new MarkerIdSource());
		var key = new TargetKey.VaultKey(DIM, -5, 2, 3, "create:item_vault");
		var firstHit = new Target.BlockTarget(DIM, -4, 2, 3, "create:item_vault");
		var otherHit = new Target.BlockTarget(DIM, -3, 2, 3, "create:item_vault");
		var first = store.create(A, firstHit, key, TYPE, TYPE.defaultPingType(), ANCHOR, 1, 20, List.of(A, B)).marker();
		var second = store.create(B, otherHit, key, TYPE, TYPE.defaultPingType(), ANCHOR, 2, 30, List.of(A, B)).marker();
		assertEquals(second.id(), store.winnerFor(key, A).orElseThrow().id());
		assertEquals(MarkerRemovalResult.Status.NOT_OWNER, store.removeOwned(A, second.id()).status());
		var removal = store.removeOwned(B, second.id());
		assertEquals(2, removal.winnerChanges().size());
		assertTrue(removal.winnerChanges().stream().allMatch(change -> change.targetKey().equals(key)
			&& change.currentWinner().equals(java.util.Optional.of(first.id()))));
		assertEquals(firstHit, store.winnerFor(key, A).orElseThrow().target());
		assertEquals(key, MarkerSnapshot.from(store.find(first.id()).orElseThrow()).targetKey());
		assertEquals(1, store.expire(20).removals().size());
	}

	@Test void externalMaterializationReclassifiesBeforeSingleGroupingAndRefreshRetainsKey() {
		var candidate = Target.ExternalBlockTarget.candidate(DIM, "provider:test", "minecraft:chest", "before", true);
		var committed = Target.ExternalBlockTarget.committed(DIM, "provider:test", "stable", "minecraft:chest", "after", true);
		var events = new ArrayList<String>(); var store = new ServerMarkerStore(new MarkerIdSource());
		var count = new AtomicInteger(); var key = TargetKey.from(committed);
		var service = service(store, (owner, target) -> {
			events.add("group"); count.incrementAndGet(); assertEquals(committed, target);
			assertEquals("after", ((Target.ExternalBlockTarget) target).providerLocator()); return key;
		}, events);
		var result = service.createDedicatedWithExternalTransaction(new ExternalBlockTransaction() {
			@Override public ExternalBlockServerProvider.MaterializationResult materialize(Target.ExternalBlockTarget target) {
				events.add("materialize"); return new ExternalBlockServerProvider.MaterializationResult.Materialized(
					new ExternalBlockServerProvider.MaterializedTarget(committed, TargetMatchContext.none(), ANCHOR));
			}
			@Override public void release(Target.ExternalBlockTarget target) { fail("accepted reference must remain held"); }
		}, A, candidate, "attention", 1, 20, List.of(A, B), (target, group, type, owner, audience) -> {
			events.add("dedicated"); assertSame(key, group); return MarkerCreationService.AdmissionResult.accepted(List.of(), Map.of());
		});
		assertTrue(result.isAccepted()); assertEquals(1, count.get());
		assertEquals(List.of("validate", "classify", "materialize", "classify", "group", "dedicated"), events);
		var marker = result.creation().orElseThrow().marker();
		var refreshed = store.updateExternalTarget(marker.id(), Target.ExternalBlockTarget.committed(DIM, "provider:test", "stable", "minecraft:chest", "new", true), ANCHOR).orElseThrow();
		assertSame(key, refreshed.targetKey()); store.forgetRecipient(B);
		assertSame(key, MarkerSnapshot.from(store.find(marker.id()).orElseThrow()).targetKey());
	}
}
