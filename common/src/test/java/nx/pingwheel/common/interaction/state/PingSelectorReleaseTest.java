package nx.pingwheel.common.interaction.state;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.PingCaptureCoordinator;
import nx.pingwheel.common.interaction.PingCaptureLogger;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.CancelCandidatePicker;
import nx.pingwheel.common.interaction.cancel.CancelMarkerCandidate;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.wheel.WheelSelection;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.*;

class PingSelectorReleaseTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final long HOLD = 20;
	private static final long LONG_AFTER_OPEN = 600_000L;
	private static final CapturedRay RAY = new CapturedRay(new WorldVector(1, 2, 3), new WorldVector(0, 0, 1));
	private static final UUID OWNER = new UUID(0, 1);
	private static final MarkerId LOCKED_MARKER = new MarkerId(9);

	@Test
	void wrongTokenDoesNotEvaluatePortsAdvanceClockOrEndNewerOpenInteraction() {
		Harness h = new Harness();
		InteractionToken old = h.machine.press();
		h.machine.abort();
		h.open();
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(old, Long.MAX_VALUE,
			unexpectedProposal(), id -> fail("old release consulted candidate lookup")));
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase());
		assertSame(h.token, h.machine.currentToken().orElseThrow());
		assertTrue(h.releaseNone(HOLD + 1).action().isEmpty(), "old timestamp must not poison the newer clock");
		assertTrue(h.logger.messages().stream().anyMatch(message ->
			message.contains("reason=wrong-token") && message.contains("token=" + old.sequence())));
	}

	@Test
	void thresholdWithoutActualOpenLeavesReleaseOnOrdinaryDefaultPath() {
		Harness h = new Harness();
		h.ready();
		h.clock.now = HOLD + 1;
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, h.clock.now,
			unexpectedProposal(), id -> fail("pre-open lookup")));
		assertEquals(PingInteractionPhase.PRESSED, h.machine.phase());
		var create = assertInstanceOf(PingInteractionAction.CreatePing.class,
			h.machine.update(false, WheelSelection.NONE).orElseThrow());
		assertSame(h.ordinary, create.context());
		assertEquals(h.ordinary.resolvedTarget().targetType().defaultPingType(), create.pingType());
	}

	@Test
	void pendingReleaseThenLateCaptureNeverEvaluatesSelectorProposalOrRetroactivelyOpens() {
		Harness h = new Harness();
		h.token = h.machine.press();
		h.clock.now = HOLD + 1;
		assertTrue(h.machine.update(false, WheelSelection.NONE).isEmpty());
		h.complete();
		h.machine.presentFrame(false);
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, h.clock.now,
			unexpectedProposal(), id -> fail("pending/default lookup")));
		assertInstanceOf(PingInteractionAction.CreatePing.class,
			h.machine.update(false, WheelSelection.NONE).orElseThrow());
	}

	@Test
	void elapsedTimeAloneNeverClosesAnActuallyOpenSelector() {
		Harness h = new Harness();
		h.open();

		h.clock.now = LONG_AFTER_OPEN;
		assertTrue(h.machine.update(true, WheelSelection.NONE).isEmpty());
		h.machine.presentFrame(true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.machine.phase());
		assertSame(h.token, h.machine.currentToken().orElseThrow());

		var result = h.machine.releaseSelectorAt(h.token, LONG_AFTER_OPEN,
			() -> new SelectorReleaseProposal.Create<>("ordinary", h.ordinary.resolvedTarget(),
				h.ordinary.resolvedTarget().targetType().defaultPingType(), "create"),
			id -> Optional.of(h.ordinary));
		assertInstanceOf(PingInteractionAction.CreatePing.class, result.action().orElseThrow());
		assertSame(h.ordinary.resolvedTarget(), h.validated, "release validates the frozen target exactly once");
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void noneTerminatesOnceWithoutEmittingTheLockedCancellation() {
		Harness h = new Harness();
		h.openWithCancellation();
		AtomicInteger proposals = new AtomicInteger();
		Supplier<SelectorReleaseProposal<String>> none = () -> {
			assertEquals(PingInteractionPhase.IDLE, h.machine.phase(), "terminal before caller ports");
			proposals.incrementAndGet();
			return new SelectorReleaseProposal.None<>();
		};
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, HOLD + 1,
			none, id -> fail("None lookup")));
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, HOLD + 2,
			none, id -> fail("duplicate lookup")));
		assertEquals(1, proposals.get());
	}

	@Test
	void cancellationAdmitsTheLockedIdOnceWithoutValidatingTheCapturedTarget() {
		Harness h = new Harness();
		h.openWithCancellation();
		h.verdict = TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED);
		Object intent = new Object();
		var result = h.machine.releaseSelectorAt(h.token, HOLD + 1,
			() -> {
				assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
				return new SelectorReleaseProposal.Cancel<>(intent);
			}, id -> fail("Cancel looked up a create candidate"));
		assertSame(intent, result.admittedIntent().orElseThrow());
		assertEquals(new PingInteractionAction.CancelMarker(LOCKED_MARKER, OWNER, DIMENSION), result.action().orElseThrow());
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, HOLD + 2,
			unexpectedProposal(), id -> fail("duplicate cancel lookup")));
		assertNull(h.validated);
	}

	@Test
	void preciseCreateKeepsTableContextRayFaceAndOpaquePropertyPayload() {
		Harness h = new Harness();
		h.open();
		var snapshot = TargetSnapshotFactory.block(DIMENSION, 7, 8, 9, "minecraft:stone");
		var resolved = h.resolver.resolve(snapshot.target(), snapshot.matchContext());
		var precise = new CapturedPingContext(h.token, resolved, RAY, Optional.empty(), Optional.of(BlockFace.NORTH));
		var property = PresentationPropertyIntent.of(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME),
			new PresentationValue.Text("Observed"), resolved.targetType().defaultPingType().id());
		var result = h.create(precise, resolved, property);
		var create = assertInstanceOf(PingInteractionAction.CreatePing.class, result.action().orElseThrow());
		assertSame(precise, create.context());
		assertSame(resolved, h.validated);
		assertEquals(RAY, create.context().ray());
		assertEquals(Optional.of(BlockFace.NORTH), create.context().blockHitFace());
		assertSame(property, result.admittedIntent().orElseThrow());
		assertNotEquals(h.ordinary.resolvedTarget().target(), create.context().resolvedTarget().target());
	}

	@Test
	void opaqueInventoryReferenceSurvivesAdmissionWithoutAnyItemOrCountAuthority() {
		Harness h = new Harness();
		h.open();
		Object opaqueReference = new Object();
		var result = h.create(h.ordinary, h.ordinary.resolvedTarget(), opaqueReference);
		assertSame(opaqueReference, result.admittedIntent().orElseThrow());
		assertSame(h.ordinary, assertInstanceOf(PingInteractionAction.CreatePing.class,
			result.action().orElseThrow()).context());
	}

	@Test
	void stableExternalIdentityDoesNotPermitAnotherCapturedLocatorInProposal() {
		Harness h = new Harness();
		h.open();
		var type = nx.pingwheel.common.domain.TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		var frozen = nx.pingwheel.common.domain.Target.ExternalBlockTarget.committed(
			DIMENSION, "sable", "tracking", "minecraft:chest", "frozen-locator", true);
		var changed = nx.pingwheel.common.domain.Target.ExternalBlockTarget.committed(
			DIMENSION, "sable", "tracking", "minecraft:chest", "other-locator", true);
		assertEquals(frozen, changed, "ordinary stable identity intentionally excludes the provider locator");
		var context = new CapturedPingContext(h.token, new ResolvedTarget(frozen, type), RAY);
		assertEquals(SelectorReleaseResult.empty(), h.create(context, new ResolvedTarget(changed, type), "create"));
		assertNull(h.validated);
	}

	@Test
	void unavailableOrMismatchedCandidateNeverFallsBackToOrdinaryOrValidates() {
		for (String mismatch : List.of("missing", "target", "token", "ray", "type")) {
			Harness h = new Harness();
			h.open();
			CapturedPingContext candidate = h.ordinary;
			if (mismatch.equals("token")) candidate = new CapturedPingContext(new ActiveInteraction().begin(),
				candidate.resolvedTarget(), RAY);
			if (mismatch.equals("ray")) candidate = new CapturedPingContext(h.token,
				candidate.resolvedTarget(), CapturedRay.defaultRay());
			var other = TargetSnapshotFactory.location(DIMENSION, 99, 0, 0);
			ResolvedTarget claim = mismatch.equals("target") ? h.resolver.resolve(other.target(), other.matchContext())
				: h.ordinary.resolvedTarget();
			var ping = mismatch.equals("type") ? PingTypeCatalog.builtIn().findById("loot").orElseThrow()
				: claim.targetType().defaultPingType();
			var lookup = Map.of("candidate", candidate);
			var result = h.machine.releaseSelectorAt(h.token, HOLD + 1,
				() -> new SelectorReleaseProposal.Create<>(mismatch.equals("missing") ? "absent" : "candidate", claim, ping, mismatch),
				id -> Optional.ofNullable(lookup.get(id)));
			assertEquals(SelectorReleaseResult.empty(), result, mismatch);
			assertNull(h.validated, mismatch);
			assertEquals(PingInteractionPhase.IDLE, h.machine.phase(), mismatch);
			String expected = switch (mismatch) {
				case "missing" -> "reason=missing-candidate";
				case "token" -> "reason=wrong-token";
				default -> "reason=ray-type-locator-mismatch";
			};
			assertTrue(h.logger.messages().stream().anyMatch(message -> message.contains(expected)),
				() -> mismatch + " logs: " + h.logger.messages());
		}
	}

	@Test
	void targetGoneReturnsOnlyFeedbackNeverTheAdmittedCreatePayload() {
		Harness h = new Harness();
		h.open();
		h.verdict = TargetValidation.gone(TargetGoneReason.DIMENSION_CHANGED);
		var result = h.create(h.ordinary, h.ordinary.resolvedTarget(), new Object());
		assertTrue(result.admittedIntent().isEmpty());
		var gone = assertInstanceOf(PingInteractionAction.TargetGone.class, result.action().orElseThrow());
		assertSame(h.ordinary, gone.context());
		assertEquals(TargetGoneReason.DIMENSION_CHANGED, gone.reason());
	}

	@Test
	void localIntentIsTerminalAndDoesNotValidateOrInspectCandidates() {
		Harness h = new Harness();
		h.open();
		var result = h.machine.releaseSelectorAt(h.token, HOLD + 1,
			() -> new SelectorReleaseProposal.Local<>("next-capture-toggle"), id -> fail("local lookup"));
		assertEquals(Optional.of("next-capture-toggle"), result.admittedIntent());
		assertTrue(result.action().isEmpty());
		assertNull(h.validated);
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void abortFencesLateCompletionBeforeAnyReleaseCanEvaluateAProposal() {
		Harness h = new Harness();
		h.token = h.machine.press();
		h.machine.abort();
		assertFalse(h.active.isCurrent(h.token));
		assertTrue(h.active.currentContext().isEmpty());
		assertTrue(h.coordinator.complete(h.token, TargetSnapshotFactory.location(DIMENSION, 4, 5, 6), RAY).isEmpty());
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, HOLD + 1,
			unexpectedProposal(), id -> fail("aborted lookup")));
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void externallySupersededOpenCannotConsumeProposalOrAffectNewActiveToken() {
		Harness h = new Harness();
		h.open();
		InteractionToken newer = h.active.begin();
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, HOLD + 1,
			unexpectedProposal(), id -> fail("superseded lookup")));
		assertTrue(h.active.isCurrent(newer));
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
	}

	@Test
	void failingProposalIsStillTerminalAndCannotBeReplayed() {
		Harness h = new Harness();
		h.open();
		assertThrows(IllegalStateException.class, () -> h.machine.releaseSelectorAt(h.token, HOLD + 1,
			() -> { throw new IllegalStateException("port failure"); }, id -> fail("failing lookup")));
		assertEquals(PingInteractionPhase.IDLE, h.machine.phase());
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, HOLD + 2,
			unexpectedProposal(), id -> fail("replayed lookup")));
	}

	@Test
	void reentrantDuplicateReleaseCannotConsumeASecondProposal() {
		Harness h = new Harness();
		h.open();
		var result = h.machine.releaseSelectorAt(h.token, HOLD + 1, () -> {
			assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, HOLD + 1,
				unexpectedProposal(), id -> fail("reentrant lookup")));
			return new SelectorReleaseProposal.Local<>("once");
		}, id -> fail("local lookup"));
		assertEquals(Optional.of("once"), result.admittedIntent());
	}

	@Test
	void callerPortsCannotReturnOldIntentAfterSupersedingItsOwnership() {
		for (String port : List.of("create-proposal", "cancel-proposal", "lookup", "validator")) {
			Harness h = new Harness();
			h.openWithCancellation();
			@Nullable InteractionToken[] newer = { null };
			if (port.equals("validator")) h.validationHook = () -> newer[0] = h.active.begin();
			var result = h.machine.releaseSelectorAt(h.token, HOLD + 1, () -> {
				if (port.endsWith("-proposal")) newer[0] = h.active.begin();
				return port.equals("cancel-proposal") ? new SelectorReleaseProposal.Cancel<>("cancel")
					: new SelectorReleaseProposal.Create<>("candidate", h.ordinary.resolvedTarget(),
						h.ordinary.resolvedTarget().targetType().defaultPingType(), "create");
			}, id -> {
				if (port.equals("lookup")) newer[0] = h.active.begin();
				return Optional.of(h.ordinary);
			});
			assertEquals(SelectorReleaseResult.empty(), result, port);
			assertTrue(h.active.isCurrent(java.util.Objects.requireNonNull(newer[0])), port);
		}
	}

	@Test
	void abortReenteredFromTerminalReleasePortsStillInvalidatesBeforeAdmittingAnything() {
		for (String port : List.of("create-proposal", "cancel-proposal", "lookup", "validator")) {
			Harness h = new Harness();
			h.openWithCancellation();
			if (port.equals("validator")) h.validationHook = h.machine::abort;
			var result = h.machine.releaseSelectorAt(h.token, HOLD + 1, () -> {
				if (port.endsWith("-proposal")) h.machine.abort();
				return port.equals("cancel-proposal") ? new SelectorReleaseProposal.Cancel<>("cancel")
					: new SelectorReleaseProposal.Create<>("candidate", h.ordinary.resolvedTarget(),
						h.ordinary.resolvedTarget().targetType().defaultPingType(), "create");
			}, id -> {
				if (port.equals("lookup")) h.machine.abort();
				return Optional.of(h.ordinary);
			});
			assertEquals(SelectorReleaseResult.empty(), result, port);
			assertFalse(h.active.isCurrent(h.token), port);
			assertTrue(h.active.currentContext().isEmpty(), port);
			assertEquals(PingInteractionPhase.IDLE, h.machine.phase(), port);
		}
	}

	@Test void crossRayPreciseModeRequiresExactCurrentInteractionPaintTableAndRevision() {
		for (String scenario : List.of("unpainted", "wrong-revision", "other-context", "press-ray", "valid")) {
			Harness h = new Harness(); h.open();
			var snapshot = TargetSnapshotFactory.block(DIMENSION, 7, 8, 9, "minecraft:stone");
			var resolved = h.resolver.resolve(snapshot.target(), snapshot.matchContext());
			var live = new CapturedPingContext(h.token, resolved, CapturedRay.defaultRay(), Optional.empty(), Optional.of(BlockFace.NORTH));
			if (!scenario.equals("unpainted")) assertTrue(h.machine.markSelectorPresented(h.token, 7, Map.of("live", live)));
			var lookup = scenario.equals("other-context") ? new CapturedPingContext(h.token, resolved, live.ray(), Optional.empty(), live.blockHitFace()) : live;
			var admission = scenario.equals("press-ray") ? SelectorReleaseProposal.Admission.PRESS_RAY : SelectorReleaseProposal.Admission.PRECISE_PRESENTED;
			var result = h.machine.releaseSelectorAt(h.token, HOLD + 1, () -> new SelectorReleaseProposal.Create<>("live", resolved,
				resolved.targetType().defaultPingType(), "opaque", admission, scenario.equals("wrong-revision") ? 6 : 7),
				id -> Optional.of(lookup));
			if (scenario.equals("valid")) {
				assertSame(live, assertInstanceOf(PingInteractionAction.CreatePing.class, result.action().orElseThrow()).context());
				assertEquals(Optional.of("opaque"), result.admittedIntent());
			} else {
				assertEquals(SelectorReleaseResult.empty(), result, scenario);
				assertNull(h.validated, scenario);
				String expected = switch (scenario) {
					case "unpainted", "wrong-revision" -> "reason=paint-revision-mismatch";
					case "other-context" -> "reason=missing-candidate";
					default -> "reason=ray-type-locator-mismatch";
				};
				assertTrue(h.logger.messages().stream().anyMatch(message -> message.contains(expected)),
					() -> scenario + " logs: " + h.logger.messages());
			}
		}
	}

	@Test void presentationFenceRejectsWrongTokenDimensionAndOldRevisionAndAbortRevokesIt() {
		Harness h = new Harness(); h.open();
		assertFalse(h.machine.markSelectorPresented(new ActiveInteraction().begin(), 1, Map.of("ordinary", h.ordinary)));
		var snapshot = TargetSnapshotFactory.location("minecraft:the_nether", 1, 2, 3);
		var foreign = new CapturedPingContext(h.token, h.resolver.resolve(snapshot.target(), snapshot.matchContext()), RAY);
		assertFalse(h.machine.markSelectorPresented(h.token, 1, Map.of("foreign", foreign)));
		assertTrue(h.machine.markSelectorPresented(h.token, 2, Map.of("ordinary", h.ordinary)));
		assertFalse(h.machine.markSelectorPresented(h.token, 1, Map.of("ordinary", h.ordinary)));
		h.machine.abort(); assertFalse(h.machine.markSelectorPresented(h.token, 3, Map.of("ordinary", h.ordinary)));
		assertEquals(SelectorReleaseResult.empty(), h.machine.releaseSelectorAt(h.token, HOLD + 1,
			unexpectedProposal(), id -> fail("aborted paint lookup")));
	}

	private static <P> Supplier<SelectorReleaseProposal<P>> unexpectedProposal() {
		return () -> fail("proposal must not be evaluated");
	}

	private static final class Harness {
		final Clock clock = new Clock();
		final ActiveInteraction active = new ActiveInteraction();
		final nx.pingwheel.common.domain.TargetResolver resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
		final PingCaptureCoordinator coordinator = new PingCaptureCoordinator(resolver, active, PingCaptureLogger.noop());
		final RecordingLogger logger = new RecordingLogger();
		ResolvedTarget validated;
		TargetValidation verdict = TargetValidation.valid();
		Runnable validationHook = () -> {};
		final PingInteractionStateMachine machine;
		InteractionToken token;
		CapturedPingContext ordinary;
		Harness() {
			machine = new PingInteractionStateMachine(coordinator, active, clock, this::validate,
				new CancelCandidatePicker(), logger, HOLD);
		}
		TargetValidation validate(ResolvedTarget target) {
			assertEquals(PingInteractionPhase.IDLE, machine.phase(), "terminal before validator");
			validated = target;
			validationHook.run();
			return verdict;
		}
		void ready() { token = machine.press(); complete(); }
		void complete() { ordinary = coordinator.complete(token, TargetSnapshotFactory.location(DIMENSION, 4, 5, 6), RAY).orElseThrow(); }
		void open() { ready(); clock.now = HOLD; machine.presentFrame(true); }
		void openWithCancellation() {
			@Unmodifiable List<CancelMarkerCandidate> candidates = List.of(
				new CancelMarkerCandidate(LOCKED_MARKER, OWNER, DIMENSION, new WorldVector(1, 2, 8)));
			token = machine.press(machine.freezeCancellation(
				new CancellationContext(OWNER, DIMENSION, RAY.origin(), RAY.direction(), candidates)));
			complete(); clock.now = HOLD; machine.presentFrame(true);
		}
		SelectorReleaseResult<String> releaseNone(long now) {
			return machine.releaseSelectorAt(token, now, SelectorReleaseProposal.None::new,
				id -> fail("None lookup"));
		}
		<P> SelectorReleaseResult<P> create(CapturedPingContext context, ResolvedTarget claimedTarget, P payload) {
			return machine.releaseSelectorAt(token, HOLD + 1,
				() -> new SelectorReleaseProposal.Create<>("candidate", claimedTarget, claimedTarget.targetType().defaultPingType(), payload),
				id -> Optional.of(context));
		}
	}
	private static final class Clock implements InteractionTimeSource {
		long now;
		public long nowMillis() { return now; }
	}

	private static final class RecordingLogger implements PingInteractionLogger {
		private final List<String> messages = new java.util.ArrayList<>();

		@Override public void debug(String message, @org.jetbrains.annotations.Nullable Object... args) {
			String rendered = message;
			for (Object arg : args) {
				int placeholder = rendered.indexOf("{}");
				if (placeholder >= 0) rendered = rendered.substring(0, placeholder) + arg + rendered.substring(placeholder + 2);
			}
			messages.add(rendered);
		}

		@Override public void debug(Supplier<@org.jetbrains.annotations.Nullable String> detail) {
			messages.add(String.valueOf(detail.get()));
		}

		List<String> messages() { return List.copyOf(messages); }
	}
}
