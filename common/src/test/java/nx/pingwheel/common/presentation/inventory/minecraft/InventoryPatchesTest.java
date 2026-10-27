package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Patch provenance and the shared raw-access boundary, without any caller policy. */
@NotNullByDefault
class InventoryPatchesTest {

	private static final InventoryResidentAccess RAW = new InventoryResidentAccessFields();

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void explicitEqualAndRemovalsSurviveWithoutDefaultsOrCopyOnWriteChanges() {
		DataComponentMap prototype = DataComponentMap.builder().set(DataComponents.MAX_DAMAGE, 7)
			.set(DataComponents.DAMAGE, 0).set(DataComponents.MAX_STACK_SIZE, 1).build();
		DataComponentPatch patch = DataComponentPatch.builder().set(DataComponents.MAX_DAMAGE, 7)
			.remove(DataComponents.DAMAGE).remove(DataComponents.CUSTOM_MODEL_DATA).build();
		PatchedDataComponentMap source = new PatchedDataComponentMap(prototype);
		source.restorePatch(patch); // Native restoration preserves even redundant raw entries.
		Map<DataComponentType<?>, Optional<?>> backing = RAW.patch(source);
		assertFalse(InventoryResidentAccessFields.<Boolean>field(source, "copyOnWrite"));
		var admission = new RecordingAdmission();
		Map<DataComponentType<?>, Optional<?>> observed = new IdentityHashMap<>();

		assertEquals(3, InventoryPatches.forEach(source, RAW, admission, observed::put));

		assertEquals(Optional.of(7), observed.get(DataComponents.MAX_DAMAGE));
		assertEquals(Optional.empty(), observed.get(DataComponents.DAMAGE));
		assertEquals(Optional.empty(), observed.get(DataComponents.CUSTOM_MODEL_DATA), "removal without a default is still explicit");
		assertFalse(observed.containsKey(DataComponents.MAX_STACK_SIZE), "an untouched default is not a patch entry");
		assertSame(backing, RAW.patch(source));
		assertFalse(InventoryResidentAccessFields.<Boolean>field(source, "copyOnWrite"), "reading must not call asPatch");
		assertEquals(1, admission.inspections);
		assertEquals(1, admission.passes);
		assertEquals(3, admission.entries);
	}

	@Test
	void nativeNormalizedEntriesAreNotInventedAgain() {
		PatchedDataComponentMap source = new PatchedDataComponentMap(
			DataComponentMap.builder().set(DataComponents.MAX_DAMAGE, 7).build());
		source.set(DataComponents.MAX_DAMAGE, 7);
		source.remove(DataComponents.CUSTOM_MODEL_DATA);
		assertTrue(RAW.patch(source).isEmpty(), "the native setter and remover erased these redundant entries");

		assertEquals(0, InventoryPatches.forEach(source, RAW, new RecordingAdmission(),
			(type, value) -> fail("a missing raw entry must not be reconstructed from defaults")));
	}

	@Test
	void poisonedPrototypeIsNeverReadAndBuilderNeverComparesValues() {
		Poison value = new Poison();
		DataComponentType<Poison> type = DataComponentType.<Poison>builder().persistent(Codec.unit(value)).build();
		DataComponentPatch patch = DataComponentPatch.builder().set(type, value).remove(DataComponents.DAMAGE).build();
		PatchedDataComponentMap source = new PatchedDataComponentMap(new PrototypeTrap());
		source.restorePatch(patch);
		DataComponentPatch.Builder rebuilt = DataComponentPatch.builder();

		InventoryPatches.forEach(source, RAW, new RecordingAdmission(),
			(component, optional) -> InventoryPatches.put(rebuilt, component, optional));
		DataComponentPatch result = rebuilt.build();

		assertSame(value, result.get(type).orElseThrow());
		assertEquals(Optional.empty(), result.get(DataComponents.DAMAGE));
	}

