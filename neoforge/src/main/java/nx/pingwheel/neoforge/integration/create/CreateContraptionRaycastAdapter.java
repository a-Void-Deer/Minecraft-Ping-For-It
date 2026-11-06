package nx.pingwheel.neoforge.integration.create;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.util.SafeExceptionReport;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticFaultReporter;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import nx.pingwheel.common.math.EntityLocalGeometryRegistry;
import nx.pingwheel.common.math.EntityLocalGeometryResolver;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;

/**
 * Create-free ownership shell for exact Create contraption entity ids.
 *
 * <p>The shell is deliberately the only class NeoClient resolves at optional
 * integration registration time. Its delegate is loaded only by a claimed
 * candidate's first trace, so a missing or incompatible Create implementation
 * still owns the candidate and prevents legacy AABB selection from reviving
 * it.</p>
 */
@NotNullByDefault
public final class CreateContraptionRaycastAdapter implements EntityLocalGeometryResolver {

	public static final String SOURCE_ID = "pingforit:create_contraption_raycast";
	private static final String DELEGATE_CLASS_NAME =
		"nx.pingwheel.neoforge.integration.create.CreateContraptionRaycastDelegate";
	private static final int PRIORITY = 100;
	private static final @Unmodifiable List<ResourceLocation> CLAIMED_ENTITY_TYPE_IDS = List.of(
		ResourceLocation.fromNamespaceAndPath("create", "stationary_contraption"),
		ResourceLocation.fromNamespaceAndPath("create", "contraption"),
		ResourceLocation.fromNamespaceAndPath("create", "carriage_contraption"),
		ResourceLocation.fromNamespaceAndPath("create", "gantry_contraption"));

	private static @Nullable EntityLocalGeometryRegistry.Registration registration;
	private static @Nullable CreateContraptionRaycastAdapter source;

	private final DelegateLoader delegateLoader;
	private final Diagnostics diagnostics;
	private @Nullable Delegate delegate;
	private DelegateState delegateState = DelegateState.UNRESOLVED;

	private CreateContraptionRaycastAdapter() {
		this(new ReflectiveDelegateLoader());
	}

	CreateContraptionRaycastAdapter(DelegateLoader delegateLoader) {
		this(delegateLoader, System::nanoTime);
	}

	CreateContraptionRaycastAdapter(DelegateLoader delegateLoader, LongSupplier clock) {
		this.delegateLoader = Objects.requireNonNull(delegateLoader, "delegateLoader");
		this.diagnostics = new Diagnostics(clock);
	}

	static @Unmodifiable List<ResourceLocation> claimedEntityTypeIds() {
		return CLAIMED_ENTITY_TYPE_IDS;
	}

	/** Called reflectively from NeoClient after the Create mod-id check. */
	public static synchronized void register() {
		if (registration != null) {
			return;
		}

		CreateContraptionRaycastAdapter candidate = new CreateContraptionRaycastAdapter();
		EntityLocalGeometryRegistry.Registration handle = EntityLocalGeometryRegistry.INSTANCE.register(
			ResourceLocation.parse(SOURCE_ID), PRIORITY, CLAIMED_ENTITY_TYPE_IDS, candidate);
		source = candidate;
		registration = handle;
	}

	/** Called reflectively during client teardown; safe and idempotent. */
	public static synchronized void close() {
		try {
			if (registration != null) {
				registration.close();
			}
		} finally {
			if (source != null) {
				source.reset();
			}
			registration = null;
			source = null;
		}
	}

	/** Reflection-only lifecycle probe used by NeoClient diagnostics. */
	public static synchronized String registrationState() {
		if (registration == null) {
			return "not-registered";
		}
		return registration.isActive() ? "registered" : "rejected";
	}

	@Override
	public EntityLocalGeometryResult trace(@Nullable Entity candidate, EntityLocalRaycastRequest request) {
		Objects.requireNonNull(request, "request");
		@Nullable Delegate resolvedDelegate = delegate(candidate, request);

		if (resolvedDelegate == null) {
			return EntityLocalGeometryResult.unavailable();
		}

		try {
			@Nullable EntityLocalGeometryResult result = resolvedDelegate.trace(candidate, request);
			if (result == null) {
				diagnostics.report(candidate, request, "null-result", null);
				return EntityLocalGeometryResult.failed();
			}
			return result;
		} catch (LinkageError | AssertionError structuralFailure) {
			markUnavailable();
			diagnostics.report(candidate, request, structuralFailure instanceof LinkageError
				? "delegate-trace-linkage" : "delegate-trace-assertion", structuralFailure);
			return EntityLocalGeometryResult.unavailable();
		}
	}

