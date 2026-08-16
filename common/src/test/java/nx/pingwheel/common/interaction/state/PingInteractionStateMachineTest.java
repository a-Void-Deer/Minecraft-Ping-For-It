package nx.pingwheel.common.interaction.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.PingCaptureCoordinator;
import nx.pingwheel.common.interaction.PingCaptureLogger;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.CancelCandidatePicker;
import nx.pingwheel.common.interaction.cancel.CancelMarkerCandidate;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.wheel.WheelSelection;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingInteractionStateMachineTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final WorldVector EYE = new WorldVector(0, 0, 0);
	private static final WorldVector LOOK = new WorldVector(0, 0, 1);

	@Test
	void shortPressAt299EmitsDefaultPingAndIdles() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 299L;

		Optional<PingInteractionAction> action = h.machine.update(false, WheelSelection.NONE, emptyContext());

		PingInteractionAction.CreatePing create = (PingInteractionAction.CreatePing) action.orElseThrow();
		assertEquals("go_to", create.pingType().id());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
		assertTrue(h.machine.currentToken().isEmpty());
	}

	@Test
	void exact300IsLongPressAndOpensWheel() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;

		Optional<PingInteractionAction> action = h.machine.update(true, WheelSelection.NONE, emptyContext());
		assertTrue(action.isEmpty(), "tick cadence must not open the wheel");
		h.machine.presentFrame(true);

		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase());
		assertSame(token, h.machine.currentToken().orElseThrow());
	}

	@Test
	void frameBoundaryAt299And300ControlsWheelAppearance() {
		Harness h = harness();
		InteractionToken token = h.press();
		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));

		h.clock.now = 299L;
		h.machine.presentFrame(true);
		assertEquals(PingInteractionPhase.PRESSED, h.machine.phase());

		h.clock.now = 300L;
		h.machine.presentFrame(true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase());
	}

	@Test
	void delayedCaptureUsesPressTimeAsLongPressBaseline() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.clock.now = 300L;
		h.machine.presentFrame(true);
		assertEquals(PingInteractionPhase.PRESSED, h.machine.phase(),
			"a frame cannot open before its capture is ready");

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 350L;
		h.machine.presentFrame(true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase(),
			"capture readiness must not restart the press timer");
	}

	@Test
	void repeatedPresentationFramesAreIdempotent() {
		Harness h = harness();
		InteractionToken token = h.press();
		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));

		h.clock.now = 300L;
		h.machine.presentFrame(true);
		h.clock.now = 301L;
		h.machine.presentFrame(true);
		h.clock.now = 302L;
		h.machine.presentFrame(true);

		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase());
		assertEquals(1L, h.logger.messages().stream().filter(m -> m.contains("wheel open")).count());
	}

	@Test
	void releaseBeforeFirstPresentationCannotBecomeWheelInteraction() {
		Harness h = harness();
		InteractionToken token = h.press();
		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));

		h.clock.now = 300L;
		h.machine.presentFrame(false);
		assertEquals(PingInteractionPhase.PRESSED, h.machine.phase());

		Optional<PingInteractionAction> action =
			h.machine.update(false, WheelSelection.NONE, emptyContext());

		assertTrue(action.isEmpty());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void releaseBetweenFrameAndTickCommitsOneActionWithoutFrameSideEffects() {
		Harness h = harness();
		InteractionToken token = h.press();
		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));

		h.clock.now = 300L;
		h.machine.presentFrame(true);
		PingType goTo = pingType("go_to");
		h.machine.update(true, WheelSelection.sector(goTo), emptyContext());

		// The key can be released after the last frame but before the tick that
		// owns the commit. Presentation observes no action and leaves the wheel
		// open for that tick.
		h.clock.now = 301L;
		h.machine.presentFrame(false);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase());

		Optional<PingInteractionAction> action =
			h.machine.update(false, WheelSelection.sector(goTo), emptyContext());
		assertEquals(goTo, ((PingInteractionAction.CreatePing) action.orElseThrow()).pingType());
		assertTrue(h.machine.update(false, WheelSelection.sector(goTo), emptyContext()).isEmpty());
	}

	@Test
	void capturedContextReadyReleaseAtExactly300YieldsNoAction() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;

		Optional<PingInteractionAction> action = h.machine.update(false, WheelSelection.NONE, emptyContext());

		assertTrue(action.isEmpty(), "release at exactly the threshold must not ping");
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
		assertTrue(h.machine.currentToken().isEmpty(), "the wheel cannot open after release");
	}

	@Test
	void wheelPingListIsFrozenOrderedAndImmutable() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.block(OVERWORLD, 0, 0, 0, "minecraft:stone"));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		List<String> ids = h.machine.wheelPingTypes().stream().map(PingType::id).toList();

		assertEquals(List.of("attention", "go_to", "danger"), ids);
		assertThrows(UnsupportedOperationException.class, () -> h.machine.wheelPingTypes().clear());
	}

	@Test
	void validSectorSelectionCommitsCreatePing() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		PingType goTo = pingType("go_to");
		h.clock.now = 310L;
		Optional<PingInteractionAction> action =
			h.machine.update(false, WheelSelection.sector(goTo), emptyContext());

		PingInteractionAction.CreatePing create = (PingInteractionAction.CreatePing) action.orElseThrow();
		assertEquals(goTo, create.pingType());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void invalidSectorIsTreatedAsNoneAndYieldsNoAction() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		// "loot" is not in the location target type's ping type list.
		PingType loot = pingType("loot");
		h.clock.now = 310L;
		Optional<PingInteractionAction> action =
			h.machine.update(false, WheelSelection.sector(loot), emptyContext());

		assertTrue(action.isEmpty());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void noSelectionCommitsNoAction() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		h.clock.now = 310L;
		Optional<PingInteractionAction> action = h.machine.update(false, WheelSelection.NONE, emptyContext());

		assertTrue(action.isEmpty());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void singlePingTypeWheelStillSupportsSectorAndCenter() {
		Harness h = singlePingTypeHarness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		assertEquals(List.of("only"), h.machine.wheelPingTypes().stream().map(PingType::id).toList());

		PingType only = h.machine.wheelPingTypes().get(0);

		h.clock.now = 310L;
		Optional<PingInteractionAction> sector =
			h.machine.update(false, WheelSelection.sector(only), emptyContext());

		assertEquals(only, ((PingInteractionAction.CreatePing) sector.orElseThrow()).pingType());

		// A fresh interaction reaches the center with a candidate marker.
		h.clock.now = 400L;
		InteractionToken second = h.press();
		h.complete(second, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 700L;
		h.machine.presentFrame(true);
		h.clock.now = 710L;

		Optional<PingInteractionAction> cancel = h.machine.update(
			false, WheelSelection.CENTER, context(List.of(ownCandidate(7L, new WorldVector(0, 0, 5)))));

		assertEquals(7L, ((PingInteractionAction.CancelMarker) cancel.orElseThrow()).markerId().value());
	}

	@Test
	void wheelOpenAt4999RemainsOpenAnd5000TimesOutWithNoAction() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		h.clock.now = 300L + 4999L;
		assertTrue(h.machine.update(true, WheelSelection.NONE, emptyContext()).isEmpty());
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase());

		h.clock.now = 300L + 5000L;
		h.machine.presentFrame(true);
		Optional<PingInteractionAction> action =
			h.machine.update(false, WheelSelection.sector(pingType("go_to")), emptyContext());

		assertTrue(action.isEmpty(), "timeout must win regardless of release/selection");
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
		assertTrue(h.logger.messages().stream().anyMatch(m -> m.contains("wheel timeout")));
	}

	@Test
	void pendingCaptureEarlyReleaseThenCaptureYieldsOneShortAction() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.clock.now = 200L;
		assertTrue(h.machine.update(false, WheelSelection.NONE, emptyContext()).isEmpty());
		assertEquals(PingInteractionPhase.PRESSED, h.machine.phase());

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 250L;
		Optional<PingInteractionAction> action = h.machine.update(false, WheelSelection.NONE, emptyContext());

		PingInteractionAction.CreatePing create = (PingInteractionAction.CreatePing) action.orElseThrow();
		assertEquals("go_to", create.pingType().id());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void pendingCaptureReleaseAtOrAfterThresholdYieldsNoAction() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.clock.now = 350L;
		assertTrue(h.machine.update(false, WheelSelection.NONE, emptyContext()).isEmpty());

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 400L;
		Optional<PingInteractionAction> action = h.machine.update(false, WheelSelection.NONE, emptyContext());

		assertTrue(action.isEmpty(), "release at/after threshold while pending must not ping");
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
		assertTrue(h.logger.messages().stream().anyMatch(m -> m.contains("long release no action")));
	}

	@Test
	void newPressSupersedesPriorMachineState() {
		Harness h = harness();
		InteractionToken first = h.press();

		h.complete(first, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase());

		InteractionToken second = h.press();

		assertSame(second, h.machine.currentToken().orElseThrow());
		assertEquals(PingInteractionPhase.PRESSED, h.machine.phase());
		assertEquals(WheelSelection.NONE, h.machine.selection());
		assertTrue(h.machine.wheelPingTypes().isEmpty());
		assertTrue(h.machine.update(false, WheelSelection.NONE, emptyContext()).isEmpty());
	}

	@Test
	void externallySupersededTokenYieldsNoActionAndIdle() {
		Harness h = harness();
		InteractionToken token = h.press();

		// A newer interaction begins externally, superseding the machine's token.
		h.interaction.begin();

		// The stale capture is rejected by the coordinator.
		assertTrue(h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0)).isEmpty());

		h.clock.now = 50L;
		assertTrue(h.machine.update(false, WheelSelection.NONE, emptyContext()).isEmpty());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
		assertTrue(h.logger.messages().stream().anyMatch(m -> m.contains("interaction superseded")));
	}

	@Test
	void containedResolverFailureLeavesPendingAndFreshPressRecovers() {
		boolean[] failFirst = { true };
		TargetResolver flakyResolver = (target, context) -> {
			if (failFirst[0]) {
				failFirst[0] = false;
				throw new IllegalStateException("synthetic resolver failure");
			}
			return DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()).resolve(target, context);
		};
		Harness h = new Harness(flakyResolver);

		InteractionToken token = h.press();

		// The capture/resolver failure is contained by the coordinator: the token
		// stays current, no capture is frozen, and the machine keeps waiting.
		assertTrue(h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0)).isEmpty());

		h.clock.now = 200L;
		assertTrue(h.machine.update(false, WheelSelection.NONE, emptyContext()).isEmpty());
		assertEquals(PingInteractionPhase.PRESSED, h.machine.phase(), "no invented timeout or retry loop");
		assertTrue(h.machine.currentToken().isPresent());

		// A fresh press supersedes the pending interaction; the next capture
		// resolves cleanly and commits a normal ping.
		h.clock.now = 300L;
		InteractionToken second = h.press();

		assertTrue(h.complete(second, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0)).isPresent());

		h.clock.now = 350L;
		Optional<PingInteractionAction> action = h.machine.update(false, WheelSelection.NONE, emptyContext());

		PingInteractionAction.CreatePing create = (PingInteractionAction.CreatePing) action.orElseThrow();
		assertEquals("go_to", create.pingType().id());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void timeReversalIsRejected() {
		Harness h = harness();
		h.clock.now = 100L;
		h.press();

		h.clock.now = 50L;

		assertThrows(IllegalStateException.class,
			() -> h.machine.update(false, WheelSelection.NONE, emptyContext()));
	}

	@Test
	void pressRejectsClockRegressionAfterCompletedInteraction() {
		Harness h = harness();
		h.clock.now = 100L;
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 200L;
		h.machine.update(false, WheelSelection.NONE, emptyContext());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());

		// Backward time on a fresh press after a completed interaction must be
		// rejected, not silently produce a shorter hold for the next interaction.
		h.clock.now = 199L;

		assertThrows(IllegalStateException.class, h::press);
	}

	@Test
	void shortPressTargetGoneYieldsTargetGoneAction() {
		Harness h = harness();
		h.verdict = TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED);
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.block(OVERWORLD, 0, 0, 0, "minecraft:stone"));
		h.clock.now = 299L;

		Optional<PingInteractionAction> action = h.machine.update(false, WheelSelection.NONE, emptyContext());

		PingInteractionAction.TargetGone gone = (PingInteractionAction.TargetGone) action.orElseThrow();
		assertEquals(TargetGoneReason.BLOCK_REPLACED, gone.reason());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void sectorTargetGoneYieldsTargetGoneAction() {
		Harness h = harness();
		h.verdict = TargetValidation.gone(TargetGoneReason.DIMENSION_CHANGED);
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		h.clock.now = 310L;
		Optional<PingInteractionAction> action =
			h.machine.update(false, WheelSelection.sector(pingType("go_to")), emptyContext());

		PingInteractionAction.TargetGone gone = (PingInteractionAction.TargetGone) action.orElseThrow();
		assertEquals(TargetGoneReason.DIMENSION_CHANGED, gone.reason());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void targetGoneMessageAndColorAreExact() {
		assertEquals("\u76EE\u6807\u6D88\u5931\u6216\u6B7B\u4EA1", PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE);
		assertEquals(0xFF5555, PingInteractionAction.TargetGone.TARGET_GONE_COLOR);
	}

	@Test
	void centerNeverValidatesCapturedTarget() {
		Harness h = harness();
		h.verdict = TargetValidation.gone(TargetGoneReason.ENTITY_GONE_OR_DEAD);
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		h.clock.now = 310L;
		Optional<PingInteractionAction> cancel = h.machine.update(
			false, WheelSelection.CENTER, context(List.of(ownCandidate(1L, new WorldVector(0, 0, 5)))));

		assertTrue(cancel.isPresent(), "center must cancel without validating the target");
		assertTrue(h.validated.isEmpty(), "center must never call the validator");
	}

	@Test
	void centerWithNoCandidateYieldsNoActionForLocationCapture() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		h.clock.now = 310L;
		Optional<PingInteractionAction> action =
			h.machine.update(false, WheelSelection.CENTER, emptyContext());

		assertTrue(action.isEmpty());
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void centerWithCandidateCancelsNearestOwnedMarker() {
		Harness h = harness();
		InteractionToken token = h.press();

		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		h.clock.now = 310L;
		CancelMarkerCandidate near = ownCandidate(1L, new WorldVector(0, 0, 5));
		CancelMarkerCandidate far = ownCandidate(2L, new WorldVector(0, 0, 20));
		Optional<PingInteractionAction> action = h.machine.update(
			false, WheelSelection.CENTER, context(List.of(far, near)));

		assertEquals(1L, ((PingInteractionAction.CancelMarker) action.orElseThrow()).markerId().value());
	}

	@Test
	void actionReusesFrozenContextIdentityWithoutReResolution() {
		Harness h = harness();
		InteractionToken token = h.press();

		CapturedPingContext frozen =
			h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 1, 2, 3)).orElseThrow();
		h.clock.now = 299L;

		Optional<PingInteractionAction> action = h.machine.update(false, WheelSelection.NONE, emptyContext());

		PingInteractionAction.CreatePing create = (PingInteractionAction.CreatePing) action.orElseThrow();
		assertSame(frozen, create.context());
		assertSame(frozen.resolvedTarget(), h.validated.get(0));
	}

	@Test
	void updateRejectsNullArguments() {
		Harness h = harness();
		h.press();

		assertThrows(NullPointerException.class,
			() -> h.machine.update(false, null, emptyContext()));
		assertThrows(NullPointerException.class,
			() -> h.machine.update(false, WheelSelection.NONE, null));
	}

	@Test
	void customThresholdsArePositiveAndHonored() {
		Harness h = harness();
		PingInteractionStateMachine custom = new PingInteractionStateMachine(
			h.coordinator, h.interaction, h.clock,
			r -> TargetValidation.valid(), new CancelCandidatePicker(), h.logger, 10L, 100L);

		InteractionToken token = custom.press();
		h.complete(token, TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0));

		h.clock.now = 10L;
		custom.presentFrame(true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, custom.phase());

		h.clock.now = 110L;
		assertTrue(custom.update(true, WheelSelection.NONE, emptyContext()).isEmpty());
		custom.presentFrame(true);
		assertEquals(PingInteractionPhase.IDLE, custom.phase());
	}

	@Test
	void customConstructorRejectsNonPositiveThresholds() {
		Harness h = harness();

		assertThrows(IllegalArgumentException.class, () -> new PingInteractionStateMachine(
			h.coordinator, h.interaction, h.clock,
			r -> TargetValidation.valid(), new CancelCandidatePicker(), h.logger, 0L, 100L));
		assertThrows(IllegalArgumentException.class, () -> new PingInteractionStateMachine(
			h.coordinator, h.interaction, h.clock,
			r -> TargetValidation.valid(), new CancelCandidatePicker(), h.logger, 10L, -1L));
	}

	@Test
	void debugLogsAreUsefulPrivateAndNotRepeatedPerTick() {
		Harness h = harness();
		InteractionToken token = h.press();
		UUID entityUuid = UUID.randomUUID();

		h.complete(token, TargetSnapshotFactory.entity(OVERWORLD, entityUuid, "minecraft:item"));
		h.clock.now = 300L;
		h.machine.presentFrame(true);

		PingType attention = pingType("attention");
		h.clock.now = 310L;
		h.machine.update(true, WheelSelection.sector(attention), emptyContext());
		h.clock.now = 320L;
		h.machine.update(true, WheelSelection.sector(attention), emptyContext());
		h.clock.now = 330L;
		h.machine.update(false, WheelSelection.sector(attention), emptyContext());

		List<String> messages = h.logger.messages();
		String joined = String.join("|", messages);

		assertTrue(messages.stream().anyMatch(m -> m.contains("press: token=0")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("wheel open")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("ping commit")));

		// selection is logged once even though update is called twice with the same value
		assertEquals(1, messages.stream().filter(m -> m.contains("wheel selection")).count());

		// private values must never appear
		assertFalse(joined.contains(entityUuid.toString()), "UUID must not be logged");
		assertFalse(joined.contains("minecraft:item"), "entity type id must not be logged");
		assertFalse(joined.contains("OVERWORLD"), "dimension id must not be logged");
	}

	private static Harness harness() {
		return new Harness(null);
	}

	private static Harness singlePingTypeHarness() {
		PingType only = new PingType("only", "k.p", "k.d", 0x112233, 0x445566, Optional.empty());
		TargetType oneType = new TargetType("one", 100, nx.pingwheel.common.domain.TargetKind.ENTITY,
			List.of(only), only);
		TargetResolver fixedResolver = (target, context) -> new ResolvedTarget(target, oneType);
		return new Harness(fixedResolver);
	}

	private static PingType pingType(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}

	private static CancellationContext emptyContext() {
		return new CancellationContext(OWNER, OVERWORLD, EYE, LOOK, List.of());
	}

	private static CancellationContext context(List<CancelMarkerCandidate> candidates) {
		return new CancellationContext(OWNER, OVERWORLD, EYE, LOOK, candidates);
	}

	private static CancelMarkerCandidate ownCandidate(long id, WorldVector position) {
		return new CancelMarkerCandidate(new nx.pingwheel.common.domain.MarkerId(id), OWNER, OVERWORLD, position);
	}

	private static final class Harness {

		final ManualClock clock = new ManualClock();
		final ActiveInteraction interaction = new ActiveInteraction();
		final RecordingLogger logger = new RecordingLogger();
		final List<ResolvedTarget> validated = new ArrayList<>();
		TargetValidation verdict = TargetValidation.valid();
		final PingCaptureCoordinator coordinator;

		final PingInteractionStateMachine machine;

		Harness(TargetResolver fixedResolver) {
			this.coordinator = new PingCaptureCoordinator(
				fixedResolver == null
					? DefaultTargetResolver.builtIn(TargetResolutionLogger.noop())
					: fixedResolver,
				interaction, PingCaptureLogger.noop());
			this.machine = new PingInteractionStateMachine(
				coordinator, interaction, clock,
				resolved -> {
					validated.add(resolved);
					return verdict;
				},
				new CancelCandidatePicker(), logger);
		}

		InteractionToken press() {
			return machine.press();
		}

		Optional<CapturedPingContext> complete(InteractionToken token, TargetSnapshot snapshot) {
			return coordinator.complete(token, snapshot);
		}
	}

	private static final class ManualClock implements InteractionTimeSource {

		long now;

		@Override
		public long nowMillis() {
			return now;
		}
	}

	private static final class RecordingLogger implements PingInteractionLogger {

		private final List<String> messages = new ArrayList<>();

		@Override
		public void debug(String message, Object... args) {
			messages.add(format(message, args));
		}

		List<String> messages() {
			return List.copyOf(messages);
		}

		private static String format(String message, Object... args) {
			StringBuilder sb = new StringBuilder();
			int from = 0;
			int argIndex = 0;
			int open;

			while ((open = message.indexOf("{}", from)) >= 0) {
				sb.append(message, from, open);
				sb.append(argIndex < args.length ? String.valueOf(args[argIndex++]) : "{}");
				from = open + 2;
			}

			sb.append(message.substring(from));
			return sb.toString();
		}
	}
}
