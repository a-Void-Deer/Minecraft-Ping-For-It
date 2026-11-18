package nx.pingwheel.neoforge.integration.create;

import java.util.Optional;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.integration.aeronautics.AeroRotorScope;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityLocalGeometryResolver;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Typed Create boundary, with no direct Aero, Simulated, Sable or Flywheel linkage. */
@NotNullByDefault
public final class AeronauticsPropellerRaycastDelegate implements EntityLocalGeometryResolver {

	public AeronauticsPropellerRaycastDelegate() {}

	@Override
	public EntityLocalGeometryResult trace(@Nullable Entity candidate, EntityLocalRaycastRequest request) {
		if (!(candidate instanceof ControlledContraptionEntity rotor)
			|| !AeroRotorScope.matches(rotor)
			|| !rotor.isAlive() || rotor.isRemoved()
			|| !rotor.level().isClientSide() || Minecraft.getInstance().level != rotor.level()) {
			return EntityLocalGeometryResult.unavailable();
		}

		EntityFrameResolution resolution = request.parentFrame(rotor);
		if (resolution.status() == EntityFrameResolution.Status.UNAVAILABLE) {
			return EntityLocalGeometryResult.unavailable();
		}
		// Current raw entity anchor, not interpolated/rendered or parent-local floats.
		Vec3 nativeAnchor = rotor.position();
		Optional<CreateContraptionRaycastEngine.LocalTransform> transform =
			CreateContraptionRaycastEngine.modelTransform(resolution.frame().orElseThrow(),
				nativeAnchor, rotor::applyLocalTransforms);
		if (transform.isEmpty()) return EntityLocalGeometryResult.unavailable();
		return CreateContraptionRaycastDelegate.traceCaptured(rotor, request, transform.orElseThrow());
	}
}
