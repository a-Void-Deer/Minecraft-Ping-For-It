package nx.pingwheel.common.platform;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PlatformInventoryScopeFactoryTest {
	private static IPlatformInventoryService.ResidentScope scope() {
		return new IPlatformInventoryService.ResidentScope() {
			@Override public Direction face() { return Direction.NORTH; }
			@Override public boolean valid() { return true; }
			@Override public boolean allows(Container container) { return true; }
			@Override public boolean audited(Container container) { return false; }
			@Override public @Unmodifiable List<Integer> slots(Container container) { return List.of(); }
		};
	}
	@NotNullByDefault
	private static class LegacyAccess implements IPlatformInventoryService.Access {
		@Override public int slots() { throw new AssertionError("scope binding must not read metadata"); }
		@Override public boolean stableCursor() { throw new AssertionError("scope binding must not read metadata"); }
		@Override public OptionalLong version() { throw new AssertionError("scope binding must not read metadata"); }
		@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("content getter"); }
		@Override public boolean visit(int limit, Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("content getter"); }
	}
	@Test void compatibilityDefaultBuildsFreshScopesOnceAndKeepsLegacyOverrideDispatch() {
		var constructions = new AtomicInteger(); var bindings = new AtomicInteger();
		var access = new LegacyAccess() {
			@Override public IPlatformInventoryService.Access scopedResidents(IPlatformInventoryService.ResidentScope scope) {
				assertEquals(Direction.NORTH, scope.face()); bindings.incrementAndGet(); return this;
			}
		};
		Supplier<IPlatformInventoryService.ResidentScope> factory = () -> { constructions.incrementAndGet(); return scope(); };
		assertSame(access, access.scopedResidents(factory)); assertEquals(1, constructions.get()); assertEquals(1, bindings.get());
		assertSame(access, access.scopedResidents(factory)); assertEquals(2, constructions.get()); assertEquals(2, bindings.get());
	}
	@Test void guardedBindingForwardsTheFactoryOnlyInsideFreshPreAndPostGates() {
		var allowed = new AtomicBoolean(); var constructions = new AtomicInteger(); var bindings = new AtomicInteger();
		var delegate = new LegacyAccess() {
			@Override public IPlatformInventoryService.Access scopedResidents(IPlatformInventoryService.ResidentScope scope) {
				bindings.incrementAndGet(); return this;
			}
		};
		var guarded = delegate.guardedBy(allowed::get);
		Supplier<IPlatformInventoryService.ResidentScope> factory = () -> { constructions.incrementAndGet(); return scope(); };
		assertThrows(IllegalStateException.class, () -> guarded.scopedResidents(factory));
		assertEquals(0, constructions.get()); assertEquals(0, bindings.get());
		allowed.set(true); var rebound = guarded.scopedResidents(factory);
		assertEquals(1, constructions.get()); assertEquals(1, bindings.get());
		allowed.set(false); assertThrows(IllegalStateException.class, () -> rebound.read(0));
		allowed.set(true);
		assertThrows(IllegalStateException.class, () -> guarded.scopedResidents(() -> {
			allowed.set(false); return scope();
		}));
		assertEquals(2, bindings.get(), "post-binding revocation still rejects the returned wrapper");
	}
	@Test void guardedProviderCanDeclineFactoryWithoutLosingGateOrLegacyBinding() {
		var bindings = new AtomicInteger(); var allowed = new AtomicBoolean(true);
		var delegate = new LegacyAccess() {
			@Override public IPlatformInventoryService.Access scopedResidents(Supplier<? extends IPlatformInventoryService.ResidentScope> factory) {
				bindings.incrementAndGet(); return this;
			}
			@Override public IPlatformInventoryService.Access scopedResidents(IPlatformInventoryService.ResidentScope scope) {
				bindings.addAndGet(10); return this;
			}
		};
		var guarded = delegate.guardedBy(allowed::get);
		guarded.scopedResidents(() -> { throw new AssertionError("unused scope was constructed"); });
		assertEquals(1, bindings.get());
		guarded.scopedResidents(scope()); assertEquals(11, bindings.get(), "the old overload retains its behavior");
		allowed.set(false);
		assertThrows(IllegalStateException.class, () -> guarded.scopedResidents(() -> { throw new AssertionError("denied factory"); }));
		assertEquals(11, bindings.get());
	}
}
