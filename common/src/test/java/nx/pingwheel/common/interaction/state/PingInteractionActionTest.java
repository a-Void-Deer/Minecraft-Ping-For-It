package nx.pingwheel.common.interaction.state;

import nx.pingwheel.common.testing.Test;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.InteractionToken;

import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertThrows;

class PingInteractionActionTest {

	@Test
	void targetGoneMessageKeyIsTheStableChatResourceKey() {
		assertEquals("pingforit.chat.target_gone", PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY);
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
