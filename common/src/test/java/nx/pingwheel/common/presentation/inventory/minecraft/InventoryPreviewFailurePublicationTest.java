package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.block.Blocks;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryPublicationBackendFixture;
import nx.pingwheel.common.presentation.inventory.InventoryPublicationEvidence;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.InventorySyncPublisher;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static nx.pingwheel.common.presentation.inventory.minecraft.InventoryPublicationReviewTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-origin proof retirement is independent of cross-domain physical scan sharing. */
@NotNullByDefault
class InventoryPreviewFailurePublicationTest {
	@BeforeAll static void bootstrap() { InventoryCertifiedReadTest.bootstrap(); }
	static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
		var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
	}
	static Object preview(InventoryPublicationBackendFixture f, UUID owner, long request) throws ReflectiveOperationException {
		Object session = java.util.Objects.requireNonNull(((Map<?, ?>) field(f.backend, "sessions")).get(owner));
		return java.util.Objects.requireNonNull(((Map<?, ?>) field(session, "previews")).get(request));
	}
	static @Unmodifiable List<Object> trackingStates(InventoryPublicationBackendFixture f, MarkerId marker) throws ReflectiveOperationException {
		var states = new java.util.ArrayList<Object>();
		for (Object state : ((Map<?, ?>) field(publisher(f), "states")).values())
			if (marker.equals(nullableField(field(state, "route"), "markerId"))) states.add(state);
		return List.copyOf(states);
	}
	static void selectPreviewOpportunity(InventoryPublicationBackendFixture f, Object preview) throws ReflectiveOperationException {
		int index = 0;
		for (Object state : ((Map<?, ?>) field(publisher(f), "states")).values()) {
			if (field(preview, "context").equals(field(state, "context"))) {
				// Align the existing rotating opportunity; no extra grant or changed budget/freshness is introduced.
				set(publisher(f), "validationCursor", index); return;
			}
			index++;
		}
		throw new AssertionError("pending preview route missing");
	}
	static void exhaustOrdinaryValidation(InventoryPublicationBackendFixture f) throws ReflectiveOperationException {
		var ledger = (CostLedger) field(f.runtime, "physical");
		@Unmodifiable Map<CostLedger.Counter, Long> amounts = Map.of(InventorySourceAccess.PROBES, ledger.remaining(InventorySourceAccess.PROBES),
			InventorySourceAccess.PROVIDER_WORK, ledger.remaining(InventorySourceAccess.PROVIDER_WORK));
		ledger.tryReserve(amounts).orElseThrow().commit(amounts);
	}
	@ParameterizedTest @CsvSource({"false,false,keep", "true,false,keep", "false,true,keep", "false,false,disconnect", "false,false,close"})
	void previewFailureRepairsDifferentTrackingProofWithoutRecapture(boolean partial, boolean delivered, String origin) throws ReflectiveOperationException {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST); boolean[] swap = {false};
		var source = new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return resolver.resolutionValidationCost(input); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
				if (swap[0]) { swap[0] = false; replace(resolver); }
				return resolver.resolve(input, cap, allowance);
			}
		};
		InventorySettings settings = InventoryPooledBackendTest.settings(false, false); settings.getTracking().setHeartbeatPeriods(1);
		try (var f = new InventoryPublicationBackendFixture(source, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			MarkerId marker = select(f); f.holdData = !delivered; f.backend.tick(21, settings);
			Object tracking = lease(f, marker); var old = (InventoryPublicationEvidence) field(tracking, "completedEvidence"); old.retain();
			long request = f.open(InventoryPublicationBackendFixture.B, InventoryPooledBackendTest.target(Blocks.CHEST), BlockFace.SOUTH, 22);
			f.holdData = true; f.backend.tick(22, settings);
			Object preview = preview(f, InventoryPublicationBackendFixture.B, request);
			var previewEvidence = (InventoryPublicationEvidence) field(preview, "publicationEvidence"); previewEvidence.retain();
			try {
				assertEquals(old.proof(), previewEvidence.proof(), "independent Preview/Tracking rounds can certify the same physical proof");
				var consumer = (InventoryRuntime.Consumer) field(tracking, "consumer"); var previewConsumer = (InventoryRuntime.Consumer) field(preview, "consumer");
				assertNotSame(field(consumer, "round"), field(previewConsumer, "round"), "Preview remains outside widened Tracking pools");
				@Unmodifiable List<Object> deniedCuts = trackingStates(f, marker);
				assertEquals(2, deniedCuts.size());
				for (Object state : deniedCuts) {
					assertNotNull(nullableField(state, delivered ? "deliveredEvidence" : "admittedEvidence"));
					assertFalse((boolean) field(state, "sourceDenied"));
				}
				Object markerRecord = field(tracking, "marker"); Object expiry = field(markerRecord, "expiresAtTick");
				long revision = (long) field(tracking, "revision");
				selectPreviewOpportunity(f, preview); swap[0] = true; f.holdData = false;
				settings.setPhysicalSlotsPerTick(IntLimit.finite(partial ? 9 : 64));
				int[] measured = {0}, validated = {0}; var fresh = new AtomicReference<@Nullable InventoryPublicationEvidence>();
				int[] reads = {0};
				resolver.duringRead = owner -> {
					// The R1 page already owns its complete read grant; later Preview probes use only residual credit.
					if (++reads[0] == 1) try { exhaustOrdinaryValidation(f); }
					catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
				};
				var observation = new AtomicReference<@Nullable Object>();
				f.measured = packet -> {
					if (!InventorySyncPublisher.requiresSource(packet)) return;
					try {
						if (measured[0]++ == 0) {
							assertEquals(field(preview, "context"), field(f.backend, "publicationOpportunityContext"));
							assertNotNull(field(f.backend, "publicationOpportunity"));
							assertEquals(partial ? 9 : 54, field(consumer, "cursor"));
							if (!partial) {
								fresh.set((InventoryPublicationEvidence) field(tracking, "completedEvidence")); observation.set(field(tracking, "completed"));
								assertNotEquals(old.proof(), java.util.Objects.requireNonNull(fresh.get()).proof()); assertEquals(0L, field(tracking, "completedCount"));
							}
						}
					} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
				};
				f.beforeValidation = (context, packet) -> {
					resolver.metadataCallback = () -> {};
					if (packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.requestId() == request) resolver.metadataCallback = () -> {
						validated[0]++;
						if (origin.equals("disconnect")) f.backend.disconnect(InventoryPublicationBackendFixture.B);
					};
				};
				int start = f.frames.size(); f.backend.tick(31, settings);
				assertTrue(measured[0] > 0); assertTrue(validated[0] > 0, "protected final Preview source validation executed despite exhausted ordinary credit");
				assertTrue(reads[0] > 0, "exhaustion ran inside the already admitted R1 native page");
				for (Object state : deniedCuts) assertTrue((boolean) field(state, "sourceDenied"), "Preview proof failure actually denied both historical Tracking cuts");
				assertEquals(InventorySharedRead.Check.STRUCTURE_INVALID, previewEvidence.validate(new InventoryValidationBudget.Allowance(previewEvidence.validationCost())));
				assertFalse((boolean) field(tracking, "invalid")); assertFalse(consumer.invalidated());
				assertEquals(partial ? 9 : 54, field(consumer, "cursor"));
				if (!partial) { assertSame(observation.get(), field(tracking, "completed")); assertSame(fresh.get(), field(tracking, "completedEvidence")); assertEquals(revision + 1, field(tracking, "revision")); }
				else assertNull(nullableField(tracking, "completed"), "old failed completion is not the valid partial replacement");
				assertEquals(0, f.runtime.memory().reserved());
				assertTrue(f.frames.subList(start, f.frames.size()).stream().noneMatch(packet -> marker.equals(packet.markerId()) && InventorySyncPublisher.requiresSource(packet)), "deferred old fragments cannot certify new data");
				f.measured = packet -> {}; f.beforeValidation = (context, packet) -> {}; resolver.metadataCallback = () -> {}; resolver.duringRead = owner -> {};
				if (origin.equals("close")) f.closePreview(InventoryPublicationBackendFixture.B, request, 31);
				long visits = InventoryPooledBackendTest.physical(f.runtime);
				for (int tick = 32; tick <= 40; tick++) {
					f.backend.tick(tick, settings); visits += InventoryPooledBackendTest.physical(f.runtime);
					if (f.clients.get(InventoryPublicationBackendFixture.A).tracking(marker).complete() && f.clients.get(InventoryPublicationBackendFixture.A).tracking(marker).entries().getFirst().count() == 0) break;
				}
				assertEquals(54, visits, "repair never repeats paid R1 content getters");
				for (UUID recipient : origin.equals("disconnect") ? List.of(InventoryPublicationBackendFixture.A) : List.of(InventoryPublicationBackendFixture.A, InventoryPublicationBackendFixture.B)) {
					var value = f.clients.get(recipient).tracking(marker); assertTrue(value.complete(), "preview-origin denial must not strand valid R1"); assertEquals(0, value.entries().getFirst().count());
					int fence = -1, data = -1;
					for (int index = start; index < f.frames.size(); index++) if (recipient.equals(f.recipients.get(index)) && marker.equals(f.frames.get(index).markerId())) {
						var packet = f.frames.get(index);
						if (packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.watermark() == 0 && packet.status() == InventoryS2CPacket.Status.UPDATING) fence = index;
						if (!packet.entries().isEmpty() && data < 0) data = index;
					}
					assertTrue(fence >= 0 && data > fence, "clean fence precedes R1 full cut");
				}
				for (Object state : trackingStates(f, marker)) assertFalse((boolean) field(state, "sourceDenied"));
				assertEquals(expiry, field(field(tracking, "marker"), "expiresAtTick")); assertNull(nullableField(tracking, "bootstrap"));
				if (!partial) { assertSame(observation.get(), field(tracking, "completed")); assertSame(fresh.get(), field(tracking, "completedEvidence")); assertEquals(revision + 1, field(tracking, "revision")); }
				if (origin.equals("close")) {
					f.backend.tick(10000, settings); assertFalse(f.backend.tracksInventory(marker));
					assertReleased(java.util.Objects.requireNonNull(fresh.get()));
				}
			} finally { previewEvidence.close(); old.close(); }
			assertReleased(old); assertReleased(previewEvidence);
		}
	}
	@ParameterizedTest @CsvSource({"structure", "owner"})
	void previewFailureRetiresMatchingProofOrExactOwnerButNotIndependentOwnerProof(String failure) throws ReflectiveOperationException {
		var original = new InventoryPooledBackendTest.Resolver(Blocks.CHEST); var replacement = new InventoryPooledBackendTest.Resolver(Blocks.CHEST);
		boolean[] swap = {false}, ownerReplacement = {false};
		var source = new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return original.resolutionValidationCost(input); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
				return (swap[0] && (ownerReplacement[0] || input.readOwner().equals(InventoryPublicationBackendFixture.B)) ? replacement : original).resolve(input, cap, allowance);
			}
		};
		var settings = InventoryPooledBackendTest.settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(source, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			// Two original owners share no Preview round; the independent B marker uses its own R1 physical proof.
			f.open(InventoryPublicationBackendFixture.A, InventoryPooledBackendTest.target(Blocks.CHEST), BlockFace.NORTH, 0);
			f.open(InventoryPublicationBackendFixture.B, InventoryPooledBackendTest.target(Blocks.CHEST), BlockFace.NORTH, 0);
			for (int tick = 0; tick < 20; tick++) f.backend.tick(tick, settings);
			MarkerId a = f.select(InventoryPublicationBackendFixture.A, 1, 20); f.closePreview(InventoryPublicationBackendFixture.A, 1, 20);
			swap[0] = true;
			MarkerId b = f.select(InventoryPublicationBackendFixture.B, 1, 20); f.closePreview(InventoryPublicationBackendFixture.B, 1, 20);
			f.holdData = true;
			for (int tick = 21; tick < 25; tick++) f.backend.tick(tick, settings);
			Object aLease = lease(f, a), bLease = lease(f, b);
			var independent = new AtomicReference<>(field(bLease, "completed")); var bEvidence = new AtomicReference<>((InventoryPublicationEvidence) field(bLease, "completedEvidence"));
			swap[0] = false;
			long request = f.open(InventoryPublicationBackendFixture.A, InventoryPooledBackendTest.target(Blocks.CHEST), BlockFace.NORTH, 25);
			f.backend.tick(25, settings); Object origin = preview(f, InventoryPublicationBackendFixture.A, request);
			var evidence = (InventoryPublicationEvidence) field(origin, "publicationEvidence"); evidence.retain();
			try {
				assertNotEquals(evidence.proof(), bEvidence.get().proof());
				selectPreviewOpportunity(f, origin); f.holdData = false; int[] callbacks = {0};
				if (failure.equals("owner")) {
					ownerReplacement[0] = true; swap[0] = true; int[] reads = {0};
					replacement.duringRead = owner -> { if (reads[0]++ == 0) try { exhaustOrdinaryValidation(f); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } };
				}
				f.measured = packet -> { if (InventorySyncPublisher.requiresSource(packet)) try {
					exhaustOrdinaryValidation(f);
					if (failure.equals("owner")) {
						var current = (InventoryPublicationEvidence) field(aLease, "completedEvidence"); assertNotEquals(evidence.proof(), current.proof());
						assertEquals(InventorySharedRead.Check.VALID, current.validate(new InventoryValidationBudget.Allowance(current.validationCost())));
						independent.set(field(bLease, "completed")); bEvidence.set((InventoryPublicationEvidence) field(bLease, "completedEvidence"));
					}
				} catch (ReflectiveOperationException e) { throw new AssertionError(e); } };
				f.beforeValidation = (context, packet) -> {
					original.metadataCallback = () -> {};
					if (packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.requestId() == request) original.metadataCallback = () -> {
						if (callbacks[0]++ != 0) return;
						if (failure.equals("structure")) replace(original); else original.owners.put(InventoryPublicationBackendFixture.A, false);
					};
				};
				int failedAt = failure.equals("owner") ? 31 : 26;
				f.backend.tick(failedAt, settings); assertTrue(callbacks[0] > 0);
				assertTrue((boolean) field(aLease, "invalid")); assertFalse((boolean) field(bLease, "invalid"));
				assertSame(independent.get(), field(bLease, "completed")); assertSame(bEvidence.get(), field(bLease, "completedEvidence"));
				f.measured = packet -> {}; f.beforeValidation = (context, packet) -> {}; original.metadataCallback = () -> {}; replacement.duringRead = owner -> {};
				for (int tick = failedAt + 1; tick < failedAt + 5; tick++) f.backend.tick(tick, settings);
				for (var client : f.clients.values()) { assertTrue(client.tracking(b).complete()); assertEquals(756, client.tracking(b).entries().getFirst().count()); }
				assertTrue(f.frames.stream().anyMatch(packet -> a.equals(packet.markerId()) && packet.status() == InventoryS2CPacket.Status.INVALID && !InventorySyncPublisher.requiresSource(packet)));
				assertEquals(0, original.deniedReads); assertEquals(0, replacement.deniedReads);
			} finally { evidence.close(); }
			assertReleased(evidence);
		}
	}
}
