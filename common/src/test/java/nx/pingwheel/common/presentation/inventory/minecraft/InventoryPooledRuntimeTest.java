package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.*;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent nine-address planner: no source implementation decides expected read or replay counts. */
@NotNullByDefault
class InventoryPooledRuntimeTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest");
	static final InventoryValidationBudget.Cost VALIDATION = new InventoryValidationBudget.Cost(32, 64, 10);
	static final InventoryValidationBudget.Cost READ = new InventoryValidationBudget.Cost(64, 128, 20);
	static InventorySourceInput input(int owner, BlockFace face) { return new InventorySourceInput(TARGET, new UUID(7, owner), face); }
	static InventorySettings settings(boolean faces, boolean players) {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(256);
		settings.getTracking().setDistinguishFaces(faces); settings.getTracking().setDistinguishPlayers(players); return settings;
	}
	@NotNullByDefault
	static final class Planner {
		final Object world = new Object(), member = new Object(), registry = RegistryAccess.EMPTY;
		final Map<InventorySourceInput, Boolean> denied = new HashMap<>();
		final List<InventorySourceInput> samplers = new ArrayList<>();
		final List<Integer> addresses = new ArrayList<>();
		final List<Source> sources = new ArrayList<>();
		boolean supported = true, structure = true, failCapacity, differentWorlds, differentMembers, differentMaps;
		boolean distinctVariants;
		int catalogEntries = InventoryItemCodec.DEFAULT_MAX_ENTRIES;
		int callbacks, open, failAt = -1;
		long amount = 3_000_003L;
		@Nullable String json;
		Runnable duringRead = () -> {}, duringCheck = () -> {};
		Proof proof(InventorySourceInput input) {
			@Unmodifiable List<Integer> slots = differentMaps && input.face() == BlockFace.SOUTH ? List.of(8,7,6,5,4,3,2,1,0) : List.of(0,1,2,3,4,5,6,7,8);
			var layout = new InventorySnapshotLayout("fixture", "", BlockPos.ZERO, List.of(new InventorySnapshotLayout.Member(BlockPos.ZERO,
				"minecraft:chest", "minecraft:chest", "member", List.of(new InventorySnapshotLayout.Segment("items", "slots", 9, slots)))));
			return new Proof(new Graph("fixture", "minecraft:overworld", "same", Route.VANILLA_CHEST, layout),
				new Witness(differentWorlds && input.readOwner().getLeastSignificantBits() == 2 ? this : world), new Witness(registry),
				List.of(new Witness(differentMembers && input.readOwner().getLeastSignificantBits() == 2 ? this : member)),
				List.of(new RouteEvidence(new Witness(member), null, null)));
		}
		Source source(InventorySourceInput input, int cap) { var source = new Source(input, cap); sources.add(source); return source; }
		@NotNullByDefault
		final class Source implements InventorySourceAccess.Source {
			final InventorySourceInput input;
			final Port port;
			final int cap;
			boolean closed;
			int closeCalls;
			InventoryItemCodec.@Nullable Catalog legacyCatalog;
			Source(InventorySourceInput input, int cap) {
				this.input = input; this.cap = cap; open++;
				var proof = proof(input);
				port = new Port() {
					@Override public Proof proof() { return proof; }
					@Override public long retainedBytes() { return 2048; }
					@Override public InventoryValidationBudget.Cost validationCost() { return VALIDATION; }
					@Override public InventoryValidationBudget.Cost readValidationCost(int slots) { return READ.times(slots); }
					@Override public InventoryValidationBudget.Cost catalogCreationCost() { return VALIDATION; }
					@Override public Check validate(Proof expected, InventoryValidationBudget.Allowance allowance) {
						try { allowance.debit(VALIDATION); } catch (InventoryItemBudget.CaptureLimitException refused) { return Check.DEFERRED; }
						callbacks++; duringCheck.run(); return check(expected);
					}
					@Override public InventoryItemCodec.Catalog createCatalog(int ceiling, InventoryValidationBudget.Allowance allowance) {
						allowance.debit(VALIDATION); require(check(proof)); return new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, catalogEntries, InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES, ceiling);
					}
					@Override public InventoryDomainCodec.@Nullable Item read(int slot, Proof expected, InventoryItemCodec.Catalog catalog,
						InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
						allowance.debit(READ); require(check(expected));
						var item = value(slot, catalog, admission); duringRead.run(); require(check(expected)); return item;
					}
				};
			}
			Check check(Proof expected) {
				if (closed || !structure) return Check.STRUCTURE_INVALID;
				if (!proof(input).equals(expected)) return Check.STRUCTURE_INVALID;
				return denied.getOrDefault(input, false) ? Check.AUTHORITY_DENIED : Check.VALID;
			}
			void require(Check check) { if (check != Check.VALID) throw new ReadFailure(check); }
			InventoryDomainCodec.Item value(int slot, InventoryItemCodec.Catalog catalog, InventoryCatalogAdmission admission) {
				callbacks++; samplers.add(input); addresses.add(slot);
				if (slot == failAt) { if (failCapacity) throw new InventoryItemBudget.CaptureLimitException("fixture item work"); throw new IllegalStateException("getter failure"); }
				var detached = new InventoryDetachedItem("minecraft:stone", InventoryIdentityMode.EXACT,
					new InventoryItemCodec.Display("minecraft:stone", "Stone", json, false), new byte[] {(byte) (distinctVariants ? slot + 1 : 1)}, 64 + (json == null ? 0 : json.length() * 2L));
				var encoded = catalog.intern(detached, admission);
				return new InventoryDomainCodec.Item(encoded.key(), amount + slot, encoded.display().label(), encoded.display().displayJson(), false);
			}
			@Override public @Nullable Port sharedRead() { return supported ? port : null; }
			@Override public SourceKey key() { return new SourceKey("fixture", "inventory", "same", input.viewKey()); }
			@Override public long sourceRetainedBytes() { return 4096; }
			@Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
			@Override public boolean valid() { callbacks++; return !closed && structure && !denied.getOrDefault(input, false); }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 9; }
			@Override public int displayBytes() { return cap; }
			@Override public long nativeCatalogGrowthUpperBound() { return InventoryItemCodec.Catalog.firstReadGrowthUpperBound(cap); }
			@Override public InventoryItemCodec.@Nullable Catalog nativeCatalog() { return legacyCatalog; }
			@Override public InventoryDomainCodec.Item read(int slot) { throw new AssertionError("missing catalogue admission"); }
			@Override public InventoryDomainCodec.Item read(int slot, InventoryCatalogAdmission admission) {
				if (legacyCatalog == null) legacyCatalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, cap);
				return value(slot, legacyCatalog, admission);
			}
			@Override public InventorySourceAccess.Page enumerate(int limit) { throw new AssertionError("indexed"); }
			@Override public void close() { closeCalls++; if (!closed) { closed = true; open--; } }
		}
		InventoryRuntime runtime(InventorySettings settings) {
			return new InventoryRuntime((input, cap) -> Optional.of(source(input, cap)), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
		}
	}
	static InventoryRuntime.Consumer attach(InventoryRuntime runtime, InventorySourceInput input) { return runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow(); }
	static List<InventoryRuntime.Consumer> four(InventoryRuntime runtime) {
		return List.of(attach(runtime, input(1, BlockFace.NORTH)), attach(runtime, input(1, BlockFace.SOUTH)), attach(runtime, input(2, BlockFace.NORTH)), attach(runtime, input(2, BlockFace.SOUTH)));
	}
	static long finish(InventoryRuntime runtime, InventoryRuntime.Consumer consumer) {
		long total = 0;
		for (int page = 0; page < 12; page++) {
			var observation = runtime.step(consumer, 3).orElseThrow();
			assertEquals(CaptureResult.Availability.READABLE, observation.result().availability());
			for (var item : observation.slots()) total += java.util.Objects.requireNonNull(item).count();
			if (observation.result().completeness() == CaptureResult.Completeness.COMPLETE) { consumer.retainCompleted(); return total; }
		}
		throw new AssertionError("no complete scan");
	}
	@ParameterizedTest @CsvSource({"true,true,36", "true,false,18", "false,true,18", "false,false,9"})
	void fourIndependentBindingsShareOnlyTheApprovedDimensions(boolean faces, boolean players, int getters) throws ReflectiveOperationException {
		var planner = new Planner(); var settings = settings(faces, players); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var consumers = four(runtime);
			for (var consumer : consumers) assertEquals(27_000_063L, finish(runtime, consumer));
			assertEquals(getters, planner.addresses.size());
			@SuppressWarnings("unchecked") var paid = (Map<InventoryRuntime.Subject, Integer>) field(runtime, "logicalUsed");
			assertEquals(getters, paid.values().stream().mapToInt(Integer::intValue).sum(), "same grouped target pays the committed prefix once per physical round");
			assertNotEquals(input(1, BlockFace.NORTH).viewKey(), input(2, BlockFace.SOUTH).viewKey());
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void unsupportedAndOrderedMappingAndWorldIdentityCannotAcquireWidenedSharing() {
		for (String boundary : List.of("unsupported", "mapping", "world", "member")) {
			var planner = new Planner(); planner.supported = !boundary.equals("unsupported"); planner.differentMaps = boundary.equals("mapping"); planner.differentWorlds = boundary.equals("world");
			planner.differentMembers = boundary.equals("member");
			var settings = settings(false, false); try (var runtime = planner.runtime(settings)) {
				runtime.advance(0, settings); for (var consumer : four(runtime)) finish(runtime, consumer);
				assertEquals(boundary.equals("unsupported") ? 36 : 18, planner.addresses.size(), boundary);
			}
			assertEquals(0, planner.open);
		}
	}
	@Test void failedPageDoesNotAppendAndNextSamplerUsesItsOwnPortAfterReplayingPrefix() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH));
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(a)); assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(b));
			assertEquals(3, runtime.step(a, 3).orElseThrow().slots().size());
			planner.duringRead = () -> { if (planner.addresses.getLast() == 5) planner.denied.put(a.input(), true); };
			assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(a, 3).orElseThrow().result().availability()); assertTrue(a.invalidated());
			planner.duringRead = () -> {};
			assertEquals(27_000_063L, finish(runtime, b));
			assertEquals(List.of(0,1,2,3,4,5,3,4,5,6,7,8), planner.addresses);
			for (int index = 6; index < 12; index++) assertEquals(b.input(), planner.samplers.get(index));
			assertFalse(b.invalidated());
		}
		assertEquals(0, planner.open);
	}
	@Test void creatorDepartureAndBindingRevocationDoNotRemovePeerDataAndEvidenceRetainsOnlyExactLease() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH));
			runtime.prepare(a); runtime.prepare(b); runtime.step(a, 3).orElseThrow(); a.close(); assertEquals(1, planner.open);
			assertEquals(27_000_063L, finish(runtime, b)); assertEquals(9, planner.addresses.size());
			try (var evidence = runtime.retainEvidence(b, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
				b.close(); assertEquals(1, planner.open); planner.denied.put(input(2, BlockFace.SOUTH), true);
				assertEquals(Check.AUTHORITY_DENIED, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
			}
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained());
	}
	@Test void mutableSameTickPolicyChangeSealsOldRoundsWithoutInvalidatingExistingAssociations() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH));
			runtime.prepare(a); runtime.prepare(b); runtime.step(a, 3).orElseThrow();
			settings.getTracking().setDistinguishPlayers(true); settings.getTracking().setDistinguishFaces(true); runtime.advance(0, settings);
			var late = attach(runtime, input(1, BlockFace.NORTH)); assertEquals(27_000_063L, finish(runtime, late));
			assertEquals(27_000_063L, finish(runtime, b));
			assertEquals(18, planner.addresses.size()); assertFalse(a.invalidated()); assertFalse(b.invalidated());
		}
	}
	@Test void lateSameTickAttachAndPreviewNeverInheritWidenedTrackingData() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH));
			var preview = runtime.attach(a.input(), new InventoryRuntime.PreviewSubject(a.input().readOwner())).orElseThrow();
			assertEquals(27_000_063L, finish(runtime, a)); assertEquals(27_000_063L, finish(runtime, preview));
			var late = attach(runtime, a.input()); assertEquals(27_000_063L, finish(runtime, late)); assertEquals(27, planner.addresses.size());
		}
	}
	@Test void refusalBeforeCallbacksPreservesCommittedCursorAndReleasesScratch() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.prepare(a);
			int callbacks = planner.callbacks; runtime.memory().setCap(runtime.memory().retained() + VALIDATION.workspaceBytes() - 1);
			assertTrue(runtime.step(a, 3).isEmpty()); assertEquals(callbacks, planner.callbacks); assertTrue(planner.addresses.isEmpty()); assertEquals(0, runtime.memory().reserved());
			runtime.memory().setCap(settings.pendingMemoryBytes()); assertEquals(27_000_063L, finish(runtime, a)); assertEquals(9, planner.addresses.size());
		} assertEquals(0, runtime.memory().retained());
	}
	@Test void preexistingSuccessorEntitlementSurvivesFlipButNewOldPolicyEntitlementsCannotAppear() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH));
			finish(runtime, a); finish(runtime, b); a.restart(); assertEquals(27_000_063L, finish(runtime, a));
			assertEquals(18, planner.addresses.size());
			settings.getTracking().setDistinguishFaces(true); settings.getTracking().setDistinguishPlayers(true); runtime.advance(0, settings);
			b.restart(); assertEquals(27_000_063L, finish(runtime, b)); assertEquals(18, planner.addresses.size());
			a.restart(); finish(runtime, a); b.restart(); finish(runtime, b); assertEquals(36, planner.addresses.size());
			settings.getTracking().setDistinguishFaces(false); settings.getTracking().setDistinguishPlayers(false); runtime.advance(0, settings);
			a.restart(); finish(runtime, a); b.restart(); finish(runtime, b); assertEquals(45, planner.addresses.size());
		}
		assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void laggingPeersKeepProgressAcrossManySharedSuccessorsAndReleaseEveryOwner() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH));
			for (int cycle = 0; cycle < 32; cycle++) {
				runtime.advance(cycle * 40L, settings);
				assertEquals(27_000_063L, finish(runtime, a)); assertEquals(27_000_063L, finish(runtime, b));
				assertEquals((cycle + 1) * 9, planner.addresses.size()); a.restart(); b.restart();
			}
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void capacityPreservesOnlyValidatedPrefixAndUntypedFailureRetiresAllProofPeers() {
		for (boolean capacity : List.of(true, false)) {
			var planner = new Planner(); planner.failAt = 5; planner.failCapacity = capacity;
			var settings = settings(false, false); var runtime = planner.runtime(settings);
			try (runtime) {
				runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH)); runtime.prepare(a); runtime.prepare(b);
				runtime.step(a, 3).orElseThrow(); var result = runtime.step(a, 3).orElseThrow();
				assertEquals(CaptureResult.Completeness.INCOMPLETE, result.result().completeness());
				if (capacity) {
					assertEquals(2, result.slots().size()); assertFalse(a.invalidated());
					assertEquals(3, runtime.step(b, 3).orElseThrow().slots().size()); assertEquals(2, runtime.step(b, 3).orElseThrow().slots().size());
				} else { assertTrue(a.invalidated()); assertTrue(b.invalidated()); assertTrue(result.slots().isEmpty()); }
				assertEquals(6, planner.addresses.size());
			} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void bindingDenialDuringReplayOrPeerOperationDoesNotResetReadablePeer() {
		for (boolean peerOperation : List.of(true, false)) {
			var planner = new Planner(); var settings = settings(false, false);
			try (var runtime = planner.runtime(settings)) {
				runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH)); runtime.prepare(a); runtime.prepare(b);
				runtime.step(b, 3).orElseThrow();
				if (peerOperation) { planner.duringRead = () -> planner.denied.put(a.input(), true); runtime.step(b, 3).orElseThrow(); planner.duringRead = () -> {}; }
				else planner.denied.put(a.input(), true);
				assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(a, 3).orElseThrow().result().availability());
				assertTrue(a.invalidated()); assertFalse(b.invalidated()); finish(runtime, b); assertEquals(9, planner.addresses.size());
			}
		}
	}
	@Test void exactOnOnRetainsExistingPreviewCrossDomainSharingAndProductionConstructorStaysLegacy() {
		for (boolean gate : List.of(true, false)) {
			var planner = new Planner(); var settings = settings(gate, gate);
			try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(planner.source(input, cap)), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, gate)) {
				runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH));
				var preview = runtime.attach(a.input(), new InventoryRuntime.PreviewSubject(a.input().readOwner())).orElseThrow();
				finish(runtime, a); finish(runtime, preview); assertEquals(9, planner.addresses.size());
			}
		}
	}
	@Test void sealedRoundNeverReopensOnRapidReverseAndPeerEntitlementBoundsFastReplacement() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH));
			finish(runtime, a); finish(runtime, b); a.restart(); finish(runtime, a);
			a.restart(); assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(a)); assertFalse(a.invalidated());
			b.restart(); finish(runtime, b); finish(runtime, a); assertEquals(27, planner.addresses.size());
			settings.getTracking().setDistinguishFaces(true); runtime.advance(0, settings);
			var between = attach(runtime, input(1, BlockFace.NORTH)); finish(runtime, between);
			settings.getTracking().setDistinguishFaces(false); runtime.advance(0, settings);
			var after = attach(runtime, input(1, BlockFace.NORTH)); finish(runtime, after); assertEquals(45, planner.addresses.size());
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained());
	}
	@Test void itemProcessingAndCatalogHistoryRemainPaidWithAmountsAboveDoubleIntegerPrecision() {
		var planner = new Planner(); planner.amount = 9_007_199_254_740_993L; var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH));
			runtime.prepare(a); runtime.prepare(b); var first = runtime.step(a, 3).orElseThrow(); var replay = runtime.step(b, 3).orElseThrow();
			assertEquals(3, planner.addresses.size()); assertEquals(planner.amount, first.slots().getFirst().count()); assertEquals(first.slots(), replay.slots());
			assertNull(planner.sources.getFirst().legacyCatalog); assertNull(planner.sources.getLast().legacyCatalog);
		}
	}
	@Test void itemWorkOneShortRefusesMetadataBeforeCallbacksAndCanRetryWithoutCursorMovement() throws ReflectiveOperationException {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.prepare(a);
			var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true);
			var work = new nx.pingwheel.common.presentation.source.CostLedger.Counter("inventory/item_work", nx.pingwheel.common.presentation.source.CostLedger.Unit.WORK);
			var ledger = new nx.pingwheel.common.presentation.source.CostLedger(Map.of(work, 9L)); field.set(runtime, ledger);
			int callbacks = planner.callbacks; assertTrue(runtime.step(a, 3).isEmpty()); assertEquals(callbacks, planner.callbacks); assertTrue(planner.addresses.isEmpty());
			runtime.advance(1, settings); assertEquals(27_000_063L, finish(runtime, a)); assertEquals(9, planner.addresses.size());
		} assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	static Object field(Object owner, String name) throws ReflectiveOperationException { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return java.util.Objects.requireNonNull(field.get(owner)); }
	@Test void uniqueCatalogTighteningRefreshesJournalBeforeReplayWithoutGettersAndCollisionHistorySpansSamplers() throws ReflectiveOperationException {
		var planner = new Planner(); planner.json = InventoryDisplayWriter.project(com.google.gson.JsonParser.parseString(
			"{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_name\":\"" + "x".repeat(1500) + "\"}}"), "minecraft:stone", 4096);
		var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH)); runtime.prepare(a); runtime.prepare(b);
			var first = runtime.step(a, 3).orElseThrow(); assertNotNull(first.slots().getFirst().displayJson());
			var round = field(a, "round"); var sweep = field(round, "sweep"); var catalog = (InventoryItemCodec.Catalog) field(sweep, "catalog");
			assertEquals(1, catalog.displays().size());
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(a, InventoryItemCodec.minimumDisplayBytes()).status());
			assertEquals(3, planner.addresses.size()); var replay = runtime.step(b, 3).orElseThrow();
			assertEquals(first.slots().getFirst().key(), replay.slots().getFirst().key()); assertEquals(1, ((InventorySlotJournal) field(round, "journal")).definitionCount());
			assertEquals(catalog.retainedDisplay(first.slots().getFirst().key()).displayJson(), replay.slots().getFirst().displayJson());
			@SuppressWarnings("unchecked") var known = (Map<String, byte[]>) field(catalog, "canonicalByDigest");
			known.put(first.slots().getFirst().key().componentsKey(), new byte[] {99});
			assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(b, 3).orElseThrow().result().availability());
			assertTrue(a.invalidated()); assertTrue(b.invalidated()); assertEquals(4, planner.addresses.size());
		}
	}
	@Test void selectedEvidenceScopeRetainsOldCompletionIndependentlyOfNewScanAndSourceClosure() throws ReflectiveOperationException {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH));
			assertTrue(runtime.retainEvidence(a, InventoryRuntime.EvidenceScope.COMPLETED).isEmpty()); finish(runtime, a);
			try (var evidence = runtime.retainEvidence(a, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
				a.restart(); runtime.prepare(a); runtime.step(a, 3).orElseThrow();
				assertEquals(Check.VALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
				a.close(); assertEquals(1, planner.open);
				assertEquals(Check.VALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
			}
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained());
	}
	@Test void distinctLogicalTargetsSharePhysicalReadsButEachPaysItsOwnLogicalCoverage() throws ReflectiveOperationException {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH));
			var other = new Target.BlockTarget("minecraft:overworld", 10, 0, 0, "minecraft:chest");
			var b = runtime.attach(input(2, BlockFace.SOUTH), new InventoryRuntime.TrackingSubject(TargetKey.from(other))).orElseThrow();
			finish(runtime, a); finish(runtime, b); assertEquals(9, planner.addresses.size());
			@SuppressWarnings("unchecked") var paid = (Map<InventoryRuntime.Subject, Integer>) field(runtime, "logicalUsed");
			assertEquals(2, paid.size()); assertEquals(18, paid.values().stream().mapToInt(Integer::intValue).sum());
		}
	}
	@Test void finalPageCheckDenialRollsBackAllNewSlotsAndKeepsPeersCompletedObservation() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH));
			runtime.prepare(a); runtime.prepare(b); finish(runtime, a); finish(runtime, b); a.restart(); b.restart();
			runtime.prepare(a); runtime.prepare(b); runtime.step(a, 3).orElseThrow();
			planner.duringCheck = () -> { if (planner.addresses.size() == 15) planner.denied.put(a.input(), true); };
			assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(a, 3).orElseThrow().result().availability());
			assertFalse(b.invalidated()); planner.duringCheck = () -> {};
			try (var evidence = runtime.retainEvidence(b, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
				assertEquals(Check.VALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
			}
			assertEquals(27_000_063L, finish(runtime, b)); assertEquals(21, planner.addresses.size());
			assertEquals(List.of(0,1,2,3,4,5,3,4,5,6,7,8), planner.addresses.subList(9,21));
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained());
	}
	@Test void commonProofFailureRetiresSamePhysicalEvidenceAcrossFrozenPolicyCohorts() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var old = attach(runtime, input(1, BlockFace.NORTH)); runtime.prepare(old); runtime.step(old, 3).orElseThrow();
			settings.getTracking().setDistinguishFaces(true); runtime.advance(0, settings);
			var current = attach(runtime, input(2, BlockFace.SOUTH)); runtime.prepare(current); runtime.step(current, 3).orElseThrow();
			planner.structure = false; assertFalse(runtime.probe(current).orElseThrow()); assertTrue(old.invalidated()); assertTrue(current.invalidated());
			assertEquals(6, planner.addresses.size());
		} assertEquals(0, planner.open);
	}
	@Test void actualSharedCatalogEntryCapacityKeepsSuccessfulPagePrefixExplicitlyIncomplete() {
		var planner = new Planner(); planner.catalogEntries = 2; planner.distinctVariants = true; var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var b = attach(runtime, input(2, BlockFace.SOUTH)); runtime.prepare(a); runtime.prepare(b);
			var first = runtime.step(a, 3).orElseThrow(); assertEquals(CaptureResult.Completeness.INCOMPLETE, first.result().completeness());
			assertEquals(CaptureResult.Availability.READABLE, first.result().availability()); assertEquals(2, first.slots().size());
			var peer = runtime.step(b, 3).orElseThrow(); assertEquals(first.slots(), peer.slots()); assertEquals(CaptureResult.Completeness.INCOMPLETE, peer.result().completeness());
			assertEquals(3, planner.addresses.size()); assertThrows(IllegalStateException.class, a::retainCompleted);
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@ParameterizedTest @CsvSource({"false,true", "true,true", "false,false", "true,false"})
	void thirtyTwoAbandonedReplacementsReleaseCapacityWhileIdlePeersKeepCompletedEvidence(boolean incomplete, boolean pooling) throws ReflectiveOperationException {
		var planner = new Planner(); var settings = settings(false, true);
		var runtime = new InventoryRuntime((input, cap) -> Optional.of(planner.source(input, cap)), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, pooling);
		var fast = new ArrayList<InventoryRuntime.Consumer>(); var slow = new ArrayList<InventoryRuntime.Consumer>();
		try (runtime) {
			// Each cohort has a distinct real owner but one grouped logical target. Separate ticks restore existing budgets only.
			for (int cohort = 0; cohort < 32; cohort++) {
				runtime.advance(cohort * 40L, settings);
				fast.add(attach(runtime, input(cohort + 1, BlockFace.NORTH))); slow.add(attach(runtime, input(cohort + 1, pooling ? BlockFace.SOUTH : BlockFace.NORTH)));
				assertEquals(27_000_063L, finish(runtime, fast.getLast())); assertEquals(27_000_063L, finish(runtime, slow.getLast()));
				fast.getLast().restart(); slow.getLast().restart(); // retain publication evidence, release original scanning ownership
			}
			assertEquals(32, field(runtime, "retainedRounds"));
			for (int cohort = 0; cohort < 32; cohort++) {
				runtime.advance((32 + cohort) * 40L, settings); var consumer = fast.get(cohort); consumer.restart();
				assertEquals(3, runtime.step(consumer, 3).orElseThrow().slots().size());
				if (incomplete) {
					planner.failCapacity = true; planner.failAt = 5;
					var rejected = runtime.step(consumer, 3).orElseThrow();
					assertEquals(CaptureResult.Completeness.INCOMPLETE, rejected.result().completeness()); assertEquals(2, rejected.slots().size());
					assertThrows(IllegalStateException.class, consumer::retainCompleted); planner.failAt = -1;
				}
			}
			assertEquals(32, field(runtime, "scanningRounds")); assertEquals(64, field(runtime, "retainedRounds"));
			assertTrue(runtime.memory().remaining() > 240_000_000); assertEquals(0, runtime.memory().reserved());
			runtime.advance(5000, settings); fast.forEach(InventoryRuntime.Consumer::restart);
			int ready = 0;
			for (var consumer : fast) if (runtime.prepare(consumer) == InventorySourceAccess.Preparation.READY) ready++;
			assertEquals(32, ready, "idle handoff-only owners must not strand every replacement slot");
			for (var consumer : slow) {
				assertFalse(consumer.invalidated());
				if (pooling) try (var evidence = runtime.retainEvidence(consumer, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
					assertEquals(Check.VALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
				} else assertEquals(Optional.of(true), runtime.probe(consumer));
			}
			assertEquals(32, field(runtime, "scanningRounds")); assertEquals(64, field(runtime, "retainedRounds"));
			assertEquals(0, runtime.memory().reserved());
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@ParameterizedTest @CsvSource({"restart,false", "close,false", "discard,false", "restart,true", "close,true", "discard,true"})
	void abandonedReplacementReleasesOnlyHandoffOwnersAndKeepsPaidQuota(String departure, boolean unacceptedComplete) throws ReflectiveOperationException {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var fast = attach(runtime, input(1, BlockFace.NORTH)); var slow = attach(runtime, input(2, BlockFace.SOUTH));
			finish(runtime, fast); finish(runtime, slow); fast.restart(); slow.restart();
			InventoryRuntime.Observation result = runtime.step(fast, 3).orElseThrow();
			if (unacceptedComplete) {
				runtime.step(fast, 3).orElseThrow(); result = runtime.step(fast, 3).orElseThrow();
				assertEquals(CaptureResult.Completeness.COMPLETE, result.result().completeness()); // deliberately not retained
			}
			Object abandoned = field(fast, "round");
			@SuppressWarnings("unchecked") var paid = (Map<InventoryRuntime.Subject, Integer>) field(runtime, "logicalUsed");
			int spent = paid.values().stream().mapToInt(Integer::intValue).sum();
			int callbacks = planner.callbacks;
			switch (departure) { case "restart" -> fast.restart(); case "close" -> fast.close(); case "discard" -> fast.discardObservation(); default -> throw new AssertionError(departure); }
			assertEquals(callbacks, planner.callbacks, "ownership cleanup cannot invoke source/provider validation");
			assertEquals(true, field(abandoned, "closed")); assertEquals(0, field(abandoned, "references")); assertEquals(0, field(abandoned, "scanners"));
			assertEquals(0, field(runtime, "scanningRounds")); assertEquals(1, field(runtime, "retainedRounds"));
			assertEquals(spent, paid.values().stream().mapToInt(Integer::intValue).sum());
			try (var evidence = runtime.retainEvidence(slow, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
				assertEquals(Check.VALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
			}
			// Resetting successor-established permits a genuinely fresh replacement and a new entitlement for this idle peer.
			runtime.advance(40, settings);
			var next = departure.equals("restart") ? fast : attach(runtime, input(3, BlockFace.NORTH));
			if (next != fast) { finish(runtime, next); next.restart(); }
			assertEquals(27_000_063L, finish(runtime, next)); next.restart();
			int reads = planner.addresses.size(); slow.restart(); assertEquals(27_000_063L, finish(runtime, slow));
			if (next == fast) assertEquals(reads, planner.addresses.size(), "idle peer adopts the fresh accepted successor, not abandoned data");
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
	void lastScannerDepartureKeepsEntitlementsOnlyWhileAcceptedCompletionOwnerRemains(boolean accepted, boolean seal) throws ReflectiveOperationException {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var fast = attach(runtime, input(1, BlockFace.NORTH)); var slow = attach(runtime, input(2, BlockFace.SOUTH));
			finish(runtime, fast); finish(runtime, slow); fast.restart(); slow.restart();
			InventoryRuntime.Observation terminal = runtime.step(fast, 3).orElseThrow();
			while (terminal.result().completeness() == CaptureResult.Completeness.CONTINUE) terminal = runtime.step(fast, 3).orElseThrow();
			if (accepted) fast.retainCompleted();
			Object successor = field(fast, "round"); Object previous = field(slow, "completedRound");
			if (seal) { settings.getTracking().setDistinguishFaces(true); settings.getTracking().setDistinguishPlayers(true); runtime.advance(0, settings); }
			fast.restart();
			assertEquals(!accepted, field(successor, "closed")); assertEquals(seal, field(previous, "sealed"));
			runtime.advance(40, settings); int reads = planner.addresses.size(); slow.restart();
			assertEquals(27_000_063L, finish(runtime, slow));
			assertEquals(reads + (accepted ? 0 : 9), planner.addresses.size(), "accepted entitlement survives seal; abandoned one requires fresh reads");
			if (accepted) { assertEquals(0, field(successor, "incomingEntitlements")); assertEquals(Optional.of(true), runtime.probe(fast)); }
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void multipleHandoffCohortsDetachBeforeLastReleaseWithoutDroppingActualScannerOrAcceptedOwner() throws ReflectiveOperationException {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings);
			var first = attach(runtime, input(1, BlockFace.NORTH)); var firstSlow = attach(runtime, input(2, BlockFace.SOUTH));
			finish(runtime, first); finish(runtime, firstSlow); first.restart(); firstSlow.restart();
			runtime.advance(40, settings);
			var second = attach(runtime, input(3, BlockFace.NORTH)); var secondSlow = attach(runtime, input(4, BlockFace.SOUTH));
			finish(runtime, second); finish(runtime, secondSlow); second.restart(); secondSlow.restart();
			runtime.advance(80, settings); runtime.step(first, 3).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(second)); Object abandoned = field(first, "round");
			assertSame(abandoned, field(second, "round")); assertEquals(2, field(abandoned, "incomingEntitlements"));
			first.restart(); assertEquals(false, field(abandoned, "closed")); assertEquals(1, field(runtime, "scanningRounds"));
			second.close(); // the last actual scanner leaves two different predecessor cohorts' handoff holds
			assertEquals(true, field(abandoned, "closed")); assertEquals(0, field(abandoned, "scanners")); assertEquals(0, field(abandoned, "references"));
			assertEquals(0, field(abandoned, "incomingEntitlements")); assertEquals(0, field(runtime, "scanningRounds"));
			assertEquals(Optional.of(true), runtime.probe(firstSlow)); assertEquals(Optional.of(true), runtime.probe(secondSlow));
			runtime.advance(120, settings); finish(runtime, first); first.restart();
			int reads = planner.addresses.size(); firstSlow.restart(); finish(runtime, firstSlow); assertEquals(reads, planner.addresses.size());
			first.close(); firstSlow.close(); firstSlow.close(); // idempotent consumer close cannot double-close the retained sources
			secondSlow.restart(); finish(runtime, secondSlow); secondSlow.restart(); secondSlow.close();
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		for (var source : planner.sources) assertEquals(1, source.closeCalls, "every separately opened lease closes exactly once");
	}
}
