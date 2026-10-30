package nx.pingwheel.common.presentation.inventory.minecraft;

import com.google.gson.Gson;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static nx.pingwheel.common.presentation.inventory.minecraft.CertifiedVault1620Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Allocation/elapsed diagnostics have no speed threshold and never pin the old validation multiplicity. */
@NotNullByDefault
class CertifiedVault1620BaselineTest {
	static final int WARMUPS = 3, SAMPLES = 5;
	private static volatile @Nullable Object escape;
	@BeforeAll static void bootstrap() throws ReflectiveOperationException { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { escape = null; CreateVaultTrackingPerformanceTest.restoreRegistries(); }
	static String nativeIdentity(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + DataComponentPatch.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, RegistryAccess.EMPTY), stack.getComponentsPatch()).getOrThrow();
	}
	/** Independent finite byte oracle for this fixture's actual compound/string/int native persistent patches. */
	static String expectedDigest(ItemStack stack) {
		var tag = DataComponentPatch.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, RegistryAccess.EMPTY), stack.getComponentsPatch()).getOrThrow();
		try {
			var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes); oracleTag(output, tag);
			return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
		} catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
	}
	static void oracleTag(DataOutputStream output, net.minecraft.nbt.Tag tag) throws java.io.IOException {
		output.writeByte(tag.getId());
		switch (tag) {
			case net.minecraft.nbt.CompoundTag compound -> {
				output.writeInt(compound.size());
				for (String key : new java.util.TreeSet<>(compound.getAllKeys())) {
					byte[] text = key.getBytes(java.nio.charset.StandardCharsets.UTF_8); output.writeInt(text.length); output.write(text);
					oracleTag(output, java.util.Objects.requireNonNull(compound.get(key)));
				}
			}
			case net.minecraft.nbt.StringTag value -> { byte[] text = value.getAsString().getBytes(java.nio.charset.StandardCharsets.UTF_8); output.writeInt(text.length); output.write(text); }
			case net.minecraft.nbt.IntTag value -> output.writeInt(value.getAsInt());
			default -> throw new AssertionError("extend independent fixture oracle for native tag " + tag.getClass());
		}
	}
	@Test void graphAt81Members1620SlotsIsCanonicalAcrossHitFaceAndOwnerWithoutContentReads() {
		var world = new World(Contents.MIXED);
		try (var first = world.source(input(BlockPos.ZERO, BlockFace.NORTH, OWNER), 4096);
			var second = world.source(input(new BlockPos(8, 2, 2), BlockFace.SOUTH, new java.util.UUID(1620, 2)), 4096)) {
			var a = java.util.Objects.requireNonNull(first.sharedRead()); var b = java.util.Objects.requireNonNull(second.sharedRead());
			assertEquals(a.proof(), b.proof()); assertNotEquals(first.key().readScope(), second.key().readScope());
			assertEquals(InventorySharedRead.Check.VALID, b.validate(a.proof(), new InventoryValidationBudget.Allowance(b.validationCost())));
			assertNull(first.nativeCatalog()); assertNull(second.nativeCatalog());
			int address = 0;
			for (var member : a.proof().graph().layout().members()) {
				assertEquals(new BlockPos(address / 180, address / 60 % 3, address / 20 % 3), member.position());
				assertEquals(1, member.segments().size()); var segment = member.segments().getFirst(); assertEquals(20, segment.localSlots());
				for (int slot = 0; slot < 20; slot++) { assertEquals(slot, segment.visibleSlots().get(slot)); address++; }
			}
			assertEquals(SLOTS, address);
		}
	}
	@Test void everyNativeAddressIncludingBlanksIsPaidExactlyOnceAndDetachedTotalsMatchPersistentPatchOracle() {
		for (Profile profile : Profile.values()) for (Contents contents : Contents.values()) {
			try (var run = new Run(contents, profile)) {
				run.prepare(); assertTrue(run.probe()); assertEquals(0, physical(run.runtime));
				var keys = new HashMap<String, InventoryScanner.Key>(); int address = 0; long paid = 0, expectedTotal = 0;
				while (run.completions == 0 && run.tick < 256) {
					run.step(64); paid += physical(run.runtime);
					if (run.last == null) continue;
					var page = run.last; assertEquals(CaptureResult.Availability.READABLE, page.result().availability());
					assertTrue(page.slots().size() <= 64); assertEquals(page.slots().size(), physical(run.runtime));
					assertEquals(address + page.slots().size(), page.result().coverage().scanned());
					for (var actual : page.slots()) {
						var expected = stack(contents, address++);
						if (contents == Contents.EMPTY) assertNull(actual);
						else {
							assertNotNull(actual); assertFalse(actual.aggregateRequired()); assertEquals(expected.getCount(), actual.count());
							assertEquals(BuiltInRegistries.ITEM.getKey(expected.getItem()).toString(), actual.key().itemId());
							assertEquals(expectedDigest(expected), actual.key().componentsKey(), "native persistent patch, independent canonical bytes and JDK digest");
							expectedTotal += expected.getCount(); String oracle = nativeIdentity(expected);
							@Nullable var previous = keys.putIfAbsent(oracle, actual.key());
							if (previous != null) assertEquals(previous, actual.key());
							else assertEquals(keys.size(), keys.values().stream().distinct().count(), "different item IDs/explicit patches cannot collapse");
						}
					}
					if (address < SLOTS) assertNotEquals(CaptureResult.Completeness.COMPLETE, page.result().completeness(), "empty prefix is not absence evidence");
				}
				assertEquals(SLOTS, address); assertEquals(SLOTS, paid); assertEquals(expectedTotal, run.total); assertEquals(1, run.completions);
				assertEquals(contents == Contents.EMPTY ? 0 : contents == Contents.REPEATED ? 1 : 3, keys.size());
				assertEquals(InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, java.util.Objects.requireNonNull(run.last).inventorySweepEvidence());
				assertEquals(CaptureResult.Consistency.EVENTUAL, run.last.result().consistency()); run.consumer.retainCompleted();
				assertTrue(run.probe()); assertEquals(0, physical(run.runtime)); assertFalse(run.consumer.invalidated());
				if (contents != Contents.EMPTY) {
					var retained = java.util.Objects.requireNonNull(run.last).slots().getLast(); assertNotNull(retained);
					run.world.vault.replaceStack(SLOTS - 1, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
					assertEquals(3_000_003L + SLOTS - 1, retained.count());
					assertEquals(expectedDigest(stack(contents, SLOTS - 1)), retained.key().componentsKey());
				}
			}
		}
	}
	static void emit(Map<String, ?> record) { System.out.println("CERTIFIED_VAULT_JSON " + new Gson().toJson(record)); }
	static String hash(Class<?> type) throws Exception {
		try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
			assertNotNull(input); return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
		}
	}
	@Test void recordCertifiedBeforeAfterRawCountersAndCurrentThreadAllocationWithoutTimingThresholds() throws Exception {
		var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean(); assertTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
		for (Profile profile : Profile.values()) {
			var meta = new LinkedHashMap<String, Object>(); meta.put("kind", "metadata"); meta.put("profile", profile); meta.put("settings", profile.settings());
			meta.put("jdk", System.getProperty("java.runtime.version")); meta.put("vm", System.getProperty("java.vm.name"));
			meta.put("sourceRevision", System.getenv().getOrDefault("PINGFORIT_BENCHMARK_REVISION", "unspecified"));
			meta.put("fixtureClassSha256", hash(CertifiedVault1620Fixture.class)); meta.put("worldClassSha256", hash(World.class));
			meta.put("members", MEMBERS); meta.put("slots", SLOTS); meta.put("warmups", WARMUPS); meta.put("samples", SAMPLES);
			meta.put("boundary", "preconstructed exact ItemStackHandler; typed member/world ports; production Source.sharedRead and 5-arg certified Runtime; not MinecraftMember reflection; no backend timed");
			meta.put("getterEvidence", "physical ledger and contiguous committed address coverage; native getter NOT instrumented");
			meta.put("applicationContext", "user-attested local JAR 2026-10-30 06:20; GLOBAL range2048 lifetime50sec/1000ticks regen100ms rate50; no live JAR/world/profile execution");
			meta.put("historicalPageFormulaNotAssertion", "k: common=5k+2 providerMatches=12k+4 memberPasses=41k+14; m=81,k=32 => providerLoaded=68848,reflectionCalls=96644; extra root/probe calls reported raw"); emit(meta);
			for (Contents contents : Contents.values()) for (String workload : new String[] {"admission", "window32", "sweep1620", "idleValidation"}) {
				counter(profile, contents, workload);
				for (int warm = 0; warm < WARMUPS; warm++) sample(bean, profile, contents, workload);
				long[] bytes = new long[SAMPLES], nanos = new long[SAMPLES];
				for (int index = 0; index < SAMPLES; index++) { var result = sample(bean, profile, contents, workload); bytes[index] = result[0]; nanos[index] = result[1]; }
				emit(Map.of("kind", "samples", "profile", profile, "contents", contents, "workload", workload, "allocatedBytes", bytes, "elapsedNanos", nanos));
				Arrays.sort(bytes); Arrays.sort(nanos); emit(Map.of("kind", "distribution", "profile", profile, "contents", contents, "workload", workload,
					"bytesMinMedianMax", new long[] {bytes[0], bytes[2], bytes[4]}, "nanosMinMedianMax", new long[] {nanos[0], nanos[2], nanos[4]}));
			}
		}
	}
	private static void setup(Run run, String workload) {
		if (!workload.equals("admission")) run.prepare();
		if (workload.equals("window32")) { run.step(32); assertEquals(32, run.slots, "fixed prepared page must be admitted"); }
		if (workload.equals("idleValidation")) { run.sweep(); run.consumer.retainCompleted(); }
	}
	private static void operate(Run run, String workload) {
		switch (workload) {
			case "admission" -> run.prepare();
			case "window32" -> run.step(32);
			case "sweep1620" -> run.sweep();
			case "idleValidation" -> { if (!run.probe()) throw new AssertionError("stable completed evidence rejected"); }
			default -> throw new AssertionError(workload);
		}
	}
	private static long @Unmodifiable [] sample(ThreadMXBean bean, Profile profile, Contents contents, String workload) {
		try (var run = new Run(contents, profile)) {
			setup(run, workload); long thread = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
			operate(run, workload); long nanos = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(thread) - before;
			escape = run.last == null ? run.world.lastSource : run.last;
			if (workload.equals("window32")) assertEquals(64, run.slots);
			escape = null; return new long[] {bytes, nanos};
		}
	}
	private static void counter(Profile profile, Contents contents, String workload) {
		try (var run = new Run(contents, profile)) {
			setup(run, workload); run.world.resetCounters(); run.countPhysical = true;
			int pages = run.pages, slots = run.slots, completions = run.completions, defers = run.defers;
			operate(run, workload); var w = run.world; var v = w.vault;
			var record = new LinkedHashMap<String, Object>(); record.put("kind", "counters"); record.put("profile", profile); record.put("contents", contents); record.put("workload", workload);
			record.put("resolutions", w.resolutions); record.put("views", w.views); record.put("commonLoaded", w.loaded); record.put("commonState", w.states);
			record.put("commonBE", w.blockEntities); record.put("readCheck", w.checks); record.put("heldKey", w.heldKeys); record.put("registry", w.registries); record.put("threadCheck", w.threadChecks);
			record.put("providerFactory", v.factories); record.put("providerLoaded", v.loadedCalls); record.put("providerRoute", v.routeCalls); record.put("providerController", v.controllerCalls);
			record.put("providerIsController", v.controllerChecks); record.put("providerWidth", v.widths); record.put("providerLength", v.lengths); record.put("providerAxis", v.axes); record.put("providerLocal", v.locals);
			record.put("getSlotsEvidence", "not instrumented: exact native owner required; local callbacks counted, not relabeled getSlots");
			// On these failure-free boundaries each width call belongs to one full successful provider pass.
			// Common checks each perform 2*m readCheck callbacks. Admission has additional legacy gates, so do not infer it there.
			if (!workload.equals("admission")) {
				record.put("inferredCommonCurrent", w.checks % (2 * MEMBERS) == 0 ? w.checks / (2 * MEMBERS) : -1);
				record.put("inferredProviderMatches", v.widths); record.put("inferenceBoundary", "stable success only; common=readCheck/(2*m); matches=width callbacks; not production instrumentation");
			}
			record.put("physicalVisits", run.physicalVisits); record.put("committedAddresses", run.slots - slots); record.put("pages", run.pages - pages); record.put("scans", run.completions - completions);
			record.put("defers", run.defers - defers); record.put("invalid", run.invalid); record.put("cursor", run.slots); record.put("tick", run.tick);
			record.put("retainedBytes", run.runtime.memory().retained()); record.put("reservedBytes", run.runtime.memory().reserved()); emit(record);
			if (workload.equals("sweep1620")) { assertEquals(SLOTS, run.physicalVisits); assertEquals(SLOTS, run.slots); }
			if (workload.equals("idleValidation")) assertEquals(0, physical(run.runtime));
		}
	}
}
