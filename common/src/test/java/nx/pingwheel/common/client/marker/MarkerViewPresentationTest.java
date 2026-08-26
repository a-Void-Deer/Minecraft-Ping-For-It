package nx.pingwheel.common.client.marker;

import java.util.Optional;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkerViewPresentationTest {

	@Test
	void providerPositionWinsWhileExternalTargetIsResolvable() {
		Vec3 anchor = new Vec3(1.0, 2.0, 3.0);
		Vec3 providerPosition = new Vec3(10.0, 20.0, 30.0);

		assertEquals(
			providerPosition,
			MarkerView.resolveExternalPosition(anchor, Optional.of(providerPosition)));
	}

	@Test
	void anchorRemainsThePresentationFallbackWithoutLocalInvalidation() {
		Vec3 anchor = new Vec3(1.0, 2.0, 3.0);

		assertEquals(anchor, MarkerView.resolveExternalPosition(anchor, Optional.empty()));
	}
}
