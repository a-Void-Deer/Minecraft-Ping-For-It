package nx.pingwheel.common.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Common entity-selection blacklist seam for optional integrations.
 *
 * <p>Predicates are evaluated in registration order and aggregate with OR
 * semantics.  A registration handle owns exactly one predicate and can be
 * closed repeatedly during integration teardown without affecting any other
 * registration.</p>
 */
public final class EntitySelectionBlacklist {

	private static final ResourceLocation SIMULATED_HONEY_GLUE_ID =
		ResourceLocation.fromNamespaceAndPath("simulated", "honey_glue");
	private static final Predicate<Entity> DEFAULT_SIMULATED_HONEY_GLUE_RULE =
		EntitySelectionBlacklist::isSimulatedHoneyGlue;

	/** The global blacklist used by the client raycast. */
	public static final EntitySelectionBlacklist INSTANCE = new EntitySelectionBlacklist();

	private final Object lock = new Object();
	private final List<Registration> registrations = new ArrayList<>();
	private volatile List<Predicate<Entity>> snapshot = List.of(DEFAULT_SIMULATED_HONEY_GLUE_RULE);

	/**
	 * Registers one entity predicate.  A matching predicate excludes the entity
	 * from the default nearest-entity raycast competition.
	 */
	public Registration register(Predicate<Entity> predicate) {
		Objects.requireNonNull(predicate, "predicate");

		synchronized (lock) {
			Registration registration = new Registration(this, predicate);
			registrations.add(registration);
			publishSnapshot();
			return registration;
		}
	}

	/**
	 * Whether any currently registered predicate excludes {@code entity}.
	 * Predicate order is deterministic, and evaluation stops at the first
	 * positive result.
	 */
	public boolean isBlacklisted(Entity entity) {
		if (entity == null) {
			return false;
		}

		for (Predicate<Entity> predicate : snapshot) {
			if (predicate.test(entity)) {
				return true;
			}
		}

		return false;
	}

	private void close(Registration registration) {
		synchronized (lock) {
			if (registration.closed) {
				return;
			}

			registration.closed = true;
			if (registrations.remove(registration)) {
				publishSnapshot();
			}
		}
	}

	private void publishSnapshot() {
		var predicates = new ArrayList<Predicate<Entity>>(registrations.size() + 1);
		predicates.add(DEFAULT_SIMULATED_HONEY_GLUE_RULE);
		predicates.addAll(registrations.stream().map(Registration::predicate).toList());
		snapshot = List.copyOf(predicates);
	}

	static boolean isDefaultIgnoredEntityId(ResourceLocation entityTypeId) {
		return SIMULATED_HONEY_GLUE_ID.equals(entityTypeId);
	}

	private static boolean isSimulatedHoneyGlue(Entity entity) {
		return isDefaultIgnoredEntityId(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	/** Lifecycle handle for one exact predicate registration. */
	public static final class Registration implements AutoCloseable {
		private final EntitySelectionBlacklist blacklist;
		private final Predicate<Entity> predicate;
		private boolean closed;

		private Registration(EntitySelectionBlacklist blacklist, Predicate<Entity> predicate) {
			this.blacklist = blacklist;
			this.predicate = predicate;
		}

		private Predicate<Entity> predicate() {
			return predicate;
		}

		/** Whether this handle is still an active registration. */
		public boolean isActive() {
			return !closed;
		}

		@Override
		public void close() {
			blacklist.close(this);
		}
	}
}
