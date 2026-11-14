package nx.pingwheel.neoforge.integration.create;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.Global;
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
public final class CreateContraptionRaycastAdapter implements EntityLocalGeometryResolver {

	public static final String SOURCE_ID = "pingforit:create_contraption_raycast";
	private static final String DELEGATE_CLASS_NAME =
		"nx.pingwheel.neoforge.integration.create.CreateContraptionRaycastDelegate";
	private static final int PRIORITY = 100;
	private static final List<ResourceLocation> CLAIMED_ENTITY_TYPE_IDS = List.of(
		ResourceLocation.fromNamespaceAndPath("create", "stationary_contraption"),
		ResourceLocation.fromNamespaceAndPath("create", "contraption"),
		ResourceLocation.fromNamespaceAndPath("create", "carriage_contraption"),
		ResourceLocation.fromNamespaceAndPath("create", "gantry_contraption"));

	private static EntityLocalGeometryRegistry.Registration registration;
	private static CreateContraptionRaycastAdapter source;

	private final DelegateLoader delegateLoader;
	private final Diagnostics diagnostics;
	private Delegate delegate;
	private DelegateState delegateState = DelegateState.UNRESOLVED;

	private CreateContraptionRaycastAdapter() {
		this(new ReflectiveDelegateLoader(), (message, failure) -> Global.LOGGER.warn(message, failure));
	}

	CreateContraptionRaycastAdapter(DelegateLoader delegateLoader) {
		this(delegateLoader, (message, failure) -> Global.LOGGER.warn(message, failure));
	}

	CreateContraptionRaycastAdapter(DelegateLoader delegateLoader, DiagnosticSink diagnosticSink) {
		this.delegateLoader = Objects.requireNonNull(delegateLoader, "delegateLoader");
		this.diagnostics = new Diagnostics(Objects.requireNonNull(diagnosticSink, "diagnosticSink"));
	}

	static List<ResourceLocation> claimedEntityTypeIds() {
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
	public EntityLocalGeometryResult trace(Entity candidate, EntityLocalRaycastRequest request) {
		Objects.requireNonNull(request, "request");
		Delegate resolvedDelegate = delegate(candidate, request);

		if (resolvedDelegate == null) {
			return EntityLocalGeometryResult.unavailable();
		}

		try {
			EntityLocalGeometryResult result = resolvedDelegate.trace(candidate, request);
			return result == null ? EntityLocalGeometryResult.failed() : result;
		} catch (LinkageError | AssertionError structuralFailure) {
			markUnavailable();
			diagnostics.report(candidate, request, structuralFailure instanceof LinkageError
				? "delegate-trace-linkage" : "delegate-trace-assertion", structuralFailure);
			return EntityLocalGeometryResult.unavailable();
		}
	}

	private synchronized Delegate delegate(Entity candidate, EntityLocalRaycastRequest request) {
		if (delegateState == DelegateState.READY) {
			return delegate;
		}
		if (delegateState == DelegateState.UNAVAILABLE) {
			return null;
		}

		try {
			delegate = Objects.requireNonNull(delegateLoader.load(), "Create contraption raycast delegate");
			delegateState = DelegateState.READY;
			return delegate;
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			delegateState = DelegateState.UNAVAILABLE;
			diagnostics.report(candidate, request, failure instanceof ReflectiveOperationException
				? "delegate-load-reflection"
				: failure instanceof LinkageError ? "delegate-load-linkage" : "delegate-load-assertion", failure);
			return null;
		}
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

	@FunctionalInterface
	interface DiagnosticSink {
		void warn(String message, Throwable failure);
	}

	interface DelegateLoader {
		Delegate load() throws ReflectiveOperationException;
	}

	interface Delegate {
		EntityLocalGeometryResult trace(Entity candidate, EntityLocalRaycastRequest request);
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
	private static final class Diagnostics {
		private static final int MAX_KEYS = 256;
		private static final long MIN_LOG_INTERVAL_NANOS = 5_000_000_000L;

		private final DiagnosticSink sink;
		private final LinkedHashMap<String, Long> lastLogged = new LinkedHashMap<>(16, 0.75F, true);

		private Diagnostics(DiagnosticSink sink) {
			this.sink = sink;
		}

		private void report(
			Entity entity,
			EntityLocalRaycastRequest request,
			String category,
			Throwable failure
		) {
			long now = System.nanoTime();
			String key = category + ':' + entityKey(entity);
			synchronized (lastLogged) {
				Long previous = lastLogged.get(key);
				if (previous != null && now - previous < MIN_LOG_INTERVAL_NANOS) {
					return;
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

			sink.warn(fullDetails(entity, request, category), failure);
		}

		private void clear() {
			synchronized (lastLogged) {
				lastLogged.clear();
			}
		}

		private static String entityKey(Entity entity) {
			if (entity == null) {
				return "<null>";
			}
			return safe(() -> entity.getUUID().toString(),
				entity.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(entity)));
		}

		private static String fullDetails(
			Entity entity,
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
				return fallback + "(" + descriptorFailure + ')';
			}
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
