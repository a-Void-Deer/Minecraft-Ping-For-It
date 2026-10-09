package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRuntimeTest {
	static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	static InventorySourceInput input(UUID owner) { return new InventorySourceInput(TARGET, owner, BlockFace.NORTH); }
	static InventoryDomainCodec.Item item(long count) { return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), count, "stone", null, false); }
	static class Source implements InventorySourceAccess.Source {
		final InventorySourceInput input; final AtomicInteger reads; final int size;
		final AtomicInteger validCalls = new AtomicInteger();
		boolean stable = true, valid = true;
		Source(InventorySourceInput input, AtomicInteger reads, int size) { this.input = input; this.reads = reads; this.size = size; }
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "same-container", input.viewKey()); }
		@Override public boolean valid() { validCalls.incrementAndGet(); return valid; }
		@Override public boolean stableCursor() { return stable; }
		@Override public int slots() { return size; }
		@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return slot == 0 ? item(7) : null; }
		@Override public InventorySourceAccess.Page enumerate(int limit) {
			var values = new java.util.ArrayList<InventoryDomainCodec.Item>();
			for (int i = 0; i < Math.min(limit, size); i++) values.add(read(i));
			return new InventorySourceAccess.Page(values, size <= limit);
		}
	}
	@Test void onePhysicalReadAndDistinctClientAndTargetLogicalSubjects() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1);
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 2)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var duplicate = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(new UUID(2, 2))).orElseThrow();
			var tracking = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size());
			assertEquals(1, runtime.step(duplicate).orElseThrow().slots().size());
			assertEquals(1, runtime.step(b).orElseThrow().slots().size());
			assertEquals(1, runtime.step(tracking).orElseThrow().slots().size());
			assertEquals(1, reads.get(), "four consumers share one admitted physical prefix");
			assertTrue(runtime.step(a).isEmpty(), "client quota survives duplicate requests");
			assertTrue(runtime.step(tracking).isEmpty(), "target quota is independent but finite");
			runtime.advance(5, settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(a).orElseThrow().result().completeness());
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(tracking).orElseThrow().result().completeness());
			assertEquals(2, reads.get(), "observed blanks are physical slots too");
		}
	}
	@Test void memoryDeferHasNoResolverOrReadAndRetainedLifetimeEndsOnLastClose() {
		AtomicInteger probes = new AtomicInteger(), reads = new AtomicInteger(); var settings = InventorySettings.serverDefaults();
		var owner = new UUID(1, 1);
		try (var runtime = new InventoryRuntime(i -> { probes.incrementAndGet(); return Optional.of(new Source(i, reads, 1)); }, 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.memory().setCap(runtime.memory().retained());
			assertTrue(runtime.step(consumer).isEmpty()); assertEquals(0, probes.get()); assertEquals(0, reads.get());
			runtime.memory().setCap(16000000); assertTrue(runtime.step(consumer).isPresent());
			long live = runtime.memory().retained(); assertTrue(live > 4096);
			consumer.close(); assertEquals(1024, runtime.memory().retained(), "quota subject survives consumer close until the period ends");
			assertEquals(0, runtime.memory().reserved()); runtime.advance(5, settings); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void cursorlessEnumerationCountsBlankViewsAndCannotInventContinuation() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
		try (var runtime = new InventoryRuntime(i -> { Source source = new Source(i, reads, 3); source.stable = false; return Optional.of(source); }, 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var observation = runtime.step(consumer).orElseThrow();
			assertEquals(2, reads.get()); assertEquals(2, observation.slots().size());
			assertNull(observation.slots().get(1)); assertEquals(CaptureResult.Completeness.INCOMPLETE, observation.result().completeness());
			runtime.advance(5, settings); runtime.step(consumer); assertEquals(2, reads.get());
		}
	}
	@Test void ownerAndFrozenFaceArePhysicalSharingBoundaries() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var b = runtime.attach(new InventorySourceInput(TARGET, owner, BlockFace.SOUTH), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(2, reads.get());
			assertThrows(NullPointerException.class, () -> new InventorySourceInput(TARGET, owner, null));
		}
	}
	@Test void sameOriginalBlockStillResolvesSeparatelyForDifferentOwnersAndFrozenFaces() {
		var owner = new UUID(17, 1); var other = new UUID(17, 2); var resolutions = new AtomicInteger();
		var reads = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet();
			return Optional.of(new Source(i, reads, 2));
		}, 16_000_000)) {
			runtime.advance(0, InventorySettings.serverDefaults());
			var first = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var anotherOwner = runtime.attach(input(other), new InventoryRuntime.PreviewSubject(other)).orElseThrow();
			var anotherFace = runtime.attach(new InventorySourceInput(TARGET, owner, BlockFace.SOUTH), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(first, 1).orElseThrow(); runtime.step(anotherOwner, 1).orElseThrow(); runtime.step(anotherFace, 1).orElseThrow();
			assertEquals(3, resolutions.get()); assertEquals(3, reads.get(), "same-binding shortcut must not bypass the owner/face source key");
			runtime.retireInvalid(input(owner)); assertTrue(first.invalidated());
			assertFalse(anotherOwner.invalidated()); assertFalse(anotherFace.invalidated());
		}
	}
	@Test void repeatedTrackingPingsSharePhysicalAndTargetQuotaAndFreshSelectSkipsOlderPreviewSweep() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 3)), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); runtime.advance(1, settings); runtime.step(preview);
			runtime.advance(2, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size()); assertEquals(2, reads.get(), "new SELECT must not inherit pre-selection preview prefix");
			assertEquals(1, runtime.step(b).orElseThrow().slots().size()); assertEquals(2, reads.get());
			assertTrue(runtime.step(a).isEmpty()); assertTrue(runtime.step(b).isEmpty(), "another Ping is not another TargetKey allowance");
			a.close(); runtime.advance(5, settings); assertEquals(1, runtime.step(b).orElseThrow().slots().size());
		}
	}
	@Test void changedCadenceCannotRefundLogicalUsageAndProviderWorkExhaustionDefersBeforeLookup() {
		AtomicInteger probes = new AtomicInteger(), reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> { probes.incrementAndGet(); return Optional.of(new Source(i, reads, 3)); }, 16000000)) {
			runtime.advance(0, settings); var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(a);
			settings.getPreview().setPeriodTicks(1); runtime.advance(1, settings); assertTrue(runtime.step(a).isEmpty()); assertEquals(1, reads.get());
			for (int i = 0; i < 128; i++) runtime.preflight(() -> Optional.of(true));
			int before = probes.get(); assertTrue(runtime.probe(input(owner)).isEmpty()); assertEquals(before, probes.get());
		}
	}
	@Test void throwingProviderReadAndCloseReleaseAllRetainedAndReservedMemory() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); throw new IllegalStateException("read"); }
			@Override public void close() { throw new IllegalStateException("cleanup"); }
		}), 16000000);
		runtime.advance(0, settings); var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
		assertEquals(CaptureResult.Availability.UNAVAILABLE, runtime.step(a).orElseThrow().result().availability());
		assertEquals(1, reads.get()); assertEquals(0, runtime.memory().reserved()); runtime.close();
		assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
	}
	@Test void restartedSweepCannotReattachAnOlderPeersIncompleteRound() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 3)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(1, reads.get());
			runtime.advance(3, settings); a.restart(); runtime.step(a); assertEquals(2, reads.get(), "fresh sweep re-reads instead of consuming the old paid prefix");
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void sameTickReopenDoesNotInheritAnOldPreviewWhileExistingTrackingSweepsStillShare() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1)), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(preview);
			var reopen = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(reopen);
			assertEquals(2, reads.get(), "reopen starts fresh even within the same tick");
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(3, reads.get());
			runtime.advance(3, settings); a.restart(); runtime.step(a); b.restart(); runtime.step(b);
			assertEquals(4, reads.get(), "new Pings do not multiply physical reads on periodic tracking sweeps");
		}
	}
	@Test void invalidStepRetiresAllThirtyTwoPhysicalRoundsWithoutClosingConsumersAndUnrelatedWorkProgresses() {
		var settings = InventorySettings.serverDefaults(); var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger(); boolean[] readable = {true};
		try (var runtime = new InventoryRuntime(input -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(input, reads, 2) {
				@Override public boolean valid() { return readable[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16000000)) {
			runtime.advance(0, settings); var consumers = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(1, index); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); consumers.add(consumer);
				assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(consumer, 1).orElseThrow().result().completeness());
			}
			assertEquals(32, reads.get()); assertTrue(runtime.memory().retained() > 9_000_000);
			readable[0] = false; runtime.advance(1, settings);
			for (var consumer : consumers) assertEquals(CaptureResult.Availability.INVALID, runtime.step(consumer, 1).orElseThrow().result().availability());
			assertEquals(32, closes.get(), "each shared provider handle is closed exactly once on invalidation, not consumer close");
			assertEquals(32L * (4096 + 1024), runtime.memory().retained(), "only bounded consumers and paid logical quota subjects remain"); assertEquals(0, runtime.memory().reserved());
			readable[0] = true; int resolvedBefore = resolutions.get(); var other = new UUID(2, 2);
			var unrelated = runtime.attach(input(other), new InventoryRuntime.PreviewSubject(other)).orElseThrow(); assertTrue(runtime.step(unrelated, 1).isPresent()); assertEquals(resolvedBefore + 1, resolutions.get());
			consumers.forEach(InventoryRuntime.Consumer::close); assertEquals(32, closes.get(), "retired rounds are not closed twice");
		}
	}
	@Test void invalidProbeRetiresSharedRoundAndPaidQuotasSurviveUntilFreshTrackingRecovery() {
		var settings = InventorySettings.serverDefaults(); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		var owner = new UUID(1, 1); var reads = new AtomicInteger(); var closes = new AtomicInteger(); boolean[] readable = {true};
		try (var runtime = new InventoryRuntime(input -> Optional.of(new Source(input, reads, 2) {
			@Override public boolean valid() { return readable[0]; }
			@Override public void close() { closes.incrementAndGet(); }
		}), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(preview); runtime.step(a); runtime.step(b); assertEquals(1, reads.get()); long before = runtime.memory().retained();
			readable[0] = false; runtime.advance(1, settings); assertEquals(Optional.of(false), runtime.probe(input(owner)));
			assertEquals(2, closes.get(), "one probe and one shared physical handle close"); assertTrue(runtime.memory().retained() < before); assertEquals(3L * 4096 + 2L * 1024, runtime.memory().retained());
			assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(a).orElseThrow().result().availability()); assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(b).orElseThrow().result().availability());
			readable[0] = true; a.restart(); b.restart(); preview.restart(); assertTrue(runtime.step(a).isEmpty()); assertTrue(runtime.step(preview).isEmpty(), "retirement never refunds paid logical usage");
			runtime.advance(3, settings); runtime.step(a); runtime.step(b); assertEquals(2, reads.get(), "recovery starts one fresh shared physical observation");
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void livePhysicalReductionWithinTickPreservesSpentSlotsAndCannotReadOrSelectPastNewCap() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2)); var reads = new AtomicInteger(); var owner = new UUID(1, 1);
		try (var runtime = new InventoryRuntime(input -> Optional.of(new Source(input, reads, 3)), 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(consumer, 1); assertEquals(1, reads.get());
			settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); runtime.advance(0, settings);
			assertTrue(runtime.step(consumer, 1).isEmpty()); assertFalse(runtime.selectionPresent(input(owner), new InventorySelection(item(7).key(), "minecraft:stone", "Stone", null, false, "danger"), 0)); assertEquals(1, reads.get());
			settings.setPhysicalSlotsPerTick(IntLimit.finite(3)); runtime.advance(0, settings); runtime.step(consumer, 1); assertEquals(2, reads.get());
			assertTrue(runtime.step(consumer, 1).isEmpty(), "raising live cap does not reset this tick's original finite ledger");
			runtime.advance(1, settings); runtime.step(consumer, 1); assertEquals(3, reads.get());
		}
	}
	@Test void snapshotPreparationSurvivesZeroScanAndDecodesFrozenSlotsAcrossPeriods() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		settings.getPreview().setPeriodTicks(1);
		UUID owner = new UUID(3, 3); AtomicInteger liveReads = new AtomicInteger(), captures = new AtomicInteger(), snapshotReads = new AtomicInteger(), snapshotCloses = new AtomicInteger();
		List<InventoryDomainCodec.Item> live = new java.util.ArrayList<>();
		for (int i = 0; i < 54; i++) live.add(item(i + 1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, liveReads, 54) {
			@Override public InventoryDomainCodec.Item read(int slot) { liveReads.incrementAndGet(); return live.get(slot); }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 1_000_000; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						captures.incrementAndGet(); List<InventoryDomainCodec.Item> frozen = List.copyOf(live);
						return Optional.of(new InventorySourceAccess.InventorySnapshot() {
							@Override public int slots() { return frozen.size(); }
							@Override public InventoryDomainCodec.Item read(int index) { snapshotReads.incrementAndGet(); return frozen.get(index); }
							@Override public long retainedBytes() { return frozen.size() * 256L; }
							@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
							@Override public void close() { snapshotCloses.incrementAndGet(); }
						});
					}
				});
			}
		}), 16_000_000)) {
			runtime.advance(0, settings);
			assertTrue(runtime.selectionPresent(input(owner), new InventorySelection(item(1).key(), "minecraft:stone", "stone", null, false, "danger"), 0));
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertTrue(runtime.step(consumer, 2).isEmpty(), "zero remaining scan allowance cannot consume non-empty snapshot slots");
			assertEquals(1, captures.get(), "preparation captures once even after the physical scan allowance is spent");
			assertEquals(1, liveReads.get(), "only the separately admitted selection witness reads the live source");
			live.replaceAll(ignored -> item(999));
			settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
			long consumed = 0;
			for (int tick = 1; tick <= 27; tick++) {
				runtime.advance(tick, settings);
				var observation = runtime.step(consumer, 2).orElseThrow();
				assertEquals(CaptureResult.Consistency.VERIFIED, observation.result().consistency());
				for (var slot : observation.slots()) {
					assertNotNull(slot);
					assertTrue(slot.count() <= 54, "consumption uses detached values, not changed live inventory");
					consumed++;
				}
			}
			assertEquals(54, consumed);
			assertEquals(54, snapshotReads.get());
			assertEquals(1, captures.get());
			assertEquals(1, liveReads.get(), "snapshot consumption never falls back to live slot reads");
		}
		assertEquals(1, snapshotCloses.get(), "last consumer close releases the retained snapshot");
	}
	@Test void cachedEmptySnapshotCompletesIndependentLogicalConsumersWithoutRepeatValidationOrReads() {
		var settings = InventorySettings.serverDefaults(); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		UUID owner = new UUID(4, 4); AtomicInteger reads = new AtomicInteger(), captures = new AtomicInteger();
		InventorySourceInput quotaInput = input(owner);
		InventorySourceInput emptyInput = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 2, 2, 3, "minecraft:chest"), owner, BlockFace.NORTH);
		java.util.Map<Integer, Source> sources = new java.util.HashMap<>();
		try (var runtime = new InventoryRuntime(i -> {
			int x = i.ordinaryTarget().orElseThrow().x();
			Source source = new Source(i, reads, x == 1 ? 1 : 0) {
				@Override public SourceKey key() { return new SourceKey("test", "inventory", "target-" + x, i.viewKey()); }
				@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return x == 1 ? item(1) : null; }
				@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
					if (x != 2) return Optional.empty();
					return Optional.of(new InventorySourceAccess.SnapshotPlan() {
						@Override public long memoryUpperBoundBytes() { return 1024; }
						@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
							captures.incrementAndGet();
							return Optional.of(new InventorySourceAccess.InventorySnapshot() {
								@Override public int slots() { return 0; }
								@Override public InventoryDomainCodec.Item read(int index) { throw new AssertionError("empty snapshot has no reads"); }
								@Override public long retainedBytes() { return 0; }
								@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
							});
						}
					});
				}
			};
			sources.put(x, source); return Optional.of(source);
		}, 4_000_000)) {
			runtime.advance(0, settings);
			var subject = new InventoryRuntime.PreviewSubject(owner);
			var quota = runtime.attach(quotaInput, subject).orElseThrow();
			assertEquals(1, runtime.step(quota).orElseThrow().slots().size(), "spend the subject logical allowance on a separate source first");
			var first = runtime.attach(emptyInput, subject).orElseThrow();
			var second = runtime.attach(emptyInput, subject).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(first).orElseThrow().result().completeness());
			int validations = sources.get(2).validCalls.get();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(second).orElseThrow().result().completeness());
			assertEquals(validations, sources.get(2).validCalls.get(), "cached empty delivery does not revalidate or step the handle");
			assertEquals(1, captures.get()); assertEquals(1, reads.get());
		}
	}
	@Test void zeroAllowanceProviderValidationRequiresFixedProviderBudgetAndMemoryDeferredRetriesAreAdmitted() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		settings.setPendingMemoryMiB(4);
		UUID owner = new UUID(5, 5); AtomicInteger reads = new AtomicInteger(), captures = new AtomicInteger(); Source[] source = new Source[1];
		try (var runtime = new InventoryRuntime(i -> {
			source[0] = new Source(i, reads, 0) {
				@Override public boolean valid() { validCalls.incrementAndGet(); return true; }
				@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
					return Optional.of(new InventorySourceAccess.SnapshotPlan() {
						@Override public long memoryUpperBoundBytes() { return 1024; }
						@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
							captures.incrementAndGet();
							return Optional.of(new InventorySourceAccess.InventorySnapshot() {
								@Override public int slots() { return 0; }
								@Override public InventoryDomainCodec.Item read(int index) { return null; }
								@Override public long retainedBytes() { return 0; }
							});
						}
					});
				}
			}; return Optional.of(source[0]);
		}, 4_000_000)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertFalse(runtime.selectionPresent(input(owner), new InventorySelection(item(1).key(), "minecraft:stone", "stone", null, false, "danger"), 0));
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			for (int i = 0; i < 128; i++) runtime.preflight(() -> Optional.of(true));
			int beforeBudgetFailure = source[0].validCalls.get();
			assertTrue(runtime.step(consumer).isEmpty());
			assertEquals(beforeBudgetFailure, source[0].validCalls.get(), "zero-slot source validation defers after provider-work exhaustion");
			assertTrue(beforeBudgetFailure > 0);
		}

		AtomicInteger memoryCaptures = new AtomicInteger(), memoryValidCalls = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, new AtomicInteger(), 0) {
			@Override public boolean valid() { memoryValidCalls.incrementAndGet(); return true; }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 256_000; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						memoryCaptures.incrementAndGet();
						return Optional.of(new InventorySourceAccess.InventorySnapshot() {
							@Override public int slots() { return 0; }
							@Override public InventoryDomainCodec.Item read(int index) { return null; }
							@Override public long retainedBytes() { return 0; }
						});
					}
				});
			}
		}), 4_000_000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var held = runtime.memory().tryReserve(runtime.memory().remaining() - 400_000).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
			int afterDefer = memoryValidCalls.get();
			assertTrue(afterDefer > 0, "round and source handle exist before snapshot-memory defer");
			while (runtime.preflight(() -> Optional.of(true)).isPresent()) {}
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
			assertEquals(afterDefer, memoryValidCalls.get(), "exhausted fixed work defers before another snapshot-plan validity check");
			runtime.advance(1, settings); held.close();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(1, memoryCaptures.get()); assertTrue(memoryValidCalls.get() > afterDefer);
		}
	}
	@Test void terminalPublicationUsesRetainedEvidenceNotAFreshSelfConsistentWrapper() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(6, 6);
		var reads = new AtomicInteger(); var resolutions = new AtomicInteger(); var closes = new AtomicInteger(); int[] layout = {1};
		try (var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); int expected = layout[0];
			return Optional.of(new Source(i, reads, 1) {
				@Override public boolean valid() { validCalls.incrementAndGet(); return layout[0] == expected; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
			layout[0] = 2;
			assertTrue(runtime.validate(input(owner)), "fresh authority can read the new topology, but does not authorize old data");
			int before = resolutions.get();
			assertEquals(Optional.of(false), runtime.probe(consumer));
			assertEquals(before, resolutions.get(), "publication checks the admitted terminal handle, never a new resolve");
			assertEquals(1, reads.get(), "publication validity never steps or reads items");
			assertTrue(consumer.invalidated()); assertEquals(2, closes.get(), "one fresh authority wrapper and one retained handle");
			layout[0] = 1;
			assertEquals(Optional.of(false), runtime.probe(consumer), "retirement cannot fall back to a now-valid fresh resolve");
			assertEquals(before, resolutions.get());
			consumer.close(); assertEquals(2, closes.get()); assertEquals(1024, runtime.memory().retained());
		}
	}
	@Test void restartedTrackingRetainsOldCompletedEvidenceAndSharedRetirementRevokesPreviouslyCheckedTerminalPeer() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(7, 7);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); int[] layout = {1};
		try (var runtime = new InventoryRuntime(i -> {
			int expected = layout[0]; return Optional.of(new Source(i, reads, 2) {
				@Override public boolean valid() { return layout[0] == expected; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000)) {
			runtime.advance(0, settings);
			var terminal = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var tracking = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(terminal, 1).orElseThrow(); runtime.step(tracking).orElseThrow();
			runtime.step(terminal, 1).orElseThrow(); runtime.step(tracking).orElseThrow(); tracking.retainCompleted(); assertEquals(2, reads.get());
			runtime.advance(3, settings); tracking.restart();
			assertEquals(Optional.of(true), runtime.probe(terminal), "earlier same-tick publication admission");
			layout[0] = 2;
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(tracking, 1).orElseThrow().result().completeness());
			assertEquals(Optional.of(false), runtime.probe(tracking), "new scanning handle is valid; the old completed result is not");
			assertTrue(terminal.invalidated(), "retiring shared evidence reaches terminal/non-scanning consumers too");
			assertEquals(Optional.of(false), runtime.probe(terminal), "an earlier check cannot shield a subsequently retired consumer");
			assertEquals(2, closes.get(), "old shared evidence and the abandoned new scan each close once");
			assertEquals(3, reads.get(), "retirement does not read a fourth slot");
			assertEquals(2L * 4096 + 2L * 1024, runtime.memory().retained());
			terminal.close(); tracking.close(); assertEquals(2, closes.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void evidenceLeaseReleasesPagesAtLastScannerRestartAndClosesOnlyWhenReplacedOrLastConsumerLeaves() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(8, 8);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var validations = new AtomicInteger(); var snapshotCloses = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 2) {
			@Override public boolean valid() { validations.incrementAndGet(); return true; }
			@Override public void close() { closes.incrementAndGet(); }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 1024; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						return Optional.of(new InventorySourceAccess.InventorySnapshot() {
							@Override public int slots() { return 2; }
							@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return slot == 0 ? item(7) : null; }
							@Override public long retainedBytes() { return 256; }
							@Override public void close() { snapshotCloses.incrementAndGet(); }
						});
					}
				});
			}
		}), 16_000_000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(a).orElseThrow(); a.retainCompleted(); runtime.step(b).orElseThrow(); b.retainCompleted();
			int before = validations.get(); assertEquals(Optional.of(true), runtime.probe(a));
			assertEquals(before + 1, validations.get(), "identical scan/completed references are validated only once");
			long retainedPages = runtime.memory().retained(); a.restart(); b.restart();
			assertTrue(runtime.memory().retained() < retainedPages, "last scanner drops pages while both evidence references stay charged");
			assertEquals(2L * 4096 + 1024 + 4096 + 262144 + 32768, runtime.memory().retained(), "one bounded evidence lease plus paid target quota and two consumers");
			assertEquals(0, closes.get()); assertEquals(1, snapshotCloses.get(), "last scanner releases detached snapshot memory, but not its source evidence");
			assertTrue(runtime.step(a).isPresent()); a.retainCompleted(); assertEquals(4, reads.get(), "restart creates a fresh sweep even in the same tick");
			assertEquals(0, closes.get(), "the slow peer still owns the old completed evidence");
			b.close(); assertEquals(1, closes.get()); a.close(); assertEquals(2, closes.get());
			assertEquals(2, snapshotCloses.get(), "each capture is released once despite scan and completed references sharing a handle");
			assertEquals(1024, runtime.memory().retained(), "replacing or releasing evidence never refunds paid logical usage");
		}
	}
	@Test void publicationEvidenceAdmissionDefersBeforeAnyProviderCallAndNeverInvalidatesOnBudgetPressure() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(9, 9); var reads = new AtomicInteger(); var validations = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public boolean valid() { validations.incrementAndGet(); return true; }
		}), 16_000_000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(consumer).orElseThrow();
			int before = validations.get(); runtime.memory().setCap(runtime.memory().retained());
			assertTrue(runtime.probe(consumer).isEmpty()); assertEquals(before, validations.get()); assertFalse(consumer.invalidated());
			runtime.memory().setCap(16_000_000);
			while (runtime.preflight(() -> Optional.of(true)).isPresent()) {}
			assertTrue(runtime.probe(consumer).isEmpty()); assertEquals(before, validations.get()); assertFalse(consumer.invalidated()); assertEquals(1, reads.get());
			runtime.advance(1, settings); assertEquals(Optional.of(true), runtime.probe(consumer)); assertEquals(before + 1, validations.get());
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void rejectedCompletedSweepDoesNotReplaceTheEvidenceOfTheStillPublishedPreviousResult() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(10, 10); var reads = new AtomicInteger(); var closes = new AtomicInteger();
		boolean[] oldValid = {true}; var resolutions = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> {
			boolean old = resolutions.incrementAndGet() == 1;
			return Optional.of(new Source(i, reads, 1) {
				@Override public boolean valid() { return !old || oldValid[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(consumer).orElseThrow(); consumer.retainCompleted();
			runtime.advance(3, settings); consumer.restart();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
			// The domain may reject this quantity (for example an overflow) and keep publishing its last accepted result.
			assertEquals(0, closes.get(), "step completion alone cannot release the published result's evidence");
			oldValid[0] = false; assertEquals(Optional.of(false), runtime.probe(consumer));
			assertEquals(2, closes.get()); assertTrue(consumer.invalidated());
		}
	}
	@Test void allThirtyTwoRetainedTrackingEvidenceLeasesAllowFreshReplacementWithoutAnUnboundedCache() {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(32);
		var reads = new AtomicInteger(); var closes = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public void close() { closes.incrementAndGet(); }
		}), 32_000_000)) {
			runtime.advance(0, settings); var consumers = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(11, index); var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
				runtime.step(consumer).orElseThrow(); consumer.retainCompleted(); consumers.add(consumer);
			}
			assertEquals(32, reads.get()); assertEquals(0, closes.get());
			runtime.advance(3, settings); consumers.forEach(InventoryRuntime.Consumer::restart);
			for (var consumer : consumers) {
				assertTrue(runtime.step(consumer).isPresent(), "bounded replacement headroom prevents evidence retention from deadlocking all 32 sources");
				consumer.retainCompleted();
			}
			assertEquals(64, reads.get()); assertEquals(32, closes.get()); assertEquals(0, runtime.memory().reserved());
			consumers.forEach(InventoryRuntime.Consumer::close); assertEquals(64, closes.get());
			assertEquals(1024, runtime.memory().retained(), "one target quota stays charged until its period ends");
		}
	}
	@Test void secondBatchOfNewConsumersCannotConsumeTheReserveNeededByThirtyTwoRetainedEvidenceReplacements() {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 64_000_000);
		try (runtime) {
			runtime.advance(0, settings);
			var existing = new java.util.ArrayList<InventoryRuntime.Consumer>();
			var newcomers = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(12, index);
				var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
				runtime.step(consumer).orElseThrow(); consumer.retainCompleted(); existing.add(consumer);
			}
			existing.forEach(InventoryRuntime.Consumer::restart);
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(13, index);
				newcomers.add(runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow());
			}
			for (int period = 1; period <= 3; period++) {
				runtime.advance(period * 3L, settings); long retained = runtime.memory().retained();
				int before = resolutions.get();
				for (var consumer : newcomers) {
					assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
					assertTrue(runtime.step(consumer).isEmpty());
					consumer.restart(); // denied observation cannot turn into a replacement lease
				}
				assertEquals(before, resolutions.get(), "the count guard rejects new physical observations before resolver/work admission");
				assertEquals(retained, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
				assertTrue(runtime.memory().remaining() > 32_000_000, "memory is sufficient; only the new-observation guard defers");
				// Fill all 32 scan positions concurrently while the 32 old evidences remain charged.
				for (var consumer : existing) assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
				assertEquals(before + 32, resolutions.get());
				assertEquals((period - 1) * 32, closes.get(), "old publication evidence survives until an accepted replacement");
				for (var consumer : existing) {
					assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
					consumer.retainCompleted();
				}
				assertEquals(period * 32, closes.get()); assertEquals((period + 1) * 32, reads.get());
				existing.forEach(InventoryRuntime.Consumer::restart); assertEquals(0, runtime.memory().reserved());
			}
			int before = reads.get(); existing.forEach(InventoryRuntime.Consumer::close);
			assertEquals(128, closes.get());
			runtime.advance(12, settings); // replace the exhausted fixed-work period, not the retained admission state
			for (var consumer : newcomers) {
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
				consumer.retainCompleted();
			}
			assertEquals(before + 32, reads.get(), "new observers become serviceable once prior evidence leases leave");
			newcomers.forEach(InventoryRuntime.Consumer::close); assertEquals(160, closes.get());
			assertEquals(1024, runtime.memory().retained(), "closing evidence never refunds the current target's paid progress");
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void compatibleAliasedInputsStillShareOnePhysicalRoundWhenNormalObservationAdmissionIsFull() {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 2) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 64_000_000)) {
			runtime.advance(0, settings); var existing = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 31; index++) {
				runtime.advance(index * 3L, settings);
				var owner = new UUID(14, index); var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
				runtime.step(consumer).orElseThrow(); consumer.retainCompleted(); consumer.restart(); existing.add(consumer);
			}
			runtime.advance(93, settings);
			var owner = new UUID(14, 31);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var aliasedInput = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 2, 2, 3, "minecraft:chest"), owner, BlockFace.NORTH);
			var b = runtime.attach(aliasedInput, new InventoryRuntime.TrackingSubject(TargetKey.from(aliasedInput.target()))).orElseThrow();
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(a, 1).orElseThrow().result().completeness());
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(b), "the 32-round guard counts physical rounds, not a compatible consumer's references");
			runtime.step(b, 1).orElseThrow(); assertEquals(33, resolutions.get()); assertEquals(63, reads.get());
			runtime.advance(94, settings);
			runtime.step(a, 1).orElseThrow(); a.retainCompleted(); runtime.step(b, 1).orElseThrow(); b.retainCompleted();
			runtime.advance(96, settings); a.restart(); b.restart();
			runtime.step(a).orElseThrow(); a.retainCompleted(); runtime.step(b).orElseThrow(); b.retainCompleted();
			assertEquals(35, resolutions.get(), "both hit targets resolve, but replacement scans share one physical round");
			assertEquals(66, reads.get()); assertEquals(3, closes.get(), "two unused alias wrappers and the old shared evidence close once");
			a.close(); assertEquals(3, closes.get()); b.close(); assertEquals(4, closes.get());
			existing.forEach(InventoryRuntime.Consumer::close); assertEquals(35, closes.get()); assertEquals(0, runtime.memory().reserved());
			assertEquals(2048, runtime.memory().retained(), "both hit-target quotas survive shared physical evidence cleanup");
		}
	}
	@Test void thirtyTwoSharedRoundsWithSkewedReplacementAndRestartKeepSlowPeersServiceableAcrossPeriods() {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		settings.setPhysicalSlotsPerTick(IntLimit.finite(32)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(32));
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 64_000_000);
		try (runtime) {
			runtime.advance(0, settings);
			var fast = new java.util.ArrayList<InventoryRuntime.Consumer>();
			var slow = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(15, index); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
				var a = runtime.attach(input(owner), subject).orElseThrow(); var b = runtime.attach(input(owner), subject).orElseThrow();
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(a).orElseThrow().result().completeness()); a.retainCompleted();
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(b).orElseThrow().result().completeness()); b.retainCompleted();
				fast.add(a); slow.add(b);
			}
			assertEquals(32, reads.get()); assertEquals(32, resolutions.get()); assertEquals(0, closes.get());
			fast.forEach(InventoryRuntime.Consumer::restart); slow.forEach(InventoryRuntime.Consumer::restart);
			for (int period = 1; period <= 3; period++) {
				runtime.advance(period * 6L - 3, settings);
				for (var consumer : fast) assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
				for (var consumer : fast) {
					assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
					consumer.retainCompleted();
				}
				assertEquals((period + 1) * 32, reads.get()); assertEquals(64, resolutions.get() - closes.get(), "all 64 physical evidence handles are retained, including the slow peers' old publications");
				assertEquals((period - 1) * 32, closes.get(), "no slow peer's old evidence is discarded to make room");
				long pinned = runtime.memory().retained(); fast.forEach(InventoryRuntime.Consumer::restart);
				assertEquals(pinned, runtime.memory().retained(), "the physical handoff lease keeps its data serviceable after every fast peer detaches");
				assertTrue(runtime.memory().remaining() > 32_000_000); assertEquals(0, runtime.memory().reserved());
				runtime.advance(period * 6L, settings);
				int before = resolutions.get();
				for (var consumer : fast) assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer), "a physical handoff cannot fork again before its slow peer advances");
				assertEquals(before, resolutions.get(), "fanout guard defers before resolver/provider work");
				for (var consumer : slow) {
					assertEquals(Optional.of(true), runtime.probe(consumer), "the still-published old evidence remains valid while waiting");
					assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer), "a slow peer remains serviceable even with all 64 retained positions occupied");
					var observed = runtime.step(consumer).orElseThrow();
					assertEquals(CaptureResult.Completeness.COMPLETE, observed.result().completeness()); assertEquals(List.of(item(7)), observed.slots());
					consumer.retainCompleted(); assertFalse(consumer.invalidated());
				}
				assertEquals(before, resolutions.get()); assertEquals((period + 1) * 32, reads.get(), "both peers use one physical replacement, not a new sweep for the lagging peer");
				assertEquals(period * 32, closes.get(), "the last old evidence reference closes each predecessor once");
				slow.forEach(InventoryRuntime.Consumer::restart);
				assertEquals(64L * 4096 + 32L * (4096 + 262144 + 32768) + 1024, runtime.memory().retained(), "only current evidence and one target quota remain after handoff completion");
				assertEquals(0, runtime.memory().reserved());
			}
			fast.forEach(InventoryRuntime.Consumer::close); assertEquals(96, closes.get(), "one peer closing cannot release the other's current evidence");
			slow.forEach(InventoryRuntime.Consumer::close); assertEquals(128, closes.get());
			assertEquals(1024, runtime.memory().retained(), "handoff completion and close never refund the target's logical progress");
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void rejectedSharedReplacementReleasesItsHandoffWithoutDroppingOldEvidenceOrRefundingTargetQuota() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(2));
		var owner = new UUID(16, 16); var reads = new AtomicInteger(); var closes = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public void close() { closes.incrementAndGet(); }
		}), 16_000_000);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
			var fast = runtime.attach(input(owner), subject).orElseThrow(); var slow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); runtime.step(slow).orElseThrow(); slow.retainCompleted();
			fast.restart(); slow.restart(); runtime.advance(1, settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(fast).orElseThrow().result().completeness());
			assertEquals(2, reads.get()); assertEquals(0, closes.get(), "an unaccepted completed sweep cannot replace either publication's evidence");
			fast.restart(); // the domain rejected the replacement instead of retaining it
			assertEquals(1, closes.get(), "a successor with only a handoff reference is cancelled, not leaked");
			assertEquals(2L * 4096 + 1024 + 4096 + 262144 + 32768, runtime.memory().retained());
			assertEquals(Optional.of(true), runtime.probe(fast)); assertEquals(Optional.of(true), runtime.probe(slow));
			assertFalse(fast.invalidated()); assertFalse(slow.invalidated());
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(fast), "the cancelled handoff allows another fresh attempt");
			assertTrue(runtime.step(fast).isEmpty(), "cancelled capture and evidence releases do not refund the two paid target slots");
			assertEquals(2, reads.get()); fast.restart(); assertEquals(2, closes.get());
			runtime.advance(3, settings); runtime.step(fast).orElseThrow(); fast.retainCompleted(); fast.restart();
			assertEquals(0, runtime.memory().reserved()); assertEquals(2, closes.get());
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(slow).orElseThrow().result().completeness()); slow.retainCompleted();
			assertEquals(3, reads.get()); assertEquals(3, closes.get(), "the old shared evidence releases only after the slow peer accepts its successor");
			fast.close(); slow.close(); assertEquals(4, closes.get()); assertEquals(1024, runtime.memory().retained());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void invalidSharedSuccessorDetachesItsHandoffAndLeavesUnrelatedOldPublishedEvidenceOwnedBySlowPeer() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(17, 17);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger(); boolean[] replacementValid = {true};
		var runtime = new InventoryRuntime(i -> {
			boolean replacement = resolutions.incrementAndGet() == 2;
			return Optional.of(new Source(i, reads, 2) {
				@Override public boolean valid() { return !replacement || replacementValid[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
			var fast = runtime.attach(input(owner), subject).orElseThrow(); var slow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); runtime.step(slow).orElseThrow(); slow.retainCompleted();
			fast.restart(); slow.restart(); runtime.advance(3, settings);
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(fast, 1).orElseThrow().result().completeness());
			replacementValid[0] = false; runtime.advance(4, settings);
			assertEquals(CaptureResult.Availability.INVALID, runtime.step(fast, 1).orElseThrow().result().availability());
			assertTrue(fast.invalidated()); assertFalse(slow.invalidated()); assertEquals(1, closes.get());
			assertEquals(Optional.of(true), runtime.probe(slow), "retiring an unaccepted successor cannot revoke independent still-valid old publication evidence");
			assertEquals(2L * 4096 + 1024 + 4096 + 262144 + 32768, runtime.memory().retained());
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(slow).orElseThrow().result().completeness()); slow.retainCompleted();
			assertEquals(5, reads.get()); assertEquals(2, closes.get(), "a fresh slow-peer replacement can release the old evidence after successor invalidity");
			fast.close(); slow.close(); assertEquals(3, closes.get()); assertEquals(1024, runtime.memory().retained());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void skewedAliasedHandoffKeepsOnePhysicalRoundAndIndependentTargetQuotasAcrossTicks() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		var owner = new UUID(18, 18); var alias = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 2, 2, 3, "minecraft:chest"), owner, BlockFace.NORTH);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000);
		try (runtime) {
			runtime.advance(0, settings);
			var fast = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var slow = runtime.attach(alias, new InventoryRuntime.TrackingSubject(TargetKey.from(alias.target()))).orElseThrow();
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); runtime.step(slow).orElseThrow(); slow.retainCompleted();
			assertEquals(1, reads.get()); assertEquals(2, resolutions.get()); assertEquals(1, closes.get(), "the unused alias wrapper closes");
			fast.restart(); slow.restart(); runtime.advance(3, settings);
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); fast.restart();
			runtime.advance(4, settings);
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(fast));
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(slow).orElseThrow().result().completeness()); slow.retainCompleted(); slow.restart();
			assertEquals(2, reads.get(), "a later alias consumer resolves compatibility but consumes the same pinned physical capture");
			assertEquals(4, resolutions.get()); assertEquals(3, closes.get(), "old shared evidence and both unused alias wrappers close once");
			assertEquals(2L * 4096 + 2048 + 4096 + 262144 + 32768, runtime.memory().retained(), "the two hit-target quota subjects remain independent");
			assertTrue(runtime.step(slow).isEmpty(), "serving the alias spends its own target allowance even when no new physical read was needed");
			assertEquals(2, reads.get()); fast.close(); slow.close(); assertEquals(5, closes.get());
			assertEquals(2048, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void closingAcceptedPeersCancelsMergedPhysicalHandoffsWithoutLeakingReferencesOrRefundingQuota() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(3));
		var owner = new UUID(19, 19); var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
			var first = runtime.attach(input(owner), subject).orElseThrow(); var firstSlow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(first).orElseThrow(); first.retainCompleted(); runtime.step(firstSlow).orElseThrow(); firstSlow.retainCompleted();
			var second = runtime.attach(input(owner), subject).orElseThrow(); var secondSlow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(second).orElseThrow(); second.retainCompleted(); runtime.step(secondSlow).orElseThrow(); secondSlow.retainCompleted();
			assertEquals(2, reads.get(), "a newly attached pair starts its own fresh observation, not the earlier completed sweep");
			first.restart(); firstSlow.restart(); second.restart(); secondSlow.restart(); runtime.advance(1, settings);
			runtime.step(first).orElseThrow(); first.retainCompleted(); runtime.step(second).orElseThrow(); second.retainCompleted();
			assertEquals(3, reads.get()); assertEquals(3, resolutions.get(), "two older evidence rounds hand off into one compatible physical replacement");
			first.restart(); second.restart(); first.close(); assertEquals(0, closes.get()); second.close();
			assertEquals(1, closes.get(), "when the last accepted peer leaves, both handoff-only leases cancel and the successor closes once");
			assertEquals(2L * 4096 + 2L * (4096 + 262144 + 32768) + 1024, runtime.memory().retained());
			assertEquals(Optional.of(true), runtime.probe(firstSlow)); assertEquals(Optional.of(true), runtime.probe(secondSlow));
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(firstSlow));
			assertTrue(runtime.step(firstSlow).isEmpty(), "closing accepted peers and handoff leases does not refund their target's three paid slots");
			firstSlow.restart(); assertEquals(2, closes.get()); assertEquals(3, reads.get());
			runtime.advance(3, settings);
			runtime.step(firstSlow).orElseThrow(); firstSlow.retainCompleted(); runtime.step(secondSlow).orElseThrow(); secondSlow.retainCompleted();
			assertEquals(4, reads.get()); assertEquals(5, resolutions.get()); assertEquals(4, closes.get(), "both old completed rounds safely release into one new accepted replacement");
			firstSlow.close(); secondSlow.close(); assertEquals(5, closes.get());
			assertEquals(1024, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void cachedHandoffValidityIsAdmittedBeforeSlowPeerConsumesItsCapturedReplacement() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(20, 20);
		var reads = new AtomicInteger(); var validations = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger(); boolean[] replacementValid = {true};
		var runtime = new InventoryRuntime(i -> {
			boolean replacement = resolutions.incrementAndGet() == 2;
			return Optional.of(new Source(i, reads, 1) {
				@Override public boolean valid() { validations.incrementAndGet(); return !replacement || replacementValid[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
			var fast = runtime.attach(input(owner), subject).orElseThrow(); var slow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); runtime.step(slow).orElseThrow(); slow.retainCompleted();
			fast.restart(); slow.restart(); runtime.advance(3, settings); runtime.step(fast).orElseThrow(); fast.retainCompleted(); fast.restart();
			runtime.advance(4, settings); replacementValid[0] = false; int before = validations.get();
			runtime.memory().setCap(runtime.memory().retained());
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(slow));
			assertEquals(before, validations.get()); assertFalse(fast.invalidated()); assertFalse(slow.invalidated());
			runtime.memory().setCap(settings.pendingMemoryBytes()); while (runtime.preflight(() -> Optional.of(true)).isPresent()) {}
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(slow));
			assertEquals(before, validations.get()); assertEquals(0, runtime.memory().reserved());
			runtime.advance(5, settings);
			assertEquals(CaptureResult.Availability.UNAVAILABLE, runtime.step(slow).orElseThrow().result().availability(), "a valid old publication cannot authorize the now-invalid cached successor");
			assertEquals(before + 1, validations.get()); assertEquals(2, reads.get(), "handoff validity never reads or redelivers invalid cached items");
			assertEquals(2, resolutions.get(), "the retained successor is checked, not replaced by a fresh self-consistent wrapper");
			assertTrue(fast.invalidated()); assertFalse(slow.invalidated()); assertEquals(1, closes.get());
			assertEquals(Optional.of(true), runtime.probe(slow), "the old evidence remains independently owned until the caller handles the unavailable handoff");
			slow.discardObservation(); assertTrue(slow.invalidated()); assertEquals(2, closes.get());
			fast.close(); slow.close(); assertEquals(2, closes.get()); assertEquals(1024, runtime.memory().retained());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
}
