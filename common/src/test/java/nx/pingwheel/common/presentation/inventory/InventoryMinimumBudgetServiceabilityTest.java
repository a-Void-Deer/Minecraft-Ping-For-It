package nx.pingwheel.common.presentation.inventory;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.ByteMultiplierGrid;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryCreationSnapshotCodec;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryDisplayWriter;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * User acceptance: every finite byte multiplier and pending-memory cap at its
 * legal minimum must still serve a stable singleton, including with the largest
 * legal display/frame pair. INCOMPLETE, lost rows and perpetual deferral are
 * failures here, even where overload handling itself follows the older contract.
 *
 * Production paths use the real Catalog/freezer, runtime, backend, publisher,
 * encoder, decoder and client store, with only the world/transport host modeled.
 * The exact-cap wire case is explicitly separate: wire validity does not prove
 * that the native freezer can produce the same display under its phase limits.
 */
@NotNullByDefault
@Tag("minimum-budget-serviceability")
class InventoryMinimumBudgetServiceabilityTest {
    private static final String ID = "minecraft:stone";
    private static final UUID OWNER = InventoryBackendTest.A;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void nativeCatalogPreflightActuallyProducesMaximumGrammarValidExactDisplay() {
        exactNativeDisplay(InventoryLimits.MAX_DISPLAY_BYTES);
    }

    @Test void nativeCatalogPreflightActuallyProducesNinetySixKiBExactDisplay() {
        exactNativeDisplay(96 * 1024);
    }

    @Test void matrixAEmptyNativeSourceCompletesAtAllMinimumCaps() {
        try (var fixture = new Fixture(settings(false, 4), ItemStack.EMPTY)) {
            fixture.open();
            fixture.advance(30);
            var preview = fixture.client.preview(fixture.request);
            assertTrue(preview.completeScan(), fixture::evidence);
            assertFalse(preview.invalid(), fixture::evidence);
            assertTrue(preview.entries().isEmpty());
            assertNotEquals(InventoryS2CPacket.Status.INCOMPLETE, preview.status());
        }
    }

    @Test void matrixASmallNativeItemCreatesStreamsAndRepairsAtAllMinimumCaps() {
        lifecycle(settings(false, 4), new ItemStack(Items.STONE), 0);
    }

    @Test void matrixBEmptyNativeSourceCompletesAtMaximumDisplayAndFrame() {
        try (var fixture = new Fixture(settings(true, 4), ItemStack.EMPTY)) {
            fixture.open();
            fixture.advance(30);
            assertTrue(fixture.client.preview(fixture.request).completeScan(), fixture::evidence);
            assertTrue(fixture.client.preview(fixture.request).entries().isEmpty());
        }
    }

    @Test void matrixBSmallNativeItemCreatesStreamsAndRepairsAtWorstGraceBoundary() {
        lifecycle(settings(true, InventoryLimits.MIN_GRACE_PERIODS), new ItemStack(Items.STONE), 0);
    }

    @Test void matrixBSmallNativeItemCreatesStreamsAndRepairsAtDefaultGrace() {
        lifecycle(settings(true, 4), new ItemStack(Items.STONE), 0);
    }

    @Test void matrixAMaximumNativeDisplaySingletonRemainsServiceable() {
        // Exactly 1 KiB is legal display grammar, but exceeds the original 832 B
        // Preview data allowance. Native production must preserve the row.
        lifecycle(settings(false, 4), exactNativeDisplay(1024), 1024);
    }

    @Test void matrixAMinimalStreamCounterexampleCreatesThenDelivers163ByteNativeDisplay() {
        // READY STREAM = 353 bytes with selected/stone/translation/attention.
        // This fits Preview and SNAPSHOT, isolating the 352-byte STREAM limit.
        lifecycle(settings(false, 4), exactNativeDisplay(163), 163);
    }

    @Test void matrixAMinimalPreviewCounterexampleDelivers632ByteNativeDisplay() {
        // A production 36-byte opaque token makes READY PREVIEW 833 B: one
        // byte beyond the fresh 832-byte data allowance, without queue pressure.
        lifecycle(settings(false, 4), exactNativeDisplay(632), 632);
    }