	@Test
	void rebuildingUsesIndependentBackingAndCallerOwnedMutableValues() {
		CompoundTag tag = new CompoundTag(); tag.putInt("value", 7);
		CustomData mutable = CustomData.of(tag);
		DataComponentPatch source = DataComponentPatch.builder().set(DataComponents.CUSTOM_DATA, mutable)
			.remove(DataComponents.CUSTOM_NAME).build();
		DataComponentPatch.Builder rebuilt = DataComponentPatch.builder();
		InventoryPatches.forEach(source, RAW, new RecordingAdmission(), (type, value) -> {
			Optional<?> owned = type == DataComponents.CUSTOM_DATA
				? Optional.of(CustomData.of(((CustomData) value.orElseThrow()).getUnsafe())) : value;
			InventoryPatches.put(rebuilt, type, owned);
		});
		DataComponentPatch result = rebuilt.build();

		mutable.getUnsafe().putInt("value", 99);
		RAW.patch(source).clear();

		assertNotSame(RAW.patch(source), RAW.patch(result));
		assertEquals(7, result.get(DataComponents.CUSTOM_DATA).orElseThrow().getUnsafe().getInt("value"));
		assertEquals(Optional.empty(), result.get(DataComponents.CUSTOM_NAME));
	}

	@Test
	void exactNativeBackingsUseOneAdmissionAndAtMostOneCapacityRead() {
		Map<DataComponentType<?>, Optional<?>> array = new Reference2ObjectArrayMap<>();
		array.put(DataComponents.DAMAGE, Optional.of(1));
		Map<DataComponentType<?>, Optional<?>> open = new Reference2ObjectOpenHashMap<>(1024);
		open.put(DataComponents.DAMAGE, Optional.empty());
		checkBacking(array, 1, false);
		checkBacking(open, 1, true);
		checkBacking(Reference2ObjectMaps.emptyMap(), 0, false);
		open.clear();
		checkBacking(open, 0, false); // No retained bucket scan for an empty patch.
	}

	private static void checkBacking(Map<DataComponentType<?>, Optional<?>> backing, int entries, boolean capacityRead) {
		var raw = new BackingAccess(backing);
		var admission = new RecordingAdmission();
		Map<DataComponentType<?>, Optional<?>> observed = new IdentityHashMap<>();
		assertEquals(entries, InventoryPatches.forEach(DataComponentPatch.EMPTY, raw, admission, observed::put));
		assertEquals(entries, observed.size());
		assertEquals(1, raw.patchReads);
		assertEquals(capacityRead ? 1 : 0, raw.capacityReads);
		assertEquals(1, admission.inspections);
		assertEquals(1, admission.passes);
		assertEquals(entries, admission.entries);
		if (capacityRead) assertTrue(admission.capacity > entries, "sparse backing cost is not just logical size");
		else assertEquals(0, admission.capacity);
	}

