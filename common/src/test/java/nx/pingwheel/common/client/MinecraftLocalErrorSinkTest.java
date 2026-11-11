package nx.pingwheel.common.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.config.CooldownFeedbackMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftLocalErrorSinkTest {
	@Test
	void trackingFailureUsesItsOwnCompleteTemplateAndLocalizedCause() {
		Component line = MinecraftLocalErrorSink.trackingFailureComponent(InventoryFailureReason.TRACKING_CAPACITY);
		TranslatableContents template = assertTranslatable(line);
		assertEquals("pingforit.inventory.tracking_failed", template.getKey());
		assertEquals(1, template.getArgs().length);
		assertEquals(InventoryFailureReason.TRACKING_CAPACITY.translationKey(), assertTranslatable((Component) template.getArgs()[0]).getKey());
		assertEquals(0xFF5555, line.getStyle().getColor().getValue());
		assertTrue(line.getSiblings().isEmpty(), "the dedicated template owns [pingforit], not the ordinary local prefix");
	}

	@Test
	void feedbackIsThePrefixResourceFollowedByTheMessageResource() {
		Component feedback = MinecraftLocalErrorSink.componentFor(
			PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY,
			PingInteractionAction.TargetGone.TARGET_GONE_COLOR);

		assertEquals(MinecraftLocalErrorSink.PREFIX_KEY, assertTranslatable(feedback).getKey());

		var parts = feedback.getSiblings();
		assertEquals(1, parts.size(), "the feedback line is exactly the prefix plus one message");
		assertEquals(
			PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY,
			assertTranslatable(parts.get(0)).getKey());
	}

	@Test
	void feedbackUsesTheConfirmedProductColor() {
		Component feedback = MinecraftLocalErrorSink.componentFor(
			PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY,
			PingInteractionAction.TargetGone.TARGET_GONE_COLOR);

		assertEquals(0xFF5555, feedback.getStyle().getColor().getValue());
	}

	@Test
	void prefixKeyIsTheStableChatResourceKey() {
		assertEquals("pingforit.chat.local_error.prefix", MinecraftLocalErrorSink.PREFIX_KEY);
	}

	@Test
	void cooldownFeedbackUsesThePrefixMessageAndConfiguredPlacementOnly() {
		Component message = MinecraftLocalErrorSink.componentFor(
			ClientPingActionDispatcher.CREATE_COOLDOWN_MESSAGE_KEY,
			ClientPingActionDispatcher.CREATE_COOLDOWN_COLOR);

		assertEquals(MinecraftLocalErrorSink.PREFIX_KEY, assertTranslatable(message).getKey());
		var parts = message.getSiblings();
		assertEquals(1, parts.size());
		assertEquals(ClientPingActionDispatcher.CREATE_COOLDOWN_MESSAGE_KEY, assertTranslatable(parts.getFirst()).getKey());
		assertEquals(0xFF5555, message.getStyle().getColor().getValue());

		List<Component> actionBar = new ArrayList<>();
		List<Component> chat = new ArrayList<>();
		MinecraftLocalErrorSink.presentCooldown(message, CooldownFeedbackMode.ACTIONBAR, actionBar::add, chat::add);
		assertEquals(List.of(message), actionBar);
		assertTrue(chat.isEmpty());

		MinecraftLocalErrorSink.presentCooldown(message, CooldownFeedbackMode.CHAT, actionBar::add, chat::add);
		assertEquals(List.of(message), chat);

		MinecraftLocalErrorSink.presentCooldown(message, CooldownFeedbackMode.HIDDEN, actionBar::add, chat::add);
		assertEquals(1, actionBar.size());
		assertEquals(1, chat.size());
	}

	private static TranslatableContents assertTranslatable(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents,
			() -> "expected translatable, got: " + component.getContents().getClass());
		return (TranslatableContents) component.getContents();
	}
}
