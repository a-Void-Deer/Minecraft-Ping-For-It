package nx.pingwheel.common.integration.sable.client;

import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.IntegrationLinkGuard;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.sable.SableDiagnostics;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator;

/**
 * Lazy, fail-soft client boundary for Sable external blocks.
 *
 * <p>The public surface contains only Minecraft and common-model values. The
 * Companion implementation and the narrow reflective access to Sable's client
 * sub-level container live in sibling integration classes and are initialized
 * only after {@link ModContext#HasSable} has been positively set.</p>
 */
public final class SableClientProvider {

	public static final String PROVIDER_ID = "sable";

	private static final IntegrationLinkGuard LINK_GUARD = new IntegrationLinkGuard(PROVIDER_ID);
	private static volatile SableClientCompanionAccess access;
	private static volatile SableDiagnostics diagnostics = SableDiagnostics.global();
	private static final Set<String> PRESENTATION_FAILURES = new HashSet<>();

	private SableClientProvider() {
	}

	/**
	 * Attempts to turn a Sable sub-level block hit into an external candidate.
	 * An empty result means that the hit could not be positively tied to a live
	 * sub-level and must use the caller's existing projected/location fallback.
	 */
	public static Optional<TargetSnapshot> capture(
		ClientLevel level, BlockHitResult hit, Vec3 rayStart, Vec3 rayEnd
	) {
		SableDiagnostics currentDiagnostics = diagnostics;
		currentDiagnostics.capture(
			"attempt",
			"start",
			"sable_loaded", ModContext.HasSable,
			"enabled", enabled(),
			"link_guard_disabled", LINK_GUARD.disabled(),
			"provider_initialized", access != null,
			"hit_type", hit == null ? null : hit.getType(),
			"hit_location", hit == null ? null : hit.getLocation(),
			"block_pos", hit == null ? null : hit.getBlockPos(),
			"ray_start", rayStart,
			"ray_end", rayEnd,
			"ray_direction", rayDirection(rayStart, rayEnd));

		if (!ModContext.HasSable) {
			logCaptureFallback("EXTERNAL_CAPTURE_FAILED", "sable-not-loaded", "attempt");
			return Optional.empty();
		}

		if (LINK_GUARD.disabled()) {
			logCaptureFallback("EXTERNAL_CAPTURE_FAILED", "link-guard-disabled", "attempt");
			return Optional.empty();
		}

		if (level == null || hit == null || rayStart == null || rayEnd == null) {
			logCaptureFallback("EXTERNAL_CAPTURE_FAILED", "invalid-capture-input", "attempt");
			return Optional.empty();
		}

		try {
			Optional<TargetSnapshot> result = getAccess().capture(level, hit, rayStart, rayEnd);

			if (result.isEmpty()) {
				currentDiagnostics.capture(
					"external-capture",
					"failed",
					"reason", "no-positive-candidate",
					"hit_location", hit.getLocation(),
					"block_pos", hit.getBlockPos());
				logCaptureFallback("EXTERNAL_CAPTURE_FAILED", "no-positive-candidate", "capture");
			}

			return result;
		} catch (ReflectiveOperationException | RuntimeException failure) {
			currentDiagnostics.captureException(
				"capture",
				"exception",
				failure,
				"hit", hit,
				"hit_location", hit.getLocation(),
				"block_pos", hit.getBlockPos(),
				"ray_start", rayStart,
				"ray_end", rayEnd,
				"ray_direction", rayDirection(rayStart, rayEnd));
			logCaptureFallback("EXTERNAL_CAPTURE_FAILED", "exception", "capture");
			return Optional.empty();
		} catch (LinkageError failure) {
			currentDiagnostics.captureException(
				"capture",
				"linkage-error",
				failure,
				"hit", hit,
				"hit_location", hit.getLocation(),
				"block_pos", hit.getBlockPos(),
				"ray_start", rayStart,
				"ray_end", rayEnd,
				"ray_direction", rayDirection(rayStart, rayEnd));
			LINK_GUARD.disableSilently();
			logCaptureFallback("EXTERNAL_CAPTURE_FAILED", "linkage-error", "capture");
			return Optional.empty();
		}
	}

