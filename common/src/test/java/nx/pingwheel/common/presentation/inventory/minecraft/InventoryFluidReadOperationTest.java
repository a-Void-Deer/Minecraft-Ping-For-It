package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Common single-use operation state machine; no provider, world or live loader is involved. */
@NotNullByDefault
class InventoryFluidReadOperationTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void acceptedSequenceRunsTwoChecksOneFreezeAndOneCompletion() throws Exception {
		var fixture = new Fixture();
		try (var operation = fixture.operation()) {
			operation.enter(fixture.provider, 0);
			operation.beforeResident(fixture.provider, 0);
			operation.detach(fixture.provider, 0,
				new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, null), 81));
			operation.afterResident(fixture.provider, 0);
			var value = Objects.requireNonNull(operation.completed(fixture.port));
			assertEquals(81, value.amount());
			assertEquals(2, fixture.port.validations);
			assertThrows(InventorySourceUnavailableException.class, () -> operation.completed(fixture.port),
				"a completed operation is single-use");
			assertReleased(operation);
		}
	}

	@Test void duplicateMisorderedWrongProviderSlotThreadAndClosedCallsPoisonTheOperation() throws Exception {
		final var first = new Fixture();
		try (var operation = first.operation()) {
			assertThrows(InventorySourceUnavailableException.class, () -> operation.beforeResident(first.provider, 0));
			assertReleased(operation);
		}
		final var duplicate = new Fixture();
		try (var operation = duplicate.operation()) {
			operation.enter(duplicate.provider, 0);
			assertThrows(InventorySourceUnavailableException.class, () -> operation.enter(duplicate.provider, 0));
			assertReleased(operation);
		}
		final var foreign = new Fixture();
		try (var operation = foreign.operation()) {
			operation.enter(foreign.provider, 0);
			assertThrows(InventorySourceUnavailableException.class, () -> operation.enter(foreign.other, 0));
			assertReleased(operation);
		}
		final var wrongSlot = new Fixture();
		try (var operation = wrongSlot.operation()) {
			operation.enter(wrongSlot.provider, 0);
			assertThrows(InventorySourceUnavailableException.class, () -> operation.enter(wrongSlot.provider, 1));
			assertReleased(operation);
		}
		final var wrongThread = new Fixture();
		try (var operation = wrongThread.operation()) {
			operation.enter(wrongThread.provider, 0);
			var failure = new AtomicReference<@Nullable Throwable>();
			var thread = new Thread(() -> failure.set(assertThrows(InventorySourceUnavailableException.class,
				() -> operation.enter(wrongThread.provider, 0))));
			thread.start(); thread.join();
			assertNotNull(failure.get());
			assertReleased(operation);
		}
		final var reordered = new Fixture();
		try (var operation = reordered.operation()) {
			operation.enter(reordered.provider, 0);
			assertThrows(InventorySourceUnavailableException.class, () -> operation.detach(reordered.provider, 0,
				IPlatformFluidService.Entry.empty()));
			assertReleased(operation);
		}
	}

	@Test void reentryDuringValidationPoisonsAndPreventsTheGetter() throws Exception {
		var fixture = new Fixture();
		var reference = new AtomicReference<InventoryFluidReadOperation>();
		fixture.port.reenter = () -> assertThrows(InventorySourceUnavailableException.class,
			() -> Objects.requireNonNull(reference.get()).detach(fixture.provider, 0, IPlatformFluidService.Entry.empty()));
		try (var operation = fixture.operation()) {
			reference.set(operation);
			operation.enter(fixture.provider, 0);
			assertThrows(InventorySourceUnavailableException.class, () -> operation.beforeResident(fixture.provider, 0));
			assertReleased(operation);
		}
	}

	@Test void deferredValidationClosesWithoutFreezeOrCompletionAndClearsOwnedReferences() throws Exception {
		var fixture = new Fixture();
		fixture.port.fail = new InventoryItemBudget.CaptureLimitException("one short");
		try (var operation = fixture.operation()) {
			operation.enter(fixture.provider, 0);
			assertSame(fixture.port.fail, assertThrows(InventoryItemBudget.CaptureLimitException.class,
				() -> operation.beforeResident(fixture.provider, 0)));
			assertReleased(operation);
			assertThrows(InventorySourceUnavailableException.class, () -> operation.afterResident(fixture.provider, 0));
		}
	}

	@Test void codecReentryCannotTurnAProvisionalDetachedValueIntoCompletion() throws Exception {
		var fixture = new Fixture();
		try (var registration = new InventoryCertifiedReadTest.CallbackComponent(); var operation = fixture.operation()) {
			registration.callback = () -> assertThrows(InventorySourceUnavailableException.class,
				() -> operation.afterResident(fixture.provider, 0));
			var patch = DataComponentPatch.builder().set(registration.type, 1).build();
			operation.enter(fixture.provider, 0); operation.beforeResident(fixture.provider, 0);
			assertThrows(InventorySourceUnavailableException.class, () -> operation.detach(fixture.provider, 0,
				new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, patch), 81)));
			assertReleased(operation);
		}
	}

	@Test void missingHandoffFailedPostcheckAndProofReplacementAllFailClosed() throws Exception {
		var fixture = new Fixture();
		try (var operation = fixture.operation()) {
			operation.enter(fixture.provider, 0); operation.beforeResident(fixture.provider, 0);
			operation.detach(fixture.provider, 0, IPlatformFluidService.Entry.empty());
			assertThrows(InventorySourceUnavailableException.class, () -> operation.completed(fixture.port));
			assertReleased(operation);
		}
		try (var operation = fixture.operation()) {
			operation.enter(fixture.provider, 0); operation.beforeResident(fixture.provider, 0);
			operation.detach(fixture.provider, 0, IPlatformFluidService.Entry.empty()); fixture.port.allowed = false;
			assertThrows(InventorySourceUnavailableException.class, () -> operation.afterResident(fixture.provider, 0));
			assertReleased(operation);
		}
		fixture.port.allowed = true;
		try (var operation = fixture.operation()) {
			fixture.port.proof = new Object();
			assertThrows(InventorySourceUnavailableException.class, () -> operation.enter(fixture.provider, 0));
			assertReleased(operation);
		}
	}

	private static void assertReleased(InventoryFluidReadOperation operation) throws Exception {
		for (var field : operation.getClass().getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
			field.setAccessible(true);
			assertNull(field.get(operation), "closed operation releases " + field.getName());
		}
	}

	@NotNullByDefault
	private static final class Fixture {
		private final TestAccess provider = new TestAccess("coordinated"), other = new TestAccess("other");
		private final PortImpl port = new PortImpl(provider);
		private InventoryFluidReadOperation operation() {
			return new InventoryFluidReadOperation(port, port.proof, provider, provider, 0, Thread.currentThread(),
				new InventoryFluidCodec.Catalog(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
				new InventoryCatalogAdmission(1_000_000, InventoryCatalogAdmission.workUpperBound(1024)),
				1024, port.allowance());
		}
	}
	@NotNullByDefault
	private static final class PortImpl implements InventoryFluidReadOperation.Port {
		private Object proof = new Object();
		private final IPlatformFluidService.Access wrapper;
		private final InventoryValidationBudget.Allowance allowance =
			new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(1_000_000, 1_000_000, 1_000_000));
		private int validations;
		private boolean allowed = true;
		private @Nullable Runnable reenter;
		private @Nullable InventoryItemBudget.CaptureLimitException fail;
		private PortImpl(IPlatformFluidService.Access wrapper) { this.wrapper = wrapper; }
		@Override public Object proof() { return proof; }
		@Override public IPlatformFluidService.Access wrapper() { return wrapper; }
		@Override public InventoryValidationBudget.Allowance allowance() { return allowance; }
		@Override public boolean active(InventoryFluidReadOperation operation) { return true; }
		@Override public boolean validate() {
			validations++;
			if (fail != null) throw fail;
			var callback = reenter; if (callback != null) { reenter = null; callback.run(); }
			return allowed;
		}
	}
	@NotNullByDefault
	private static final class TestAccess implements IPlatformFluidService.CoordinatedAccess {
		private final String name;
		private TestAccess(String name) { this.name = name; }
		@Override public InventorySnapshotLayout layout() {
			return new InventorySnapshotLayout("test", name, BlockPos.ZERO,
				List.of(new InventorySnapshotLayout.Member(BlockPos.ZERO, "test:block", "test:entity", "root", List.of())));
		}
		@Override public Optional<String> alias() { return Optional.empty(); }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 1; }
		@Override public IPlatformFluidService.RouteInfo routeInfo() {
			return new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, name);
		}
		@Override public IPlatformFluidService.OperationProfile validationProfile() { return IPlatformFluidService.OperationProfile.NONE; }
		@Override public IPlatformFluidService.OperationProfile readProfile() { return IPlatformFluidService.OperationProfile.NONE; }
		@Override public boolean valid(IPlatformFluidService.Operation operation) { return true; }
		@Override public boolean consume(int slot, IPlatformFluidService.Visitor visitor, IPlatformFluidService.Operation operation) {
			return false;
		}
		@Override public IPlatformFluidService.OperationProfile coordinatedResidentProfile() {
			return IPlatformFluidService.OperationProfile.NONE;
		}
		@Override public void consumeCoordinatedResident(int slot, InventoryFluidReadOperation operation, IPlatformFluidService.Operation admitted) {
			throw new AssertionError("state machine test never enters the provider forward");
		}
		@Override public void close() {}
	}
}
