package nx.pingwheel.common.integration.sable.client;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.sable.SableDiagnostics;
import nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.client.outline.ExternalBlockOutlineTransform;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

/**
 * Compatibility boundary for the optional Sable integration.
 *
 * <p>Sable only publishes a Companion artifact for Minecraft 1.21.1. The
 * 1.20.1 branch keeps every call site and diagnostic contract intact but
 * intentionally resolves no external Sable targets, so the core Ping/Marker
 * feature set remains independent of an unavailable optional mod.</p>
 */
public final class SableClientProvider {

	public static final String PROVIDER_ID = "sable";

	private static volatile SableDiagnostics diagnostics = SableDiagnostics.global();
	private static final SablePresentationLogGate PRESENTATION_FAILURES =
		new SablePresentationLogGate();

	private SableClientProvider() {}

	/** Attempts to turn a Sable sub-level block hit into an external candidate. */
	public static Optional<TargetSnapshot> capture(
		ClientLevel level, BlockHitResult hit, Vec3 rayStart, Vec3 rayEnd
	) {
		Object[] fields = {
			"provider", PROVIDER_ID,
			"hit_location", hit == null ? null : hit.getLocation()
		};
		diagnostics.capture("attempt", "start", fields);
		diagnostics.capture("fallback", "EXTERNAL_CAPTURE_FAILED",
			append(new Object[] {
				"detail_reason", "sable-not-available-on-this-version",
				"fallback_stage", "attempt"
			}, fields));
		return Optional.empty();
	}

	/**
	 * Projects a block hit inside a Sable sub-level back into the real level.
	 * The 1.20.1 branch has no Sable client companion, so no projection exists.
	 */
	public static Optional<Vec3> projectOutOfSubLevel(ClientLevel level, Vec3 hitPosition) {
		return Optional.empty();
	}

	/** Resolves a committed external block using the empty collision context. */
	public static Optional<ExternalBlockPresentation> resolvePresentation(
		ClientLevel level, Target.ExternalBlockTarget target, float partialTick
	) {
		return resolvePresentation(level, target, partialTick, CollisionContext.empty());
	}

	/** Resolves a committed external block presentation, or empty when unavailable. */
	public static Optional<ExternalBlockPresentation> resolvePresentation(
		ClientLevel level,
		Target.ExternalBlockTarget target,
		float partialTick,
		CollisionContext collisionContext
	) {
		if (!enabled() || level == null || target == null
			|| !PROVIDER_ID.equals(target.providerId()) || !target.isCommitted()
			|| SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			logPresentationFailure("resolve-presentation", "provider-unavailable", "target", target);
			return Optional.empty();
		}

		logPresentationFailure("resolve-presentation", "provider-unavailable", "target", target);
		return Optional.empty();
	}

	/** Resolves only the smooth world presentation center. */
	public static Optional<Vec3> resolvePosition(
		ClientLevel level, Target.ExternalBlockTarget target, float partialTick
	) {
		if (!enabled() || level == null || target == null
			|| !PROVIDER_ID.equals(target.providerId()) || !target.isCommitted()
			|| SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			logPresentationFailure("resolve-position", "invalid-target", "target", target);
			return Optional.empty();
		}

		logPresentationFailure("resolve-position", "provider-unavailable", "target", target);
		return Optional.empty();
	}

	/** Resolves a display name for a committed external block, or empty. */
	public static Optional<Component> resolveName(
		ClientLevel level, Target.ExternalBlockTarget target
	) {
		if (!enabled() || level == null || target == null
			|| !PROVIDER_ID.equals(target.providerId()) || !target.isCommitted()
			|| SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			logPresentationFailure("resolve-name", "invalid-target", "target", target);
			return Optional.empty();
		}

		logPresentationFailure("resolve-name", "provider-unavailable", "target", target);
		return Optional.empty();
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
		PRESENTATION_FAILURES.clear();
	}

	static void logPresentationFailure(String operation, String reason, Object... fields) {
		String key = presentationFailureKey(operation, reason, null, fields);
		if (!PRESENTATION_FAILURES.shouldLog(key)) {
			return;
		}

		diagnostics.capture("presentation", reason, append(
			new Object[] {"operation", operation}, fields));
	}

	static void logPresentationException(
		String operation, Throwable failure, Object... fields
	) {
		Objects.requireNonNull(failure, "failure");
		String key = presentationFailureKey(
			operation, "exception", failure.getClass().getName(), fields);
		if (!PRESENTATION_FAILURES.shouldLog(key)) {
			return;
		}

		diagnostics.captureException(
			"presentation", "exception", failure,
			append(new Object[] {"operation", operation}, fields));
	}

	private static boolean enabled() {
		return false;
	}

	private static String presentationFailureKey(
		String operation, String reason, String failureClass, Object[] fields
	) {
		String targetIdentity = "none";
		for (int index = 0; index + 1 < fields.length; index += 2) {
			if ("target".equals(fields[index])) {
				targetIdentity = stableTargetIdentity(fields[index + 1]);
				break;
			}
		}

		return operation + "|" + reason + "|"
			+ (failureClass == null ? "none" : failureClass) + "|" + targetIdentity;
	}

	private static String stableTargetIdentity(Object target) {
		if (target instanceof Target.ExternalBlockTarget external) {
			String stableId = external.stableTargetId();
			String identity = stableId == null || stableId.isBlank()
				? external.providerLocator()
				: stableId;
			return external.dimensionId() + "|" + external.providerId() + "|" + identity;
		}

		return target == null ? "null" : target.getClass().getName();
	}

	private static Object[] append(Object[] first, Object[] second) {
		Object[] result = new Object[first.length + second.length];
		System.arraycopy(first, 0, result, 0, first.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	/** Immutable render data resolved from a live Sable sub-level. */
	public record ExternalBlockPresentation(
		Vec3 worldCenter,
		BlockPos localBlockPos,
		BlockState blockState,
		ClientLevel localLevel,
		VoxelShape shape,
		Matrix4d renderPose
	) {
		public ExternalBlockPresentation {
			Objects.requireNonNull(worldCenter, "worldCenter");
			Objects.requireNonNull(localBlockPos, "localBlockPos");
			Objects.requireNonNull(blockState, "blockState");
			Objects.requireNonNull(localLevel, "localLevel");
			Objects.requireNonNull(shape, "shape");
			Objects.requireNonNull(renderPose, "renderPose");
			renderPose = new Matrix4d(renderPose);
		}

		/** The transformed integer-corner origin, kept in double precision. */
		public Vec3 worldBlockOrigin() {
			return ExternalBlockOutlineTransform.worldBlockOrigin(
				renderPose, localBlockPos);
		}

		/** The transformed model origin, including the live local-state offset. */
		public Vec3 worldBlockOrigin(Vec3 modelOffset) {
			return ExternalBlockOutlineTransform.worldBlockOrigin(
				renderPose, localBlockPos, modelOffset);
		}

		/** The pose's orientation and non-uniform scale, without translation. */
		public Matrix4f orientationScale() {
			return ExternalBlockOutlineTransform.orientationScale(renderPose);
		}
	}
}
