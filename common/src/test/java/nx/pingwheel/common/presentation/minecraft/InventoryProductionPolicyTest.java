package nx.pingwheel.common.presentation.minecraft;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventoryResourceCodec;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryResourceValue;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySyncPublisher;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryServer;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual presentation tick and permission derivation, with only native content and transport replaced. */
@NotNullByDefault
class InventoryProductionPolicyTest {

	private static final UUID OWNER = new UUID(704, 2);
	private static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 64, 3, "minecraft:chest");
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@ParameterizedTest
	@EnumSource(InventoryResourceKind.class)
	void actualTickRevokesOnlyTheDedicatedLaneAndPreservesAcceptedSiblingPreview(InventoryResourceKind denied) throws Exception {
		try (var f = new Fixture(Map.of(denied.fieldId(), 2))) {
			long request = f.open();
			var sibling = denied == InventoryResourceKind.FLUID ? InventoryResourceKind.ITEM : InventoryResourceKind.FLUID;
			var accepted = f.client.preview(request, sibling);
			var revokedReference = f.client.selectable(request, denied).getFirst();
			var oldCut = f.lastPreview(request, denied);
			long epoch = f.session().epoch, view = f.session().view;
			var mask = f.session().mask;
			f.presentation.clear();
			f.player.permission = 0;
			f.tick(1);
			assertAll("actual OP -> deop tick keeps the sibling and the presentation generation",
				() -> assertEquals(epoch, f.session().epoch),
				() -> assertEquals(view, f.session().view),
				() -> assertEquals(mask, f.session().mask, "DEDICATED fields never enter the SECTION mask"),
				() -> assertEquals(0, f.resets()),
				() -> assertEquals(accepted, f.client.preview(request, sibling)),
				() -> assertFalse(f.client.hasPreview(request, denied)),
				() -> assertEquals(1, f.states.get(sibling).reads),
				() -> assertEquals(1, f.states.get(denied).reads),
				() -> assertEquals(1, f.opens()),
				() -> assertEquals(0, f.closes()));
			assertEquals(Optional.of(false), f.client.authorization(denied, epoch, view, "entity_block"));
			f.client.accept(oldCut);
			assertFalse(f.client.hasPreview(request, denied));
			assertEquals(ClientInventory.DispatchOutcome.STALE_REFERENCE, f.client.select(revokedReference, "attention", packet -> fail("retired token dispatched")));
			f.player.permission = 2; f.tick(2);
			assertEquals(view, f.session().view); assertEquals(accepted, f.client.preview(request, sibling));
			assertEquals(1, f.states.get(sibling).reads); assertEquals(1, f.opens()); assertEquals(0, f.closes());
			assertEquals(2, f.states.get(denied).reads, "reallow uses a fresh observation under the same parent request");
			var fresh = f.client.preview(request, denied);
			assertTrue(fresh.completeScan()); assertTrue(fresh.baselineId() > oldCut.baselineId());
			assertNotEquals(revokedReference.entryKey(), f.client.selectable(request, denied).getFirst().entryKey());
			f.client.accept(oldCut); assertEquals(fresh, f.client.preview(request, denied), "late old cut cannot resurrect a retired token");
			assertEquals(ClientInventory.DispatchOutcome.STALE_REFERENCE, f.client.select(revokedReference, "attention", packet -> fail("old token after reallow")));
			int getters = f.states.values().stream().mapToInt(state -> state.reads).sum();
			f.backend.handle(OWNER, InventoryC2SPacket.select(denied, f.client.epoch(), epoch, view, 1, request,
				oldCut.baselineId(), oldCut.statusRevision(), revokedReference.entryKey(), "attention"), 2, f.settings);
			assertEquals(0, f.store.size(), "server also rejects a retired lane token");
			assertEquals(getters, f.states.values().stream().mapToInt(state -> state.reads).sum());
		}
	}

	@Test void actualTickFreshLaneReallowKeepsTheSingleParentTargetQuotaAndCloseBoundary() throws Exception {
		try (var f = new Fixture(Map.of(InventoryPresentation.FLUIDS, 2))) {
			f.settings.getPreview().setMaxTargetsPerClient(IntLimit.finite(1));
			long request = f.open(); f.player.permission = 0; f.tick(1); f.player.permission = 2; f.tick(2);
			assertEquals(2, f.states.get(InventoryResourceKind.FLUID).reads); assertEquals(1, f.states.get(InventoryResourceKind.ITEM).reads);
			f.backend.handle(OWNER, InventoryC2SPacket.open(f.client.epoch(), f.session().epoch, f.session().view, 2, TARGET, BlockFace.NORTH), 2, f.settings);
			f.tick(2); assertTrue(f.packets.stream().noneMatch(p -> p.requestId() == 2));
			f.client.close(request); f.backend.handle(OWNER, f.requests.getLast(), 2, f.settings);
			int getters = f.states.values().stream().mapToInt(state -> state.reads).sum();
			f.tick(3); assertEquals(getters, f.states.values().stream().mapToInt(state -> state.reads).sum());
			assertEquals(1, f.opens()); assertEquals(1, f.closes());
		}
	}

	@Test void actualTickRetainsSectionPermissionAndChildBlacklistResetBoundaries() throws Exception {
		try (var f = new Fixture(Map.of(PresentationBasic.BLOCK_STATE, 2))) {
			f.open(); long view = f.session().view;
			f.presentation.clear(); f.player.permission = 0; f.tick(1);
			assertEquals(view + 1, f.session().view); assertEquals(1, f.resets());
			assertFalse(f.session().mask.get("entity_block").get(PresentationBasic.ID).contains(PresentationBasic.BLOCK_STATE));
			assertEquals(0, f.client.stats().previewChannels());
			f.presentation.clear();
			f.policy.setRules("entity_block", new PresentationSettings.RuleSet(List.of(), List.of(), false,
				List.of(new PresentationPropertyRef(PresentationBasic.ID, PresentationBasic.BLOCK_STATE, List.of("facing")))));
			f.tick(2); assertEquals(view + 2, f.session().view); assertEquals(1, f.resets());
		}
	}

	@ParameterizedTest
	@CsvSource({"ITEM,false", "ITEM,true", "FLUID,false", "FLUID,true"})
	void actualTickPreservesSiblingTrackingRecipientCutCursorAndQueuedData(InventoryResourceKind sibling, boolean partial) throws Exception {
		var denied = sibling == InventoryResourceKind.ITEM ? InventoryResourceKind.FLUID : InventoryResourceKind.ITEM;
		try (var f = new Fixture(Map.of(denied.fieldId(), 2))) {
			long request = f.open(); MarkerId marker = f.select(request, sibling);
			f.tick(1); assertTrue(f.client.tracking(marker).complete());
			if (partial) {
				f.states.get(sibling).slots = 8;
				f.settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
				f.tick(4); // A real replacement sweep now owns one paid native-content prefix.
				assertTrue(f.client.tracking(marker).complete());
			} else { f.refuseData = true; f.states.get(sibling).amount = 82; f.tick(4); }
			Object lease = f.trackingLease(marker), recipient = f.recipient(lease);
			var context = (SyncPublisher.Context) value(recipient, "context");
			var consumer = (InventoryRuntime.Consumer) value(lease, "consumer");
			int cursor = (int) value(consumer, "cursor"), reads = f.states.get(sibling).reads;
			var accepted = f.client.tracking(marker);
			Object publisherState = f.publisherState(context);
			@Unmodifiable List<?> queued = List.copyOf((List<?>) value(publisherState, "admitted"));
			if (!partial) assertFalse(queued.isEmpty(), "transport refusal owns an actual admitted complete replacement cut");
			long offered = (long) value(publisherState, "offeredWatermark"), delivered = (long) value(publisherState, "deliveredWatermark");
			long view = f.session().view;
			f.presentation.clear(); f.player.permission = 0;
			// Same authoritative tick cannot create another physical allowance or advance a paid cursor.
			f.tick(f.server.now);
			assertAll("sibling policy change keeps production tracking state exactly",
				() -> assertEquals(view, f.session().view), () -> assertEquals(0, f.resets()),
				() -> assertSame(recipient, f.recipient(lease)), () -> assertSame(publisherState, f.publisherState(context)),
				() -> assertEquals(accepted, f.client.tracking(marker)),
				() -> assertEquals(cursor, value(consumer, "cursor")), () -> assertEquals(reads, f.states.get(sibling).reads),
				() -> assertEquals(offered, value(publisherState, "offeredWatermark")),
				() -> assertEquals(delivered, value(publisherState, "deliveredWatermark")));
			assertEquals(queued, value(publisherState, "admitted"), "sibling revocation cannot drop or rebuild admitted fragments");
			f.player.permission = 2; f.tick(f.server.now);
			assertSame(recipient, f.recipient(lease)); assertEquals(accepted, f.client.tracking(marker)); assertEquals(reads, f.states.get(sibling).reads);
			assertEquals(queued, value(publisherState, "admitted"));
			if (!partial) {
				f.refuseData = false; f.tick(f.server.now);
				assertEquals(82, f.client.tracking(marker).entries().getFirst().count());
				assertEquals(context.baseline(), f.client.tracking(marker).baselineId()); assertEquals(reads, f.states.get(sibling).reads);
			}
			assertEquals(1, f.opens()); assertEquals(0, f.closes());
		}
	}

	@Test void actualTickSiblingRevocationCannotSwallowTheAlreadyCommittedPendingOutcome() throws Exception {
		try (var f = new Fixture(Map.of(InventoryPresentation.FLUIDS, 2))) {
			long request = f.open(); f.refuseResults = true;
			MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			assertNull(f.client.selectionResult(1)); assertEquals(1, f.store.size());
			f.player.permission = 0; f.tick(1);
			assertNull(f.client.selectionResult(1));
			f.refuseResults = false; f.tick(2);
			@Nullable var result = f.client.selectionResult(1); assertNotNull(result); assertEquals(marker, result.markerId());
			assertEquals(1, f.store.size());
			assertEquals(1, f.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SELECTED).count());
			assertEquals(1, f.opens()); assertEquals(0, f.closes());
			f.client.close(request); assertEquals(1, f.closes());
		}
	}

	@Test void actualTickAllLaneDenialPurgesOnlyInventoryAndKeepsBasicKnown() throws Exception {
		try (var f = new Fixture(Map.of(InventoryPresentation.ITEMS, 2, InventoryPresentation.FLUIDS, 2))) {
			long request = f.open(); MarkerId marker = f.select(request, InventoryResourceKind.ITEM); f.tick(1);
			assertTrue(f.client.tracking(marker).complete()); long view = f.session().view;
			f.presentation.clear(); int before = f.packets.size(); f.player.permission = 0; f.tick(2);
			assertEquals(view, f.session().view); assertEquals(0, f.resets()); assertTrue(PresentationServer.inventoryKnows(OWNER, marker));
			assertFalse(f.client.hasPreview(request)); assertTrue(f.client.tracking(marker).entries().isEmpty());
			assertEquals(Optional.of(false), f.client.receiptAuthorization(marker, f.session().epoch, view, "entity_block"));
			assertTrue(f.packets.subList(before, f.packets.size()).stream().allMatch(p -> p.entries().isEmpty()));
			assertEquals(1, f.states.get(InventoryResourceKind.FLUID).reads);
		}
	}

	@ParameterizedTest
	@EnumSource(InventoryResourceKind.class)
	void actualTickDeniesQueuedOwnLaneButNeverChangesTheSiblingPreview(InventoryResourceKind denied) throws Exception {
		try (var f = new Fixture(Map.of(denied.fieldId(), 2))) {
			long request = f.open(); var sibling = denied == InventoryResourceKind.ITEM ? InventoryResourceKind.FLUID : InventoryResourceKind.ITEM;
			var accepted = f.client.preview(request, sibling); f.refuseData = true;
			MarkerId marker = f.select(request, denied); f.tick(1);
			assertTrue(f.client.tracking(marker).entries().isEmpty());
			Object lease = f.trackingLease(marker), recipient = f.recipient(lease);
			var context = (SyncPublisher.Context) value(recipient, "context");
			assertTrue(((InventorySyncPublisher) value(f.backend, "publisher")).pending(context), "the real publisher owns a queued quantity cut");
			int before = f.packets.size(), reads = f.states.get(denied).reads;
			f.player.permission = 0; f.refuseData = false; f.tick(2);
			assertEquals(accepted, f.client.preview(request, sibling));
			assertEquals(reads, f.states.get(denied).reads, "a denied field performs no getter");
			assertTrue(f.client.tracking(marker).entries().isEmpty());
			assertTrue(f.packets.subList(before, f.packets.size()).stream().noneMatch(p -> marker.equals(p.markerId()) && !p.entries().isEmpty()));
			assertFalse(((InventorySyncPublisher) value(f.backend, "publisher")).pending(context));
			assertEquals(Optional.of(false), f.client.receiptAuthorization(marker, f.session().epoch, f.session().view, "entity_block"));
			f.player.permission = 2; f.tick(3);
			assertTrue(f.client.tracking(marker).baselineId() > context.baseline());
			assertEquals(Optional.of(false), f.client.receiptAuthorization(marker, f.session().epoch, f.session().view, "entity_block"), "reallow cannot restore receipt eligibility");
		}
	}

	@ParameterizedTest
	@EnumSource(InventoryResourceKind.class)
	void actualTickReallowCannotAcceptAnOldLaneTrackingCutBeforeItsNewBaseline(InventoryResourceKind denied) throws Exception {
		try (var f = new Fixture(Map.of(denied.fieldId(), 2))) {
			long request = f.open(); MarkerId marker = f.select(request, denied); f.tick(1);
			var oldCut = f.packets.stream().filter(p -> marker.equals(p.markerId()) && !p.entries().isEmpty()).reduce((a, b) -> b).orElseThrow();
			assertTrue(f.client.tracking(marker).complete());
			f.player.permission = 0; f.tick(2); assertTrue(f.client.tracking(marker).entries().isEmpty());
			f.refuseData = true; f.player.permission = 2; f.tick(3);
			assertTrue(f.client.tracking(marker).entries().isEmpty());
			f.client.accept(oldCut); assertTrue(f.client.tracking(marker).entries().isEmpty(), "old cut stays fenced after same-view reallow");
			f.refuseData = false; f.tick(3);
			assertTrue(f.client.tracking(marker).complete()); assertTrue(f.client.tracking(marker).baselineId() > oldCut.baselineId());
			assertEquals(1, f.opens()); assertEquals(0, f.closes());
		}
	}

	@Test void actualTickRetriesLanePolicyAfterControlAdmissionPressureWithoutResettingTheSibling() throws Exception {
		try (var f = new Fixture(Map.of(InventoryPresentation.FLUIDS, 2))) {
			long request = f.open(); var accepted = f.client.preview(request, InventoryResourceKind.ITEM);
			@Nullable IntLimit oldMemory = f.settings.getPendingMemoryMiB();
			f.settings.setPendingMemoryMiB(0); // deterministic retained-admission pressure, independent of field authority
			f.player.permission = 0; f.tick(1);
			assertTrue(f.client.hasPreview(request, InventoryResourceKind.FLUID), "denial control could not be admitted yet");
			f.settings.setPendingMemoryMiB(oldMemory); f.tick(2);
			assertFalse(f.client.hasPreview(request, InventoryResourceKind.FLUID));
			assertEquals(accepted, f.client.preview(request, InventoryResourceKind.ITEM));
		}
	}

	@ParameterizedTest
	@CsvSource({"FLUID,true", "FLUID,false", "ITEM,true", "ITEM,false"})
	void actualTickRetriesAFreshFencedPolicyUntilHandoffEvenWhenAuthorityReturnsBeforeRefresh(InventoryResourceKind lane, boolean allow) throws Exception {
		try (var f = new Fixture(Map.of(lane.fieldId(), 2))) {
			InventoryResourceKind sibling = lane == InventoryResourceKind.FLUID ? InventoryResourceKind.ITEM : InventoryResourceKind.FLUID;
			long request = f.open(), trackingRequest = f.open();
			MarkerId marker = f.select(trackingRequest, sibling); f.tick(1);
			var siblingPreview = f.client.preview(request, sibling);
			var siblingTracking = f.client.tracking(marker); assertTrue(siblingTracking.complete());
			Object recipient = f.recipient(f.trackingLease(marker));
			long epoch = f.session().epoch, view = f.session().view;
			f.presentation.clear();
			if (allow) { f.player.permission = 0; f.tick(1); }
			assertEquals(Optional.of(!allow), f.client.authorization(lane, epoch, view, "entity_block"));
			// Legitimate HELLO traffic spends the existing shared preview/control byte window.
			for (int i = 0; i < 100; i++) f.backend.handle(OWNER, InventoryC2SPacket.hello(), 1, f.settings);
			f.player.permission = allow ? 2 : 0; f.tick(2);
			@Nullable var queued = f.queuedPolicy(lane);
			assertNotNull(queued, "the desired lane control was admitted but its byte window is exhausted");
			boolean pendingWhileQueued = f.pendingPolicies().containsKey(lane);
			int sent = f.packets.size();
			// Another connection drains globally before this owner's Backend policy is refreshed.
			f.player.permission = allow ? 0 : 2;
			if (!allow) PresentationServer.tick(f.server, f.store); // widening still requires the actual presentation policy tick
			f.backend.handle(new UUID(999, 999), InventoryC2SPacket.hello(), 2, f.settings);
			assertNull(f.queuedPolicy(lane), "fresh authority rejects the queued old control");
			assertEquals(sent, f.packets.size(), "a fresh-fenced control is never sent");
			f.player.permission = allow ? 2 : 0;
			int processed = f.requests.size(), largestLaneQueue = 0;
			for (int tick = 3; tick <= 150; tick++) {
				f.tick(tick); f.client.tick(true);
				while (processed < f.requests.size()) f.backend.handle(OWNER, f.requests.get(processed++), tick, f.settings);
				largestLaneQueue = Math.max(largestLaneQueue, f.queuedPolicyCount());
			}
			assertAll("current grant must converge without resetting its sibling after 150 real server/client ticks",
				() -> assertEquals(Optional.of(allow), f.client.authorization(lane, epoch, view, "entity_block")),
				() -> assertEquals(allow, f.client.hasPreview(request, lane)),
				() -> assertTrue(pendingWhileQueued, "enqueue is not successful handoff"),
				() -> assertEquals(siblingPreview, f.client.preview(request, sibling)),
				() -> assertEquals(siblingTracking.baselineId(), f.client.tracking(marker).baselineId()),
				() -> assertSame(recipient, f.recipient(f.trackingLease(marker))),
				() -> assertEquals(0, f.resets()), () -> assertEquals(view, f.session().view),
				() -> assertTrue(f.pendingPolicies().isEmpty()));
			assertTrue(largestLaneQueue <= 2); assertEquals(0, f.queuedPolicyCount());
			assertEquals(1, f.packets.subList(sent, f.packets.size()).stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.POLICY
				&& p.resourceKind() == lane && p.allowedTypes().contains("entity_block") == allow).count(), "retry only until successful handoff, not periodic policy spam");
		}
	}

	@ParameterizedTest
	@CsvSource({"FLUID,true", "FLUID,false", "ITEM,true", "ITEM,false"})
	void queuedPolicyKeepsItsExactOwnerAcrossRepeatedTicksAndStaleAuthorityOrSupersededCompletions(InventoryResourceKind lane, boolean allow) throws Exception {
		try (var f = new Fixture(Map.of(lane.fieldId(), 2))) {
			f.open();
			if (allow) { f.player.permission = 0; f.tick(1); }
			for (int i = 0; i < 100; i++) f.backend.handle(OWNER, InventoryC2SPacket.hello(), 1, f.settings);
			f.player.permission = allow ? 2 : 0; f.tick(2);
			var original = f.pendingPolicy(lane); Object queued = Objects.requireNonNull(f.queuedPolicy(lane));
			var handoff = f.controlHandoff();
			int sent = f.packets.size();
			for (int i = 0; i < 40; i++) {
				f.tick(2); f.publisher().drainControls();
				assertSame(original, f.pendingPolicy(lane)); assertSame(queued, f.queuedPolicy(lane));
				assertTrue(f.queuedPolicyCount() <= 2); assertTrue(f.pendingPolicies().size() <= 2);
			}
			assertEquals(sent, f.packets.size(), "budget-deferred drains never report a handoff or replace the queued owner");
			f.player.permission = allow ? 0 : 2; PresentationServer.tick(f.server, f.store);
			handoff.accepted(OWNER, original);
			assertSame(original, f.pendingPolicy(lane), "a completion under changed fresh authority cannot clear the desired update");
			f.tick(2); var superseding = f.pendingPolicy(lane); assertNotSame(original, superseding);
			handoff.accepted(OWNER, original); assertSame(superseding, f.pendingPolicy(lane));
			f.player.permission = allow ? 2 : 0; f.tick(2);
			var latest = f.pendingPolicy(lane);
			assertEquals(original, latest); assertNotSame(original, latest, "returning to the same value is a new desired owner");
			handoff.accepted(OWNER, original); assertSame(latest, f.pendingPolicy(lane), "an old equal-valued completion cannot clear the replacement");
			f.tick(10);
			assertTrue(f.pendingPolicies().isEmpty()); assertEquals(0, f.queuedPolicyCount());
			assertEquals(Optional.of(allow), f.client.authorization(lane, f.session().epoch, f.session().view, "entity_block"));
		}
	}

	@ParameterizedTest
	@CsvSource({"FLUID,true", "FLUID,false", "ITEM,true", "ITEM,false"})
	void successfulPolicySendThatChangesAuthorityBeforeReturnKeepsItsDesiredOwnerRetryable(InventoryResourceKind lane, boolean allow) throws Exception {
		try (var f = new Fixture(Map.of(lane.fieldId(), 2))) {
			f.open();
			if (allow) { f.player.permission = 0; f.tick(1); }
			for (int i = 0; i < 100; i++) f.backend.handle(OWNER, InventoryC2SPacket.hello(), 1, f.settings);
			f.player.permission = allow ? 2 : 0; f.tick(2);
			var desired = f.pendingPolicy(lane);
			f.afterSend = packet -> {
				if (packet != desired) return;
				f.afterSend = null; f.player.permission = allow ? 0 : 2;
				PresentationServer.tick(f.server, f.store);
			};
			f.tick(10);
			assertSame(desired, f.pendingPolicy(lane), "normal transport return alone cannot complete a now-unauthorized desired value");
			assertFalse(f.publisher().controlQueued(OWNER, desired));
			f.player.permission = allow ? 2 : 0; f.tick(11);
			assertTrue(f.pendingPolicies().isEmpty()); assertFalse(f.publisher().controlQueued(OWNER, desired));
			assertEquals(2, f.packets.stream().filter(packet -> packet == desired).count(), "only the still-current successful handoff completes retry");
		}
	}

	@ParameterizedTest
	@CsvSource({"ITEM,VIEW", "FLUID,VIEW", "ITEM,EPOCH", "FLUID,EPOCH", "ITEM,DISCONNECT", "FLUID,DISCONNECT",
		"ITEM,REPLACE", "FLUID,REPLACE", "ITEM,CLOSE", "FLUID,CLOSE"})
	void retiredPolicyCompletionCannotClearOrReviveWorkAcrossLifecycleBoundaries(InventoryResourceKind lane, String boundary) throws Exception {
		try (var f = new Fixture(Map.of(lane.fieldId(), 2))) {
			f.open(); f.player.permission = 0; f.tick(1);
			for (int i = 0; i < 100; i++) f.backend.handle(OWNER, InventoryC2SPacket.hello(), 1, f.settings);
			f.player.permission = 2; f.tick(2);
			var retired = f.pendingPolicy(lane); var handoff = f.controlHandoff(); int sent = f.packets.size();
			switch (boundary) {
				case "VIEW" -> { f.policy.setPermissionLevels(Map.of(lane.fieldId(), 1)); f.tick(2); }
				case "EPOCH" -> { PresentationServer.reset(); PresentationServer.activate(f.server); f.negotiatePresentation(); f.tick(2); }
				case "DISCONNECT" -> f.backend.disconnect(OWNER);
				case "REPLACE" -> { f.backend.disconnect(OWNER); f.backend.handle(OWNER, InventoryC2SPacket.hello(), 2, f.settings); }
				case "CLOSE" -> f.backend.close();
				default -> throw new AssertionError(boundary);
			}
			if (boundary.equals("DISCONNECT") || boundary.equals("CLOSE")) {
				handoff.accepted(OWNER, retired); f.publisher().drainControls();
				assertTrue(((Map<?, ?>) value(f.backend, "sessions")).isEmpty()); assertEquals(0, f.queuedPolicyCount());
			} else {
				@Unmodifiable Map<?, ?> desired = Map.copyOf(f.pendingPolicies()); assertFalse(desired.isEmpty());
				assertNotSame(retired, f.pendingPolicy(lane));
				handoff.accepted(OWNER, retired);
				assertEquals(desired.size(), f.pendingPolicies().size());
				for (var entry : desired.entrySet()) assertSame(entry.getValue(), f.pendingPolicies().get(entry.getKey()));
				f.tick(10); assertTrue(f.pendingPolicies().isEmpty()); assertEquals(0, f.queuedPolicyCount());
			}
			assertTrue(f.packets.subList(sent, f.packets.size()).stream().noneMatch(packet -> packet == retired), "retired queued POLICY cannot send after the boundary");
		}
	}

	@ParameterizedTest
	@EnumSource(InventoryResourceKind.class)
	void actualTickMetadataRefreshUsesTheFixedMarkersOwnFreshDedicatedReceiptGrant(InventoryResourceKind tracked) throws Exception {
		try (var f = new Fixture(Map.of(InventoryPresentation.FLUIDS, 2))) {
			long request = f.open(); MarkerId marker = f.select(request, tracked); f.tick(1);
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.INVENTORY,
				f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).reduce((a, b) -> b).orElseThrow().content().kind());
			f.player.permission = 0; f.tick(2); f.presentation.clear();
			PresentationServer.updated(f.server, f.store.find(marker).orElseThrow());
			assertEquals(tracked == InventoryResourceKind.ITEM ? nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.INVENTORY
				: nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.SUPPRESSED, f.presentation.getLast().content().kind());
		}
	}

	private static Object value(Object object, String name) throws ReflectiveOperationException { return Objects.requireNonNull(field(object.getClass(), name).get(object)); }

	@NotNullByDefault
	private static final class State { int reads; int slots = 1; long amount = 81; }

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final Server server = DoubleChestFixture.allocate(Server.class);
		final World level = DoubleChestFixture.allocate(World.class);
		final Player player = DoubleChestFixture.allocate(Player.class);
		final Players players = DoubleChestFixture.allocate(Players.class);
		final PresentationSettings policy = ServerConfig.HANDLER.getConfig().getPresentation();
		final PresentationSettings savedPolicy = policy.deepCopy();
		final InventorySettings settings = InventorySettings.serverDefaults();
		final ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource());
		final List<PresentationS2CPacket> presentation = new ArrayList<>();
		final List<InventoryS2CPacket> packets = new ArrayList<>();
		final List<InventoryC2SPacket> requests = new ArrayList<>();
		final ClientInventory client = new ClientInventory(packet -> requests.add((InventoryC2SPacket) packet));
		final EnumMap<InventoryResourceKind, State> states = new EnumMap<>(InventoryResourceKind.class);
		final InventoryRuntime runtime;
		final InventoryBackend backend;
		boolean refuseResults, refuseData;
		@Nullable Consumer<InventoryS2CPacket> afterSend;

		Fixture(@Unmodifiable Map<String, Integer> permissions) throws Exception {
			server.players = players; server.world = level; players.player = player; player.level = level; level.server = server;
			player.permission = 2;
			policy.copyFrom(PresentationSettings.serverDefaults()); policy.setPermissionLevels(permissions);
			Network.receiver = this::receive;
			PresentationServer.reset(); PresentationServer.activate(server);
			negotiatePresentation();
			for (var kind : InventoryResourceKind.values()) states.put(kind, new State());
			runtime = new InventoryRuntime(input -> Optional.of(new InventorySourceAccess.Source() {
				@Override public SourceKey key() { return new SourceKey("test", "production-policy", "chest", input.viewKey()); }
				@Override public boolean valid() { return true; }
				@Override public boolean stableCursor() { return true; }
				@Override public int slots() { return states.get(input.resourceKind()).slots; }
				@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
				@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new AssertionError("resource reader required"); }
				@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
					states.get(input.resourceKind()).reads++;
					String id = input.resourceKind() == InventoryResourceKind.ITEM ? "minecraft:stone" : "minecraft:water";
					return InventoryResourceCodec.value(new InventoryScanner.Key(input.resourceKind(), id, "native"), states.get(input.resourceKind()).amount, id, null, false);
				}
			}), settings.pendingMemoryBytes());
			backend = new InventoryBackend(new InventoryBackend.Host() {
				@Override public Optional<InventoryBackend.Policy> policy(UUID id) { return PresentationServer.inventoryPolicy(id).map(p -> new InventoryBackend.Policy(p.epoch(), p.view(), p.types(), p.fluidTypes())); }
				@Override public Optional<InventoryBackend.Opened> open(UUID id, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
				@Override public boolean annotationAllowed(nx.pingwheel.common.presentation.inventory.InventorySourceInput input, String annotation) { return annotation.equals("attention"); }
				@Override public InventoryBackend.Created create(UUID id, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
					var type = TargetTypeCatalog.builtIn().findById(frozen.targetType()).orElseThrow();
					@Nullable var rejection = admission.prepare(frozen.target(), nx.pingwheel.common.marker.TargetKey.from(frozen.target()), type.id(), List.of(OWNER));
					if (rejection != null) return new InventoryBackend.Created(null, rejection);
					var marker = store.create(id, frozen.target(), type, type.defaultPingType(), new MarkerAnchor(1, 64, 3), 0, 10000, List.of(OWNER)).marker();
					return new InventoryBackend.Created(marker, null);
				}
				@Override public void publishCreated(InventoryBackend.Created created) {
					PresentationServer.created(server, created.marker(), nx.pingwheel.common.name.TargetNameJsonCodec.UNKNOWN, "owner", Map.of(PresentationBasic.ID,
						new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("\"chest\"")), false)));
				}
				@Override public boolean knows(UUID id, MarkerId marker) { return PresentationServer.inventoryKnows(id, marker); }
				@Override public boolean authorized(SyncPublisher.Context context) { return true; }
				@Override public int encodedBytes(InventoryS2CPacket packet) { return packet.encodedSize(nx.pingwheel.common.presentation.inventory.InventoryDomainCodec.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES); }
				@Override public void send(UUID id, InventoryS2CPacket packet) {
					if (refuseResults && (packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT)) throw new IllegalStateException("result transport deferred");
					if (refuseData && !packet.entries().isEmpty()) throw new IllegalStateException("quantity transport deferred");
					packets.add(packet); client.accept(packet);
					@Nullable Consumer<InventoryS2CPacket> effect = afterSend;
					if (effect != null) effect.accept(packet);
				}
			}, runtime, 1);
			field(InventoryServer.class, "active").set(null, server); field(InventoryServer.class, "backend").set(null, backend);
			backend.handle(OWNER, InventoryC2SPacket.hello(), 0, settings);
		}
		private void receive(IPacket packet) {
			if (!(packet instanceof PresentationS2CPacket p)) return;
			presentation.add(p);
			if (p.kind() == PresentationS2CPacket.Kind.RESET) client.presentationReset(p.epoch(), p.view());
			if (p.kind() == PresentationS2CPacket.Kind.CREATED) client.markerCreated(p.snapshot());
		}
		long open() {
			long request = client.open(TARGET, BlockFace.NORTH, "entity_block");
			backend.handle(OWNER, requests.getLast(), server.now, settings); tick(server.now);
			return request;
		}
		void tick(int tick) { server.now = tick; PresentationServer.tick(server, store); backend.tick(tick, settings); }
		void negotiatePresentation() { PresentationServer.negotiate(server, player, PresentationC2SPacket.hello(Map.of(
			PresentationBasic.ID, 1, InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA)), store); }
		PresentationServer.Session session() { return Objects.requireNonNull(sessions().get(OWNER)); }
		long resets() { return presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.RESET).count(); }
		long opens() { return requests.stream().filter(p -> p.kind() == InventoryC2SPacket.Kind.OPEN).count(); }
		long closes() { return requests.stream().filter(p -> p.kind() == InventoryC2SPacket.Kind.CLOSE).count(); }
		InventoryS2CPacket lastPreview(long request, InventoryResourceKind kind) { return packets.stream().filter(p -> p.requestId() == request && p.resourceKind() == kind && p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).reduce((a, b) -> b).orElseThrow(); }
		MarkerId select(long request, InventoryResourceKind kind) {
			assertEquals(ClientInventory.DispatchOutcome.SENT, client.select(client.selectable(request, kind).getFirst(), "attention", packet -> {
				requests.add(packet); return ClientInventory.DispatchOutcome.SENT;
			}));
			backend.handle(OWNER, requests.getLast(), server.now, settings);
			return store.allMarkers().getLast().id();
		}
		Object trackingLease(MarkerId marker) throws ReflectiveOperationException { return Objects.requireNonNull(((Map<?, ?>) value(backend, "tracking")).get(marker)); }
		Object recipient(Object lease) throws ReflectiveOperationException { return Objects.requireNonNull(((Map<?, ?>) value(lease, "recipients")).get(OWNER)); }
		Object publisherState(SyncPublisher.Context context) throws ReflectiveOperationException {
			return ((Map<?, ?>) value(value(backend, "publisher"), "states")).values().stream().filter(state -> {
				try { return value(state, "context").equals(context); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			}).findFirst().orElseThrow();
		}
		Map<?, ?> pendingPolicies() throws ReflectiveOperationException { return (Map<?, ?>) value(Objects.requireNonNull(((Map<?, ?>) value(backend, "sessions")).get(OWNER)), "pendingPolicies"); }
		InventoryS2CPacket pendingPolicy(InventoryResourceKind lane) throws ReflectiveOperationException { return (InventoryS2CPacket) Objects.requireNonNull(pendingPolicies().get(lane)); }
		InventorySyncPublisher publisher() throws ReflectiveOperationException { return (InventorySyncPublisher) value(backend, "publisher"); }
		InventorySyncPublisher.ControlHandoff controlHandoff() throws ReflectiveOperationException { return (InventorySyncPublisher.ControlHandoff) value(publisher(), "controlHandoff"); }
		@Nullable Object queuedPolicy(InventoryResourceKind lane) throws ReflectiveOperationException { return ((Map<?, ?>) value(value(backend, "publisher"), "controls")).get(OWNER + "/POLICY/" + lane + "/0"); }
		int queuedPolicyCount() throws ReflectiveOperationException { return (int) ((Map<?, ?>) value(value(backend, "publisher"), "controls")).keySet().stream().filter(key -> key.toString().startsWith(OWNER + "/POLICY/")).count(); }
		@Override public void close() {
			InventoryServer.reset(); PresentationServer.reset(); BudgetDelayMonitoring.reset(); Network.receiver = null;
			policy.copyFrom(savedPolicy); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@SuppressWarnings("unchecked")
	private static Map<UUID, PresentationServer.Session> sessions() {
		try { return (Map<UUID, PresentationServer.Session>) field(PresentationServer.class, "SESSIONS").get(null); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static Field field(Class<?> owner, String name) throws NoSuchFieldException { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }

	/** Common-test-only recording transport, selected through the normal service boundary. */
	@NotNullByDefault
	public static final class Network implements IPlatformNetworkService {
		static @Nullable Consumer<IPacket> receiver;
		@Override public void sendToServer(IPacket packet) { throw new AssertionError("server fixture sent C2S"); }
		@Override public void sendToClient(IPacket packet, ServerPlayer player) { Objects.requireNonNull(receiver, "no recording transport").accept(packet); }
	}
	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private @Nullable World world;
		private int now;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public int getTickCount() { return now; }
		@Override public DedicatedPlayerList getPlayerList() { return Objects.requireNonNull(players); }
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
		@Override public @Nullable ServerLevel getLevel(ResourceKey<Level> dimension) { return dimension.equals(Level.OVERWORLD) ? world : null; }
	}
	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private @Nullable Player player;
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return id.equals(OWNER) ? player : null; }
		@Override public @Unmodifiable List<ServerPlayer> getPlayers() { return List.of(Objects.requireNonNull(player)); }
	}
	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private @Nullable World level;
		private int permission;
		private Player() { super(null, null, null, null); }
		@Override public UUID getUUID() { return OWNER; }
		@Override public GameProfile getGameProfile() { return new GameProfile(OWNER, "owner"); }
		@Override public ServerLevel serverLevel() { return Objects.requireNonNull(level); }
		@Override public boolean hasPermissions(int required) { return permission >= required; }
	}
	@NotNullByDefault
	private static final class World extends ServerLevel {
		private @Nullable Server server;
		private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public MinecraftServer getServer() { return Objects.requireNonNull(server); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public boolean isLoaded(BlockPos pos) { return true; }
		@Override public boolean hasChunkAt(BlockPos pos) { return true; }
		@Override public BlockState getBlockState(BlockPos pos) { return Blocks.CHEST.defaultBlockState(); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
	}
}
