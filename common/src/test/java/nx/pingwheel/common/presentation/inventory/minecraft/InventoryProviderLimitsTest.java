package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.Map;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryProviderLimitsTest {
	@Test void independentNativeBoundsDoNotSuppressEachOtherAndDebugReportsEveryHit() throws ReflectiveOperationException {
		try (var channels = new ChannelScope(); var capture = new InventoryDiagnosticsTestCapture(Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			InventoryProviderLimits.report(ProviderLimitSite.VAULT_SLOTS, 16385, 16384);
			assertTrue(capture.events().isEmpty());
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			for (var site : List.of(ProviderLimitSite.VAULT_SLOTS, ProviderLimitSite.TANK_MEMBERS)) {
				InventoryProviderLimits.report(site, 16385, 16384);
				InventoryProviderLimits.report(site, 16385, 16384);
			}
			assertEquals(2, capture.events().size());
			assertTrue(capture.messages().contains("inventory-provider/vault-slots"));
			assertTrue(capture.messages().contains("inventory-provider/tank-members"));
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			for (int index = 0; index < 3; index++) InventoryProviderLimits.report(ProviderLimitSite.VAULT_SLOTS, 16385, 16384);
			assertEquals(5, capture.events().size());
		}
	}
	@Test void typedEvidenceKeepsReasonAndBoundsWithoutProviderText() {
		var failure = InventoryProviderLimits.exceeded(ProviderLimitSite.FABRIC_FLUID_UNDERLYING_DEPTH, 33, 32);
		assertEquals(InventoryFailureReason.NATIVE_DEPTH_LIMIT, failure.reason());
		assertEquals(ProviderLimitSite.FABRIC_FLUID_UNDERLYING_DEPTH, failure.site());
		assertEquals(33, failure.required()); assertEquals(32, failure.limit());
	}
	@Test void malformedSnapshotMappingIsNotReportedAsCapacity() {
		assertThrows(IllegalArgumentException.class,
			() -> new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 2, List.of(0, 0)));
		var failure = assertThrows(InventoryProviderLimitException.class,
			() -> new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 16385, List.of()));
		assertEquals(ProviderLimitSite.SNAPSHOT_LOCAL_SLOTS, failure.site());
	}
	@Test void snapshotTextRefusalDisclosesOnlyTheOwnedSiteAndScalarLength() {
		try (var capture = new InventoryDiagnosticsTestCapture(Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			var failure = assertThrows(InventoryProviderLimitException.class, () -> new InventorySnapshotLayout.Member(
				net.minecraft.core.BlockPos.ZERO, "private:" + "x".repeat(249), "test:entity", "member", List.of()));
			assertEquals(ProviderLimitSite.SNAPSHOT_BLOCK_ID, failure.site());
			assertEquals(257, failure.required()); assertEquals(256, failure.limit());
			assertEquals(1, capture.events().size());
			assertTrue(capture.messages().contains("required=257"));
			assertFalse(capture.messages().contains("private:"));
		}
	}
	@Test void transferredFluidAdmissionIsLifecycleMisuseRatherThanCapacity() {
		var work = new CostLedger.Counter("provider-test", CostLedger.Unit.WORK);
		try (var admission = new InventoryFluidAdmission(new CostLedger(Map.of(work, 10000L)), work,
			new RetainedMemoryLedger(10000), InventoryValidationBudget.NONE, InventoryValidationBudget.NONE)) {
			try (var ownership = admission.retain()) {
				var failure = assertThrows(IllegalStateException.class, () -> admission.enter(IPlatformFluidService.OperationProfile.NONE));
				assertFalse(failure instanceof InventoryProviderLimitException);
			}
		}
	}
	@Test void fluidGrantOverflowKeepsItsTypedCapacityAndSettlesPreviouslyAcceptedGrants() {
		var work = new CostLedger.Counter("provider-grant-test", CostLedger.Unit.WORK);
		var ledger = new CostLedger(Map.of(work, Long.MAX_VALUE));
		var memory = new RetainedMemoryLedger(Long.MAX_VALUE);
		try (var admission = new InventoryFluidAdmission(ledger, work, memory, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE)) {
			for (int index = 0; index < 65536; index++) {
				try (var operation = admission.enter(IPlatformFluidService.OperationProfile.NONE)) { operation.allowance(); }
			}
			var failure = assertThrows(InventoryProviderLimitException.class, () -> admission.enter(IPlatformFluidService.OperationProfile.NONE));
			assertEquals(ProviderLimitSite.FLUID_ADMISSION_GRANTS, failure.site());
			assertEquals(65537, failure.required()); assertEquals(65536, failure.limit());
		}
		assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		assertTrue(ledger.used(work) > 0);
	}
	@Test void refusedReflectionMetadataRetainsUnclassifiedFallbackAndReportsEachDebugHit() {
		try (var capture = new InventoryDiagnosticsTestCapture(Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			for (int attempt = 0; attempt < 2; attempt++) {
				assertEquals(IPlatformInventoryService.ResidentReads.MethodOwnership.UNCLASSIFIED,
					IPlatformInventoryService.ResidentReads.methodOwnership(new Object(), Object.class, "x".repeat(129)));
				assertFalse(IPlatformInventoryService.ResidentReads.reflectionShape(Object.class, "equals", Object.class, Object.class, Object.class));
			}
			assertEquals(4, capture.events().size());
			assertTrue(capture.messages().contains("inventory-provider/reflection-member-name"));
			assertTrue(capture.messages().contains("inventory-provider/reflection-parameters"));
			assertFalse(capture.messages().contains("x".repeat(129)));
		}
	}
	/** Restore process-owned channels so test order cannot consume another case's admission. */
	@NotNullByDefault
	private static final class ChannelScope implements AutoCloseable {
		private final @Unmodifiable List<Saved> saved;
		ChannelScope() throws ReflectiveOperationException {
			var values = new ArrayList<Saved>();
			Field side = DiagnosticChannel.class.getDeclaredField("server"); side.setAccessible(true);
			for (var site : List.of(ProviderLimitSite.VAULT_SLOTS, ProviderLimitSite.TANK_MEMBERS)) {
				Object state = side.get(site.channel());
				for (Field field : state.getClass().getDeclaredFields()) {
					field.setAccessible(true); values.add(new Saved(state, field, field.get(state)));
					if (field.getType() == boolean.class) field.setBoolean(state, false); else field.setLong(state, 0L);
				}
			}
			saved = List.copyOf(values);
		}
		@Override public void close() throws IllegalAccessException {
			for (Saved value : saved) value.field().set(value.state(), value.value());
		}
	}
	@NotNullByDefault
	private record Saved(Object state, Field field, @Nullable Object value) {}
}