	@Test
	void unsupportedOuterAndBackingRefuseBeforeTheirMethodsOrVisitor() {
		var admission = new RecordingAdmission();
		assertThrows(InventoryPatches.UnsupportedPatchException.class,
			() -> InventoryPatches.forEach(new PrototypeTrap(), RAW, admission, (type, value) -> fail("visitor")));
		assertThrows(InventoryPatches.UnsupportedPatchException.class,
			() -> InventoryPatches.forEach(DataComponentMap.EMPTY, RAW, admission, (type, value) -> fail("visitor")));
		assertEquals(0, admission.passes);
		Map<DataComponentType<?>, Optional<?>> trap = new BackingTrap();
		assertThrows(AssertionError.class, trap::size, "positive control: unknown backing callbacks are armed");
		assertThrows(InventoryPatches.UnsupportedPatchException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, new BackingAccess(trap), admission, (type, value) -> fail("visitor")));
		assertThrows(InventoryPatches.UnsupportedPatchException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, new BackingAccess(new HashMap<>()), admission, (type, value) -> fail("visitor")));
		assertThrows(InventoryPatches.UnsupportedPatchException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, new BackingAccess(null), admission, (type, value) -> fail("visitor")));
		assertEquals(0, admission.passes);
	}

	@Test
	void malformedEntriesRefuseWithoutBeingPassedToVisitor() {
		Map<@Nullable DataComponentType<?>, @Nullable Optional<?>> invalid = new Reference2ObjectArrayMap<>();
		invalid.put(DataComponents.DAMAGE, null);
		assertThrows(InventoryPatches.UnsupportedPatchException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, new BackingAccess(invalid), new RecordingAdmission(), (type, value) -> fail("visitor")));
		invalid.clear(); invalid.put(null, Optional.empty());
		assertThrows(InventoryPatches.UnsupportedPatchException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, new BackingAccess(invalid), new RecordingAdmission(), (type, value) -> fail("visitor")));
	}

	@Test
	void invalidOpenHashCapacityRefusesBeforeTraversalAdmission() {
		Map<DataComponentType<?>, Optional<?>> entries = new Reference2ObjectOpenHashMap<>();
		entries.put(DataComponents.DAMAGE, Optional.of(1));
		var raw = new BackingAccess(entries);
		var admission = new RecordingAdmission();
		raw.capacityOverride = 3; // Not a native power-of-two table.
		assertThrows(InventoryPatches.UnsupportedPatchException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, raw, admission, (type, value) -> fail("visitor")));
		assertEquals(0, admission.passes);
		assertEquals(1, raw.capacityReads);
	}

	@Test
	void admissionAndVisitorFailuresPropagateUnchangedBeforeFurtherWork() {
		Map<DataComponentType<?>, Optional<?>> entries = new Reference2ObjectArrayMap<>();
		entries.put(DataComponents.DAMAGE, Optional.of(1));
		var raw = new BackingAccess(entries);
		var admission = new RecordingAdmission();
		admission.refuseInspection = true;
		assertSame(admission.failure, assertThrows(IllegalStateException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, raw, admission, (type, value) -> fail("visitor"))));
		assertEquals(0, raw.patchReads);
		admission.refuseInspection = false; admission.refusePass = true;
		assertSame(admission.failure, assertThrows(IllegalStateException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, raw, admission, (type, value) -> fail("visitor"))));
		admission.refusePass = false;
		assertSame(admission.failure, assertThrows(IllegalStateException.class,
			() -> InventoryPatches.forEach(DataComponentPatch.EMPTY, raw, admission, (type, value) -> { throw admission.failure; })));
	}

	@NotNullByDefault
	private static final class RecordingAdmission implements InventoryPatches.Admission {
		private int inspections, passes, entries, capacity;
		private boolean refuseInspection, refusePass;
		private final IllegalStateException failure = new IllegalStateException("admission refused");
		@Override public void inspect() { inspections++; if (refuseInspection) throw failure; }
		@Override public void traverse(int entries, int capacity) {
			passes++; this.entries = entries; this.capacity = capacity;
			if (refusePass) throw failure;
		}
	}

	@NotNullByDefault
	private static final class BackingAccess implements InventoryResidentAccess {
		private final @Nullable Map<@Nullable DataComponentType<?>, @Nullable Optional<?>> backing;
		private @Nullable Integer capacityOverride;
		private int patchReads, capacityReads;
		private BackingAccess(@Nullable Map<@Nullable DataComponentType<?>, @Nullable Optional<?>> backing) { this.backing = backing; }
		@Override public @Nullable Map<@Nullable DataComponentType<?>, @Nullable Optional<?>> patch(DataComponentPatch patch) { patchReads++; return backing; }
		@Override public int referenceMapCapacity(Map<?, ?> map) {
			capacityReads++; return capacityOverride == null ? InventoryResidentAccess.super.referenceMapCapacity(map) : capacityOverride;
		}
	}

	@NotNullByDefault
	private static final class PrototypeTrap implements DataComponentMap {
		@Override public <T> @Nullable T get(DataComponentType<? extends T> type) { throw new AssertionError("prototype get"); }
		@Override public Set<DataComponentType<?>> keySet() { throw new AssertionError("prototype traversal"); }
	}

	@NotNullByDefault
	private static final class BackingTrap extends AbstractMap<DataComponentType<?>, Optional<?>> {
		@Override public int size() { throw new AssertionError("unknown backing size"); }
		@Override public Set<Map.Entry<DataComponentType<?>, Optional<?>>> entrySet() { throw new AssertionError("unknown backing traversal"); }
	}

	@NotNullByDefault
	private static final class Poison {
		@Override public int hashCode() { throw new AssertionError("value hashing"); }
		@Override public boolean equals(@Nullable Object other) { throw new AssertionError("value equality"); }
		@Override public String toString() { throw new AssertionError("value formatting"); }
	}
}
