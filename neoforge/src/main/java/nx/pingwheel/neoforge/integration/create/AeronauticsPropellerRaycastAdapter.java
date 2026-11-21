package nx.pingwheel.neoforge.integration.create;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.integration.aeronautics.AeroRotorScope;
import nx.pingwheel.common.math.EntityLocalGeometryRegistry;
import nx.pingwheel.common.math.EntityLocalGeometryResolver;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Optional-type-free exact owner; failures never release the claim to coarse picking. */
@NotNullByDefault
public final class AeronauticsPropellerRaycastAdapter implements EntityLocalGeometryResolver {

	public static final String SOURCE_ID = "pingforit:aeronautics_propeller_raycast";
	private static final String DELEGATE_CLASS_NAME =
		"nx.pingwheel.neoforge.integration.create.AeronauticsPropellerRaycastDelegate";
	private static final int PRIORITY = 100;
	private static final @Unmodifiable List<ResourceLocation> CLAIMED_ENTITY_TYPE_IDS =
		List.of(AeroRotorScope.ENTITY_TYPE_ID);
	private static @Nullable EntityLocalGeometryRegistry.Registration registration;
	private static @Nullable AeronauticsPropellerRaycastAdapter source;

	private final DelegateLoader loader;
	private final DiagnosticChannel reports = DiagnosticChannel.interval("aeronautics-propeller-shell", Duration.ofSeconds(5));
	private @Nullable EntityLocalGeometryResolver delegate;
	private boolean resolved;

	private AeronauticsPropellerRaycastAdapter() {
		this(() -> {
			Class<?> type = Class.forName(DELEGATE_CLASS_NAME, true, AeronauticsPropellerRaycastAdapter.class.getClassLoader());
			Object instance = type.getConstructor().newInstance();
			if (!(instance instanceof EntityLocalGeometryResolver resolver)) {
				throw new ReflectiveOperationException("Aero raycast delegate does not implement EntityLocalGeometryResolver");
			}
			return resolver;
		});
	}

	AeronauticsPropellerRaycastAdapter(DelegateLoader loader) { this.loader = Objects.requireNonNull(loader); }

	static @Unmodifiable List<ResourceLocation> claimedEntityTypeIds() { return CLAIMED_ENTITY_TYPE_IDS; }

	/** Invoked reflectively by the optional client lifecycle gate. */
	public static synchronized void register() {
		if (registration != null) return;
		AeronauticsPropellerRaycastAdapter candidate = new AeronauticsPropellerRaycastAdapter();
		registration = register(EntityLocalGeometryRegistry.INSTANCE, candidate);
		source = candidate;
	}

	static EntityLocalGeometryRegistry.Registration register(
		EntityLocalGeometryRegistry registry, AeronauticsPropellerRaycastAdapter candidate
	) {
		return registry.register(ResourceLocation.parse(SOURCE_ID), PRIORITY, CLAIMED_ENTITY_TYPE_IDS,
			EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT, candidate);
	}

	public static synchronized void close() {
		try {
			if (registration != null) registration.close();
		} finally {
			if (source != null) source.reset();
			registration = null;
			source = null;
		}
	}

	public static synchronized String registrationState() {
		return registration == null ? "not-registered" : registration.isActive() ? "registered" : "rejected";
	}

	@Override
	public EntityLocalGeometryResult trace(@Nullable Entity candidate, EntityLocalRaycastRequest request) {
		@Nullable EntityLocalGeometryResolver current = delegate(request);
		if (current == null) return EntityLocalGeometryResult.unavailable();
		try {
			@Nullable EntityLocalGeometryResult result = current.trace(candidate, request);
			if (result == null) {
				Diagnostics.client().atNormal().rateLimited(reports).event(Level.WARN,
					"aeronautics-propeller-shell", "null-result-candidate-rejected", () -> "sourceId=" + SOURCE_ID);
				return EntityLocalGeometryResult.failed();
			}
			return result;
		} catch (LinkageError | AssertionError failure) {
			synchronized (this) { delegate = null; resolved = true; }
			report(failure instanceof LinkageError ? "delegate-trace-linkage" : "delegate-trace-assertion", request, failure);
			return EntityLocalGeometryResult.unavailable();
		}
	}

	private @Nullable EntityLocalGeometryResolver delegate(EntityLocalRaycastRequest request) {
		Throwable failure;
		synchronized (this) {
			if (resolved) return delegate;
			try {
				delegate = Objects.requireNonNull(loader.load(), "Aero raycast delegate");
				resolved = true;
				return delegate;
			} catch (ReflectiveOperationException | LinkageError | AssertionError unavailable) {
				resolved = true;
				failure = unavailable;
			}
		}
		report(failure instanceof ReflectiveOperationException ? "delegate-load-reflection"
			: failure instanceof LinkageError ? "delegate-load-linkage" : "delegate-load-assertion", request, failure);
		return null;
	}

	private void report(String handling, EntityLocalRaycastRequest request, Throwable failure) {
		Diagnostics.client().atNormal().rateLimited(reports).failure(Level.WARN,
			"aeronautics-propeller-shell", handling,
			() -> "sourceId=" + SOURCE_ID + "; delegateClass=" + DELEGATE_CLASS_NAME
				+ "; start=" + request.start() + "; end=" + request.end() + "; policy=" + request.policy(), failure);
	}

	synchronized void reset() { delegate = null; resolved = false; }

	@FunctionalInterface
	@NotNullByDefault
	interface DelegateLoader {
		EntityLocalGeometryResolver load() throws ReflectiveOperationException;
	}
}
