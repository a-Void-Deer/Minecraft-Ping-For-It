package nx.pingwheel.common.presentation.inventory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryChecksums;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static nx.pingwheel.common.presentation.inventory.InventoryResourceKind.*;

@NotNullByDefault
class ClientInventoryLanesTest {

	private final List<IPacket> sent = new ArrayList<>();
	private final ClientInventory client = new ClientInventory(sent::add);
	private final Target.BlockTarget target = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	private final MarkerId marker = new MarkerId(4);

	@Test void creationAuthorityBeforeAnyInventoryHandshakeIsMarkerScopedAndCannotClearOlderDenials() {
		client.presentationReset(100, 1);
		client.acceptedSchemas(Set.of(ITEM, FLUID));
		client.markerCreated(markerSnapshot(target));
		var snapshot = new InventoryCreationSnapshot(ITEM, "creation-key", "minecraft:gunpowder", "item.minecraft.gunpowder",
			null, 5, false, "take", false, true);
		assertTrue(client.creationSnapshot(marker, snapshot));
		assertEquals(java.util.Optional.of(true), client.receiptAuthorization(marker, 100, 1, "entity_block"));
		assertTrue(client.authorization(ITEM, 100, 1, "entity_block").isEmpty());
		assertTrue(client.receiptAuthorization(marker, 101, 1, "entity_block").isEmpty());
		assertEquals(java.util.Optional.of(false), client.receiptAuthorization(marker, 100, 1, "block"));
		assertEquals(InventoryClientStore.NO_BASELINE, client.tracking(marker).baselineId());
		assertEquals(0, client.stats().trackedChannels());
		assertFalse(client.tracking(marker).complete());
		client.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(3, 3, 0, 0)).stamp(100, 1));
		policy(ITEM, false);
		assertNull(client.creationSnapshot(marker));
		policy(ITEM, true);
		assertFalse(client.creationSnapshot(marker, snapshot), "receipt membership is consumed even after deny/re-allow");
		assertEquals(java.util.Optional.of(false), client.receiptAuthorization(marker, 100, 1, "entity_block"));
		MarkerId later = new MarkerId(5);
		client.markerCreated(new MarkerSnapshot(later, new UUID(1, 2), target, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 1, 10000));
		assertTrue(client.creationSnapshot(later, snapshot));
		assertEquals(java.util.Optional.of(true), client.receiptAuthorization(later, 100, 1, "entity_block"));
		assertEquals(java.util.Optional.of(false), client.receiptAuthorization(marker, 100, 1, "entity_block"),
			"a new marker's receipt cannot clear a different marker's latched denial");
		assertTrue(sent.isEmpty(), "creation seeding performs no negotiation, reads or network requests");
	}

	@Test void lateCreationSeedCannotDowngradeAClosedFreshTrackingProjectionOrEnterItsChecksum() {
		ready(); sent.clear();
		data(ITEM, 0, marker, 0, 1, true, 9);
		assertTrue(client.tracking(marker).complete());
		assertEquals(9, client.tracking(marker).entries().getFirst().count());
		var snapshot = new InventoryCreationSnapshot(ITEM, "other-creation-key", "minecraft:gunpowder", "item.minecraft.gunpowder",
			null, 5, false, "take", false, true);
		assertTrue(client.creationSnapshot(marker, snapshot));
		assertEquals(snapshot, client.creationSnapshot(marker), "receipt retains its immutable creation value independently");
		assertEquals(9, client.tracking(marker).entries().getFirst().count());
		client.accept(InventoryS2CPacket.heartbeat(ITEM, 7, 0, marker, 2, 1, 1,
			InventoryChecksums.checksumEntries(ITEM, List.of(entry(9, 1)))).stamp(100, 1));
		assertTrue(sent.isEmpty(), "creation metadata cannot cause a digest mismatch or corrective repair");
		assertTrue(client.tracking(marker).complete());
	}

	private long ready() {
		client.presentationReset(100, 1);
		client.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(3, 3, 0, 0)).stamp(100, 1));
		for (var kind : InventoryResourceKind.values()) policy(kind, true);
		client.markerCreated(new MarkerSnapshot(marker, new UUID(1, 2), target, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 0, 10000));
		return client.open(target, BlockFace.NORTH, "entity_block");
	}
	private MarkerSnapshot markerSnapshot(Target target) {
		return new MarkerSnapshot(marker, new UUID(1, 2), target, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 0, 10000);
	}
	private void sableMarker() {
		client.markerRemoved(marker);
		client.markerCreated(markerSnapshot(Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "tracking",
			"minecraft:chest", "opaque", true)));
	}
	private void policy(InventoryResourceKind kind, boolean allowed) {
		client.accept(InventoryS2CPacket.policy(kind, 7, 100, 1, allowed ? Set.of("entity_block") : Set.of()));
	}
	private InventoryS2CPacket.Entry entry(long amount, long revision) {
		return new InventoryS2CPacket.Entry("same-token", "minecraft:water", "block.minecraft.water", null, amount, revision, false, null, 0, false, "attention");
	}
	private void data(InventoryResourceKind kind, long request, @Nullable MarkerId markerId, int index, int parts, boolean complete, long amount) {
		var entries = amount < 0 ? List.<InventoryS2CPacket.Entry>of() : List.of(entry(amount, 1));
		client.accept(InventoryS2CPacket.data(kind, markerId == null ? InventoryS2CPacket.Kind.PREVIEW : InventoryS2CPacket.Kind.SNAPSHOT,
			7, request, markerId, 2, 1, 1, index, parts, complete, complete ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UPDATING,
			InventoryChecksums.checksumEntries(kind, entries), entries).stamp(100, 1));
	}

	@Test void mixedOutOfOrderAndEmptyCutsKeepLaneCompletionStatusAndPermissionsIndependent() {
		long request = ready();
		assertEquals(1, sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p && p.kind() == InventoryC2SPacket.Kind.OPEN).count());
		data(FLUID, request, null, 1, 2, true, -1);
		data(ITEM, request, null, 0, 1, true, 8);
		assertFalse(client.preview(request, FLUID).completeScan());
		assertTrue(client.preview(request, ITEM).completeScan());
		data(FLUID, request, null, 0, 2, true, 80);
		assertEquals(80, client.preview(request, FLUID).entries().getFirst().count());
		assertEquals(8, client.preview(request, ITEM).entries().getFirst().count());
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 2, 2, 2, 0, 1,
			false, InventoryS2CPacket.Status.INVALID, 0, List.of()).stamp(100, 1));
		assertTrue(client.preview(request, FLUID).invalid());
		assertEquals(8, client.preview(request, ITEM).entries().getFirst().count());
		policy(FLUID, false);
		assertFalse(client.hasPreview(request, FLUID));
		assertTrue(client.hasPreview(request, ITEM));
		client.close(request);
		assertFalse(client.hasPreview(request));
		assertEquals(1, sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p && p.kind() == InventoryC2SPacket.Kind.CLOSE).count());
	}

	@Test void sharedCommitRejectsWrongLaneOutcomeWithoutConsumingTheCorrectOutcome() {
		long request = ready();
		data(ITEM, request, null, 0, 1, true, 1);
		data(FLUID, request, null, 0, 1, true, 81);
		var item = client.selectable(request, ITEM).getFirst();
		var fluid = client.selectable(request, FLUID).getFirst();
		List<InventoryC2SPacket> selected = new ArrayList<>();
		assertEquals(ClientInventory.DispatchOutcome.SENT, client.select(fluid, "attention", packet -> { selected.add(packet); return ClientInventory.DispatchOutcome.SENT; }));
		assertEquals(FLUID, selected.getFirst().resourceKind());
		assertEquals(ClientInventory.DispatchOutcome.ALREADY_RELEASED, client.select(item, "attention", packet -> fail("second lane cannot submit")));
		long commit = selected.getFirst().commitId();
		client.accept(InventoryS2CPacket.selected(ITEM, 7, request, commit, marker, null).stamp(100, 1));
		assertNull(client.selectionResult(commit));
		client.accept(InventoryS2CPacket.selected(FLUID, 7, request, commit, marker, null).stamp(100, 1));
		assertEquals(FLUID, client.selectionResult(commit).resourceKind());
	}
	@Test void wrongLaneOrOldRequestReferenceCannotAuthorizeASelection() {
		long request = ready();
		data(ITEM, request, null, 0, 1, true, 2);
		var item = client.selectable(request, ITEM).getFirst();
		var forgedFluid = new ClientInventory.PreviewEntryReference(FLUID, request, item.baselineId(), item.stateRevision(), item.entryKey());
		assertEquals(ClientInventory.DispatchOutcome.STALE_REFERENCE,
			client.select(forgedFluid, "attention", packet -> fail("an ITEM reference cannot populate the FLUID lane")));
		client.close(request);
		long reopened = client.open(target, BlockFace.NORTH, "entity_block");
		data(ITEM, request, null, 0, 1, true, 9);
		assertTrue(client.preview(reopened, ITEM).entries().isEmpty());
		assertEquals(ClientInventory.DispatchOutcome.STALE_REFERENCE,
			client.select(item, "attention", packet -> fail("late reference cannot reach the transport")));
	}

	@Test void fixedKindTrackingFencesWrongLaneIncludingEmptyControlsAndRevokeThenResetClearsBothGroups() {
		long request = ready();
		data(FLUID, request, marker, 0, 1, false, 80);
		assertFalse(client.tracking(marker).complete());
		data(ITEM, request, marker, 0, 1, true, 900);
		client.accept(InventoryS2CPacket.status(ITEM, 7, request, marker, 8, 99, 9, InventoryS2CPacket.Status.INVALID, 0).stamp(100, 1));
		assertEquals(FLUID, client.tracking(marker).resourceKind());
		assertEquals(80, client.tracking(marker).entries().getFirst().count());
		policy(FLUID, false);
		data(ITEM, request, marker, 0, 1, true, 900);
		assertTrue(client.tracking(marker).entries().isEmpty());
		assertEquals(FLUID, client.trackingKind(marker).orElseThrow());
		client.presentationReset(100, 2);
		assertFalse(client.hasPreview(request));
		assertTrue(client.trackingKind(marker).isEmpty());
		assertEquals(0, client.stats().trackedChannels());
		assertEquals(0, client.stats().unknownSessions());
	}

	@Test void incompatibleSchemaDisablesOnlyInventoryAndNeverKeepsARequestLoading() {
		long request = ready();
		client.acceptedSchemas(Set.of());
		assertFalse(client.hasPreview(request));
		assertTrue(client.schemaUnavailable());
		assertEquals(false, client.authorization(FLUID, 100, 1, "entity_block").orElseThrow());
		assertFalse(client.ready());
	}
	@Test void unknownKindReceiptDenialRequiresEveryLaneToBeExplicitlyDeniedInTheCurrentView() {
		ready();
		policy(ITEM, false);
		assertTrue(client.receiptAuthorization(marker, 100, 1, "entity_block").isEmpty());
		policy(FLUID, false);
		assertEquals(false, client.receiptAuthorization(marker, 100, 1, "entity_block").orElseThrow());
		assertTrue(client.receiptAuthorization(marker, 100, 2, "entity_block").isEmpty());
		policy(ITEM, true);
		assertTrue(client.receiptAuthorization(marker, 100, 1, "entity_block").isEmpty(), "an unrelated lane grant cannot invent marker kind");
		client.presentationReset(100, 2);
		assertTrue(client.receiptAuthorization(marker, 100, 2, "entity_block").isEmpty());
	}
	@Test void denialHistoryStartsAtMembershipAndCannotBeClearedByRefreshOrReallow() {
		long request = ready();
		client.markerRemoved(marker);
		policy(ITEM, false); policy(ITEM, true);
		client.markerCreated(markerSnapshot(target));
		data(ITEM, request, marker, 0, 1, true, 4);
		assertEquals(true, client.receiptAuthorization(marker, 100, 1, "entity_block").orElseThrow(), "pre-membership denial is irrelevant");
		client.markerRemoved(marker);
		policy(ITEM, false);
		client.markerCreated(markerSnapshot(target));
		policy(ITEM, true);
		client.markerCreated(markerSnapshot(target));
		assertTrue(client.receiptAuthorization(marker, 100, 1, "entity_block").isEmpty(), "a possible FLUID receipt is still unknown");
		data(ITEM, request, marker, 0, 1, true, 4);
		assertTrue(client.tracking(marker).complete());
		assertEquals(false, client.receiptAuthorization(marker, 100, 1, "entity_block").orElseThrow(), "initial denial survives reallow and refresh");
		assertTrue(client.receiptAuthorization(marker, 101, 1, "entity_block").isEmpty());
		assertTrue(client.receiptAuthorization(marker, 100, 2, "entity_block").isEmpty());
	}
	@ParameterizedTest
	@EnumSource(InventoryResourceKind.class)
	void eachLaneRetainsOnlyItsOwnDenialWhenTheFirstFreshDataBindsTheKind(InventoryResourceKind denied) {
		long request = ready();
		policy(denied, false); policy(denied, true);
		assertTrue(client.receiptAuthorization(marker, 100, 1, "entity_block").isEmpty());
		data(denied, request, marker, 0, 1, true, 80);
		assertTrue(client.tracking(marker).complete());
		assertEquals(false, client.receiptAuthorization(marker, 100, 1, "entity_block").orElseThrow());
	}
	@ParameterizedTest
	@ValueSource(strings = {"remove", "view-reset", "epoch-reset", "disconnect"})
	void newMembershipDoesNotInheritReceiptDenials(String transition) {
		long request = ready();
		policy(ITEM, false); policy(ITEM, true);
		data(ITEM, request, marker, 0, 1, true, 4);
		assertEquals(false, client.receiptAuthorization(marker, 100, 1, "entity_block").orElseThrow());
		long epoch = 100, view = 1;
		switch (transition) {
			case "remove" -> client.markerRemoved(marker);
			case "view-reset" -> { view = 2; client.presentationReset(epoch, view); }
			case "epoch-reset" -> { epoch = 101; client.presentationReset(epoch, view); }
			case "disconnect" -> {
				client.reset();
				client.presentationReset(epoch, view);
				client.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(3, 3, 0, 0)).stamp(epoch, view));
			}
			default -> throw new AssertionError(transition);
		}
		assertTrue(client.trackingKind(marker).isEmpty()); assertTrue(client.tracking(marker).entries().isEmpty());
		for (var kind : InventoryResourceKind.values())
			client.accept(InventoryS2CPacket.policy(kind, 7, epoch, view, Set.of("entity_block")));
		client.markerCreated(markerSnapshot(target));
		if (epoch != 100 || view != 1) policy(ITEM, false); // A stale policy cannot recreate history in the new view.
		client.accept(InventoryS2CPacket.data(ITEM, InventoryS2CPacket.Kind.SNAPSHOT, 7, 0, marker, 2, 1, 1, 0, 1,
			true, InventoryS2CPacket.Status.READY, InventoryChecksums.checksumEntries(ITEM, List.of(entry(4, 1))), List.of(entry(4, 1))).stamp(epoch, view));
		assertTrue(client.tracking(marker).complete());
		assertEquals(true, client.receiptAuthorization(marker, epoch, view, "entity_block").orElseThrow());
	}
	@Test void markerMetadataCapacityDoesNotRetainHistoryForAnUnacceptedMembership() {
		ready();
		for (int index = 0; index < ClientInventory.MAX_TRACKED_CHANNELS; index++) {
			var id = new MarkerId(1000 + index);
			client.markerCreated(new MarkerSnapshot(id, new UUID(1, 2), target, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 0, 10000));
		}
		var overflow = new MarkerId(1000 + ClientInventory.MAX_TRACKED_CHANNELS - 1);
		policy(ITEM, false); policy(ITEM, true);
		client.markerRemoved(marker);
		client.markerCreated(new MarkerSnapshot(overflow, new UUID(1, 2), target, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 0, 10000));
		client.accept(InventoryS2CPacket.status(ITEM, 7, 0, overflow, 2, 1, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		assertEquals(true, client.receiptAuthorization(overflow, 100, 1, "entity_block").orElseThrow());
	}
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void sableFluidRebaselineRetainsTheClosedSeedOrCompleteCutUntilEveryFreshPartArrives(boolean completed) {
		long request = ready(); sableMarker();
		data(FLUID, request, marker, 0, 1, completed, 80);
		assertFalse(client.tracking(marker).grey());
		if (completed) {
			client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 2, 1, 1, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
			assertTrue(client.tracking(marker).complete(), "same-fence UPDATING cannot restart a completed cut; the server must advance the scope fence");
		}
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 3, 2, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		assertUpdatingQuantity(80);
		assertEquals(3, client.tracking(marker).baselineId()); assertEquals(2, client.tracking(marker).statusRevision());
		data(FLUID, request, marker, 0, 1, true, 999);
		assertUpdatingQuantity(80);
		var fresh = entry(162, 2);
		long checksum = InventoryChecksums.checksumEntries(FLUID, List.of(fresh));
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.SNAPSHOT, 7, request, marker, 3, 2, 1, 1, 2,
			true, InventoryS2CPacket.Status.READY, checksum, List.of(fresh)).stamp(100, 1));
		assertUpdatingQuantity(80);
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 3, 2, 1, InventoryS2CPacket.Status.READY, checksum).stamp(100, 1));
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 3, 2, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		assertUpdatingQuantity(80);
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.SNAPSHOT, 7, request, marker, 3, 2, 1, 0, 2,
			true, InventoryS2CPacket.Status.READY, checksum, List.of()).stamp(100, 1));
		assertEquals(162, client.tracking(marker).entries().getFirst().count());
		assertTrue(client.tracking(marker).complete()); assertFalse(client.tracking(marker).grey());
		long repairs = client.stats().repairRequests();
		client.accept(InventoryS2CPacket.heartbeat(7, request, marker, 3, 2, 1, checksum).withResourceKind(FLUID).stamp(100, 1));
		assertEquals(repairs, client.stats().repairRequests(), "history contributes nothing to the new digest");
	}
	private void assertUpdatingQuantity(long amount) {
		var tracking = client.tracking(marker);
		assertEquals(InventoryS2CPacket.Status.UPDATING, tracking.status());
		assertFalse(tracking.grey()); assertFalse(tracking.complete());
		assertEquals(amount, tracking.entries().getFirst().count());
	}
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void repeatedSableFluidRebaselineCannotAdoptAnUnfinishedSourceCutAsHistory(boolean closedProvisional) {
		long request = ready(); sableMarker(); data(FLUID, request, marker, 0, 1, true, 80);
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 3, 2, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.SNAPSHOT, 7, request, marker, 3, 2, 1, 0, 2,
			!closedProvisional, closedProvisional ? InventoryS2CPacket.Status.UPDATING : InventoryS2CPacket.Status.READY, 0, List.of(entry(1, 2))).stamp(100, 1));
		if (closedProvisional) client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.SNAPSHOT, 7, request, marker, 3, 2, 1, 1, 2,
			false, InventoryS2CPacket.Status.UPDATING, 0, List.of()).stamp(100, 1));
		assertUpdatingQuantity(80);
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 4, 3, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		assertUpdatingQuantity(80);
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.SNAPSHOT, 7, request, marker, 3, 2, 1, 1, 2,
			true, InventoryS2CPacket.Status.READY, 0, List.of()).stamp(100, 1));
		assertUpdatingQuantity(80);
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.SNAPSHOT, 7, request, marker, 4, 3, 1, 0, 1,
			true, InventoryS2CPacket.Status.READY, InventoryChecksums.checksumEntries(FLUID, List.of(entry(0, 3))), List.of(entry(0, 3))).stamp(100, 1));
		assertTrue(client.tracking(marker).complete()); assertFalse(client.tracking(marker).grey());
		assertEquals(0, client.tracking(marker).entries().getFirst().count(), "zero requires a fresh complete authoritative cut");
	}
	@ParameterizedTest
	@ValueSource(strings = {"INVALID", "INCOMPLETE"})
	void sableFluidRealFailureStaysGreyAcrossFurtherUpdatingUntilFreshReplacement(String failure) {
		long request = ready(); sableMarker(); data(FLUID, request, marker, 0, 1, false, 80);
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 3, 2, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 3, 3, 0, InventoryS2CPacket.Status.valueOf(failure), 0).stamp(100, 1));
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 4, 4, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		assertEquals(InventoryS2CPacket.Status.valueOf(failure), client.tracking(marker).status());
		assertTrue(client.tracking(marker).grey()); assertFalse(client.tracking(marker).complete());
		assertEquals(80, client.tracking(marker).entries().getFirst().count());
		policy(FLUID, false);
		assertTrue(client.tracking(marker).entries().isEmpty(), "real read denial still purges retained quantities");
	}
	@ParameterizedTest
	@CsvSource({"ITEM,sable", "FLUID,other", "FLUID,minecraft"})
	void nonSableFluidRebaselineKeepsItsExistingGreyHistoryRule(InventoryResourceKind kind, String provider) {
		long request = ready(); client.markerRemoved(marker);
		client.markerCreated(markerSnapshot(provider.equals("minecraft") ? target
			: Target.ExternalBlockTarget.committed("minecraft:overworld", provider, "tracking", "minecraft:chest", "opaque", true)));
		data(kind, request, marker, 0, 1, true, 80);
		client.accept(InventoryS2CPacket.status(kind, 7, request, marker, 3, 2, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		assertEquals(InventoryS2CPacket.Status.UPDATING, client.tracking(marker).status());
		assertTrue(client.tracking(marker).grey()); assertFalse(client.tracking(marker).complete());
		assertEquals(80, client.tracking(marker).entries().getFirst().count());
	}
	@Test void closedFluidDigestKeepsSubMillibucketDifferencesAndRepairCarriesItsOwnKind() {
		long request = ready();
		data(FLUID, request, marker, 0, 1, true, 1);
		var value = client.tracking(marker);
		assertTrue(value.complete()); assertEquals(1, value.entries().getFirst().count());
		long checksumFor80 = InventoryChecksums.checksumEntries(FLUID, List.of(entry(80, 1)));
		client.accept(InventoryS2CPacket.heartbeat(7, request, marker, 2, 1, 1, checksumFor80).withResourceKind(FLUID).stamp(100, 1));
		var repair = sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p && p.kind() == InventoryC2SPacket.Kind.RESYNC)
			.map(packet -> (InventoryC2SPacket) packet).findFirst().orElseThrow();
		assertEquals(FLUID, repair.resourceKind());
		assertEquals(1, client.tracking(marker).entries().getFirst().count(), "equal local display never merges authoritative units");
		data(ITEM, request, marker, 0, 1, true, 80);
		assertEquals(1, client.tracking(marker).entries().getFirst().count());
	}
	@Test void fluidFreshMultipartCompletionAndRecoveryNeverPromotePartialCountsOrLateSeeds() {
		long request = ready();
		data(FLUID, request, marker, 0, 1, false, 80);
		var fresh = entry(81, 2);
		long checksum = InventoryChecksums.checksumEntries(FLUID, List.of(fresh));
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 2, 1, 2, InventoryS2CPacket.Status.READY, checksum).stamp(100, 1));
		assertEquals(InventoryS2CPacket.Status.UPDATING, client.tracking(marker).status());
		assertEquals(80, client.tracking(marker).entries().getFirst().count());
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.STREAM, 7, request, marker, 2, 1, 2, 1, 2,
			true, InventoryS2CPacket.Status.READY, checksum, List.of(fresh)).stamp(100, 1));
		assertFalse(client.tracking(marker).complete());
		assertEquals(80, client.tracking(marker).entries().getFirst().count());
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.STREAM, 7, request, marker, 2, 1, 2, 0, 2,
			true, InventoryS2CPacket.Status.READY, checksum, List.of()).stamp(100, 1));
		assertTrue(client.tracking(marker).complete()); assertEquals(81, client.tracking(marker).entries().getFirst().count());
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 2, 2, 2, InventoryS2CPacket.Status.INVALID, 0).stamp(100, 1));
		assertTrue(client.tracking(marker).grey()); assertFalse(client.tracking(marker).complete());
		assertEquals(81, client.tracking(marker).entries().getFirst().count());
		data(FLUID, request, marker, 0, 1, false, 80);
		assertEquals(81, client.tracking(marker).entries().getFirst().count(), "late old seed cannot restore validity");
		client.accept(InventoryS2CPacket.status(FLUID, 7, request, marker, 3, 3, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1));
		client.accept(InventoryS2CPacket.data(FLUID, InventoryS2CPacket.Kind.SNAPSHOT, 7, request, marker, 3, 3, 3, 0, 1,
			true, InventoryS2CPacket.Status.READY, InventoryChecksums.checksumEntries(FLUID, List.of()), List.of()).stamp(100, 1));
		assertTrue(client.tracking(marker).complete()); assertFalse(client.tracking(marker).grey());
		assertTrue(client.tracking(marker).entries().isEmpty(), "closed empty projection is distinct from retained history");
	}
}
