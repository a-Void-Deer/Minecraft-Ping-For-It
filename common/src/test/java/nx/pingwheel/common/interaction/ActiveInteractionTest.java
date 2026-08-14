package nx.pingwheel.common.interaction;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveInteractionTest {

	private static final String OVERWORLD = "minecraft:overworld";

	@Test
	void beginSupersedesPreviousToken() {
		ActiveInteraction interaction = new ActiveInteraction();

		InteractionToken first = interaction.begin();
		assertTrue(interaction.isCurrent(first));

		InteractionToken second = interaction.begin();

		assertTrue(interaction.isCurrent(second));
		assertFalse(interaction.isCurrent(first));
	}

	@Test
	void noTokenIsCurrentBeforeBegin() {
		ActiveInteraction interaction = new ActiveInteraction();

		assertFalse(interaction.isCurrent(new InteractionToken(0L)));
	}

	@Test
	void nullTokenIsNeverCurrent() {
		ActiveInteraction interaction = new ActiveInteraction();

		assertFalse(interaction.isCurrent(null));
	}

	@Test
	void staleTokenCompletionIsRejectedWithoutStateMutation() {
		ActiveInteraction interaction = new ActiveInteraction();

		InteractionToken first = interaction.begin();
		interaction.begin(); // supersedes first

		assertFalse(interaction.tryComplete(first, context(first, OVERWORLD)));
		assertTrue(interaction.currentContext().isEmpty());
	}

	@Test
	void duplicateCompletionIsRejectedAndCaptureStaysFrozen() {
		ActiveInteraction interaction = new ActiveInteraction();
		InteractionToken token = interaction.begin();

		CapturedPingContext firstCapture = context(token, OVERWORLD);
		CapturedPingContext secondCapture = context(token, "minecraft:the_nether");

		assertTrue(interaction.tryComplete(token, firstCapture));
		assertFalse(interaction.tryComplete(token, secondCapture));

		CapturedPingContext current = interaction.currentContext().orElseThrow();
		assertSame(firstCapture, current);
		assertEquals(OVERWORLD, current.resolvedTarget().target().dimensionId());
	}

	@Test
	void beginClearsPreviousCapture() {
		ActiveInteraction interaction = new ActiveInteraction();

		InteractionToken first = interaction.begin();
		assertTrue(interaction.tryComplete(first, context(first, OVERWORLD)));
		assertTrue(interaction.currentContext().isPresent());

		interaction.begin();

		assertTrue(interaction.currentContext().isEmpty());
	}

	@Test
	void completionRejectsContextWithMismatchedToken() {
		ActiveInteraction interaction = new ActiveInteraction();

		InteractionToken first = interaction.begin();
		InteractionToken second = interaction.begin();

		assertThrows(IllegalArgumentException.class,
			() -> interaction.tryComplete(second, context(first, OVERWORLD)));
	}

	@Test
	void completionRejectsNullContext() {
		ActiveInteraction interaction = new ActiveInteraction();
		InteractionToken token = interaction.begin();

		assertThrows(NullPointerException.class, () -> interaction.tryComplete(token, null));
	}

	@Test
	void beginFailsCleanlyWhenSequenceExhausted() {
		ActiveInteraction interaction = new ActiveInteraction(Long.MAX_VALUE);

		assertThrows(IllegalStateException.class, interaction::begin);
	}

	@Test
	void initialSequenceRejectsNegative() {
		assertThrows(IllegalArgumentException.class, () -> new ActiveInteraction(-1L));
	}

	private static CapturedPingContext context(InteractionToken token, String dimensionId) {
		Target target = new Target.LocationTarget(dimensionId, 0, 0, 0);
		TargetType location = TargetTypeCatalog.builtIn().findById("location").orElseThrow();

		return new CapturedPingContext(token, new ResolvedTarget(target, location));
	}
}
