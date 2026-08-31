package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.world.level.BlockGetter;

/**
 * Deterministic registry and resolver for client block presentations.
 *
 * <p>Registrations are published as an immutable volatile snapshot. The first
 * registration for an id wins, and the first resolver that returns a handled
 * result wins. Resolver failures from optional code are ignored so the normal
 * direct presentation remains available.</p>
 */
public final class BlockPresentationResolverRegistry {
	/** The common registry with the vanilla door and bed resolvers installed. */
	public static final BlockPresentationResolverRegistry INSTANCE =
		new BlockPresentationResolverRegistry(true);

	private final Object lock = new Object();
	private final Map<String, Registration> registrations = new LinkedHashMap<>();
	private volatile List<BlockPresentationResolver> snapshot = List.of();

	/** Creates a registry with the deterministic vanilla resolvers installed. */
	public BlockPresentationResolverRegistry() {
		this(true);
	}

	/**
	 * Creates an isolated registry. The no-built-ins option is intended for
	 * resolver-order tests and optional resolver composition.
	 */
	public BlockPresentationResolverRegistry(boolean installVanillaResolvers) {
		if (installVanillaResolvers) {
			register(new VanillaDoorBlockPresentationResolver());
			register(new VanillaBedBlockPresentationResolver());
		}
	}

	/**
	 * Registers a resolver after validating its stable namespaced id. Invalid
	 * and duplicate registrations return a harmless rejected handle.
	 */
	public Registration register(BlockPresentationResolver resolver) {
		if (resolver == null) {
			return Registration.rejected();
		}

		final String id;
		try {
			id = EntityBlockGeometrySourceIds.validate(resolver.id());
		} catch (Exception | LinkageError | AssertionError failure) {
			return Registration.rejected();
		}

		if (id == null) {
			return Registration.rejected();
		}

		synchronized (lock) {
			if (registrations.containsKey(id)) {
				return Registration.rejected();
			}

			Registration registration = new Registration(this, id, resolver);
			registrations.put(id, registration);
			publishSnapshot();
			return registration;
		}
	}

	/** Returns the immutable registration-order resolver snapshot. */
	public List<BlockPresentationResolver> snapshot() {
		return snapshot;
	}

	/** Resolves one source spec against the current world state. */
	public BlockPresentation resolve(BlockGetter world, BlockOutlineSpec sourceSpec) {
		return resolve(new BlockPresentationContext(world, sourceSpec));
	}

	/** Convenience overload for source-first call sites. */
	public BlockPresentation resolve(BlockOutlineSpec sourceSpec, BlockGetter world) {
		return resolve(world, sourceSpec);
	}

	/** Resolves one already-constructed context against this registry. */
	public BlockPresentation resolve(BlockPresentationContext context) {
		Objects.requireNonNull(context, "context");

		if (!context.sourceBlockMatches()) {
			return new BlockPresentation(context.sourceSpec(), List.of());
		}

		for (BlockPresentationResolver resolver : snapshot) {
			BlockPresentationResolution result;
			try {
				result = resolver.resolve(context);
			} catch (Exception | LinkageError | AssertionError failure) {
				continue;
			}

			if (result != null && result.handled()) {
				return new BlockPresentation(context.sourceSpec(), result.subjects());
			}
		}

		return new BlockPresentation(context.sourceSpec(),
			context.directResolution().subjects());
	}

	/** Resolves only the validated direct source presentation. */
	public BlockPresentation resolveDirect(BlockGetter world, BlockOutlineSpec sourceSpec) {
		return resolveDirect(new BlockPresentationContext(world, sourceSpec));
	}

	/** Convenience overload for source-first call sites. */
	public BlockPresentation resolveDirect(BlockOutlineSpec sourceSpec, BlockGetter world) {
		return resolveDirect(world, sourceSpec);
	}

	/** Resolves only the validated direct source presentation. */
	public BlockPresentation resolveDirect(BlockPresentationContext context) {
		Objects.requireNonNull(context, "context");
		return new BlockPresentation(context.sourceSpec(), context.directResolution().subjects());
	}

	private void close(Registration registration) {
		synchronized (lock) {
			if (registration.closed) {
				return;
			}

			registration.closed = true;
			if (registrations.get(registration.id) == registration) {
				registrations.remove(registration.id);
				publishSnapshot();
			}
		}
	}

	private void publishSnapshot() {
		snapshot = List.copyOf(new ArrayList<>(
			registrations.values().stream().map(Registration::resolver).toList()));
	}

	/** Lifecycle handle for one exact resolver registration. */
	public static final class Registration implements AutoCloseable {
		private final BlockPresentationResolverRegistry registry;
		private final String id;
		private final BlockPresentationResolver resolver;
		private boolean closed;

		private Registration(
			BlockPresentationResolverRegistry registry,
			String id,
			BlockPresentationResolver resolver
		) {
			this.registry = registry;
			this.id = id;
			this.resolver = resolver;
		}

		private Registration() {
			this.registry = null;
			this.id = null;
			this.resolver = null;
			this.closed = true;
		}

		private static Registration rejected() {
			return new Registration();
		}

		private BlockPresentationResolver resolver() {
			return resolver;
		}

		/** Whether this handle owns an accepted registration. */
		public boolean accepted() {
			return registry != null;
		}

		@Override
		public void close() {
			if (registry != null) {
				registry.close(this);
			}
		}
	}
}
