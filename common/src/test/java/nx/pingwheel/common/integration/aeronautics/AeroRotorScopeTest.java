package nx.pingwheel.common.integration.aeronautics;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class AeroRotorScopeTest {
	@Test void onlyTheSharedNormalAndGyroRegistryIdentityAcquiresFrameSemantics() {
		assertTrue(AeroRotorScope.matchesId(ResourceLocation.parse("aeronautics:propeller_bearing_contraption")));
		for (String id : List.of("create:controlled_contraption", "create:contraption", "simulated:propeller_bearing_contraption",
			"aeronautics:propeller_bearing", "aeronautics:gyro_propeller_bearing_contraption", "other:propeller_bearing_contraption")) {
			assertFalse(AeroRotorScope.matchesId(ResourceLocation.parse(id)), id);
		}
		assertFalse(AeroRotorScope.matchesId(null));
	}
}