	private @Nullable Delegate delegate(@Nullable Entity candidate, EntityLocalRaycastRequest request) {
		Throwable unavailable;
		synchronized (this) {
			if (delegateState == DelegateState.READY) return delegate;
			if (delegateState == DelegateState.UNAVAILABLE) return null;
			try {
				delegate = Objects.requireNonNull(delegateLoader.load(), "Create contraption raycast delegate");
				delegateState = DelegateState.READY;
				return delegate;
			} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
				delegateState = DelegateState.UNAVAILABLE;
				unavailable = failure;
			}
		}
		diagnostics.report(candidate, request, unavailable instanceof ReflectiveOperationException
			? "delegate-load-reflection"
			: unavailable instanceof LinkageError ? "delegate-load-linkage" : "delegate-load-assertion", unavailable);
		return null;
	}

	private synchronized void markUnavailable() {
		delegate = null;
		delegateState = DelegateState.UNAVAILABLE;
	}

	synchronized void reset() {
		delegate = null;
		delegateState = DelegateState.UNRESOLVED;
		diagnostics.clear();
	}

	interface DelegateLoader {
		Delegate load() throws ReflectiveOperationException;
	}

	interface Delegate {
		EntityLocalGeometryResult trace(@Nullable Entity candidate, EntityLocalRaycastRequest request);
	}

	private enum DelegateState {
		UNRESOLVED,
		READY,
		UNAVAILABLE
	}

	/**
	 * Small LRU/rate gate for failures swallowed by this optional shell. Runtime
	 * callback failures deliberately bypass it and reach the common registry's
	 * existing callback diagnostic instead.
	 */
	@NotNullByDefault
	static final class Diagnostics {
		private static final int MAX_KEYS = 256;
		private static final long MIN_LOG_INTERVAL_NANOS = 5_000_000_000L;

		private final LongSupplier clock;
		private final LinkedHashMap<String, Long> lastLogged = new LinkedHashMap<>(16, 0.75F, true);

		Diagnostics(LongSupplier clock) {
			this.clock = Objects.requireNonNull(clock, "clock");
		}

		void report(
			@Nullable Entity entity,
			EntityLocalRaycastRequest request,
			String category,
			@Nullable Throwable failure
		) {
			var selected = nx.pingwheel.common.diagnostics.Diagnostics.client().atNormal().rateLimited(
				DiagnosticChannel.domain("create-contraption-shell", () -> admit(category + ':' + entityKey(entity))));
			if (failure == null) {
				selected.event(Level.WARN, "create-contraption-shell", category, () -> fullDetails(entity, request, category));
			} else {
				selected.failure(Level.WARN, "create-contraption-shell", "owned-candidate-unavailable",
					() -> fullDetails(entity, request, category), failure);
			}
		}

		private boolean admit(String key) {
			long now;
			try {
				now = clock.getAsLong();
			} catch (RuntimeException | LinkageError failure) {
				fault(DiagnosticFaultReporter.Stage.CHANNEL_CLOCK, failure);
				return false;
			}
			synchronized (lastLogged) {
				@Nullable Long previous = lastLogged.get(key);
				if (previous != null && now - previous < MIN_LOG_INTERVAL_NANOS) {
					return false;
				}
				if (previous == null && lastLogged.size() >= MAX_KEYS) {
					Iterator<String> iterator = lastLogged.keySet().iterator();
					if (iterator.hasNext()) {
						iterator.next();
						iterator.remove();
					}
				}
				lastLogged.put(key, now);
			}
			return true;
		}

		private void clear() {
			synchronized (lastLogged) {
				lastLogged.clear();
			}
		}

		private static String entityKey(@Nullable Entity entity) {
			if (entity == null) {
				return "<null>";
			}
			return entity.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(entity));
		}

		private static String fullDetails(
			@Nullable Entity entity,
			EntityLocalRaycastRequest request,
			String category
		) {
			String nullEntity = "<null>";
			String entityRegistry = entity == null ? nullEntity : safe(
				() -> String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())), "<unavailable>");
			String entityClass = entity == null ? nullEntity : entity.getClass().getName();
			String entityUuid = entity == null ? nullEntity
				: safe(() -> String.valueOf(entity.getUUID()), "<unavailable>");
			String entityPosition = entity == null ? nullEntity
				: safe(() -> String.valueOf(entity.position()), "<unavailable>");

			return "Create contraption raycast diagnostic: sourceId=" + SOURCE_ID
				+ "; delegateClass=" + DELEGATE_CLASS_NAME
				+ "; category=" + category
				+ "; entityRegistry=" + entityRegistry
				+ "; entityClass=" + entityClass
				+ "; entityUuid=" + entityUuid
				+ "; entityPosition=" + entityPosition
				+ "; requestStart=" + request.start()
				+ "; requestEnd=" + request.end()
				+ "; cameraFeetPosition=" + request.cameraFeetPosition()
				+ "; policy=" + request.policy()
				+ "; partialTick=" + request.partialTick();
		}

		private static String safe(Supplier<String> supplier, String fallback) {
			try {
				return supplier.get();
			} catch (RuntimeException | LinkageError | AssertionError descriptorFailure) {
				fault(DiagnosticFaultReporter.Stage.CONTEXT, descriptorFailure);
				return fallback + "(" + SafeExceptionReport.format(descriptorFailure,
					(accessor, failure) -> fault(DiagnosticFaultReporter.Stage.ACCESSOR, failure)) + ')';
			}
		}

		private static void fault(DiagnosticFaultReporter.Stage stage, Throwable failure) {
			nx.pingwheel.common.diagnostics.Diagnostics.faults().report(DiagnosticSide.CLIENT, "create-contraption-shell",
				stage, nx.pingwheel.common.diagnostics.Diagnostics.profile(DiagnosticSide.CLIENT), failure);
		}
	}

	private static final class ReflectiveDelegateLoader implements DelegateLoader {

		@Override
		public Delegate load() throws ReflectiveOperationException {
			Class<?> delegateClass = Class.forName(
				DELEGATE_CLASS_NAME, true, CreateContraptionRaycastAdapter.class.getClassLoader());
			if (!EntityLocalGeometryResolver.class.isAssignableFrom(delegateClass)) {
				throw new ReflectiveOperationException(
					"Create contraption raycast delegate does not implement EntityLocalGeometryResolver");
			}
			Object instance = delegateClass.getConstructor().newInstance();
			if (!(instance instanceof EntityLocalGeometryResolver resolver)) {
				throw new ReflectiveOperationException(
					"Create contraption raycast delegate instance is not an EntityLocalGeometryResolver");
			}
			return resolver::trace;
		}
	}
}
