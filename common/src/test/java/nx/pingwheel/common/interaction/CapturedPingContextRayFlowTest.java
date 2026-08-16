package nx.pingwheel.common.interaction;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CapturedPingContextRayFlowTest {

	@Test
	void coordinatorFreezesTheRayInTheAcceptedContextAndActiveInteraction() {
		ActiveInteraction activeInteraction = new ActiveInteraction();
		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(
			(target, matchContext) -> new ResolvedTarget(
				target,
				TargetTypeCatalog.builtIn().findById("location").orElseThrow()),
			activeInteraction,
			PingCaptureLogger.noop());
		InteractionToken token = coordinator.begin();
		TargetSnapshot snapshot = TargetSnapshotFactory.location("minecraft:overworld", 1.0, 2.0, 3.0);
		CapturedRay ray = new CapturedRay(
			new nx.pingwheel.common.interaction.cancel.WorldVector(4.0, 5.0, 6.0),
			new nx.pingwheel.common.interaction.cancel.WorldVector(0.0, 2.0, 0.0));

		Optional<CapturedPingContext> result = coordinator.complete(token, snapshot, ray);

		CapturedPingContext context = result.orElseThrow();
		assertSame(ray, context.ray());
		assertEquals(context, activeInteraction.currentContext().orElseThrow());
	}
}
