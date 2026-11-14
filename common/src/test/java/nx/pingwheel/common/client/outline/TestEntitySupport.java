package nx.pingwheel.common.client.outline;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import nx.pingwheel.common.testing.TestLevel;

/**
 * Headless {@link Entity} factory shared by client and math tests.
 *
 * <p>The vanilla {@code Entity} constructor calls
 * {@code level.getNextEntityId()}, so it needs a non-null Level. {@link TestLevel}
 * provides the minimal headless Level; this helper keeps construction and the
 * 26.3 save/hurt signatures in one place.</p>
 */
public final class TestEntitySupport {

	private TestEntitySupport() {
	}

	public static void bootStrap() {
		TestLevel.ensureBootstrapped();
	}

	public static TestEntity newEntity() {
		return newEntity(EntityTypes.PIG);
	}

	public static TestEntity newEntity(EntityType<?> type) {
		return new TestEntity(type, TestLevel.get());
	}

	/** Minimal {@link Entity} subclass implementing only the abstract methods. */
	public static final class TestEntity extends Entity {

		public TestEntity(EntityType<?> type, Level level) {
			super(type, level);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {
			// intentionally empty
		}

		@Override
		public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
			return false;
		}

		@Override
		protected void readAdditionalSaveData(ValueInput input) {
			// intentionally empty
		}

		@Override
		protected void addAdditionalSaveData(ValueOutput output) {
			// intentionally empty
		}
	}
}