	/**
	 * Preserves the legacy projected-position fallback for a Sable hit. This is
	 * intentionally separate from candidate capture so an unresolved candidate
	 * never becomes a partially populated external target.
	 */
	public static Optional<Vec3> projectOutOfSubLevel(ClientLevel level, Vec3 hitPosition) {
		if (!enabled() || level == null || hitPosition == null) {
			return Optional.empty();
		}

		try {
			Vec3 projected = getAccess().projectOutOfSubLevel(level, hitPosition);
			if (projected != null && finite(projected)) {
				return Optional.of(projected);
			}

			diagnostics.capture(
				"projection",
				"failed",
				"reason", projected == null ? "no-result" : "non-finite-result",
				"hit_position", hitPosition,
				"projected_position", projected);
			return Optional.empty();
		} catch (RuntimeException failure) {
			diagnostics.captureException(
				"projection",
				"exception",
				failure,
				"hit_position", hitPosition);
			return Optional.empty();
		} catch (LinkageError failure) {
			diagnostics.captureException(
				"projection",
				"linkage-error",
				failure,
				"hit_position", hitPosition);
			LINK_GUARD.disableSilently();
			return Optional.empty();
		}
	}

	/**
	 * Resolves a committed external block using an empty collision context. The
	 * result is used by marker icon/label presentation, where only the current
	 * smooth world center is needed.
	 */
	public static Optional<ExternalBlockPresentation> resolvePresentation(
		ClientLevel level, Target.ExternalBlockTarget target, float partialTick
	) {
		return resolvePresentation(level, target, partialTick, CollisionContext.empty());
	}

	/**
	 * Resolves only the smooth world presentation center. Unlike the render-data
	 * route this does not require shape construction, so a transient shape/render
	 * failure cannot make a resolvable marker jump to its static anchor.
	 */
	public static Optional<Vec3> resolvePosition(
		ClientLevel level, Target.ExternalBlockTarget target, float partialTick
	) {
		if (!enabled()) {
			return Optional.empty();
		}

		if (level == null || target == null || !PROVIDER_ID.equals(target.providerId())
			|| !target.isCommitted()) {
			logPresentationFailure("resolve-position", "invalid-target", "target", target);
			return Optional.empty();
		}

		if (SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			logPresentationFailure(
				"resolve-position", "provider-locator-parse-failure",
				"target", target,
				"provider_locator", target.providerLocator());
			return Optional.empty();
		}

		try {
			Optional<Vec3> result = getAccess().resolvePosition(level, target, partialTick);
			if (result.isPresent()) {
				clearPresentationFailure("resolve-position");
			} else {
				logPresentationFailure("resolve-position", "provider-unavailable", "target", target);
			}
			return result;
		} catch (ReflectiveOperationException | RuntimeException failure) {
			logPresentationException("resolve-position", failure, "target", target);
			return Optional.empty();
		} catch (LinkageError failure) {
			logPresentationException("resolve-position", failure, "target", target);
			LINK_GUARD.disableSilently();
			return Optional.empty();
		}
	}

	/**
	 * Resolves a committed external block and its current native shape in the
	 * actual sub-level context. The render path supplies the live camera
	 * collision context; no main-level state is consulted.
	 */
	public static Optional<ExternalBlockPresentation> resolvePresentation(
		ClientLevel level,
		Target.ExternalBlockTarget target,
		float partialTick,
		CollisionContext collisionContext
	) {
		if (!enabled()) {
			return Optional.empty();
		}

		if (level == null || target == null || collisionContext == null
			|| !PROVIDER_ID.equals(target.providerId()) || !target.isCommitted()) {
			logPresentationFailure("resolve-presentation", "invalid-target", "target", target);
			return Optional.empty();
		}

		if (SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			logPresentationFailure(
				"resolve-presentation", "provider-locator-parse-failure",
				"target", target,
				"provider_locator", target.providerLocator());
			return Optional.empty();
		}

		try {
			Optional<ExternalBlockPresentation> result =
				getAccess().resolve(level, target, partialTick, collisionContext);
			if (result.isPresent()) {
				clearPresentationFailure("resolve-presentation");
			} else {
				logPresentationFailure("resolve-presentation", "provider-unavailable", "target", target);
			}
			return result;
		} catch (ReflectiveOperationException | RuntimeException failure) {
			logPresentationException("resolve-presentation", failure, "target", target);
			return Optional.empty();
		} catch (LinkageError failure) {
			logPresentationException("resolve-presentation", failure, "target", target);
			LINK_GUARD.disableSilently();
			return Optional.empty();
		}
	}

	/** Resolves the current localized block/entity-block name, if available. */
	public static Optional<Component> resolveName(
		ClientLevel level, Target.ExternalBlockTarget target
	) {
		if (!enabled()) {
			return Optional.empty();
		}

		if (level == null || target == null || !PROVIDER_ID.equals(target.providerId())) {
			logPresentationFailure("resolve-name", "invalid-target", "target", target);
			return Optional.empty();
		}

		if (SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			logPresentationFailure(
				"resolve-name", "provider-locator-parse-failure",
				"target", target,
				"provider_locator", target.providerLocator());
			return Optional.empty();
		}

		try {
			Optional<Component> result = getAccess().resolveName(level, target);
			if (result.isPresent()) {
				clearPresentationFailure("resolve-name");
			} else {
				logPresentationFailure("resolve-name", "provider-unavailable", "target", target);
			}
			return result;
		} catch (ReflectiveOperationException | RuntimeException failure) {
			logPresentationException("resolve-name", failure, "target", target);
			return Optional.empty();
		} catch (LinkageError failure) {
			logPresentationException("resolve-name", failure, "target", target);
			LINK_GUARD.disableSilently();
			return Optional.empty();
		}
	}

