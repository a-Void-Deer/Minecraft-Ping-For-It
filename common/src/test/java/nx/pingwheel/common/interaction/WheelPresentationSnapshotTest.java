package nx.pingwheel.common.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.WheelPresentationSnapshot;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;

class WheelPresentationSnapshotTest {

	@Test
	void frozenTargetIsVisibleOnlyForAnOpenWheel() {
		ResolvedTarget resolved = new ResolvedTarget(
			new Target.LocationTarget("minecraft:overworld", 1.0, 2.0, 3.0),
			TargetTypeCatalog.builtIn().findById("location").orElseThrow());
		CapturedPingContext context = new CapturedPingContext(new InteractionToken(4L), resolved);
		List<PingType> pingTypes = PingTypeCatalog.builtIn().entries();

		assertTrue(WheelPresentationSnapshot.visible(
			PingInteractionPhase.WHEEL_OPEN,
			Optional.of(context),
			pingTypes).isPresent());
		assertTrue(WheelPresentationSnapshot.visible(
			PingInteractionPhase.PRESSED,
			Optional.of(context),
			pingTypes).isEmpty());
		assertTrue(WheelPresentationSnapshot.visible(
			PingInteractionPhase.WHEEL_OPEN,
			Optional.empty(),
			pingTypes).isEmpty());

		WheelPresentationSnapshot snapshot = WheelPresentationSnapshot.visible(
			PingInteractionPhase.WHEEL_OPEN,
			Optional.of(context),
			pingTypes).orElseThrow();
		assertEquals(resolved, snapshot.context().resolvedTarget());
		assertEquals(pingTypes, snapshot.pingTypes());
	}
}
