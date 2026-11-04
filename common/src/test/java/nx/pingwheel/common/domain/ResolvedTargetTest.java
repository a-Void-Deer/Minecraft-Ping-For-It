package nx.pingwheel.common.domain;

import nx.pingwheel.common.testing.Test;

import java.util.UUID;

import static nx.pingwheel.common.testing.Assertions.assertSame;
import static nx.pingwheel.common.testing.Assertions.assertThrows;

class ResolvedTargetTest {

	@Test
	void freezesTargetAndWinningTargetType() {
		Target target = new Target.EntityTarget("minecraft:overworld", UUID.randomUUID());
		TargetType targetType = TargetTypeCatalog.builtIn().findById("entity").orElseThrow();

		ResolvedTarget resolved = new ResolvedTarget(target, targetType);

		assertSame(target, resolved.target());
		assertSame(targetType, resolved.targetType());
	}

	@Test
	void rejectsNullTarget() {
		TargetType targetType = TargetTypeCatalog.builtIn().findById("entity").orElseThrow();

		assertThrows(NullPointerException.class, () -> new ResolvedTarget(null, targetType));
	}

	@Test
	void rejectsNullTargetType() {
		Target target = new Target.LocationTarget("minecraft:overworld", 0, 0, 0);

		assertThrows(NullPointerException.class, () -> new ResolvedTarget(target, null));
	}
}
