package nx.pingwheel.common.platform;

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import nx.pingwheel.common.platform.FluidAddressWitness.Schema;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessMatch;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessUse;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class FluidAddressWitnessTest {
	private static final CostLedger.Counter WORK = new CostLedger.Counter("fluid-witness", CostLedger.Unit.WORK);
	private static final Schema SCHEMA = Schema.FABRIC_TRANSFER_V1;

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final CostLedger work = new CostLedger(Map.of(WORK, 1_000_000L));
		final RetainedMemoryLedger memory = new RetainedMemoryLedger(4_000_000);
		final InventoryFluidAdmission admission = new InventoryFluidAdmission(work, WORK, memory,
			InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
		FluidAddressWitness capture(Schema schema, Object[] identities) {
			try (var operation = admission.enter(FluidAddressWitness.profile(WitnessUse.CAPTURE, identities.length))) {
				return FluidAddressWitness.capture(schema, identities, operation);
			}
		}
		WitnessMatch compare(FluidAddressWitness expected, Schema schema, Object[] identities) {
			try (var operation = admission.enter(FluidAddressWitness.profile(WitnessUse.COMPARE, identities.length))) {
				return expected.compare(schema, identities, operation);
			}
		}
		@Override public void close() { admission.close(); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained()); }
	}

	@Test void originalVectorIsNotRetainedAndIdentityNeverCallsNativeEqualsHashOrFormatting() {
		try (var f = new Fixture()) {
			Object first = new HostileIdentity(), second = new HostileIdentity();
			Object[] input = {first, second}; var expected = f.capture(SCHEMA, input);
			input[0] = new HostileIdentity();
			assertEquals(WitnessMatch.SAME, f.compare(expected, SCHEMA, new Object[] {first, second}));
			assertEquals(WitnessMatch.CHANGED, f.compare(expected, SCHEMA, input));
			assertEquals(WitnessMatch.CHANGED, f.compare(expected, SCHEMA, new Object[] {second, first}));
		}
	}

	@Test void schemaLengthAndLiveRouteChangesAreChangedRatherThanUnverifiable() {
		try (var f = new Fixture()) {
			Object root = new Object(), address = new Object(); var expected = f.capture(SCHEMA, new Object[] {root, address});
			assertEquals(WitnessMatch.CHANGED, f.compare(expected, Schema.NEOFORGE_CAPABILITY_V1, new Object[] {root, address}));
			assertEquals(WitnessMatch.CHANGED, f.compare(expected, SCHEMA, new Object[] {root}));
			assertEquals(WitnessMatch.CHANGED, f.compare(expected, SCHEMA, new Object[] {new Object(), address}));
		}
	}

	@Test void deterministicallyClearedEvidenceNeverProvesContinuityOrClaimsRemappingByItself() {
		try (var f = new Fixture()) {
			Object root = new Object(), address = new Object(); var expected = f.capture(SCHEMA, new Object[] {root, address});
			expected.clearForTesting(0);
			assertEquals(WitnessMatch.UNVERIFIABLE, f.compare(expected, SCHEMA, new Object[] {root, address}));
			assertEquals(WitnessMatch.CHANGED, f.compare(expected, SCHEMA, new Object[] {root, new Object()}));
			assertEquals(WitnessMatch.CHANGED, f.compare(expected, Schema.FORGE_CAPABILITY_V1, new Object[] {root, address}));
			expected.clearForTesting(1);
			assertEquals(WitnessMatch.UNVERIFIABLE, f.compare(expected, SCHEMA, new Object[] {root, address}));
		}
	}

	@Test void minimumAndMaximumVectorsFitRealGrantsAndComparisonHasNoRetainedCharge() {
		for (int count : new int[] {1, FluidAddressWitness.MAX_REFERENCES}) try (var f = new Fixture()) {
			Object[] current = new Object[count]; Arrays.setAll(current, index -> new Object());
			var profile = FluidAddressWitness.profile(WitnessUse.CAPTURE, count);
			assertEquals(256L + 112L * count, profile.retainedBytes());
			assertEquals(0, profile.memberGateCalls()); assertEquals(0, profile.currentAuthorityCalls());
			var expected = f.capture(SCHEMA, current); long retainedReservation = f.memory.reserved();
			var compare = FluidAddressWitness.profile(WitnessUse.COMPARE, count);
			assertEquals(0, compare.retainedBytes()); assertEquals(0, compare.memberGateCalls()); assertEquals(0, compare.currentAuthorityCalls());
			assertEquals(WitnessMatch.SAME, f.compare(expected, SCHEMA, current));
			assertTrue(f.memory.reserved() >= retainedReservation); // Only operation bookkeeping/scratch can grow.
			var ownership = f.admission.retain(); f.admission.close();
			assertEquals(profile.retainedBytes() + 512, f.memory.retained()); assertEquals(0, f.memory.reserved());
			assertEquals(WitnessMatch.SAME, compareAfterAdmissionClose(expected, current));
			ownership.close(); assertEquals(0, f.memory.retained());
		}
	}

	private static WitnessMatch compareAfterAdmissionClose(FluidAddressWitness expected, Object[] current) {
		try (var next = new Fixture()) { return next.compare(expected, SCHEMA, current); }
	}

	@Test void missingGrantRefusesBeforeWeakAllocationAndRepeatedHelperUseCannotRefillItsAllowance() {
		try (var f = new Fixture(); var operation = f.admission.enter(IPlatformFluidService.OperationProfile.NONE)) {
			assertThrows(InventoryItemBudget.CaptureLimitException.class,
				() -> FluidAddressWitness.capture(SCHEMA, new Object[] {new Object()}, operation));
		}
		try (var f = new Fixture()) {
			var operation = f.admission.enter(FluidAddressWitness.profile(WitnessUse.CAPTURE, 1));
			try {
				Object[] current = {new Object()}; var expected = FluidAddressWitness.capture(SCHEMA, current, operation);
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> FluidAddressWitness.capture(SCHEMA, current, operation));
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> expected.compare(SCHEMA, current, operation));
			} finally { operation.close(); }
			assertThrows(IllegalStateException.class, () -> FluidAddressWitness.capture(SCHEMA, new Object[] {new Object()}, operation));
		}
	}

	@Test void workAndOwnershipRefusalCannotRunTheVectorBuilderOrLeakTickets() {
		for (boolean refuseMemory : new boolean[] {false, true}) {
			var ledger = new CostLedger(Map.of(WORK, refuseMemory ? 10_000L : 0L));
			var memory = new RetainedMemoryLedger(refuseMemory ? 0 : 100_000); boolean[] allocated = {false};
			try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE)) {
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> {
					try (var operation = admission.enter(FluidAddressWitness.profile(WitnessUse.CAPTURE, 1))) {
						operation.allowance().requireAvailable(FluidAddressWitness.profile(WitnessUse.CAPTURE, 1).ownedCost());
						allocated[0] = true; FluidAddressWitness.capture(SCHEMA, new Object[] {new Object()}, operation);
					}
				});
			}
			assertFalse(allocated[0]); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
			assertEquals(0, ledger.used(WORK)); assertEquals(ledger.limit(WORK), ledger.remaining(WORK));
		}
	}

	@Test void vectorBoundsAndNullIdentitiesFailClosedWithAttemptedWorkStillPaid() {
		for (int count : new int[] {-1, 0, FluidAddressWitness.MAX_REFERENCES + 1, Integer.MAX_VALUE}) {
			assertThrows(IllegalArgumentException.class, () -> FluidAddressWitness.profile(WitnessUse.CAPTURE, count));
			assertThrows(IllegalArgumentException.class, () -> FluidAddressWitness.retainedBytes(count));
		}
		try (var f = new Fixture(); var operation = f.admission.enter(FluidAddressWitness.profile(WitnessUse.CAPTURE, 1))) {
			long before = operation.allowance().attemptedWorkUnits();
			assertThrows(NullPointerException.class, () -> FluidAddressWitness.capture(SCHEMA, new Object[1], operation));
			assertEquals(FluidAddressWitness.profile(WitnessUse.CAPTURE, 1).ownedCost().workUnits(), operation.allowance().attemptedWorkUnits() - before);
		}
	}

	@Test void valueStoresOnlyFixedSchemaAndPrivateWeakVectorWithoutPublicDereferenceOrTestHook() throws Exception {
		assertTrue(Modifier.isFinal(FluidAddressWitness.class.getModifiers()));
		for (var field : FluidAddressWitness.class.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
			assertTrue(Modifier.isPrivate(field.getModifiers())); assertTrue(Modifier.isFinal(field.getModifiers()));
			assertTrue(field.getType() == Schema.class || field.getType() == WeakReference[].class);
		}
		for (var method : FluidAddressWitness.class.getDeclaredMethods()) if (Modifier.isPublic(method.getModifiers()))
			assertTrue(Set.of("profile", "retainedBytes", "capture", "compare").contains(method.getName()));
		assertFalse(Modifier.isPublic(FluidAddressWitness.class.getDeclaredMethod("clearForTesting", int.class).getModifiers()));
	}

	@NotNullByDefault
	private static final class HostileIdentity {
		@Override public boolean equals(@org.jetbrains.annotations.Nullable Object other) { throw new AssertionError("native equals callback"); }
		@Override public int hashCode() { throw new AssertionError("native hash callback"); }
		@Override public String toString() { throw new AssertionError("native formatting callback"); }
	}
}
