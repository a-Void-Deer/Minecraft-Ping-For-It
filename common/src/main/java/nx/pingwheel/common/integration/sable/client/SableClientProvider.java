package nx.pingwheel.common.integration.sable.client;

import java.util.Objects;
import java.util.Optional;

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
		if (!enabled() || level == null || hit == null || rayStart == null || rayEnd == null) {
			return Optional.empty();
		}

		try {
			return getAccess().capture(level, hit, rayStart, rayEnd);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return Optional.empty();
		} catch (LinkageError ignored) {
			LINK_GUARD.disableSilently();
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
			return projected != null && finite(projected) ? Optional.of(projected) : Optional.empty();
		} catch (RuntimeException ignored) {
			return Optional.empty();
		} catch (LinkageError ignored) {
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
		if (!enabled() || level == null || target == null || !PROVIDER_ID.equals(target.providerId())
			|| !target.isCommitted() || SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			return Optional.empty();
		}

		try {
			return getAccess().resolvePosition(level, target, partialTick);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return Optional.empty();
		} catch (LinkageError ignored) {
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
		if (!enabled() || level == null || target == null || collisionContext == null
			|| !PROVIDER_ID.equals(target.providerId()) || !target.isCommitted()
			|| SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			return Optional.empty();
		}

		try {
			return getAccess().resolve(level, target, partialTick, collisionContext);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return Optional.empty();
		} catch (LinkageError ignored) {
			LINK_GUARD.disableSilently();
			return Optional.empty();
		}
	}

	/** Resolves the current localized block/entity-block name, if available. */
	public static Optional<Component> resolveName(
		ClientLevel level, Target.ExternalBlockTarget target
	) {
		if (!enabled() || level == null || target == null || !PROVIDER_ID.equals(target.providerId())
			|| SableExternalBlockLocator.parse(target.providerLocator()).isEmpty()) {
			return Optional.empty();
		}

		try {
			return getAccess().resolveName(level, target);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return Optional.empty();
		} catch (LinkageError ignored) {
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
				try {
					current = SableClientCompanionAccess.create();
					access = current;
				} catch (ReflectiveOperationException failure) {
					LINK_GUARD.disableSilently();
					throw new IllegalStateException("Sable client integration is unavailable", failure);
				} catch (RuntimeException | LinkageError failure) {
					// Sable's client internals are optional and privacy-sensitive. Do
					// not expose reflection arguments or exception details in logs.
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
