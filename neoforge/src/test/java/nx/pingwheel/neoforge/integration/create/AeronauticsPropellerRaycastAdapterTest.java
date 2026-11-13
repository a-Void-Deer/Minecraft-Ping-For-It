package nx.pingwheel.neoforge.integration.create;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.math.EntityLocalGeometryRegistry;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;
import nx.pingwheel.common.math.Raycast;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.common.math.RaycastSelection;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Behavioral optional-owner tests; Create and Aero classes are absent at runtime. */
@NotNullByDefault
class AeronauticsPropellerRaycastAdapterTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void registrationPinsOnlyTheSharedRotorIdAndLoadedExactAdmission() throws Exception {
		var registry = new EntityLocalGeometryRegistry();
		AtomicInteger loads = new AtomicInteger();
		var shell = new AeronauticsPropellerRaycastAdapter(() -> {
			loads.incrementAndGet();
			throw new ClassNotFoundException("optional Aero delegate");
		});
		try (var registration = AeronauticsPropellerRaycastAdapter.register(registry, shell)) {
			Object snapshot = snapshot(registry);
			@Unmodifiable List<?> entries = (List<?>) VaultOptionalHarness.field(snapshot.getClass(), "entries").get(snapshot);
			assertEquals(1, entries.size());
			Object entry = entries.getFirst();
			assertEquals(ResourceLocation.parse(AeronauticsPropellerRaycastAdapter.SOURCE_ID), invoke(entry, "sourceId"));
			assertEquals(EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT, invoke(entry, "admission"));
			assertEquals(List.of("aeronautics:propeller_bearing_contraption"),
				((Collection<?>) invoke(entry, "entityTypeIds")).stream().map(Object::toString).toList());
			assertEquals(0, loads.get(), "registration cannot resolve optional classes");
			for (ResourceLocation id : CreateContraptionRaycastAdapter.claimedEntityTypeIds()) {
				assertFalse(AeronauticsPropellerRaycastAdapter.claimedEntityTypeIds().contains(id));
			}
			assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, shell.trace(null, request()).outcome());
			assertTrue(registration.isActive(), "delegate absence cannot withdraw exact ownership");
			assertEquals(1, ((List<?>) VaultOptionalHarness.field(snapshot(registry).getClass(), "entries").get(snapshot(registry))).size());
		}
		assertTrue(((List<?>) VaultOptionalHarness.field(snapshot(registry).getClass(), "entries").get(snapshot(registry))).isEmpty());
	}

	@Test void lazyLoadFailureAndTraceLinkageAreQuarantinedUntilSessionReset() {
		AtomicInteger loads = new AtomicInteger(), traces = new AtomicInteger();
		var shell = new AeronauticsPropellerRaycastAdapter(() -> {
			loads.incrementAndGet();
			return (entity, request) -> { traces.incrementAndGet(); throw new NoClassDefFoundError("Aero drift"); };
		});
		assertEquals(0, loads.get());
		for (int i = 0; i < 2; i++) assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, shell.trace(null, request()).outcome());
		assertEquals(1, loads.get()); assertEquals(1, traces.get());
		shell.reset();
		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, shell.trace(null, request()).outcome());
		assertEquals(2, loads.get()); assertEquals(2, traces.get());

		AtomicInteger failures = new AtomicInteger();
		var missing = new AeronauticsPropellerRaycastAdapter(() -> {
			failures.incrementAndGet(); throw new ReflectiveOperationException("not installed");
		});
		for (int i = 0; i < 2; i++) assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, missing.trace(null, request()).outcome());
		assertEquals(1, failures.get());
	}

	@Test void runtimeFailuresAreNotQuarantinedAndNullResultsRejectTheCandidate() {
		AtomicInteger traces = new AtomicInteger();
		var shell = new AeronauticsPropellerRaycastAdapter(() -> (candidate, request) -> {
			traces.incrementAndGet(); throw new IllegalStateException("transient capture failure");
		});
		assertThrows(IllegalStateException.class, () -> shell.trace(null, request()));
		assertThrows(IllegalStateException.class, () -> shell.trace(null, request()));
		assertEquals(2, traces.get());
		var nullResult = new AeronauticsPropellerRaycastAdapter(() -> (candidate, request) -> null);
		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, nullResult.trace(null, request()).outcome());
	}

	@Test void staticLifecycleClosesIdempotentlyAndCanRegisterAgain() {
		AeronauticsPropellerRaycastAdapter.close();
		try {
			assertEquals("not-registered", AeronauticsPropellerRaycastAdapter.registrationState());
			AeronauticsPropellerRaycastAdapter.register(); AeronauticsPropellerRaycastAdapter.register();
			assertEquals("registered", AeronauticsPropellerRaycastAdapter.registrationState());
			AeronauticsPropellerRaycastAdapter.close(); AeronauticsPropellerRaycastAdapter.close();
			assertEquals("not-registered", AeronauticsPropellerRaycastAdapter.registrationState());
			AeronauticsPropellerRaycastAdapter.register();
			assertEquals("registered", AeronauticsPropellerRaycastAdapter.registrationState());
		} finally { AeronauticsPropellerRaycastAdapter.close(); }
	}

	@Test void optionalFreeShellCanActuallyRegisterAndCloseWithEveryOptionalPackageHidden() throws Exception {
		String shellName = AeronauticsPropellerRaycastAdapter.class.getName();
		ClassLoader parent = getClass().getClassLoader();
		ClassLoader isolated = new ClassLoader(parent) {
			@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("com.simibubi.create.") || name.startsWith("dev.engine_room.flywheel.")
					|| name.startsWith("org.valkyrienskies.") || name.startsWith("dev.ryanhcode.")
					|| name.equals("nx.pingwheel.neoforge.integration.create.AeronauticsPropellerRaycastDelegate")) {
					throw new ClassNotFoundException(name);
				}
				if (name.equals(shellName) || name.startsWith(shellName + '$')) {
					@Nullable Class<?> loaded = findLoadedClass(name);
					if (loaded == null) {
						try (var bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
							if (bytes == null) throw new ClassNotFoundException(name);
							byte[] content = bytes.readAllBytes(); loaded = defineClass(name, content, 0, content.length);
						} catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
					}
					if (resolve) resolveClass(loaded); return loaded;
				}
				return super.loadClass(name, resolve);
			}
		};
		Class<?> shell = Class.forName(shellName, true, isolated);
		try {
			shell.getMethod("register").invoke(null);
			assertEquals("registered", shell.getMethod("registrationState").invoke(null));
		} finally { shell.getMethod("close").invoke(null); }
		assertEquals("not-registered", shell.getMethod("registrationState").invoke(null));
	}

	@Test void ownedNonHitsDoNotOccludeTheRealRearCandidateThroughTheCommonSelectionSeam() throws Exception {
		for (var outcome : List.of(EntityLocalGeometryResult.miss(), EntityLocalGeometryResult.unavailable(), EntityLocalGeometryResult.failed())) {
			var registry = new EntityLocalGeometryRegistry();
			var shell = new AeronauticsPropellerRaycastAdapter(() -> (entity, request) -> outcome);
			Entity front = entity(2), rear = entity(7);
			registry.register(ResourceLocation.parse(AeronauticsPropellerRaycastAdapter.SOURCE_ID), 100,
				List.of(BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.ARMOR_STAND)), (entity, request) ->
					entity == front ? shell.trace(entity, request) : EntityLocalGeometryResult.hit(
						new nx.pingwheel.common.math.LocalGeometryHit(.7, net.minecraft.core.BlockPos.ZERO,
							nx.pingwheel.common.math.LocalGeometryKind.BLOCK, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
							"minecraft:stone", Optional.empty(), new Vec3(7, .5, .5))));
			Optional<?> selected = (Optional<?>) traceMethod().invoke(null, List.of(front, rear), request().start(),
				request().end(), request().policy(), snapshot(registry), request());
			var selection = (RaycastSelection) selected.orElseThrow();
			assertSame(rear, ((net.minecraft.world.phys.EntityHitResult) selection.hitResult()).getEntity());
			assertEquals(new Vec3(7, .5, .5), selection.worldPoint().orElseThrow());
		}
	}

	@Test void shellFailureDiagnosticsAreBounded() {
		var shell = new AeronauticsPropellerRaycastAdapter(() -> { throw new LinkageError("optional original failure"); });
		try (var capture = new CreateDiagnosticCapture()) {
			assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, shell.trace(null, request()).outcome());
			assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, shell.trace(null, request()).outcome());
			assertEquals(1, capture.events.size());
			assertNull(capture.events.getFirst().getThrown());
			assertTrue(capture.events.getFirst().getMessage().getFormattedMessage().contains("optional original failure"));
			assertTrue(capture.events.getFirst().getMessage().getFormattedMessage().contains("delegate-load-linkage"));
		}
	}

	@Test void normalAndDisabledDiagnosticsNeverReadTheOptionalOriginalFailure() {
		var original = new CountingFailure();
		var disabled = new AeronauticsPropellerRaycastAdapter(() -> { throw original; });
		try (var capture = new CreateDiagnosticCapture(org.apache.logging.log4j.Level.OFF, LoggingProfile.DEEP)) {
			assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, disabled.trace(null, request()).outcome());
			assertTrue(capture.events.isEmpty()); assertEquals(0, original.reads);
		}
		var normal = new AeronauticsPropellerRaycastAdapter(() -> { throw original; });
		try (var capture = new CreateDiagnosticCapture(org.apache.logging.log4j.Level.WARN, LoggingProfile.NORMAL)) {
			assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, normal.trace(null, request()).outcome());
			assertEquals(1, capture.events.size()); assertEquals(0, original.reads);
			String summary = capture.events.getFirst().getMessage().getFormattedMessage();
			assertTrue(summary.contains("delegate-load-linkage")); assertFalse(summary.contains("private optional failure"));
		}
	}

	private static Object snapshot(EntityLocalGeometryRegistry registry) throws Exception {
		var method = EntityLocalGeometryRegistry.class.getDeclaredMethod("snapshot"); method.setAccessible(true); return method.invoke(registry);
	}
	private static Object invoke(Object owner, String name) throws Exception {
		var method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner);
	}
	private static Method traceMethod() {
		for (Method method : Raycast.class.getDeclaredMethods()) {
			if (method.getName().equals("traceEntityCandidates") && method.getParameterCount() == 6) {
				method.setAccessible(true); return method;
			}
		}
		throw new AssertionError("common candidate seam unavailable");
	}
	private static Entity entity(double x) {
		// Candidate selection needs only the type and bounds, not NeoForge's
		// loader-initialized fluid registry reached by the Entity constructor.
		Entity entity = VaultOptionalHarness.allocate(FixtureEntity.class);
		entity.setBoundingBox(EntityType.ARMOR_STAND.getDimensions().makeBoundingBox(new Vec3(x, 0, .5)));
		return entity;
	}
	private static EntityLocalRaycastRequest request() {
		return new EntityLocalRaycastRequest(new Vec3(0, .5, .5), new Vec3(10, .5, .5),
			RaycastPolicy.from(false, false, false), 1, CollisionContext.empty(), Vec3.ZERO);
	}
	@NotNullByDefault
	private static final class FixtureEntity extends Entity {
		FixtureEntity() { super(EntityType.ARMOR_STAND, null); }
		@Override public EntityType<?> getType() { return EntityType.ARMOR_STAND; }
		@Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}
	}
	@NotNullByDefault
	private static final class CountingFailure extends LinkageError {
		int reads;
		@Override public @Nullable String getMessage() { reads++; return "private optional failure"; }
		@Override public synchronized @Nullable Throwable getCause() { reads++; return null; }
	}
}