    @Test void matrixBNativeEscapedCustomDataNearDisplayCeilingCreatesStreamsAndRepairs() {
        // Escaped NULs carry real native custom-data values. They expand sixfold
        // in JSON while staying below the independent canonical identity ceiling.
        // A preflight failure is reported at the native fixture assertion below;
        // it must never be misreported as max-display end-to-end success.
        lifecycle(settings(true, 4), exactNativeDisplay(128 * 1024), 128 * 1024);
    }

    @Test void matrixBNativeEscapedCustomDataAtWorstGraceBoundaryRemainsServiceable() {
        lifecycle(settings(true, InventoryLimits.MIN_GRACE_PERIODS), exactNativeDisplay(128 * 1024), 128 * 1024);
    }

    @Test void matrixBNativeNinetySixKiBControlSeparatesPhaseCeilingFromWireServiceability() {
        lifecycle(settings(true, 4), exactNativeDisplay(96 * 1024), 96 * 1024);
    }

    @Test void isolatedTrackingSnapshotOfNativeMaximumDisplayMustNotDeferForever() {
        publisherSingleton(true, false);
    }

    @Test void isolatedTrackingStreamOfNativeMaximumDisplayMustNotDeferForever() {
        publisherSingleton(true, true);
    }

    @Test void isolatedTrackingStreamOfNativeMinimumDisplayMustNotDeferForever() {
        publisherSingleton(false, true);
    }

