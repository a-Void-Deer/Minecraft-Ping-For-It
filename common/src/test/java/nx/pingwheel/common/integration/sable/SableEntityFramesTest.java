package nx.pingwheel.common.integration.sable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.math.*;
import nx.pingwheel.common.integration.ModContext;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import org.jetbrains.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class SableEntityFramesTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void absentAndUnrelatedEntitiesNeverCallOptionalApi() {
		AtomicInteger calls = new AtomicInteger();
		SableEntityFrames.Access provider = entity -> { calls.incrementAndGet(); throw new AssertionError(); };
		Entity entity = new TestEntity();
		assertEquals(EntityFrameResolution.Status.WORLD, SableEntityFrames.resolve(entity, false, true, provider).status());
		assertEquals(EntityFrameResolution.Status.WORLD, SableEntityFrames.resolve(entity, true, false, provider).status());
		assertEquals(0, calls.get());
		boolean previous = ModContext.HasSable;
		try {
			ModContext.HasSable = true;
			assertEquals(EntityFrameResolution.Status.WORLD, SableEntityFrames.logical(entity).status());
		} finally { ModContext.HasSable = previous; }
	}
	@Test void positiveContainingFramePreservesIdentityWhileFailuresNeverBecomeWorld() {
		Entity entity = new TestEntity(); UUID id = UUID.randomUUID();
		EntityParentFrame frame = EntityParentFrame.copyOf(new Matrix4d().translation(-30_000_000, 2, 3), new Quaterniond());
		EntityFrameResolution resolution = SableEntityFrames.resolve(entity, true, true,
			value -> EntityFrameResolution.contained(frame, id));
		assertEquals(id, resolution.subLevelId().orElseThrow());
		assertEquals(new Vec3(0, 2, 3), resolution.frame().orElseThrow().toWorld(new Vec3(30_000_000, 0, 0)));
		for (Throwable failure : java.util.List.of(new IllegalStateException(), new NoClassDefFoundError())) {
			EntityFrameResolution rejected = SableEntityFrames.resolve(entity, true, true,
				value -> { if (failure instanceof RuntimeException runtime) throw runtime; throw (LinkageError) failure; });
			assertEquals(EntityFrameResolution.Status.UNAVAILABLE, rejected.status());
			assertTrue(rejected.frame().isEmpty()); assertTrue(rejected.subLevelId().isEmpty());
		}
	}
	@Test void facadeCanLoadWithBothMinecraftClientAndCompanionHidden() throws Exception {
		ClassLoader parent = getClass().getClassLoader();
		ClassLoader isolated = new ClassLoader(parent) {
			@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("net.minecraft.client.") || name.startsWith("dev.ryanhcode.sable.")) throw new ClassNotFoundException(name);
				if (name.equals(SableEntityFrames.class.getName()) || name.equals(SableEntityFrames.class.getName() + "$Access")) {
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
		Class<?> facade = Class.forName(SableEntityFrames.class.getName(), true, isolated);
		assertEquals(EntityFrameResolution.Status.WORLD,
			((EntityFrameResolution) facade.getMethod("logical", Entity.class).invoke(null, new TestEntity())).status());
	}

	@Test void actualLogicalAdapterUsesCurrentNativeContainmentAndCopiesPose() {
		Vec3 nativePivot = new Vec3(10_000_000, 1, 30_000_000);
		Vec3 worldPivot = new Vec3(1152.1, 64, .5);
		Entity entity = new TestEntity(); entity.setPos(nativePivot.add(.25, .5, .5));
		UUID id = UUID.randomUUID(); Pose3d pose = new Pose3d();
		pose.rotationPoint().set(nativePivot.x, nativePivot.y, nativePivot.z);
		pose.position().set(worldPivot.x, worldPivot.y, worldPivot.z);
		pose.orientation().set(1, 1, 1, 1); // Companion transforms with the rotation of the quaternion, not its magnitude.
		pose.scale().set(2, -3, .5);
		SubLevelAccess owner = new SubLevelAccess() {
			@Override public Pose3dc logicalPose() { return pose; }
			@Override public Pose3dc lastPose() { throw new AssertionError("logical frame cannot use old pose"); }
			@Override public BoundingBox3dc boundingBox() { throw new AssertionError("rotor cannot depend on host block bounds"); }
			@Override public UUID getUniqueId() { return id; }
			@Override public @Nullable String getName() { return null; }
		};
		var resolution = SableEntityFrameAccess.logical(entity, candidate -> {
			assertSame(entity, candidate); assertEquals(nativePivot.add(.25, .5, .5), candidate.position()); return owner;
		});
		EntityParentFrame frame = resolution.frame().orElseThrow();
		pose.position().zero(); pose.rotationPoint().zero(); pose.orientation().identity(); pose.scale().set(1);
		assertEquals(worldPivot, frame.toWorld(nativePivot));
		assertEquals(nativePivot, frame.toNative(worldPivot));
		// Scale the native offset to (.5, -1.5, .25), then permute it to (.25, .5, -1.5).
		Vec3 worldPoint = new Vec3(1152.35, 64.5, -1);
		assertEquals(worldPoint, frame.toWorld(entity.position()));
		assertEquals(entity.position(), frame.toNative(worldPoint));
		assertEquals(EntityFrameResolution.Status.WORLD,
			SableEntityFrameAccess.logical(entity, candidate -> null).status());
	}
	@NotNullByDefault
	private static final class TestEntity extends Entity {
		TestEntity() { super(EntityType.COW, null); }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
