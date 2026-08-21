package nx.pingwheel.common.client.outline;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Headless {@link Entity} factory for tests that need a live-ish entity to
 * exercise {@code handles(Entity)}, the runner, and the locator resolver.
 *
 * <p>Construction runs the plain vanilla registry bootstrap once (exactly like
 * {@code OutlineOnlyBufferSourceTest}) because {@code EntityType}'s class
 * initializer reaches the vanilla registries. A custom {@code EntityType}
 * cannot be built after the registry freeze (the {@code EntityType} constructor
 * creates an intrusive holder), so the produced entity reuses the already
 * registered {@link EntityType#PIG}; the entity is created with a
 * {@code null} level, which the vanilla {@code Entity} constructor tolerates.
 */
final class TestEntitySupport {

	private TestEntitySupport() {}

	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	static TestEntity newEntity() {
		return new TestEntity(EntityType.PIG, null);
	}

	/** Minimal {@link Entity} subclass implementing only the abstract methods. */
	static final class TestEntity extends Entity {
		TestEntity(EntityType<?> type, Level level) {
			super(type, level);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {
			// intentionally empty
		}

		@Override
		protected void readAdditionalSaveData(CompoundTag tag) {
			// intentionally empty
		}

		@Override
		protected void addAdditionalSaveData(CompoundTag tag) {
			// intentionally empty
		}
	}
}