    private static void publisherSingleton(boolean maximum, boolean stream) {
        var settings = settings(maximum, InventoryLimits.MIN_GRACE_PERIODS);
        int cap = (int) settings.effectiveDisplayBytes();
        var encoded = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, cap).encode(exactNativeDisplay(cap));
        var transport = new InventorySyncPublisherTest.Transport();
        var memory = new RetainedMemoryLedger(settings.pendingMemoryBytes());
        var marker = new MarkerId(1);
        var client = new ClientInventory(ignored -> {});
        client.presentationReset(100, 1);
        client.markerCreated(new MarkerSnapshot(marker, OWNER, InventoryRuntimeTest.TARGET, "entity_block", "attention",
            new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3), 0, 1000),
            InventoryTestGrants.content(InventoryTestGrants.allowAll(List.of(OWNER)), OWNER, InventoryResourceKind.ITEM));
        client.accept(roundTrip(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 16,
            cap, (int) settings.effectiveFrameBytes())).stamp(100, 1), settings));
        client.accept(roundTrip(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block")), settings));
        var context = new SyncPublisher.Context("isolated-tracking", OWNER, "100/1", 1, 1);
        try (var publisher = new InventorySyncPublisher(memory, transport)) {
            publisher.advance(0, settings);
            assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 0, marker, "target", "attention")));
            if (stream) {
                // Establish an actual closed baseline before offering the same
                // exact identity's larger display. This isolates STREAM refusal
                // from a preceding impossible large snapshot/Preview queue.
                var baseline = new InventoryDomainCodec.Item(encoded.key(), 7, encoded.display().label(), null, false);
                publish(publisher, context, baseline, 1);
                publisher.drain();
                transport.sent.forEach(packet -> client.accept(roundTrip(packet, settings)));
                transport.sent.clear();
                assertTrue(client.tracking(marker).complete(), "small control baseline must close before the STREAM probe");
            }
            var item = new InventoryDomainCodec.Item(encoded.key(), 11, encoded.display().label(), encoded.display().displayJson(), false);
            for (int period = 1; period <= 12; period++) {
                publisher.advance(period * 3L, settings);
                publish(publisher, context, item, stream ? 2 : 1);
                publisher.drain();
                transport.sent.forEach(packet -> client.accept(roundTrip(packet, settings)));
                transport.sent.clear();
            }
            assertTrue(client.tracking(marker).complete(), "a stable admitted singleton must finish after repeated fresh periods");
            assertEquals(11, client.tracking(marker).entries().getFirst().count(),
                "minimum byte/queue caps cannot strand the selected row after unlimited retries");
            assertEquals(cap, ByteBufUtil.utf8Bytes(java.util.Objects.requireNonNull(client.tracking(marker).entries().getFirst().displayJson())));
        }
        assertEquals(0, memory.retained());
        assertEquals(0, memory.reserved());
    }

    private static void publish(InventorySyncPublisher publisher, SyncPublisher.Context context, InventoryDomainCodec.Item item, long revision) {
        var result = new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID,
            Map.of("selected", InventoryDomainCodec.encode(item)))), new CaptureResult.Coverage("inventory", revision, 1, java.util.OptionalLong.of(1)),
            CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.VERIFIED, Optional.empty(), Optional.empty());
        // Acceptance is a requirement too; a queue defer is a failure, with wire
        // feasibility independently asserted by the separate arithmetic test.
        assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publish(result,
            new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("selected")), context,
            new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 1L))));
    }

    @Test void maximumGrammarValidWireSingletonCanActuallyEnterClientPreviewAndTracking() {
        var settings = settings(true, 4);
        var requests = new ArrayList<IPacket>();
        var client = new ClientInventory(requests::add);
        client.presentationReset(100, 1);
        client.accept(roundTrip(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 16,
            InventoryLimits.MAX_DISPLAY_BYTES, InventoryLimits.MAX_FRAME_BYTES)).stamp(100, 1), settings));
        client.accept(roundTrip(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block")), settings));
        long request = client.open(InventoryRuntimeTest.TARGET, BlockFace.NORTH, "entity_block");
        assertEquals(1, request);
        client.accept(roundTrip(InventoryS2CPacket.opened(7, 100, 1, request, 1), settings));
        var entry = new InventoryS2CPacket.Entry("selected", ID, "block.minecraft.stone",
            exactWireDisplay(InventoryLimits.MAX_DISPLAY_BYTES), 7, 1, false, InventoryS2CPacket.Status.READY);
        client.accept(roundTrip(data(InventoryS2CPacket.Kind.PREVIEW, entry, null), settings));
        assertTrue(client.preview(request).completeScan());
        assertEquals(1, client.preview(request).entries().size(), "wire admission must retain the real display-sized row");
        var marker = new MarkerSnapshot(new MarkerId(1), OWNER, InventoryRuntimeTest.TARGET, "entity_block", "attention",
            new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3), 0, 1000);
        client.markerCreated(marker, InventoryTestGrants.content(InventoryTestGrants.allowAll(List.of(OWNER)), OWNER, InventoryResourceKind.ITEM));
        client.accept(roundTrip(data(InventoryS2CPacket.Kind.SNAPSHOT, entry, marker.id()), settings));
        assertTrue(client.tracking(marker.id()).complete());
        assertEquals(7, client.tracking(marker.id()).entries().getFirst().count());
        assertEquals(InventoryLimits.MAX_DISPLAY_BYTES,
            ByteBufUtil.utf8Bytes(java.util.Objects.requireNonNull(client.tracking(marker.id()).entries().getFirst().displayJson())));
    }

    @Test void maximumGrammarValidWireSingletonFitsMinimumPreviewStreamSnapshotAndQueues() {
        var settings = settings(true, InventoryLimits.MIN_GRACE_PERIODS);
        String display = exactWireDisplay(InventoryLimits.MAX_DISPLAY_BYTES);
        var entry = new InventoryS2CPacket.Entry("selected", ID, "block.minecraft.stone", display,
            7, 1, false, InventoryS2CPacket.Status.READY, 0, false, "attention");
        var preview = data(InventoryS2CPacket.Kind.PREVIEW, entry, null);
        var snapshot = data(InventoryS2CPacket.Kind.SNAPSHOT, entry, new MarkerId(1));
        var stream = data(InventoryS2CPacket.Kind.STREAM, entry, new MarkerId(1));
        int p = roundTrip(preview, settings).encodedSize((int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes());
        int s = roundTrip(snapshot, settings).encodedSize((int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes());
        int t = roundTrip(stream, settings).encodedSize((int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes());
        long queue = 128L + 3L * (entry.key().length() + entry.itemId().length() + entry.label().length() + display.length());
        var receipt = new InventoryCreationSnapshot(InventoryResourceKind.ITEM, entry.key(), ID, entry.label(), display,
            7, false, "attention", true, false);
        int creation = InventoryCreationSnapshotCodec.encodedSize(receipt);
        assertAll("User-required singleton serviceability, each ledger independently",
            () -> assertTrue(p <= dataCapacity(settings.previewClientPeriodBytes()), "Preview wire required=" + p + " available=" + dataCapacity(settings.previewClientPeriodBytes())),
            () -> assertTrue(p <= settings.previewGlobalPeriodBytes() - reserve(settings.previewClientPeriodBytes()), "Preview global wire"),
            () -> assertTrue(t <= dataCapacity(settings.trackingStreamPeriodBytesPerClientTarget()), "STREAM wire required=" + t + " available=" + dataCapacity(settings.trackingStreamPeriodBytesPerClientTarget())),
            () -> assertTrue(s <= dataCapacity(settings.trackingSnapshotClientPeriodBytes()), "SNAPSHOT wire required=" + s + " available=" + dataCapacity(settings.trackingSnapshotClientPeriodBytes())),
            () -> assertTrue(s <= settings.trackingSnapshotFragmentBytes(), "snapshot fragment bound"),
            () -> assertTrue(s <= 2 * settings.trackingGlobalPeriodBytes() - reserve(settings.trackingGlobalPeriodBytes()), "Tracking global grace=1"),
            () -> assertTrue(queue <= settings.previewQueueBytes(), "Preview queue required=" + queue + " available=" + settings.previewQueueBytes()),
            () -> assertTrue(queue <= settings.trackingRollingQueueBytes(), "Tracking queue required=" + queue + " available=" + settings.trackingRollingQueueBytes()),
            () -> assertTrue(creation <= settings.trackingRollingQueueBytes(), "one-recipient creation staging required=" + creation + " available=" + settings.trackingRollingQueueBytes()));
    }

    private static void lifecycle(InventorySettings settings, ItemStack stack, int expectedDisplayBytes) {
        try (var fixture = new Fixture(settings, stack)) {
            fixture.open();
            fixture.advance(30);
            var preview = fixture.client.preview(fixture.request);
            assertTrue(preview.completeScan(), fixture::evidence);
            assertEquals(1, preview.entries().size(), fixture::evidence);
            var row = preview.entries().getFirst();
            assertFalse(row.fallback(), "canonical identity must remain exact");
            if (expectedDisplayBytes > 0) assertEquals(expectedDisplayBytes, ByteBufUtil.utf8Bytes(java.util.Objects.requireNonNull(row.displayJson())),
                "native fixture precondition: size ceiling admission is not native production evidence");
            assertEquals(7, row.count());
            fixture.backend.handle(OWNER, InventoryC2SPacket.select(fixture.epoch(), 100, 1, 1, fixture.request,
                preview.baselineId(), preview.statusRevision(), row.key(), "attention"), ++fixture.tick, settings);
            assertEquals(1, fixture.host.store.size(), fixture::evidence);
            var marker = fixture.host.store.allMarkers().getFirst();
            var receipt = fixture.backend.creationSnapshot(marker.id()).orElseThrow();
            var grant = InventoryTestGrants.content(marker.authorization(), OWNER, InventoryResourceKind.ITEM);
            var created = PresentationS2CPacket.created(100, 1, 1, MarkerSnapshot.from(marker), "Owner",
                PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME), grant, PresentationReceiptContent.inventory(receipt),
                new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false));
            int createdBytes = created.encodedCreatedBodyBytes();
            var initial = fixture.backend.reserveInitial(marker.id(), OWNER, 100, 1, createdBytes, created.createdEncodingWorkspace());
            assertTrue(initial.isPresent(), "full atomic CREATED must fit queue and workspace: body=" + createdBytes + " " + fixture.evidence());
            var bytes = new FriendlyByteBuf(Unpooled.buffer());
            try (var staging = initial.orElseThrow()) {
                assertEquals(InventoryBackend.InitialDecision.READY, staging.beforeHandoff());
                created.write(bytes);
                var decoded = PresentationS2CPacket.readSafe(bytes);
                assertFalse(decoded.isCorrupt());
                staging.settle(createdBytes, created.createdEncodingWorkspace());
                fixture.client.markerCreated(decoded.snapshot(), decoded.markerGrant());
                assertTrue(fixture.client.creationSnapshot(marker.id(), java.util.Objects.requireNonNull(decoded.content().inventorySnapshot())));
                assertFalse(bytes.isReadable());
            } finally { bytes.release(); }
            assertEquals(7, java.util.Objects.requireNonNull(fixture.client.creationSnapshot(marker.id())).amount());
            fixture.backend.handle(OWNER, InventoryC2SPacket.close(fixture.epoch(), fixture.request).stamp(100, 1), fixture.tick, settings);
            fixture.advance(20);
            fixture.assertCount(marker.id(), 7);
            int start = fixture.host.packets.size();
            fixture.amount = 11;
            fixture.advance(20);
            fixture.assertCount(marker.id(), 11);
            assertTrue(fixture.host.packets.subList(start, fixture.host.packets.size()).stream()
                .anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.STREAM && !packet.entries().isEmpty()), fixture::evidence);
            long baseline = fixture.client.tracking(marker.id()).baselineId();
            fixture.backend.handle(OWNER, InventoryC2SPacket.resync(fixture.epoch(), 0, marker.id()).stamp(100, 1), ++fixture.tick, settings);
            fixture.advance(20);
            fixture.assertCount(marker.id(), 11);
            assertTrue(fixture.client.tracking(marker.id()).baselineId() > baseline, fixture::evidence);
            assertEquals(1, fixture.host.store.size(), "repair must not create again");
        }
    }

    private static InventorySettings settings(boolean maximumSizes, int grace) {
        var settings = InventorySettings.serverDefaults();
        // Period/resync/heartbeat defaults are explicit scenario preconditions.
        settings.getPreview().setPeriodTicks(5);
        settings.getTracking().setPeriodTicks(3);
        settings.getTracking().setResyncMinPeriods(5);
        settings.getTracking().setHeartbeatPeriods(16);
        settings.getTracking().setGracePeriods(grace);
        settings.setPendingMemoryMiB(InventoryLimits.MIN_PENDING_MEMORY_MIB);
        settings.setPhysicalSlotsPerTick(IntLimit.finite(InventoryLimits.MIN_ORDINARY_COUNT));
        settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(InventoryLimits.MIN_ORDINARY_COUNT));
        settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(InventoryLimits.MIN_ORDINARY_COUNT));
        settings.getPreview().setMaxSlotsServer(IntLimit.finite(InventoryLimits.MIN_ORDINARY_COUNT));
        settings.getPreview().setMaxTargetsPerClient(IntLimit.finite(1));
        settings.getTracking().setMaxVariantsPerTarget(IntLimit.finite(1));
        settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(InventoryLimits.MIN_ORDINARY_COUNT));
        settings.getTracking().setMaxSlotsServer(IntLimit.finite(InventoryLimits.MIN_TRACKING_SLOTS_SERVER));
        settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.CLIENT.minimum()));
        settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.GLOBAL.minimum()));
        settings.getTracking().setStreamByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.CLIENT.minimum()));
        settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.CLIENT.minimum()));
        settings.getTracking().setGlobalByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.GLOBAL.minimum()));
        settings.setDisplayByteMultiplier(maximumSizes ? InventorySizeScale.DISPLAY.maximumMultiplier() : InventorySizeScale.DISPLAY.minimumMultiplier());
        settings.setFrameByteMultiplier(maximumSizes ? InventorySizeScale.FRAME.maximumMultiplier() : InventorySizeScale.FRAME.minimumMultiplier());
        settings.validate();
        var values = java.util.Objects.requireNonNull(InventoryConfigValues.from(settings));
        assertTrue(values.isSafe());
        assertTrue(values.isSizePairCompatible());
        return settings;
    }

    private static ItemStack customData(String value) {
        var tag = new CompoundTag();
        tag.putString("payload", value);
        var stack = new ItemStack(Items.STONE);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** Calibrate framing only; acceptance assertions still require full native production. */
    private static ItemStack exactNativeDisplay(int bytes) {
        int projectionCap = Math.max(InventoryItemCodec.minimumDisplayBytes(), bytes);
        var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, projectionCap);
        var empty = catalog.encode(customData(""));
        String json = java.util.Objects.requireNonNull(empty.display().displayJson());
        int extra = bytes - ByteBufUtil.utf8Bytes(json);
        assertTrue(extra > 0, "custom-data display envelope fits");
        var stack = customData("\0".repeat(extra / 6) + "x".repeat(extra % 6));
        var actual = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, projectionCap).encode(stack);
        assertFalse(actual.display().aggregateRequired(), "native identity precondition; a folded value is not a largest exact singleton");
        String display = java.util.Objects.requireNonNull(actual.display().displayJson(), "native display refused before wire admission");
        assertEquals(bytes, ByteBufUtil.utf8Bytes(display), "native phase refused/stripped the target: report native ceiling separately from wire");
        assertTrue(InventoryDisplayWriter.validProjection(ID, display));
        assertFalse(ItemStack.SINGLE_ITEM_CODEC.parse(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(display)).getOrThrow().isEmpty());
        return stack;
    }

    private static String exactWireDisplay(int bytes) {
        String prefix = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":{\"payload\":\"";
        String suffix = "\"},\"!minecraft:lore\":{}}}";
        String json = prefix + "x".repeat(bytes - prefix.length() - suffix.length()) + suffix;
        assertEquals(bytes, ByteBufUtil.utf8Bytes(json));
        assertTrue(InventoryDisplayWriter.validProjection(ID, json));
        var decoded = ItemStack.SINGLE_ITEM_CODEC.parse(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
        assertTrue(decoded.is(Items.STONE));
        assertNotNull(decoded.get(DataComponents.CUSTOM_DATA));
        return json;
    }

    private static long reserve(long cap) { return Math.min(cap, 128 + cap / 16); }
    private static long dataCapacity(long cap) { return cap - reserve(cap); }
    private static InventoryS2CPacket data(InventoryS2CPacket.Kind kind, InventoryS2CPacket.Entry entry, @Nullable MarkerId marker) {
        return InventoryS2CPacket.data(kind, 7, marker == null ? 1 : 0, marker, 1, 1, 1,
            0, 1, true, InventoryS2CPacket.Status.READY, InventoryChecksums.checksumEntries(List.of(entry)), List.of(entry)).stamp(100, 1);
    }
    private static InventoryS2CPacket roundTrip(InventoryS2CPacket packet, InventorySettings settings) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buffer, (int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes());
            assertEquals(packet.encodedSize((int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes()), buffer.readableBytes());
            var decoded = InventoryS2CPacket.readSafe(buffer);
            assertFalse(decoded.isCorrupt());
            assertFalse(buffer.isReadable());
            return decoded;
        } finally { buffer.release(); }
    }

    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        final InventorySettings settings;
        final ItemStack stack;
        final InventoryBackendTest.Host host = new InventoryBackendTest.Host();
        final List<IPacket> requests = new ArrayList<>();
        final ClientInventory client = new ClientInventory(requests::add);
        final InventoryRuntime runtime;
        final InventoryBackend backend;
        long tick = -1, request, amount = 7;
        int cursor, reads;
        Fixture(InventorySettings settings, ItemStack stack) {
            this.settings = settings; this.stack = stack;
            host.frozenLanes = Set.of(InventoryResourceKind.ITEM);
            host.expiry = 1000;
            host.grants = recipient -> recipient.equals(OWNER) ? Set.of(InventoryResourceKind.ITEM) : Set.of();
            runtime = new InventoryRuntime((input, displayBytes) -> Optional.of(new NativeSource(this, input, displayBytes)), settings.pendingMemoryBytes());
            backend = new InventoryBackend(host, runtime, 1);
            client.presentationReset(100, 1);
        }
        long epoch() { return host.epoch(OWNER); }
        void open() {
            backend.handle(OWNER, InventoryC2SPacket.hello(), 0, settings);
            drain();
            request = client.open(InventoryRuntimeTest.TARGET, BlockFace.NORTH, "entity_block");
            assertTrue(request > 0);
            var open = requests.stream().filter(packet -> packet instanceof InventoryC2SPacket inventory
                && inventory.kind() == InventoryC2SPacket.Kind.OPEN).map(packet -> (InventoryC2SPacket) packet).findFirst().orElseThrow();
            backend.handle(OWNER, open, 0, settings);
        }
        void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                backend.tick(++tick, settings);
                drain();
                client.tick(true);
                assertTrue(runtime.memory().retained() + runtime.memory().reserved() <= settings.pendingMemoryBytes(), this::evidence);
            }
        }
        void drain() {
            for (; cursor < host.packets.size(); cursor++) if (OWNER.equals(host.recipients.get(cursor)))
                client.accept(roundTrip(host.packets.get(cursor), settings));
        }
        void assertCount(MarkerId marker, long amount) {
            var value = client.tracking(marker);
            assertTrue(value.complete(), this::evidence);
            assertNotEquals(InventoryS2CPacket.Status.INCOMPLETE, value.status(), this::evidence);
            assertEquals(1, value.entries().size(), this::evidence);
            assertEquals(amount, value.entries().getFirst().count(), this::evidence);
        }
        String evidence() {
            return "tick=" + tick + " reads=" + reads + " memory=" + runtime.memory().retained() + "/" + runtime.memory().cap()
                + " preview=" + client.preview(request).status() + " rows=" + client.preview(request).entries().size()
                + " packets=" + host.packets.stream().map(packet -> packet.kind() + ":" + packet.status() + ":" + packet.failureReason()).toList();
        }
        @Override public void close() {
            backend.close();
            assertEquals(0, runtime.memory().retained());
            assertEquals(0, runtime.memory().reserved());
        }
    }

    @NotNullByDefault
    private static final class NativeSource implements InventorySourceAccess.Source {
        final Fixture fixture;
        final InventorySourceInput input;
        final InventoryItemCodec.Catalog catalog;
        NativeSource(Fixture fixture, InventorySourceInput input, int displayBytes) {
            this.fixture = fixture; this.input = input;
            catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, displayBytes);
        }
        @Override public SourceKey key() { return new SourceKey("test", "inventory", "minimum-budget-native", input.viewKey()); }
        @Override public boolean valid() { return true; }
        @Override public boolean stableCursor() { return true; }
        @Override public long sourceRetainedBytes() { return 16 * 1024; }
        @Override public int slots() { return fixture.stack.isEmpty() ? 0 : 1; }
        @Override public int displayBytes() { return catalog.displayBytes(); }
        @Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
        @Override public InventoryItemCodec.Catalog nativeCatalog() { return catalog; }
        @Override public long nativeCatalogGrowthUpperBound() { return catalog.retainedGrowthUpperBound(); }
        @Override public long nativeCatalogGrowthUpperBound(int attempts) { return catalog.retainedGrowthUpperBound(attempts); }
        @Override public InventoryReprojectionSummary reprojectionSummary(int cap) { return catalog.reprojectionSummary(cap); }
        @Override public InventoryDomainCodec.Item read(int slot) { throw new AssertionError("native Catalog admission must precede freeze"); }
        @Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
            assertEquals(0, slot);
            fixture.reads++;
            var encoded = catalog.encode(fixture.stack, InventoryItemBudget.root(displayBytes()), java.util.Objects.requireNonNull(admission));
            return new InventoryDomainCodec.Item(encoded.key(), fixture.amount, encoded.display().label(), encoded.display().displayJson(), encoded.display().aggregateRequired());
        }
        @Override public void close() {}
    }
}