	private static boolean enabled() {
		return ModContext.HasSable && !LINK_GUARD.disabled();
	}

	private static SableClientCompanionAccess getAccess() {
		SableClientCompanionAccess current = access;

		if (current != null) {
			return current;
		}

			synchronized (SableClientProvider.class) {
			current = access;

			if (current == null) {
				diagnostics.capture(
					"provider-init",
					"start",
					"link_guard_disabled", LINK_GUARD.disabled(),
					"provider_initialized", false);

				try {
					current = SableClientCompanionAccess.create(diagnostics);
					access = current;
					diagnostics.capture(
						"provider-init",
						"success",
						"provider_initialized", true,
						"internal_access", current.hasInternalAccess());
				} catch (ReflectiveOperationException failure) {
					diagnostics.captureException(
						"provider-init",
						"reflection-failure",
						failure,
						"provider_initialized", false);
					LINK_GUARD.disableSilently();
					throw new IllegalStateException("Sable client integration is unavailable", failure);
				} catch (RuntimeException failure) {
					diagnostics.captureException(
						"provider-init",
						"runtime-failure",
						failure,
						"provider_initialized", false);
					LINK_GUARD.disableSilently();
					throw failure;
				} catch (LinkageError failure) {
					diagnostics.captureException(
						"provider-init",
						"linkage-error",
						failure,
						"provider_initialized", false);
					LINK_GUARD.disableSilently();
					throw failure;
				}
			}

			return current;
		}
	}

	private static boolean finite(Vec3 value) {
		return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	/** Emits a capture fallback without making the runtime know logger details. */
	public static void logCaptureFallback(
		String fallback, String detailReason, String stage, Object... fields
	) {
		Object[] base = {
			"fallback", fallback,
			"detail_reason", detailReason,
			"fallback_stage", stage
		};
		diagnostics.capture("fallback", fallback, append(base, fields));
	}

	static void setDiagnosticsForTests(SableDiagnostics replacement) {
		diagnostics = Objects.requireNonNull(replacement, "replacement");
		synchronized (PRESENTATION_FAILURES) {
			PRESENTATION_FAILURES.clear();
		}
	}

	private static Vec3 rayDirection(Vec3 rayStart, Vec3 rayEnd) {
		return rayStart == null || rayEnd == null ? null : rayEnd.subtract(rayStart);
	}

	private static void logPresentationFailure(String operation, String reason, Object... fields) {
		String key = operation + "|" + reason;
		synchronized (PRESENTATION_FAILURES) {
			if (!PRESENTATION_FAILURES.add(key)) {
				return;
			}
		}

		diagnostics.capture("presentation", reason, append(
			new Object[] {"operation", operation}, fields));
	}

	private static void logPresentationException(
		String operation, Throwable failure, Object... fields
	) {
		String key = operation + "|exception|" + failure.getClass().getName()
			+ "|" + String.valueOf(failure.getMessage());
		synchronized (PRESENTATION_FAILURES) {
			if (!PRESENTATION_FAILURES.add(key)) {
				return;
			}
		}

		diagnostics.captureException(
			"presentation", "exception", failure,
			append(new Object[] {"operation", operation}, fields));
	}

	private static void clearPresentationFailure(String operation) {
		String prefix = operation + "|";
		synchronized (PRESENTATION_FAILURES) {
			PRESENTATION_FAILURES.removeIf(key -> key.startsWith(prefix));
		}
	}

	private static Object[] append(Object[] first, Object[] second) {
		Object[] result = new Object[first.length + second.length];
		System.arraycopy(first, 0, result, 0, first.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	/** Immutable render data resolved from the live Sable sub-level. */
	public record ExternalBlockPresentation(
		Vec3 worldCenter,
		BlockPos localBlockPos,
		net.minecraft.world.level.block.state.BlockState blockState,
		VoxelShape shape,
		Matrix4f renderPose
	) {
		public ExternalBlockPresentation {
			Objects.requireNonNull(worldCenter, "worldCenter");
			Objects.requireNonNull(localBlockPos, "localBlockPos");
			Objects.requireNonNull(blockState, "blockState");
			Objects.requireNonNull(shape, "shape");
			Objects.requireNonNull(renderPose, "renderPose");
			renderPose = new Matrix4f(renderPose);
		}
	}
}
