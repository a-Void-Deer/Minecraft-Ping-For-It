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

	@Test void actualSelectedCreationWireContainsThePreviewResourceBeforeTracking() throws Exception {
		try (var f = new Fixture(Map.of())) {
			f.states.get(InventoryResourceKind.ITEM).amount = 5;
			long request = f.open(); MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED
				&& p.snapshot().id().equals(marker)).findFirst().orElseThrow();
			var encoded = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
			try {
				created.write(encoded);
				String wire = encoded.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
				assertTrue(wire.contains("minecraft:stone"), "atomic CREATED must carry selected Preview resource before Tracking has run");
			} finally { encoded.release(); }
		}
	}

	@Test void memoryRefusalPrecedesBasicEncodingAndPacketConstruction() throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open(); long revision = f.session().revision;
			f.beforePublish = () -> f.runtime.memory().setCap(f.runtime.memory().retained() + f.runtime.memory().reserved());
			MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			assertEquals(revision, f.session().revision, "no packet factory/Basic encode before construction admission");
			assertFalse(PresentationServer.inventoryKnows(OWNER, marker));
			assertTrue(f.presentation.stream().noneMatch(p -> p.kind() == PresentationS2CPacket.Kind.CREATED));
			f.beforePublish = null; f.tick(1);
			assertTrue(PresentationServer.inventoryKnows(OWNER, marker), "real pressure defers rather than suppressing the receipt");
		}
	}

	@Test void sourceCallbackCannotLeakRevokedBasicCustomName() throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open(); f.refuseCreated = true;
			MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			f.basicSource(marker).value = new PresentationSection(PresentationBasic.ID, 1, Map.of(
				PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Chest\"}"),
				PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("private-custom-name")), false);
			boolean[] checked = {false};
			f.states.get(InventoryResourceKind.ITEM).onValidation = () -> {
				checked[0] = true; f.policy.setPermissionLevels(Map.of(PresentationBasic.CUSTOM_NAME, 4));
			};
			f.refuseCreated = false; f.tick(1);
			assertTrue(checked[0], "actual post-encoding source validation ran");
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow();
			assertFalse(decodedBasic(created).fields().containsKey(PresentationBasic.CUSTOM_NAME),
				"CREATED must not disclose any Basic field revoked by the final source callback");
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.INVENTORY, created.content().kind(),
				"still-authorized creation snapshot is not suppressed with an unrelated Basic field");
			assertTrue(PresentationServer.inventoryKnows(OWNER, marker));
		}
	}

	@Test void deferredKnownMetadataRefreshMustReachTransportOnRetry() throws Exception {
		try (var nativeSource = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest.Fixture(1, 1, true);
			var f = new Fixture(Map.of(), nativeSource)) {
			long request = f.open(); MarkerId marker = f.select(request, InventoryResourceKind.FLUID);
			var anchor = new MarkerAnchor(10, 70, 30);
			var updated = f.store.updateExternalTarget(marker, (Target.ExternalBlockTarget) nativeSource.target, anchor).orElseThrow();
			f.refuseCreated = true; PresentationServer.updated(f.server, updated);
			assertTrue(f.session().baselinePending);
			f.refuseCreated = false; for (int tick = 1; tick <= 3; tick++) f.tick(tick);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).toList();
			assertEquals(2, created.size(), "a known same-view initial does not complete a refused metadata refresh");
			assertEquals(anchor, created.getLast().snapshot().anchor());
			assertFalse(f.session().baselinePending);
		}
	}

	@Test void sourceCallbackChildDenialRebuildsAnnotationsWithoutSuppressingAllowedContent() throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open(); MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			var ref = new PresentationPropertyRef(PresentationBasic.ID, PresentationBasic.BLOCK_STATE, List.of("private"));
			var retained = new PresentationSection(PresentationBasic.ID, 1, Map.of(
				PresentationBasic.NAME, new PresentationValue.Text("\"chest\""),
				PresentationBasic.BLOCK_STATE, new PresentationValue.RecordValue(Map.of("private", new PresentationValue.Flag(true)))), false, Map.of(ref, "danger"));
			f.session().sent.get(marker.value()).sections.put(PresentationBasic.ID, retained);
			f.basicSource(marker).observation = PresentationServer.Source.Observation.FAILED;
			f.states.get(InventoryResourceKind.ITEM).onValidation = () -> {
				var rules = f.policy.rulesFor("entity_block"); rules.setChildBlack(List.of(ref)); f.policy.setRules("entity_block", rules);
			};
			long revision = f.session().revision;
			PresentationServer.updated(f.server, f.store.find(marker).orElseThrow());
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).toList();
			assertEquals(2, created.size(), "revoked annotation's first encoded body never reaches transport");
			assertTrue(decodedBasic(created.getLast()).fields().containsKey(PresentationBasic.BLOCK_STATE));
			assertTrue(decodedBasic(created.getLast()).annotations().isEmpty());
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.INVENTORY, created.getLast().content().kind());
			assertEquals(revision + 2, f.session().revision, "the rebuilt packet is independently constructed after a second admission");
		}
	}

	@Test void repeatedPostencodeNarrowingStopsAfterTwoPaidAttemptsThenRetries() throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open(); f.refuseCreated = true;
			MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			f.basicSource(marker).value = new PresentationSection(PresentationBasic.ID, 1, Map.of(
				PresentationBasic.NAME, new PresentationValue.Text("\"chest\""),
				PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("private"),
				PresentationBasic.HEALTH, new PresentationValue.NumberValue(9)), false);
			int[] cuts = {0};
			f.states.get(InventoryResourceKind.ITEM).onValidation = new Runnable() {
				private long revision = -1;
				@Override public void run() {
					if (revision != f.session().revision) {
						revision = f.session().revision; cuts[0]++;
						f.policy.setPermissionLevels(cuts[0] == 1 ? Map.of(PresentationBasic.CUSTOM_NAME, 4)
							: Map.of(PresentationBasic.CUSTOM_NAME, 4, PresentationBasic.HEALTH, 4));
					}
					if (cuts[0] < 2) f.states.get(InventoryResourceKind.ITEM).onValidation = this;
				}
			};
			f.refuseCreated = false; long revision = f.session().revision; f.tick(1);
			assertEquals(2, cuts[0]); assertEquals(revision + 2, f.session().revision);
			assertFalse(PresentationServer.inventoryKnows(OWNER, marker));
			assertTrue(f.presentation.stream().noneMatch(p -> p.kind() == PresentationS2CPacket.Kind.CREATED));
			assertEquals(0, f.runtime.memory().reserved());
			f.tick(2);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow();
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.INVENTORY, created.content().kind());
			assertFalse(decodedBasic(created).fields().containsKey(PresentationBasic.CUSTOM_NAME));
			assertFalse(decodedBasic(created).fields().containsKey(PresentationBasic.HEALTH));
		}
	}

	@Test void narrowedBasicRebuildCannotConstructBeforeANewMemoryAdmission() throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open(); f.refuseCreated = true; MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			f.basicSource(marker).value = new PresentationSection(PresentationBasic.ID, 1, Map.of(
				PresentationBasic.NAME, new PresentationValue.Text("\"chest\""),
				PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("private")), false);
			f.states.get(InventoryResourceKind.ITEM).onValidation = () -> {
				f.policy.setPermissionLevels(Map.of(PresentationBasic.CUSTOM_NAME, 4)); f.runtime.memory().setCap(0);
			};
			f.refuseCreated = false; long revision = f.session().revision; f.tick(1);
			assertEquals(revision + 1, f.session().revision, "second Basic construction cannot reuse the first envelope's grant");
			assertFalse(PresentationServer.inventoryKnows(OWNER, marker));
			assertTrue(f.presentation.stream().noneMatch(p -> p.kind() == PresentationS2CPacket.Kind.CREATED));
			assertEquals(0, f.runtime.memory().reserved()); f.tick(2);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow();
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.INVENTORY, created.content().kind());
			assertFalse(decodedBasic(created).fields().containsKey(PresentationBasic.CUSTOM_NAME));
		}
	}

	@Test void deferredKnownRefreshCoalescesToLatestCanonicalLocatorAndAnchor() throws Exception {
		try (var nativeSource = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest.Fixture(1, 1, true);
			var f = new Fixture(Map.of(), nativeSource)) {
			long request = f.open(); MarkerId marker = f.select(request, InventoryResourceKind.FLUID);
			var target = (Target.ExternalBlockTarget) nativeSource.target;
			f.refuseCreated = true;
			PresentationServer.updated(f.server, f.store.updateExternalTarget(marker, target, new MarkerAnchor(10, 70, 30)).orElseThrow());
			var latestTarget = Target.ExternalBlockTarget.committed(target.dimensionId(), target.providerId(), target.stableTargetId(),
				target.expectedBlockRegistryId(), "latest-locator", target.hasBlockEntity());
			var latest = f.store.updateExternalTarget(marker, latestTarget, new MarkerAnchor(20, 80, 40)).orElseThrow();
			PresentationServer.updated(f.server, latest);
			f.refuseCreated = false; for (int tick = 1; tick <= 4; tick++) f.tick(tick);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).toList();
			assertEquals(2, created.size(), "refused refreshes retain no historical send queue");
			assertEquals(latest.anchor(), created.getLast().snapshot().anchor());
			assertEquals("latest-locator", ((Target.ExternalBlockTarget) created.getLast().snapshot().target()).providerLocator());
			assertEquals(created.getFirst().content().inventorySnapshot(), created.getLast().content().inventorySnapshot());
			assertFalse(f.session().baselinePending);
		}
	}

	@Test void successfulOlderHandoffCannotClearAReentrantNewerMetadataRefresh() throws Exception {
		try (var nativeSource = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest.Fixture(1, 1, true);
			var f = new Fixture(Map.of(), nativeSource)) {
			long request = f.open(); MarkerId marker = f.select(request, InventoryResourceKind.FLUID);
			var target = (Target.ExternalBlockTarget) nativeSource.target;
			var latest = new MarkerAnchor(20, 80, 40);
			Network.admitRecipient = (packet, recipient) -> {
				Network.admitRecipient = null;
				PresentationServer.updated(f.server, f.store.updateExternalTarget(marker, target, latest).orElseThrow());
				return true;
			};
			PresentationServer.updated(f.server, f.store.updateExternalTarget(marker, target, new MarkerAnchor(10, 70, 30)).orElseThrow());
			for (int tick = 1; tick <= 3; tick++) f.tick(tick);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).toList();
			assertEquals(3, created.size()); assertEquals(latest, created.getLast().snapshot().anchor());
			assertFalse(f.session().baselinePending);
		}
	}

	@ParameterizedTest @CsvSource({"REMOVE", "EXPIRE", "VIEW", "DISCONNECT"})
	void pendingKnownRefreshIsCancelledByItsLifecycleOrReplacedView(String boundary) throws Exception {
		try (var nativeSource = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest.Fixture(1, 1, true);
			var f = new Fixture(Map.of(), nativeSource)) {
			long request = f.open(); MarkerId marker = f.select(request, InventoryResourceKind.FLUID); long view = f.session().view;
			f.refuseCreated = true;
			var updated = f.store.updateExternalTarget(marker, (Target.ExternalBlockTarget) nativeSource.target, new MarkerAnchor(10, 70, 30)).orElseThrow();
			PresentationServer.updated(f.server, updated);
			if (boundary.equals("REMOVE")) {
				f.store.removeByServer(marker, nx.pingwheel.common.marker.MarkerRemovalReason.CANCELLED);
				f.backend.remove(marker); PresentationServer.removed(f.player, marker, nx.pingwheel.common.marker.MarkerRemovalReason.CANCELLED);
				PresentationServer.forget(marker);
			} else if (boundary.equals("VIEW")) f.policy.setPermissionLevels(Map.of(PresentationBasic.CUSTOM_NAME, 4));
			else if (boundary.equals("DISCONNECT")) {
				PresentationServer.disconnect(OWNER); f.backend.disconnect(OWNER); f.players.player = null;
			}
			f.refuseCreated = false; int now = boundary.equals("EXPIRE") ? 10001 : 1;
			for (int tick = now; tick < now + 3; tick++) f.tick(tick);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).toList();
			assertEquals(1, created.stream().filter(p -> p.view() == view).count(), "old-view pending refresh never reappears");
			assertEquals(boundary.equals("VIEW") ? 2 : 1, created.size(), "view baseline may publish once; expired/removed/disconnected waits cannot");
		}
	}

	@Test void nativeFluidScopeChangePermanentlySuppressesLateCreationHistory() throws Exception {
		try (var nativeSource = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest.Fixture(1, 1, true);
			var f = new Fixture(Map.of(), nativeSource)) {
			UUID late = new UUID(801, 11); f.audience = List.of(OWNER, late); nativeSource.amount = 5;
			long request = f.open(); MarkerId marker = f.select(request, InventoryResourceKind.FLUID);
			assertNotNull(f.backend.creationSnapshot(marker).orElseThrow());
			f.tick(1); nativeSource.scope = "second-sublevel"; f.tick(2);
			assertTrue(f.backend.creationSnapshot(marker).isEmpty());
			for (int tick = 3; tick <= 10; tick++) f.tick(tick);
			assertFalse((boolean) value(f.trackingLease(marker), "invalid"), "same lease source recomputes, not marker removal");
			f.join(late); f.players.others.get(late).permission = 2; f.tick(11);
			var packet = f.deliveries.stream().filter(d -> d.recipient().equals(late)
				&& d.packet().kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow().packet();
			assertNull(packet.content().inventorySnapshot());
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.SUPPRESSED, packet.content().kind());
			assertEquals(0, nativeSource.liveAccess);
		}
	}

	@ParameterizedTest @CsvSource({"ROUTE", "LAYOUT"})
	void nativeFluidSameMarkerKeyCannotAuthorizeOldCreationAfterSourceShapeOrRouteReplacement(String boundary) throws Exception {
		try (var nativeSource = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest.Fixture(1,1,true);
			var f = new Fixture(Map.of(),nativeSource)) {
			long request=f.open(); f.refuseCreated=true;
			MarkerId marker=f.select(request,InventoryResourceKind.FLUID); var key=f.store.find(marker).orElseThrow().targetKey();
			f.tick(1);
			if(boundary.equals("ROUTE")) nativeSource.routeIdentity=new Object(); else nativeSource.layoutToken="replacement-shape";
			f.refuseCreated=false; f.tick(2);
			assertEquals(key,f.store.find(marker).orElseThrow().targetKey());
			var packet=f.presentation.stream().filter(p->p.kind()==PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow();
			assertNull(packet.content().inventorySnapshot()); assertTrue(f.backend.creationSnapshot(marker).isEmpty());
			assertEquals(0,nativeSource.liveAccess);
		}
	}

	@ParameterizedTest @EnumSource(InventoryResourceKind.class)
	void trackingExactToAggregateRetirementCannotRepublishOldCreationSnapshot(InventoryResourceKind lane) throws Exception {
		try (var f = new Fixture(Map.of())) {
			UUID late = new UUID(801, 12); f.audience = List.of(OWNER, late);
			f.states.get(lane).safeLabel = true;
			long request = f.open(); MarkerId marker = f.select(request, lane);
			assertFalse(f.backend.creationSnapshot(marker).orElseThrow().aggregate());
			f.states.get(lane).aggregate = true; f.tick(1);
			assertTrue(f.backend.creationSnapshot(marker).isEmpty());
			f.states.get(lane).aggregate = false; for (int tick = 2; tick <= 10; tick++) f.tick(tick);
			f.join(late); f.players.others.get(late).permission = 2; f.tick(11);
			var packet = f.deliveries.stream().filter(d -> d.recipient().equals(late)
				&& d.packet().kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow().packet();
			assertNull(packet.content().inventorySnapshot()); assertTrue(f.backend.creationSnapshot(marker).isEmpty());
		}
	}

	@ParameterizedTest @EnumSource(InventoryResourceKind.class)
	void physicalPreviewCompletionWithoutCurrentCutHandoffCreatesUncertainImmediately(InventoryResourceKind lane) throws Exception {
		try (var f = new Fixture(Map.of())) {
			f.states.get(lane).amount = 5; f.states.get(lane).slots = 2; f.states.get(lane).oneValue = true; f.states.get(lane).verified = true;
			f.settings.setPhysicalSlotsPerTick(IntLimit.finite(lane == InventoryResourceKind.ITEM ? 1 : 2));
			long request = f.open();
			assertFalse(f.client.preview(request, lane).completeScan());
			Object preview = f.previewState(request, lane); long original = (long) value(preview, "revision");
			f.refusePreview = true;
			f.settings.setPhysicalSlotsPerTick(IntLimit.finite(32)); f.tick(1);
			assertTrue((long) value(preview, "revision") > original);
			assertEquals(nx.pingwheel.common.presentation.source.CaptureResult.Completeness.COMPLETE,
				((nx.pingwheel.common.presentation.source.CaptureResult) value(value(preview, "last"), "result")).completeness());
			var context = (SyncPublisher.Context) value(preview, "context");
			assertFalse(f.publisher().previewCutComplete(context, original));
			assertFalse(f.publisher().previewCutComplete(context, (long) value(preview, "revision")), "older partial handoff cannot certify the newer complete cut");
			MarkerId marker = f.select(request, lane);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED
				&& p.snapshot().id().equals(marker)).findFirst().orElseThrow();
			var snapshot = Objects.requireNonNull(created.content().inventorySnapshot());
			assertEquals(5, snapshot.amount()); assertFalse(snapshot.previewComplete()); assertTrue(snapshot.quantityUncertain());
		}
	}

	@Test void mixedRecipientDeferralsPagePastBlockedInventoryWithoutBlockingSiblingOrPlainMarkers() throws Exception {
		try (var f = new Fixture(Map.of())) {
			UUID a = new UUID(802, 1), b = new UUID(802, 2); f.join(a); f.join(b);
			f.players.others.get(a).permission = 2; f.players.others.get(b).permission = 2; f.tick(1);
			f.audience = List.of(OWNER, a, b);
			var blocked = new java.util.HashSet<String>(); f.blockedCreated = blocked;
			long firstRequest = f.open();
			f.beforePublish = () -> { for (UUID id : List.of(a, b)) blocked.add(id + "/" + f.store.allMarkers().getLast().id().value()); };
			MarkerId first = f.select(firstRequest, InventoryResourceKind.ITEM);
			long secondRequest = f.open(); f.beforePublish = () -> blocked.add(b + "/" + f.store.allMarkers().getLast().id().value());
			MarkerId second = f.select(secondRequest, InventoryResourceKind.FLUID); f.beforePublish = null;
			var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
			var plain = f.store.create(OWNER, TARGET, type, type.defaultPingType(), new MarkerAnchor(1,64,3), 1,10000,f.audience).marker();
			PresentationServer.created(f.server, plain, nx.pingwheel.common.name.TargetNameJsonCodec.UNKNOWN, "owner", Map.of(PresentationBasic.ID,
				new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME,new PresentationValue.Text("chest")),false)));
			f.tick(2);
			assertFalse(PresentationServer.inventoryKnows(a, first)); assertFalse(PresentationServer.inventoryKnows(b, first));
			assertTrue(PresentationServer.inventoryKnows(a, second)); assertFalse(PresentationServer.inventoryKnows(b, second));
			assertTrue(PresentationServer.inventoryKnows(a, plain.id())); assertTrue(PresentationServer.inventoryKnows(b, plain.id()));
			blocked.remove(a + "/" + first.value()); f.tick(3);
			assertTrue(PresentationServer.inventoryKnows(a, first)); assertFalse(PresentationServer.inventoryKnows(b, first));
			blocked.clear(); f.tick(4); f.tick(5);
			assertTrue(PresentationServer.inventoryKnows(b, first)); assertTrue(PresentationServer.inventoryKnows(b, second));
			for (UUID id : List.of(a,b)) for (MarkerId marker : List.of(first,second,plain.id()))
				assertEquals(1, f.deliveries.stream().filter(d -> d.recipient().equals(id) && d.packet().kind()==PresentationS2CPacket.Kind.CREATED
					&& d.packet().snapshot().id().equals(marker)).count(), "known current-view records are skipped on retry passes");
		}
	}

	@ParameterizedTest
	@CsvSource({"ITEM,false", "ITEM,true", "FLUID,false", "FLUID,true"})
	void selectedPreviewCreatesAtomicReceiptBeforeTheFirstTrackingTick(InventoryResourceKind lane, boolean partial) throws Exception {
		try (var f = new Fixture(Map.of())) {
			f.states.get(lane).amount = 5; f.states.get(lane).verified = true;
			if (partial) { f.states.get(lane).slots = 8; f.settings.setPhysicalSlotsPerTick(IntLimit.finite(lane == InventoryResourceKind.ITEM ? 1 : 2)); }
			long request = f.open();
			int reads = f.states.get(lane).reads;
			MarkerId marker = f.select(request, lane);
			var created = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED
				&& p.snapshot().id().equals(marker)).findFirst().orElseThrow(() -> new AssertionError("SELECT must hand off atomic CREATED before a Tracking tick"));
			var snapshot = Objects.requireNonNull(created.content().inventorySnapshot(), "atomic inventory snapshot");
			assertEquals(lane, snapshot.resourceKind()); assertEquals(5, snapshot.amount());
			assertEquals(!partial, snapshot.previewComplete());
			assertEquals(partial || !f.states.get(lane).verified, snapshot.quantityUncertain());
			assertEquals(reads, f.states.get(lane).reads, "receipt construction never rereads contents");
			f.states.get(lane).amount = 9;
			for (int tick = 1; tick <= 30; tick++) f.tick(tick);
			assertEquals(snapshot, value(f.trackingLease(marker), "creationSnapshot"));
			assertEquals(5, snapshot.amount(), "Tracking completion cannot rewrite creation history");
		}
	}

	@ParameterizedTest
	@CsvSource({"ITEM,false", "ITEM,true", "FLUID,false", "FLUID,true"})
	void twentyFourIndependentHellosCannotDelayCreationBehindRecipientPolicy(InventoryResourceKind lane, boolean partial) throws Exception {
		try (var f = new Fixture(Map.of(lane.fieldId(), 2))) {
			f.states.get(lane).amount = 5;
			if (partial) { f.states.get(lane).slots = 8; f.settings.setPhysicalSlotsPerTick(IntLimit.finite(lane == InventoryResourceKind.ITEM ? 1 : 2)); }
			long request = f.open();
			f.settings.getPreview().setGlobalByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(new java.math.BigDecimal("0.125")));
			for (int index = 0; index < 24; index++) f.join(new UUID(800, index));
			Player recipient = f.players.others.values().iterator().next();
			var recipientSession = sessions().get(recipient.getUUID());
			assertNotNull(recipientSession);
			assertFalse(recipientSession.inventoryTypes.contains("entity_block") && lane == InventoryResourceKind.ITEM
				|| recipientSession.fluidTypes.contains("entity_block") && lane == InventoryResourceKind.FLUID);
			for (Player joined : f.players.others.values()) joined.permission = 2;
			f.tick(0);
			assertTrue(f.publisher().controlQueued(recipient.getUUID(), f.pendingPolicy(recipient.getUUID(), lane)), "fresh POLICY must really be queued");
			f.audience = List.of(OWNER, recipient.getUUID());
			MarkerId marker = f.select(request, lane);
			var created = f.deliveries.stream().filter(d -> d.recipient().equals(recipient.getUUID())
				&& d.packet().kind() == PresentationS2CPacket.Kind.CREATED && d.packet().snapshot().id().equals(marker)).findFirst().orElseThrow();
			var snapshot = Objects.requireNonNull(created.packet().content().inventorySnapshot());
			assertEquals(5, snapshot.amount()); assertEquals(!partial, snapshot.previewComplete());
			if (partial) assertTrue(snapshot.quantityUncertain());
			assertTrue(f.publisher().controlQueued(recipient.getUUID(), f.pendingPolicy(recipient.getUUID(), lane)), "CREATED precedes POLICY handoff");
		}
	}

	@ParameterizedTest
	@CsvSource({"ITEM,DEFER", "FLUID,DEFER", "ITEM,THROW", "FLUID,THROW"})
	void actualCanonicalRetryRetainsCreationAfterARefusedOrThrowingEncodedHandoff(InventoryResourceKind lane, String failure) throws Exception {
		try (var f = new Fixture(Map.of())) {
			f.states.get(lane).amount = 5;
			long request = f.open();
			f.refuseCreated = true; f.throwCreated = failure.equals("THROW");
			MarkerId marker = f.select(request, lane);
			assertFalse(PresentationServer.inventoryKnows(OWNER, marker));
			assertTrue(f.session().baselinePending); assertEquals(marker.value(), f.session().blockedInitial);
			f.tick(1); assertFalse(PresentationServer.inventoryKnows(OWNER, marker));
			f.states.get(lane).amount = 9; f.refuseCreated = false; f.throwCreated = false;
			f.tick(2);
			var initial = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow();
			assertEquals(5, Objects.requireNonNull(initial.content().inventorySnapshot()).amount());
			assertTrue(PresentationServer.inventoryKnows(OWNER, marker));
			f.tick(3);
			assertEquals(1, f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).count());
		}
	}

	@ParameterizedTest
	@EnumSource(InventoryResourceKind.class)
	void expiredDeferredInitialIsNeverRetried(InventoryResourceKind lane) throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open(); f.refuseCreated = true;
			MarkerId marker = f.select(request, lane); f.refuseCreated = false;
			f.tick(10001);
			assertFalse(PresentationServer.inventoryKnows(OWNER, marker));
			assertTrue(f.presentation.stream().noneMatch(p -> p.kind() == PresentationS2CPacket.Kind.CREATED));
			assertFalse(f.session().baselinePending);
		}
	}

	@ParameterizedTest
	@EnumSource(InventoryResourceKind.class)
	void lateFirstRecipientReceivesCreationFiveAfterTrackingNine(InventoryResourceKind lane) throws Exception {
		try (var f = new Fixture(Map.of())) {
			UUID late = new UUID(801, 1); f.audience = List.of(OWNER, late);
			f.states.get(lane).amount = 5; long request = f.open(); MarkerId marker = f.select(request, lane);
			f.states.get(lane).amount = 9; for (int tick = 1; tick <= 8; tick++) f.tick(tick);
			assertEquals(9, f.client.tracking(marker).entries().getFirst().count());
			f.join(late); f.players.others.get(late).permission = 2; f.tick(9);
			var packet = f.deliveries.stream().filter(d -> d.recipient().equals(late) && d.packet().kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow().packet();
			assertEquals(5, Objects.requireNonNull(packet.content().inventorySnapshot()).amount());
		}
	}

	@ParameterizedTest
	@EnumSource(InventoryResourceKind.class)
	void invalidCreationNeverResurrectsItsSnapshotForALateFirstRecipient(InventoryResourceKind lane) throws Exception {
		try (var f = new Fixture(Map.of())) {
			UUID late = new UUID(801, 2); f.audience = List.of(OWNER, late);
			long request = f.open(); MarkerId marker = f.select(request, lane);
			f.states.get(lane).valid = false; f.tick(1);
			assertTrue(f.backend.creationSnapshot(marker).isEmpty());
			f.states.get(lane).valid = true; f.tick(5); f.join(late); f.players.others.get(late).permission = 2; f.tick(6);
			var packet = f.deliveries.stream().filter(d -> d.recipient().equals(late)
				&& d.packet().kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow().packet();
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.SUPPRESSED, packet.content().kind());
			assertNull(packet.content().inventorySnapshot());
		}
	}

	@ParameterizedTest
	@CsvSource({"ITEM,SOURCE", "FLUID,SOURCE", "ITEM,NAME", "FLUID,NAME", "ITEM,LANE", "FLUID,LANE"})
	void freshSourceCallbackRevocationAfterEncodingSuppressesTheAtomicContent(InventoryResourceKind lane, String boundary) throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open();
			f.beforePublish = () -> f.states.get(lane).onValidation = () -> {
				if (boundary.equals("SOURCE")) f.states.get(lane).valid = false;
				else { f.policy.setPermissionLevels(Map.of(boundary.equals("NAME") ? PresentationBasic.NAME : lane.fieldId(), 2)); f.player.permission = 0; }
			};
			MarkerId marker = f.select(request, lane);
			var packet = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow();
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.SUPPRESSED, packet.content().kind());
			assertNull(packet.content().inventorySnapshot()); assertTrue(PresentationServer.inventoryKnows(OWNER, marker));
			if (boundary.equals("SOURCE")) {
				assertTrue(f.backend.creationSnapshot(marker).isEmpty());
				f.states.get(lane).valid = true; f.tick(1);
				assertTrue(f.backend.creationSnapshot(marker).isEmpty(), "later recovery cannot restore old chat eligibility");
			}
		}
	}

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

	@ParameterizedTest @CsvSource({"REMOVE", "DISCONNECT", "CLOSE"})
	void reentrantSourceLifecycleChangeRefundsInitialOwnershipBeforeCleanup(String boundary) throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open();
			f.beforePublish = () -> f.states.get(InventoryResourceKind.ITEM).onValidation = () -> {
				if (boundary.equals("REMOVE")) f.backend.remove(f.store.allMarkers().getLast().id());
				else if (boundary.equals("DISCONNECT")) f.backend.disconnect(OWNER);
				else f.backend.close();
			};
			MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			assertFalse(PresentationServer.inventoryKnows(OWNER, marker));
			assertTrue(f.presentation.stream().noneMatch(p -> p.kind() == PresentationS2CPacket.Kind.CREATED));
			assertTrue(((Map<?, ?>) value(f.backend, "tracking")).isEmpty());
			assertEquals(0, f.runtime.memory().reserved());
		}
	}

	@Test void permissionSuppressionCannotBecomeInventoryAfterTransportRetryAndReallow() throws Exception {
		try (var f = new Fixture(Map.of())) {
			long request = f.open(); f.refuseCreated = true;
			f.beforePublish = () -> f.states.get(InventoryResourceKind.ITEM).onValidation = () -> {
				f.policy.setPermissionLevels(Map.of(InventoryPresentation.ITEMS, 2)); f.player.permission = 0;
			};
			MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			assertFalse(PresentationServer.inventoryKnows(OWNER,marker));
			f.player.permission = 2; f.refuseCreated = false; f.beforePublish = null; f.tick(1);
			var packet = f.presentation.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).findFirst().orElseThrow();
			assertEquals(nx.pingwheel.common.presentation.PresentationReceiptContent.Kind.SUPPRESSED,packet.content().kind());
			assertNull(packet.content().inventorySnapshot());
		}
	}

	private static PresentationSection decodedBasic(PresentationS2CPacket packet) {
		var encoded = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(packet.sectionBytes()));
		try { return nx.pingwheel.common.presentation.PresentationCodec.read(encoded, id -> true); }
		finally { encoded.release(); }
	}
	private static Object value(Object object, String name) throws ReflectiveOperationException { return Objects.requireNonNull(field(object.getClass(), name).get(object)); }

	@NotNullByDefault
	private static final class State {
		int reads; int slots = 1; long amount = 81; boolean verified, aggregate, oneValue, safeLabel, valid = true;
		@Nullable Runnable onValidation;
	}
	@NotNullByDefault
	private record Delivery(UUID recipient, PresentationS2CPacket packet) {}

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
		final List<Delivery> deliveries = new ArrayList<>();
		@Unmodifiable List<UUID> audience = List.of(OWNER);
		final List<InventoryS2CPacket> packets = new ArrayList<>();
		final List<InventoryC2SPacket> requests = new ArrayList<>();
		final ClientInventory client = new ClientInventory(packet -> requests.add((InventoryC2SPacket) packet));
		final EnumMap<InventoryResourceKind, State> states = new EnumMap<>(InventoryResourceKind.class);
		final InventoryRuntime runtime;
		final InventoryBackend backend;
		final Target requestedTarget;
		boolean refuseResults, refuseData, refusePreview;
		boolean refuseCreated, throwCreated;
		@Nullable Set<String> blockedCreated;
		@Nullable Runnable beforePublish;
		@Nullable Consumer<InventoryS2CPacket> afterSend;

		Fixture(@Unmodifiable Map<String, Integer> permissions) throws Exception { this(permissions, null); }
		Fixture(@Unmodifiable Map<String, Integer> permissions,
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest.@Nullable Fixture nativeSource) throws Exception {
			requestedTarget = nativeSource == null ? TARGET : Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "minecraft:chest", "locator", true);
			server.players = players; server.world = level; players.player = player; players.others = new java.util.LinkedHashMap<>(); player.level = level; level.server = server;
			player.permission = 2;
			policy.copyFrom(PresentationSettings.serverDefaults()); policy.setPermissionLevels(permissions);
			Network.receiver = (packet, recipient) -> { if (packet instanceof PresentationS2CPacket p) deliveries.add(new Delivery(recipient.getUUID(), p)); if (recipient.getUUID().equals(OWNER)) receive(packet); };
			Network.admitEncoded = () -> { if (throwCreated) throw new IllegalStateException("encoded handoff failure"); return !refuseCreated; };
			Network.admitRecipient = (packet, recipient) -> blockedCreated == null || !blockedCreated.contains(recipient.getUUID() + "/" + packet.snapshot().id().value());
			PresentationServer.reset(); PresentationServer.activate(server);
			negotiatePresentation();
			for (var kind : InventoryResourceKind.values()) states.put(kind, new State());
			InventoryRuntime.SourceResolver fallback = (input, display) -> Optional.of(new InventorySourceAccess.Source() {
				@Override public SourceKey key() { return new SourceKey("test", "production-policy", "chest", input.viewKey()); }
				@Override public boolean valid() {
					@Nullable Runnable effect = states.get(input.resourceKind()).onValidation;
					if (effect != null) { states.get(input.resourceKind()).onValidation = null; effect.run(); }
					return states.get(input.resourceKind()).valid;
				}
				@Override public java.util.OptionalLong version() { return states.get(input.resourceKind()).verified ? java.util.OptionalLong.of(1) : java.util.OptionalLong.empty(); }
				@Override public boolean stableCursor() { return true; }
				@Override public int slots() { return states.get(input.resourceKind()).slots; }
				@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
				@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new AssertionError("resource reader required"); }
				@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
					states.get(input.resourceKind()).reads++;
					if (slot > 0 && states.get(input.resourceKind()).oneValue) return null;
					String id = input.resourceKind() == InventoryResourceKind.ITEM ? "minecraft:stone" : "minecraft:water";
					boolean aggregate = states.get(input.resourceKind()).aggregate;
					return InventoryResourceCodec.value(new InventoryScanner.Key(input.resourceKind(), id, aggregate ? "" : "native"), states.get(input.resourceKind()).amount,
						states.get(input.resourceKind()).safeLabel ? input.resourceKind() == InventoryResourceKind.ITEM ? "stone" : "water" : id, null, aggregate);
				}
			});
			var resolver = nativeSource == null ? fallback : nx.pingwheel.common.presentation.inventory.minecraft.InventoryCreationNativeFluidFixture.resolver(nativeSource);
			runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes());
			backend = new InventoryBackend(new InventoryBackend.Host() {
				@Override public Optional<InventoryBackend.Policy> policy(UUID id) { return PresentationServer.inventoryPolicy(id).map(p -> new InventoryBackend.Policy(p.epoch(), p.view(), p.types(), p.fluidTypes())); }
				@Override public Optional<InventoryBackend.Opened> open(UUID id, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
				@Override public boolean annotationAllowed(nx.pingwheel.common.presentation.inventory.InventorySourceInput input, String annotation) { return annotation.equals("attention"); }
				@Override public Optional<nx.pingwheel.common.presentation.inventory.InventorySourceInput> bindCommitted(nx.pingwheel.common.presentation.inventory.InventorySourceInput input, Target committed) {
					return nativeSource == null ? InventoryBackend.Host.super.bindCommitted(input, committed)
						: Optional.of(new nx.pingwheel.common.presentation.inventory.InventorySourceInput(committed, input.readOwner(), input.face(), input.resourceKind()));
				}
				@Override public InventoryBackend.Created create(UUID id, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
					var type = TargetTypeCatalog.builtIn().findById(frozen.targetType()).orElseThrow();
					Target committed = nativeSource == null ? frozen.target() : nativeSource.target;
					@Nullable var rejection = admission.prepare(committed, nx.pingwheel.common.marker.TargetKey.from(committed), type.id(), audience);
					if (rejection != null) return new InventoryBackend.Created(null, rejection);
					var marker = store.create(id, committed, type, type.defaultPingType(), new MarkerAnchor(1, 64, 3), 0, 10000, audience).marker();
					return new InventoryBackend.Created(marker, null);
				}
				@Override public void publishCreated(InventoryBackend.Created created) {
					@Nullable Runnable effect = beforePublish; if (effect != null) effect.run();
					PresentationServer.created(server, created.marker(), nx.pingwheel.common.name.TargetNameJsonCodec.UNKNOWN, "owner", Map.of(PresentationBasic.ID,
						new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("\"chest\"")), false)));
				}
				@Override public boolean knows(UUID id, MarkerId marker) { return PresentationServer.inventoryKnows(id, marker); }
				@Override public boolean authorized(SyncPublisher.Context context) { return true; }
				@Override public int encodedBytes(InventoryS2CPacket packet) { return packet.encodedSize(nx.pingwheel.common.presentation.inventory.InventoryDomainCodec.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES); }
				@Override public void send(UUID id, InventoryS2CPacket packet) {
					if (refusePreview && packet.kind() == InventoryS2CPacket.Kind.PREVIEW) throw new IllegalStateException("preview cut handoff deferred");
					if (refuseResults && (packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT)) throw new IllegalStateException("result transport deferred");
					if (refuseData && !packet.entries().isEmpty()) throw new IllegalStateException("quantity transport deferred");
					packets.add(packet); if (id.equals(OWNER)) client.accept(packet);
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
			long request = client.open(requestedTarget, BlockFace.NORTH, "entity_block");
			backend.handle(OWNER, requests.getLast(), server.now, settings); tick(server.now);
			return request;
		}
		void tick(int tick) { server.now = tick; PresentationServer.tick(server, store); backend.tick(tick, settings); }
		void negotiatePresentation() { PresentationServer.negotiate(server, player, PresentationC2SPacket.hello(Map.of(
			PresentationBasic.ID, 1, InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA)), store); }
		void join(UUID id) {
			Player joined = DoubleChestFixture.allocate(Player.class); joined.id = id; joined.level = level; joined.permission = 0;
			players.others.put(id, joined);
			PresentationServer.negotiate(server, joined, PresentationC2SPacket.hello(Map.of(PresentationBasic.ID, 1,
				InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA)), store);
			backend.handle(id, InventoryC2SPacket.hello(), server.now, settings);
		}
		PresentationServer.Session session() { return Objects.requireNonNull(sessions().get(OWNER)); }
		@SuppressWarnings("unchecked")
		PresentationServer.Source basicSource(MarkerId marker) throws ReflectiveOperationException {
			var leases = (Map<Long, PresentationServer.Lease>) field(PresentationServer.class, "LEASES").get(null);
			return Objects.requireNonNull(leases.get(marker.value()).sources.get(PresentationBasic.ID));
		}
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
		Object previewState(long request, InventoryResourceKind lane) throws ReflectiveOperationException {
			Object owner = Objects.requireNonNull(((Map<?, ?>) value(backend, "sessions")).get(OWNER));
			return ((Map<?, ?>) value(owner, "previews")).values().stream().filter(preview -> {
				try { return (long) value(preview, "request") == request && ((nx.pingwheel.common.presentation.inventory.InventorySourceInput) value(preview, "input")).resourceKind() == lane; }
				catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			}).findFirst().orElseThrow();
		}
		Object recipient(Object lease) throws ReflectiveOperationException { return Objects.requireNonNull(((Map<?, ?>) value(lease, "recipients")).get(OWNER)); }
		Object publisherState(SyncPublisher.Context context) throws ReflectiveOperationException {
			return ((Map<?, ?>) value(value(backend, "publisher"), "states")).values().stream().filter(state -> {
				try { return value(state, "context").equals(context); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			}).findFirst().orElseThrow();
		}
		Map<?, ?> pendingPolicies() throws ReflectiveOperationException { return (Map<?, ?>) value(Objects.requireNonNull(((Map<?, ?>) value(backend, "sessions")).get(OWNER)), "pendingPolicies"); }
		InventoryS2CPacket pendingPolicy(InventoryResourceKind lane) throws ReflectiveOperationException { return (InventoryS2CPacket) Objects.requireNonNull(pendingPolicies().get(lane)); }
		InventoryS2CPacket pendingPolicy(UUID id, InventoryResourceKind lane) throws ReflectiveOperationException { return (InventoryS2CPacket) Objects.requireNonNull(((Map<?, ?>) value(Objects.requireNonNull(((Map<?, ?>) value(backend, "sessions")).get(id)), "pendingPolicies")).get(lane)); }
		InventorySyncPublisher publisher() throws ReflectiveOperationException { return (InventorySyncPublisher) value(backend, "publisher"); }
		InventorySyncPublisher.ControlHandoff controlHandoff() throws ReflectiveOperationException { return (InventorySyncPublisher.ControlHandoff) value(publisher(), "controlHandoff"); }
		@Nullable Object queuedPolicy(InventoryResourceKind lane) throws ReflectiveOperationException { return ((Map<?, ?>) value(value(backend, "publisher"), "controls")).get(OWNER + "/POLICY/" + lane + "/0"); }
		int queuedPolicyCount() throws ReflectiveOperationException { return (int) ((Map<?, ?>) value(value(backend, "publisher"), "controls")).keySet().stream().filter(key -> key.toString().startsWith(OWNER + "/POLICY/")).count(); }
		@Override public void close() {
			InventoryServer.reset(); PresentationServer.reset(); BudgetDelayMonitoring.reset(); Network.receiver = null; Network.admitEncoded = null; Network.admitRecipient = null;
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
	public static final class Network implements IPlatformNetworkService, InventoryCreationReceiptTransport.EncodedTransport {
		static @Nullable java.util.function.BiConsumer<IPacket, ServerPlayer> receiver;
		static @Nullable java.util.function.BooleanSupplier admitEncoded;
		static @Nullable java.util.function.BiPredicate<PresentationS2CPacket,ServerPlayer> admitRecipient;
		@Override public void sendToServer(IPacket packet) { throw new AssertionError("server fixture sent C2S"); }
		@Override public void sendToClient(IPacket packet, ServerPlayer player) { Objects.requireNonNull(receiver, "no recording transport").accept(packet, player); }
		@Override public boolean sendEncodedCreated(PresentationS2CPacket packet, ServerPlayer player, net.minecraft.network.FriendlyByteBuf encoded) {
			if (admitEncoded != null && !admitEncoded.getAsBoolean()) return false;
			if (admitRecipient != null && !admitRecipient.test(packet,player)) return false;
			var decoded = new PresentationS2CPacket(encoded);
			assertFalse(decoded.isCorrupt());
			sendToClient(decoded, player); return true;
		}
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
		private Map<UUID, Player> others = new java.util.LinkedHashMap<>();
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return id.equals(OWNER) ? player : others.get(id); }
		@Override public @Unmodifiable List<ServerPlayer> getPlayers() { return List.of(Objects.requireNonNull(player)); }
	}
	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private @Nullable UUID id;
		private @Nullable World level;
		private int permission;
		private Player() { super(null, null, null, null); }
		@Override public UUID getUUID() { return id == null ? OWNER : id; }
		@Override public GameProfile getGameProfile() { return new GameProfile(getUUID(), "owner"); }
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
