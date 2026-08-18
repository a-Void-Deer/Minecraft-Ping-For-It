package nx.pingwheel.common.interaction.state;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.InteractionToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PingInteractionActionTest {

	@Test
	void targetGoneMessageIsTheExactChineseFallback() {
		assertEquals("\u76EE\u6807\u6D88\u5931\u6216\u6B7B\u4EA1", PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE);
	}

	@Test
	void targetGoneColorIsLightRed() {
		assertEquals(0xFF5555, PingInteractionAction.TargetGone.TARGET_GONE_COLOR);
	}

	@Test
	void createPingRejectsNullFields() {
		CapturedPingContext context = context();

		assertThrows(NullPointerException.class,
			() -> new PingInteractionAction.CreatePing(null, pingType()));
		assertThrows(NullPointerException.class,
			() -> new PingInteractionAction.CreatePing(context, null));
	}

	@Test
	void cancelMarkerRejectsNullMarkerId() {
		assertThrows(NullPointerException.class, () -> new PingInteractionAction.CancelMarker(null));
	}

	@Test
	void targetGoneRejectsNullFields() {
		CapturedPingContext context = context();

		assertThrows(NullPointerException.class,
			() -> new PingInteractionAction.TargetGone(null, TargetGoneReason.BLOCK_REPLACED));
		assertThrows(NullPointerException.class,
			() -> new PingInteractionAction.TargetGone(context, null));
	}

	private static CapturedPingContext context() {
		InteractionToken token = new ActiveInteraction().begin();
		ResolvedTarget resolved = new ResolvedTarget(
			new Target.LocationTarget("minecraft:overworld", 0, 0, 0),
			TargetTypeCatalog.builtIn().findById("location").orElseThrow());
		return new CapturedPingContext(token, resolved);
	}

	private static nx.pingwheel.common.domain.PingType pingType() {
		return TargetTypeCatalog.builtIn().findById("location").orElseThrow().defaultPingType();
	}
}
