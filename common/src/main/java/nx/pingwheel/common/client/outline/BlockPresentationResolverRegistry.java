package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.world.level.BlockGetter;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Deterministic registry and resolver for client block presentations.
 *
 * <p>Registrations are published as an immutable volatile snapshot. The first
 * registration for an id wins, and the first resolver that returns a handled
 * result wins. Resolver failures from optional code are ignored so the normal
 * direct presentation remains available.</p>
 */
public final class BlockPresentationResolverRegistry {
	/** The common registry with the vanilla multipart resolvers installed. */
	public static final BlockPresentationResolverRegistry INSTANCE =
		new BlockPresentationResolverRegistry(true);

	private final Object lock = new Object();
	private final Map<String, Registration> registrations = new LinkedHashMap<>();
	private volatile @Unmodifiable List<BlockPresentationResolver> snapshot = List.of();

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
			register(new VanillaChestBlockPresentationResolver());
		}
	}

	/**
	 * Registers a resolver after validating its stable namespaced id. Invalid
	 * and duplicate registrations return a harmless rejected handle.
	 */
	public Registration register(@Nullable BlockPresentationResolver resolver) {
		@Nullable ValidatedResolver validatedResolver = validate(resolver);
		if (validatedResolver == null) {
			return Registration.rejected();
		}

		synchronized (lock) {
			if (registrations.containsKey(validatedResolver.id())) {
				OutlineDiagnostics.rejected("block-presentation-register", validatedResolver.id(), "duplicate-id", null);
				return Registration.rejected();
			}

			Registration registration = new Registration(
				this, validatedResolver.id(), validatedResolver.resolver());
			registrations.put(validatedResolver.id(), registration);
			publishSnapshot();
			return registration;
		}
	}

	/**
	 * Registers a specialization immediately before a known resolver while
	 * preserving every other resolver's registration order.
	 */
	public Registration registerBefore(@Nullable String anchorResolverId, @Nullable BlockPresentationResolver resolver) {
		@Nullable ValidatedResolver validatedResolver = validate(resolver);
		@Nullable String anchorId = validateId(anchorResolverId);
		if (validatedResolver == null || anchorId == null) {
			return Registration.rejected();
		}

		synchronized (lock) {
			if (!registrations.containsKey(anchorId)
				|| registrations.containsKey(validatedResolver.id())) {
				OutlineDiagnostics.rejected("block-presentation-register", validatedResolver.id(), "missing-anchor-or-duplicate", null);
				return Registration.rejected();
			}

			Registration registration = new Registration(
				this, validatedResolver.id(), validatedResolver.resolver());
			Map<String, Registration> reordered = new LinkedHashMap<>();
			for (Map.Entry<String, Registration> entry : registrations.entrySet()) {
				if (entry.getKey().equals(anchorId)) {
					reordered.put(validatedResolver.id(), registration);
				}
				reordered.put(entry.getKey(), entry.getValue());
			}
			registrations.clear();
			registrations.putAll(reordered);
			publishSnapshot();
			return registration;
		}
	}

	/** Returns the immutable registration-order resolver snapshot. */
	public @Unmodifiable List<BlockPresentationResolver> snapshot() {
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
			@Nullable BlockPresentationResolution result;
			try {
				result = resolver.resolve(context);
			} catch (Exception | LinkageError | AssertionError failure) {
				Diagnostics.warn("block-presentation-resolve", resolver.getClass().getName(),
					() -> "source=" + context.sourceSpec() + "; resolverClass=" + resolver.getClass().getName(), failure);
				continue;
			}
			if (result == null) {
				Diagnostics.event(Level.WARN, "block-presentation-resolve",
					resolver.getClass().getName(), "null-resolution", () -> "source=" + context.sourceSpec());
			}

			if (result != null && result.handled()) {
				return new BlockPresentation(
					context.sourceSpec(), result.subjects(), result.coverageRelations());
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

	private static @Nullable ValidatedResolver validate(@Nullable BlockPresentationResolver resolver) {
		if (resolver == null) {
			OutlineDiagnostics.rejected("block-presentation-register", "resolver", "null-resolver", null);
			return null;
		}

		try {
			@Nullable String id = validateId(resolver.id());
			return id == null ? null : new ValidatedResolver(id, resolver);
		} catch (Exception | LinkageError | AssertionError failure) {
			OutlineDiagnostics.rejected("block-presentation-register", resolver.getClass().getName(), "resolver-id-failed", failure);
			return null;
		}
	}

	private static @Nullable String validateId(@Nullable String id) {
		try {
			@Nullable String validated = EntityBlockGeometrySourceIds.validate(id);
			if (validated == null) {
				OutlineDiagnostics.rejected("block-presentation-register", String.valueOf(id), "invalid-id", null);
			}
			return validated;
		} catch (Exception | LinkageError | AssertionError failure) {
			OutlineDiagnostics.rejected("block-presentation-register", String.valueOf(id), "invalid-id", failure);
			return null;
		}
	}

	private record ValidatedResolver(String id, BlockPresentationResolver resolver) {}

	/** Lifecycle handle for one exact resolver registration. */
	public static final class Registration implements AutoCloseable {
		private final @Nullable BlockPresentationResolverRegistry registry;
		private final @Nullable String id;
		private final @Nullable BlockPresentationResolver resolver;
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
			return Objects.requireNonNull(resolver);
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
