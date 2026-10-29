package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Production certified chest, backend ingress, packet writer/reader and ClientInventory regression boundaries. */
@NotNullByDefault
class InventoryPublicationReviewTest {
	@BeforeAll static void bootstrap() { InventoryCertifiedReadTest.bootstrap(); }
	static Object field(Object owner, String name) throws ReflectiveOperationException {
		var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return java.util.Objects.requireNonNull(field.get(owner));
	}
	static @Nullable Object nullableField(Object owner, String name) throws ReflectiveOperationException {
		var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
	}
	static Object lease(InventoryPublicationBackendFixture f, MarkerId marker) throws ReflectiveOperationException { return java.util.Objects.requireNonNull(((Map<?, ?>) field(f.backend, "tracking")).get(marker)); }
	static InventorySyncPublisher publisher(InventoryPublicationBackendFixture f) throws ReflectiveOperationException { return (InventorySyncPublisher) field(f.backend, "publisher"); }
	static MarkerId select(InventoryPublicationBackendFixture f) {
		long request = f.open(InventoryPublicationBackendFixture.A, InventoryPooledBackendTest.target(Blocks.CHEST), BlockFace.NORTH, 0);
		for (int tick = 0; tick < 20; tick++) f.backend.tick(tick, f.settings);
		MarkerId marker = f.select(InventoryPublicationBackendFixture.A, request, 20); f.closePreview(InventoryPublicationBackendFixture.A, request, 20); return marker;
	}
	static long spent(InventoryPublicationBackendFixture f, UUID player) throws ReflectiveOperationException {
		long total = 0;
		for (String name : List.of("clientBytes", "streamBytes")) for (var entry : ((Map<?, ?>) field(publisher(f), name)).entrySet())
			if (entry.getKey().toString().contains(player.toString())) total += (long) entry.getValue();
		return total;
	}
	static void replace(InventoryPooledBackendTest.Resolver resolver) {
		for (BlockPos position : List.copyOf(resolver.world.entities.keySet())) resolver.world.entities.put(position, new ChestBlockEntity(position, resolver.world.states.get(position)));
	}
	static void assertReleased(InventoryPublicationEvidence evidence) throws ReflectiveOperationException {
		assertEquals(0, field(evidence, "references"), "every cut reference releases exactly once");
	}
	@NotNullByDefault
	record Delivery(Object state, long watermark, int part, long heartbeat) {
		static Delivery capture(Object state) throws ReflectiveOperationException {
			return new Delivery(state, (long) field(state, "deliveredWatermark"), (int) field(state, "part"), (long) field(state, "lastHeartbeat"));
		}
	}
	@ParameterizedTest @CsvSource({"policy,SNAPSHOT", "view,SNAPSHOT", "session,SNAPSHOT", "known,SNAPSHOT", "none,SNAPSHOT", "policy,HEARTBEAT", "known,HEARTBEAT", "view,STATUS", "policy,STREAM"})
	void finalProofRecipientRevocationCannotSpendOrHandOffAValidCut(String boundary, InventoryS2CPacket.Kind kind) throws ReflectiveOperationException {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST);
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setHeartbeatPeriods(1);
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			MarkerId marker = select(f);
			if (kind != InventoryS2CPacket.Kind.SNAPSHOT) {
				for (int tick = 21; tick < 30; tick++) { f.backend.tick(tick, settings); if (f.clients.get(InventoryPublicationBackendFixture.A).tracking(marker).complete()) break; }
				assertTrue(f.clients.get(InventoryPublicationBackendFixture.A).tracking(marker).complete());
				if (kind == InventoryS2CPacket.Kind.STREAM) for (var entity : resolver.world.entities.values())
					((ChestBlockEntity) entity).setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 10));
			}
			int[] proofCalls = {0}; long[] before = {-1}; boolean[] revoked = {false};
			int[] revocationStart = {-1};
			var delivered = new AtomicReference<@Nullable Delivery>();
			f.beforeValidation = (context, packet) -> {
				resolver.metadataCallback = () -> {};
				if (context.recipient().equals(InventoryPublicationBackendFixture.A) && marker.equals(packet.markerId()) && packet.kind() == kind && InventorySyncPublisher.requiresSource(packet)) {
					resolver.metadataCallback = () -> {
						if (proofCalls[0]++ != 0) return;
						revocationStart[0] = f.frames.size();
						try {
							before[0] = spent(f, InventoryPublicationBackendFixture.A);
							for (Object state : ((Map<?, ?>) field(publisher(f), "states")).values()) if (context.equals(field(state, "context"))) delivered.set(Delivery.capture(state));
						} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
						revoked[0] = !boundary.equals("none");
						switch (boundary) {
							case "policy" -> f.currentPolicy = player -> Optional.of(new InventoryBackend.Policy(100, 1, player.equals(InventoryPublicationBackendFixture.A) ? Set.of() : Set.of("entity_block")));
							case "view" -> f.currentPolicy = player -> Optional.of(new InventoryBackend.Policy(100, player.equals(InventoryPublicationBackendFixture.A) ? 2 : 1, Set.of("entity_block")));
							case "session" -> f.currentPolicy = player -> player.equals(InventoryPublicationBackendFixture.A) ? Optional.empty() : Optional.of(new InventoryBackend.Policy(100, 1, Set.of("entity_block")));
							case "known" -> f.known = (player, id) -> !player.equals(InventoryPublicationBackendFixture.A);
							case "none" -> { }
							default -> throw new AssertionError(boundary);
						}
					};
				}
			};
			for (int tick = kind == InventoryS2CPacket.Kind.SNAPSHOT ? 21 : 30; tick < 70 && proofCalls[0] == 0; tick++) f.backend.tick(tick, settings);
			assertTrue(proofCalls[0] > 0, "mutation ran inside the outgoing frame's final metadata proof, not encodedBytes");
			long handed = 0;
			for (int index = revocationStart[0]; index < f.frames.size(); index++) if (f.recipients.get(index).equals(InventoryPublicationBackendFixture.A) && marker.equals(f.frames.get(index).markerId()) && InventorySyncPublisher.requiresSource(f.frames.get(index))) handed++;
			if (revoked[0]) {
				assertEquals(0, handed, "post-proof recipient revocation must fence quantities and certificates");
				assertEquals(before[0], spent(f, InventoryPublicationBackendFixture.A), "denied recipient frame spends no wire allowance");
				Delivery atProof = java.util.Objects.requireNonNull(delivered.get());
				assertEquals(atProof, Delivery.capture(atProof.state()), "denial advances neither delivered cut, admitted part nor heartbeat cadence");
			}
			else { assertTrue(handed > 0); assertTrue(f.clients.get(InventoryPublicationBackendFixture.A).tracking(marker).complete()); }
			assertEquals(1, f.markers.size());
		}
	}
	@ParameterizedTest @CsvSource({"false", "true"})
	void obsoleteAdmittedProofDoesNotDiscardNewDifferentCompletedZero(boolean missingOldSource) throws ReflectiveOperationException {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST); boolean[] swap = {false}; int[] replacements = {0};
		var replacement = new InventoryPooledBackendTest.Resolver(Blocks.CHEST);
		for (var entity : replacement.world.entities.values()) ((ChestBlockEntity) entity).clearContent();
		var source = new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return resolver.resolutionValidationCost(input); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
				if (swap[0]) {
					swap[0] = false; replacements[0]++;
					if (missingOldSource) resolver.world.entities.clear(); else replace(resolver);
				}
				return (missingOldSource && replacements[0] != 0 ? replacement : resolver).resolve(input, cap, allowance);
			}
		};
		var settings = InventoryPooledBackendTest.settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(source, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			MarkerId marker = select(f); f.holdData = true; f.backend.tick(21, settings);
			Object tracking = lease(f, marker); var old = (InventoryPublicationEvidence) field(tracking, "completedEvidence"); old.retain();
			try {
				assertEquals(756L, field(tracking, "completedCount")); assertTrue(publisher(f).pending((SyncPublisher.Context) field(((Map<?, ?>) field(tracking, "recipients")).values().iterator().next(), "context")));
				swap[0] = true; f.holdData = false; f.backend.tick(31, settings);
				assertEquals(1, replacements[0]); assertFalse((boolean) field(tracking, "invalid"), "obsolete R0 proof must not invalidate R1");
				assertNotNull(nullableField(tracking, "completed")); assertEquals(0L, field(tracking, "completedCount"));
				var fresh = (InventoryPublicationEvidence) field(tracking, "completedEvidence"); assertNotEquals(old.proof(), fresh.proof());
				assertEquals(InventorySharedRead.Check.VALID, fresh.validate(new InventoryValidationBudget.Allowance(fresh.validationCost())));
				long visits = InventoryPooledBackendTest.physical(f.runtime);
				for (int tick = 32; tick < 41; tick++) { f.backend.tick(tick, settings); visits += InventoryPooledBackendTest.physical(f.runtime); if (f.clients.values().stream().allMatch(client -> client.tracking(marker).complete())) break; }
				assertEquals(54, visits, "repair must publish the completed zero without content recapture");
				for (var client : f.clients.values()) { assertTrue(client.tracking(marker).complete()); assertEquals(0, client.tracking(marker).entries().getFirst().count()); }
			} finally { old.close(); }
			assertReleased(old);
		}
	}
	@ParameterizedTest @CsvSource({"false", "true"})
	void historicalDeliveredHeartbeatFailurePreservesDifferentNewScanAndItsPaidCursor(boolean completed) throws ReflectiveOperationException {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST); boolean[] swap = {false};
		var source = new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return resolver.resolutionValidationCost(input); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
				if (swap[0]) {
					swap[0] = false; replace(resolver);
					if (completed) for (var entity : resolver.world.entities.values()) for (int slot = 0; slot < 27; slot++)
						((ChestBlockEntity) entity).setItem(slot, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, slot + 1));
				}
				return resolver.resolve(input, cap, allowance);
			}
		};
		var settings = InventoryPooledBackendTest.settings(false, false); settings.getTracking().setHeartbeatPeriods(1);
		try (var f = new InventoryPublicationBackendFixture(source, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			MarkerId marker = select(f); f.backend.tick(21, settings); assertTrue(f.clients.get(InventoryPublicationBackendFixture.A).tracking(marker).complete());
			Object tracking = lease(f, marker); var old = (InventoryPublicationEvidence) field(tracking, "completedEvidence"); old.retain();
			try {
				long previousBaseline = f.clients.get(InventoryPublicationBackendFixture.A).tracking(marker).baselineId();
				swap[0] = true; settings.setPhysicalSlotsPerTick(IntLimit.finite(completed ? 64 : 9));
				f.blocked = (context, packet) -> InventorySyncPublisher.requiresSource(packet) && packet.kind() != InventoryS2CPacket.Kind.HEARTBEAT;
				int start = f.frames.size(); f.backend.tick(31, settings);
				var consumer = (InventoryRuntime.Consumer) field(tracking, "consumer");
				assertFalse((boolean) field(tracking, "invalid")); assertFalse(consumer.invalidated());
				assertEquals(completed ? 54 : 9, field(consumer, "cursor"), "obsolete delivered proof cannot rewind the new scan");
				if (completed) { assertNotNull(nullableField(tracking, "completed")); assertEquals(756L, field(tracking, "completedCount")); }
				else assertNull(nullableField(tracking, "completed"), "failed historical completion, not partial new sum, is withdrawn");
				assertTrue(f.frames.subList(start, f.frames.size()).stream().noneMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.HEARTBEAT), "old delivered checksum must not escape");
				long visits = InventoryPooledBackendTest.physical(f.runtime); f.blocked = (context, packet) -> false;
				for (int tick = 32; tick < 41; tick++) {
					f.backend.tick(tick, settings); visits += InventoryPooledBackendTest.physical(f.runtime);
					if (f.clients.values().stream().allMatch(client -> client.tracking(marker).complete() && client.tracking(marker).baselineId() != previousBaseline)) break;
				}
				assertEquals(54, visits, "no content getter is repeated solely for publication repair");
				for (var client : f.clients.values()) {
					var value = client.tracking(marker); assertTrue(value.complete()); assertEquals(completed ? 756 : 0, value.entries().getFirst().count()); assertNotEquals(previousBaseline, value.baselineId());
				}
				for (UUID player : List.of(InventoryPublicationBackendFixture.A, InventoryPublicationBackendFixture.B)) {
					int fence = -1, data = -1;
					for (int index = start; index < f.frames.size(); index++) if (f.recipients.get(index).equals(player)) {
						var packet = f.frames.get(index);
						if (packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.watermark() == 0 && packet.status() == InventoryS2CPacket.Status.UPDATING) fence = index;
						if (!packet.entries().isEmpty() && data < 0) data = index;
					}
					assertTrue(fence >= 0 && data > fence, "clean fence precedes valid new full-cut fragments for each recipient");
				}
			} finally { old.close(); }
			assertReleased(old);
		}
	}
	@ParameterizedTest @CsvSource({"same-proof", "owner-denied"})
	void currentProofFailureAndOwnerDenialStillInvalidateWhileOtherOwnersStayIndependent(String failure) throws ReflectiveOperationException {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST); var settings = InventoryPooledBackendTest.settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			@Unmodifiable List<MarkerId> ids = InventoryPooledBackendTest.selectFour(f, Blocks.CHEST); boolean[] ran = {false};
			f.beforeValidation = (context, packet) -> {
				resolver.metadataCallback = () -> {};
				if (!ran[0] && ids.getFirst().equals(packet.markerId()) && InventorySyncPublisher.requiresSource(packet)) resolver.metadataCallback = () -> {
					if (ran[0]) return; ran[0] = true;
					if (failure.equals("same-proof")) replace(resolver); else resolver.owners.put(InventoryPublicationBackendFixture.A, false);
				};
			};
			f.backend.tick(21, settings); assertTrue(ran[0]);
			assertTrue((boolean) field(lease(f, ids.getFirst()), "invalid"));
			resolver.metadataCallback = () -> {}; f.beforeValidation = (context, packet) -> {};
			if (failure.equals("owner-denied")) for (int tick = 22; tick < 30; tick++) f.backend.tick(tick, settings);
			else f.backend.tick(22, settings);
			assertTrue(f.frames.stream().anyMatch(packet -> ids.getFirst().equals(packet.markerId()) && packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.status() == InventoryS2CPacket.Status.INVALID), "failure-only controls remain serviceable without a valid source grant");
			if (failure.equals("owner-denied")) for (var client : f.clients.values()) for (MarkerId id : ids.subList(2, 4)) assertTrue(client.tracking(id).complete());
			assertEquals(0, resolver.deniedReads);
		}
	}
	@Test void failedHistoricalOwnerAuthorityStillDiscardsADifferentlyProvenNewCompletion() throws ReflectiveOperationException {
		var settings = InventoryPooledBackendTest.settings(false, false); var planner = new InventoryPooledRuntimeTest.Planner();
		int[] generation = {0}; boolean[] replace = {false};
		var source = new InventoryRuntime.SourceResolver() {
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) {
				if (replace[0]) { replace[0] = false; generation[0]++; }
				var delegate = planner.source(input, cap); var port = delegate.port; int opened = generation[0];
				return Optional.of(new InventorySourceAccess.Source() {
					final InventorySharedRead.Port boundPort = makePort();
					@Override public nx.pingwheel.common.presentation.source.SourceKey key() { return delegate.key(); }
					@Override public long sourceRetainedBytes() { return delegate.sourceRetainedBytes(); }
					@Override public InventorySourceAccess.ReadMode readMode() { return delegate.readMode(); }
					@Override public int slots() { return delegate.slots(); }
					@Override public boolean stableCursor() { return true; }
					@Override public boolean valid() { return delegate.valid(); }
					@Override public int displayBytes() { return cap; }
					@Override public long nativeCatalogGrowthUpperBound() { return delegate.nativeCatalogGrowthUpperBound(); }
					@Override public InventoryItemCodec.@Nullable Catalog nativeCatalog() { return delegate.nativeCatalog(); }
					@Override public InventoryDomainCodec.@Nullable Item read(int slot, InventoryCatalogAdmission admission) { return delegate.read(slot, admission); }
					@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new AssertionError("certified read only"); }
					@Override public InventorySourceAccess.Page enumerate(int limit) { throw new AssertionError("indexed"); }
					@Override public void close() { delegate.close(); }
					@Override public InventorySharedRead.Port sharedRead() { return boundPort; }
					private InventorySharedRead.Port makePort() {
						return new InventorySharedRead.Port() {
							final InventorySharedRead.Proof expected = new InventorySharedRead.Proof(port.proof().graph(), new InventorySharedRead.Witness(opened == 0 ? planner.world : planner), port.proof().registries(), port.proof().members(), port.proof().routeEvidence());
							@Override public InventorySharedRead.Proof proof() { return expected; }
							@Override public long retainedBytes() { return port.retainedBytes(); }
							@Override public InventoryValidationBudget.Cost validationCost() { return port.validationCost(); }
							@Override public InventoryValidationBudget.Cost readValidationCost(int slots) { return port.readValidationCost(slots); }
							@Override public InventoryValidationBudget.Cost catalogCreationCost() { return port.catalogCreationCost(); }
							@Override public InventorySharedRead.Check validate(InventorySharedRead.Proof proof, InventoryValidationBudget.Allowance allowance) {
								allowance.debit(validationCost());
								return opened == 0 && generation[0] != 0 ? InventorySharedRead.Check.AUTHORITY_DENIED : InventorySharedRead.Check.VALID;
							}
							@Override public InventoryItemCodec.Catalog createCatalog(int cap, InventoryValidationBudget.Allowance allowance) { return port.createCatalog(cap, allowance); }
							@Override public InventoryDomainCodec.@Nullable Item read(int slot, InventorySharedRead.Proof proof, InventoryItemCodec.Catalog catalog, InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return port.read(slot, port.proof(), catalog, admission, allowance); }
						};
					}
				});
			}
		};
		try (var f = new InventoryPublicationBackendFixture(source, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			MarkerId marker = select(f); f.holdData = true; f.backend.tick(21, settings);
			Object tracking = lease(f, marker); var old = (InventoryPublicationEvidence) field(tracking, "completedEvidence"); old.retain();
			try {
				// Unlike a structural failure, typed owner denial remains binding-wide even when newer proof validates independently.
				@Nullable InventoryPublicationEvidence[] newer = {null};
				f.beforeValidation = (context, packet) -> { if (InventorySyncPublisher.requiresSource(packet)) try {
					newer[0] = (InventoryPublicationEvidence) field(tracking, "completedEvidence");
					assertNotEquals(old.proof(), newer[0].proof()); assertEquals(InventorySharedRead.Check.VALID, newer[0].validate(new InventoryValidationBudget.Allowance(newer[0].validationCost())));
				} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); } };
				replace[0] = true; f.holdData = false; f.backend.tick(31, settings);
				assertNotNull(newer[0]); assertTrue((boolean) field(tracking, "invalid"));
				assertTrue(f.clients.values().stream().noneMatch(client -> client.tracking(marker).complete()));
			} finally { old.close(); }
		}
		assertEquals(0, planner.open); for (var opened : planner.sources) assertEquals(1, opened.closeCalls);
	}
	@Test void repairedCutSurvivesTransportDeferralAndStillTearsDownOnExpiry() throws ReflectiveOperationException {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST); boolean[] swap = {false};
		var source = new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return resolver.resolutionValidationCost(input); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
				if (swap[0]) { swap[0] = false; replace(resolver); }
				return resolver.resolve(input, cap, allowance);
			}
		};
		var settings = InventoryPooledBackendTest.settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(source, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			MarkerId marker = select(f); f.holdData = true; f.backend.tick(21, settings);
			Object tracking = lease(f, marker); swap[0] = true; f.holdData = false; f.backend.tick(31, settings);
			assertFalse((boolean) field(tracking, "invalid")); var fresh = (InventoryPublicationEvidence) field(tracking, "completedEvidence");
			int[] measurements = {0}; f.measured = packet -> { if (InventorySyncPublisher.requiresSource(packet)) { measurements[0]++; throw new IllegalArgumentException("fixture encoding refusal"); } };
			int audits = resolver.world.audits; f.backend.tick(32, settings);
			assertTrue(measurements[0] > 0); assertEquals(audits, resolver.world.audits);
			assertTrue(f.frames.stream().filter(packet -> packet.markerId() != null && packet.baselineId() > 0).noneMatch(packet -> !packet.entries().isEmpty()), "only empty recovery fences can hand off during encoding refusal");
			assertTrue((int) field(fresh, "references") > 0);
			f.backend.tick(10000, settings); assertFalse(f.backend.tracksInventory(marker)); assertReleased(fresh);
		}
	}
	@Test void metadataCallbackMayAttemptReentrantTickWithoutResettingPublicationLedgers() {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST); var settings = InventorySettings.serverDefaults();
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, InventoryPooledBackendTest.group(Blocks.CHEST))) {
			MarkerId marker = select(f); boolean[] called = {false};
			f.beforeValidation = (context, packet) -> { if (InventorySyncPublisher.requiresSource(packet) && !called[0]) resolver.metadataCallback = () -> {
				if (called[0]) return; called[0] = true; f.backend.tick(99, settings);
			}; };
			f.backend.tick(21, settings); assertTrue(called[0]); resolver.metadataCallback = () -> {};
			assertTrue(f.clients.values().stream().allMatch(client -> client.tracking(marker).complete()));
		}
	}
}
