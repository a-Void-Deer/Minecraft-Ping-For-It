package nx.pingwheel.common.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.client.PresentationView;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static nx.pingwheel.common.chat.PendingContentChatController.Authorization.*;

class PendingContentChatControllerTest {

	private static final String BASIC = PresentationBasic.ID;
	private static final String CREATE = "create:presentation";
	private static final MarkerId ID = new MarkerId(42);
	private static final PresentationPropertyRef NAME = PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
	private static final PresentationPropertyRef CUSTOM = PresentationPropertyRef.root(BASIC, PresentationBasic.CUSTOM_NAME);
	private static final PresentationPropertyRef HEALTH = PresentationPropertyRef.root(BASIC, PresentationBasic.HEALTH);
	private static final PresentationPropertyRef STRESS = PresentationPropertyRef.root(CREATE, "create:kinetic.stress");
	private static final PresentationPropertyRef RPM = new PresentationPropertyRef(CREATE, "create:kinetic.speed", List.of("effective_rpm"));
	private static final String NAME_JSON = "{\"text\":\"Server Name\"}";

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void onlyNewlySeenAcceptedContentKindsBeginAndDuplicatePendingCannotReplaceCapture() {
		Fixture f = new Fixture();
		var receipt = PresentationReceiptContent.properties(List.of(CUSTOM));
		var accepted = f.created(ID, receipt, "Original");
		assertFalse(f.controller.begin(accepted, false));
		assertFalse(f.controller.begin(new PresentationS2CPacket(), true));
		assertFalse(f.controller.begin(f.created(ID, PresentationReceiptContent.whole(), "Whole"), true));
		assertFalse(f.controller.begin(f.created(ID, PresentationReceiptContent.suppressed(), "Hidden"), true));
		assertEquals(0, f.controller.pendingCount());
		assertTrue(f.controller.begin(accepted, true));
		assertFalse(f.controller.begin(f.created(ID, PresentationReceiptContent.properties(List.of(NAME)), "Replacement"), true));
		f.projection(ID, namedCustom("Selected", true, false));
		assertTrue(f.controller.attempt(ID));
		String text = flatten(f.sent.getFirst());
		assertTrue(text.contains("Original"));
		assertFalse(text.contains("Replacement"));
		assertFalse(f.controller.begin(accepted, false), "known same-ID CREATED cannot restart a resolved receipt");
		assertFalse(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
	}

	@Test void separateAdapterArrivalsRequireEveryExplicitRefAndNeverSendAPartialList() {
		Fixture f = new Fixture();
		var receipt = PresentationReceiptContent.properties(List.of(CUSTOM, RPM));
		assertTrue(f.begin(receipt));
		assertFalse(f.controller.attempt(ID));
		f.projection(ID, namedCustom("Selected", true, false));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.sent.isEmpty(), "Basic arrival alone must not make a partial receipt");
		var speed = new PresentationSection(CREATE, 1, Map.of("create:kinetic.speed",
			new PresentationValue.RecordValue(Map.of("effective_rpm", new PresentationValue.NumberValue(64)))),
			false, Map.of(RPM, "danger"));
		f.projection(ID, namedCustom("Selected", true, false), speed);
		assertTrue(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
		String text = flatten(f.sent.getFirst());
		assertTrue(text.contains("64"));
		assertTrue(text.contains("Selected"));
		assertTrue(text.indexOf("64") < text.indexOf("Selected"), "receipt metadata owns deterministic ref order");
		assertFalse(text.contains(NAME_JSON), "only the known NAME JSON decoder supplies the target");
		assertTrue(text.contains("Server Name"));
		assertFalse(f.controller.attempt(ID));
	}

	@Test void everyRefNeedsItsOwnAnnotationAndExplicitNameIsNotAHudPlan() {
		Fixture f = new Fixture();
		var named = new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text(NAME_JSON),
			PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Selected")), false, Map.of(NAME, "attention"));
		f.projection(ID, named);
		assertTrue(f.begin(PresentationReceiptContent.properties(List.of(NAME, CUSTOM))));
		assertFalse(f.controller.attempt(ID), "annotation on the default/name does not authorize another explicit ref");
		f.projection(ID, new PresentationSection(BASIC, 1, named.fields(), false,
			Map.of(NAME, "attention", CUSTOM, "danger")));
		assertTrue(f.controller.attempt(ID));
		Component message = f.sent.getFirst();
		assertEquals(2, countKey(message, ContentChatTemplate.FIELD_KEY), "explicit NAME is included despite HUD exclusion");
		assertEquals(1, countKey(message, "presentation.pingforit.type.danger.display"));
	}

	@Test void nameAuthorizedMissingMalformedOrStaleWaitsButDeniedCancels() {
		Fixture f = new Fixture();
		f.projection(ID, new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Selected")), false, Map.of(CUSTOM, "attention")));
		assertTrue(f.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
		assertFalse(f.controller.attempt(ID));
		f.projection(ID, namedCustom("Selected", true, true));
		assertFalse(f.controller.attempt(ID));
		f.projection(ID, new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("not JSON"),
			PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Selected")), false, Map.of(CUSTOM, "attention")));
		assertFalse(f.controller.attempt(ID), "raw/generic text must never substitute for NAME JSON");
		assertEquals(1, f.controller.pendingCount());
		f.allowed = Map.of(BASIC, Set.of(PresentationBasic.CUSTOM_NAME));
		assertFalse(f.controller.attempt(ID));
		assertEquals(0, f.controller.pendingCount(), "explicit NAME denial cancels");
		f.allowAll(); f.projection(ID, namedCustom("Selected", true, false));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.sent.isEmpty());
	}

	@Test void missingFirstRefCannotConcealDenialOfALaterRef() {
		Fixture f = new Fixture();
		assertTrue(f.begin(PresentationReceiptContent.properties(List.of(RPM, CUSTOM))));
		f.allowed = Map.of(BASIC, Set.of(PresentationBasic.NAME), CREATE, Set.of("create:kinetic.speed"));
		assertFalse(f.controller.attempt(ID));
		assertEquals(0, f.controller.pendingCount());
		assertTrue(f.sent.isEmpty());
	}

	@Test void healthWaitsForRequiredAuthorizedMaximumAndDeniedContextCancels() {
		Fixture f = new Fixture();
		assertTrue(f.begin(PresentationReceiptContent.properties(List.of(HEALTH))));
		f.projection(ID, health(null));
		assertFalse(f.controller.attempt(ID));
		f.projection(ID, health(new PresentationValue.Text("unknown")));
		assertFalse(f.controller.attempt(ID));
		f.projection(ID, health(new PresentationValue.NumberValue(20)));
		assertTrue(f.controller.attempt(ID));
		assertEquals(1, countKey(f.sent.getFirst(), "presentation.pingforit.format.health_max"));
		Fixture denied = new Fixture();
		assertTrue(denied.begin(PresentationReceiptContent.properties(List.of(HEALTH))));
		denied.allowed = Map.of(BASIC, Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH));
		assertFalse(denied.controller.attempt(ID));
		assertEquals(0, denied.controller.pendingCount());
	}

	@Test void staleSelectedSectionWaitsAndOptionalCapacityNeverBlocksStress() {
		Fixture f = new Fixture();
		assertTrue(f.begin(PresentationReceiptContent.properties(List.of(STRESS))));
		Map<String, PresentationValue> fields = Map.of("create:kinetic.stress", new PresentationValue.NumberValue(12),
			"create:kinetic.capacity", new PresentationValue.NumberValue(10));
		f.allowed = Map.of(BASIC, Set.of(PresentationBasic.NAME), CREATE, Set.of("create:kinetic.stress"));
		f.projection(ID, name(), new PresentationSection(CREATE, 1, fields, true, Map.of(STRESS, "attention")));
		assertFalse(f.controller.attempt(ID));
		f.projection(ID, name(), new PresentationSection(CREATE, 1, fields, false, Map.of(STRESS, "attention")));
		assertTrue(f.controller.attempt(ID), "capacity percentage is optional and policy exclusion cannot block stress");
		assertEquals(1, countKey(f.sent.getFirst(), "presentation.pingforit.format.su"));
		assertEquals(0, countKey(f.sent.getFirst(), "presentation.pingforit.format.su_percent"));
	}

	@Test void inventoryGatesRejectPartialUnknownBaselineGreyAndStatusOnlyValuesButAcceptZero() {
		Fixture f = new Fixture();
		assertTrue(f.begin(PresentationReceiptContent.inventory()));
		ClientInventory.EntryView entry = entry(96, false, InventoryS2CPacket.Status.READY, "take");
		List<ClientInventory.Tracking> unusable = List.of(
			tracking(ID, entry, false, false, 4, InventoryS2CPacket.Status.READY),
			tracking(ID, entry, true, false, 0, InventoryS2CPacket.Status.READY),
			tracking(ID, entry, true, true, 4, InventoryS2CPacket.Status.READY),
			tracking(ID, entry, true, false, 4, InventoryS2CPacket.Status.UPDATING),
			tracking(ID, entry, true, false, 4, InventoryS2CPacket.Status.INCOMPLETE),
			tracking(ID, entry, true, false, 4, InventoryS2CPacket.Status.UNCERTAIN),
			tracking(new MarkerId(99), entry, true, false, 4, InventoryS2CPacket.Status.READY),
			tracking(ID, entry(-1, false, null, "take"), true, false, 4, InventoryS2CPacket.Status.READY),
			tracking(ID, entry(96, false, null, null), true, false, 4, InventoryS2CPacket.Status.READY),
			tracking(ID, entry(96, false, null, "not_a_type"), true, false, 4, InventoryS2CPacket.Status.READY),
			tracking(ID, entry(96, true, InventoryS2CPacket.Status.INVALID, "take"), true, false, 4, InventoryS2CPacket.Status.READY),
			tracking(ID, new ClientInventory.EntryView("selected", "illegal item id", "item.minecraft.gunpowder", null,
				96, false, null, "take"), true, false, 4, InventoryS2CPacket.Status.READY),
			new ClientInventory.Tracking(ID, InventoryS2CPacket.Status.READY, false, true, 4, 1, 1, List.of(entry, entry)));
		for (var rejected : unusable) {
			f.tracking.put(ID, rejected);
			assertFalse(f.controller.attempt(ID), () -> "must not complete from " + rejected);
			assertTrue(f.sent.isEmpty());
		}
		f.tracking.put(ID, tracking(ID, entry(0, false, InventoryS2CPacket.Status.UNCERTAIN, "take"),
			true, false, 4, InventoryS2CPacket.Status.READY));
		assertTrue(f.controller.attempt(ID));
		assertEquals("0", countArgument(f.sent.getFirst()));
		assertEquals(1, countKey(f.sent.getFirst(), ContentChatTemplate.QUALITY_KEY));
	}

	@Test void componentFallbackAndLongCountRemainValidWithoutASecondStreamLine() {
		Fixture f = new Fixture();
		assertTrue(f.begin(PresentationReceiptContent.inventory()));
		f.tracking.put(ID, tracking(ID, entry(9007199254740993L, true, InventoryS2CPacket.Status.COMPONENT_TOO_LONG, "take"),
			true, false, 4, InventoryS2CPacket.Status.READY));
		assertTrue(f.controller.attempt(ID));
		assertEquals("9007199254740993", countArgument(f.sent.getFirst()));
		assertEquals(1, countKey(f.sent.getFirst(), ContentChatTemplate.QUALITY_KEY));
		f.tracking.put(ID, tracking(ID, entry(97, false, null, "take"), true, false, 4, InventoryS2CPacket.Status.READY));
		assertFalse(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
	}

	@Test void readyInventoryStillWaitsForFreshNameAndNegotiationThenReadsTheLatestCount() {
		Fixture f = new Fixture();
		assertTrue(f.begin(PresentationReceiptContent.inventory()));
		f.tracking.put(ID, tracking(ID, entry(95, false, null, "take"), true, false, 4, InventoryS2CPacket.Status.READY));
		f.projection(ID, new PresentationSection(BASIC, 1, name().fields(), true));
		assertFalse(f.controller.attempt(ID));
		f.projection(ID, name()); f.ready = false;
		assertFalse(f.controller.attempt(ID));
		assertEquals(1, f.controller.pendingCount());
		f.ready = true;
		f.tracking.put(ID, tracking(ID, entry(96, false, null, "take"), true, false, 4, InventoryS2CPacket.Status.READY));
		assertTrue(f.controller.attempt(ID));
		assertEquals("96", countArgument(f.sent.getFirst()), "a pending receipt never caches an earlier count");
	}

	@Test void inventoryPolicyUnknownWaitsAndExplicitDenialCancelsEvenWithMissingName() {
		Fixture f = new Fixture();
		assertTrue(f.begin(PresentationReceiptContent.inventory()));
		f.inventoryAuthorization = UNKNOWN;
		assertFalse(f.controller.attempt(ID));
		assertEquals(1, f.controller.pendingCount());
		f.projection(ID);
		f.inventoryAuthorization = DENIED;
		assertFalse(f.controller.attempt(ID));
		assertEquals(0, f.controller.pendingCount());
		f.inventoryAuthorization = ALLOWED; f.projection(ID, name());
		f.tracking.put(ID, tracking(ID, entry(96, false, null, "take"), true, false, 4, InventoryS2CPacket.Status.READY));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.sent.isEmpty());
	}

	@Test void resetRemovalEvictionDisconnectAndChangedFencesDiscardLateData() {
		for (int reason = 0; reason < 8; reason++) {
			Fixture f = new Fixture();
			assertTrue(f.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
			switch (reason) {
				case 0 -> f.controller.reset();
				case 1 -> f.controller.cancel(ID);
				case 2 -> f.controller.retainLive(Set.of());
				case 3 -> f.controller.clear();
				case 4 -> f.view++;
				case 5 -> f.epoch++;
				case 6 -> f.live.remove(ID);
				case 7 -> f.live.put(ID, snapshot(ID, 2));
				default -> throw new AssertionError();
			}
			f.projection(ID, namedCustom("Late", true, false));
			assertFalse(f.controller.attempt(ID));
			assertEquals(0, f.controller.pendingCount(), "lifecycle reason " + reason);
			assertTrue(f.sent.isEmpty());
		}
	}

	@Test void pendingBoundIsPositiveFailsClosedAndDoesNotEvictAnotherCapture() {
		Fixture f = new Fixture();
		assertThrows(IllegalArgumentException.class, () -> new PendingContentChatController(0, f::current,
			new LifecycleFormatter(), f.sent::add));
		f.controller = f.production(1, f.sent::add);
		var first = f.created(ID, PresentationReceiptContent.properties(List.of(CUSTOM)), "First");
		assertTrue(f.controller.begin(first, true));
		MarkerId second = new MarkerId(43);
		f.install(second);
		var overflow = f.created(second, PresentationReceiptContent.inventory(), "Overflow");
		assertFalse(f.controller.begin(overflow, true));
		assertEquals(1, f.controller.pendingCount());
		f.projection(ID, namedCustom("Selected", true, false));
		assertTrue(f.controller.attempt(ID));
		assertFalse(f.controller.begin(overflow, false), "known membership does not retry an overflow receipt");
		assertFalse(f.controller.attempt(second));
		assertEquals(1, f.sent.size());
	}

	@Test void cancellationHasNoPermanentSentHistoryAndRetainLiveKeepsUnseenSelectionsPending() {
		Fixture f = new Fixture();
		var accepted = f.created(ID, PresentationReceiptContent.properties(List.of(CUSTOM)), "First");
		assertTrue(f.controller.begin(accepted, true));
		f.controller.retainLive(Set.of(ID));
		assertEquals(1, f.controller.pendingCount());
		f.controller.cancel(ID);
		assertFalse(f.controller.begin(accepted, false), "same membership cannot restart after cancel");
		f.live.remove(ID); f.install(ID); // Caller observed a genuine new local membership.
		assertTrue(f.controller.begin(accepted, true));
		f.projection(ID, namedCustom("Selected", true, false));
		assertTrue(f.controller.attempt(ID));
		f.controller.clear(); f.live.clear(); f.install(ID);
		assertTrue(f.controller.begin(accepted, true), "clear is not a permanent once-per-ID registry");
	}

	@Test void resetOrClearInsideNameOrComposerCannotSendLateOutput() {
		for (int callback = 0; callback < 4; callback++) {
			Fixture f = new Fixture();
			LifecycleFormatter formatter = new LifecycleFormatter();
			f.controller = new PendingContentChatController(4, f::current, formatter, f.sent::add);
			Runnable invalidate = callback % 2 == 0 ? f.controller::reset : f.controller::clear;
			if (callback < 2) formatter.onName = invalidate; else formatter.onProperties = invalidate;
			f.projection(ID, namedCustom("Selected", true, false));
			assertTrue(f.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
			assertFalse(f.controller.attempt(ID));
			assertEquals(0, f.controller.pendingCount());
			assertTrue(f.sent.isEmpty());
		}
	}

	@Test void reentrantComposerAttemptCannotDuplicateAndSinkRunsOnlyAfterPendingIsRemoved() {
		Fixture f = new Fixture();
		LifecycleFormatter formatter = new LifecycleFormatter();
		formatter.onProperties = () -> assertFalse(f.controller.attempt(ID));
		f.controller = new PendingContentChatController(4, f::current, formatter, message -> {
			assertEquals(0, f.controller.pendingCount());
			assertFalse(f.controller.attempt(ID));
			f.controller.reset(); f.sent.add(message);
		});
		f.projection(ID, namedCustom("Selected", true, false));
		assertTrue(f.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
		assertTrue(f.controller.attempt(ID));
		assertEquals(1, formatter.compositions);
		assertEquals(1, f.sent.size());
		assertFalse(f.controller.attempt(ID));
	}

	@Test void throwingSinkCannotLeaveADuplicateReceiptAndThrowingComposerCanRetryFreshData() {
		Fixture f = new Fixture();
		LifecycleFormatter formatter = new LifecycleFormatter();
		f.controller = new PendingContentChatController(4, f::current, formatter, message -> {
			assertFalse(f.controller.attempt(ID));
			throw new IllegalStateException("sink failed");
		});
		f.projection(ID, namedCustom("Selected", true, false));
		assertTrue(f.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
		formatter.onProperties = () -> { throw new IllegalStateException("format failed"); };
		assertFalse(f.controller.attempt(ID));
		assertEquals(1, f.controller.pendingCount());
		formatter.onProperties = () -> {};
		assertThrows(IllegalStateException.class, () -> f.controller.attempt(ID));
		assertEquals(0, f.controller.pendingCount());
		assertFalse(f.controller.attempt(ID));
	}

	@Test void composerRevocationOrValueReplacementIsRevalidatedBeforeEmission() {
		Fixture revoked = new Fixture();
		LifecycleFormatter formatter = new LifecycleFormatter();
		revoked.controller = new PendingContentChatController(4, revoked::current, formatter, revoked.sent::add);
		revoked.projection(ID, namedCustom("Old", true, false));
		assertTrue(revoked.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
		formatter.onProperties = () -> revoked.allowed = Map.of(BASIC, Set.of(PresentationBasic.NAME));
		assertFalse(revoked.controller.attempt(ID));
		assertEquals(0, revoked.controller.pendingCount());
		assertTrue(revoked.sent.isEmpty());

		Fixture replaced = new Fixture();
		LifecycleFormatter changing = new LifecycleFormatter();
		replaced.controller = new PendingContentChatController(4, replaced::current, changing, replaced.sent::add);
		replaced.projection(ID, namedCustom("Old", true, false));
		assertTrue(replaced.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
		changing.onProperties = () -> replaced.projection(ID, namedCustom("New", true, false));
		assertFalse(replaced.controller.attempt(ID));
		assertEquals(1, replaced.controller.pendingCount());
		assertTrue(replaced.sent.isEmpty());
		changing.onProperties = () -> {};
		assertTrue(replaced.controller.attempt(ID));
	}

	@Test void clearAndRebeginDuringComposerCannotEmitTheOldEqualCapture() {
		Fixture f = new Fixture();
		LifecycleFormatter formatter = new LifecycleFormatter();
		f.controller = new PendingContentChatController(4, f::current, formatter, f.sent::add);
		f.projection(ID, namedCustom("Selected", true, false));
		var accepted = f.created(ID, PresentationReceiptContent.properties(List.of(CUSTOM)), "Author");
		assertTrue(f.controller.begin(accepted, true));
		formatter.onProperties = () -> {
			f.controller.clear();
			assertTrue(f.controller.begin(accepted, true));
		};
		assertFalse(f.controller.attempt(ID), "pending identity is reference identity, not record equality");
		assertEquals(1, f.controller.pendingCount());
		assertTrue(f.sent.isEmpty());
		formatter.onProperties = () -> {};
		assertTrue(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
	}

	@Test void inventoryComposerResetAndBaselineReplacementCannotEmitThePreviousCount() {
		Fixture f = new Fixture();
		ClientInventory.EntryView entry = entry(96, false, null, "take");
		f.tracking.put(ID, tracking(ID, entry, true, false, 4, InventoryS2CPacket.Status.READY));
		f.controller = new PendingContentChatController(4, f::current, new LifecycleFormatter() {
			@Override public Optional<Component> inventory(Component author, Component target, ClientInventory.EntryView received) {
				f.controller.reset(); return Optional.of(Component.literal("Old count"));
			}
		}, f.sent::add);
		assertTrue(f.begin(PresentationReceiptContent.inventory()));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.sent.isEmpty());
		assertEquals(0, f.controller.pendingCount());
		f.controller = new PendingContentChatController(4, f::current, new LifecycleFormatter() {
			@Override public Optional<Component> inventory(Component author, Component target, ClientInventory.EntryView received) {
				f.tracking.put(ID, tracking(ID, entry, true, false, 5, InventoryS2CPacket.Status.READY));
				return Optional.of(Component.literal("Old baseline"));
			}
		}, f.sent::add);
		assertTrue(f.begin(PresentationReceiptContent.inventory()));
		assertFalse(f.controller.attempt(ID), "even equal counts on a new baseline require a fresh attempt");
		assertTrue(f.sent.isEmpty());
		assertEquals(1, f.controller.pendingCount());
	}

	@Test void reentrantReadPortClearFencesBeginAndFinalRead() {
		Fixture f = new Fixture();
		int[] reads = {0};
		f.controller = new PendingContentChatController(4, id -> {
			var current = f.current(id);
			if (++reads[0] == 1) f.controller.clear();
			return current;
		}, new LifecycleFormatter(), f.sent::add);
		assertFalse(f.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
		assertEquals(0, f.controller.pendingCount());
		f.projection(ID, namedCustom("Selected", true, false));
		reads[0] = 0;
		f.controller = new PendingContentChatController(4, id -> {
			var current = f.current(id);
			if (++reads[0] == 3) f.controller.clear(); // begin, attempt-before, attempt-after
			return current;
		}, new LifecycleFormatter(), f.sent::add);
		assertTrue(f.begin(PresentationReceiptContent.properties(List.of(CUSTOM))));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.sent.isEmpty());
		assertEquals(0, f.controller.pendingCount());
	}

	@Test void inFlightBoundAlsoSurvivesClearAndCrossMarkerReentrantAttempts() {
		Fixture f = new Fixture();
		MarkerId second = new MarkerId(43);
		f.install(second);
		f.projection(ID, namedCustom("First", true, false));
		f.projection(second, namedCustom("Second", true, false));
		var receipt = PresentationReceiptContent.properties(List.of(CUSTOM));
		LifecycleFormatter formatter = new LifecycleFormatter();
		f.controller = new PendingContentChatController(1, f::current, formatter, f.sent::add);
		formatter.onProperties = () -> {
			f.controller.clear();
			assertTrue(f.controller.begin(f.created(second, receipt, "Second"), true));
			assertFalse(f.controller.attempt(second), "clear does not discard the active attempt bound");
		};
		assertTrue(f.controller.begin(f.created(ID, receipt, "First"), true));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.sent.isEmpty());
		assertEquals(1, f.controller.pendingCount());
		formatter.onProperties = () -> {};
		assertTrue(f.controller.attempt(second));
		assertEquals(1, f.sent.size());
	}

	private static final class Fixture {
		long epoch = 81, view = 1;
		boolean ready = true;
		Map<String, Set<String>> allowed;
		PendingContentChatController.Authorization inventoryAuthorization = ALLOWED;
		final Map<MarkerId, MarkerSnapshot> live = new LinkedHashMap<>();
		final Map<MarkerId, PresentationView> projections = new LinkedHashMap<>();
		final Map<MarkerId, ClientInventory.Tracking> tracking = new LinkedHashMap<>();
		final List<Component> sent = new ArrayList<>();
		PendingContentChatController controller;
		Fixture() { allowAll(); install(ID); controller = production(4, sent::add); }
		void allowAll() {
			allowed = Map.of(BASIC, Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME,
				PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH), CREATE,
				Set.of("create:kinetic.speed", "create:kinetic.stress", "create:kinetic.capacity"));
		}
		void install(MarkerId id) { live.put(id, snapshot(id, 1)); projection(id, name()); }
		void projection(MarkerId id, PresentationSection... sections) {
			Map<String, PresentationSection> values = new LinkedHashMap<>();
			for (var section : sections) values.put(section.adapterId(), section);
			projections.put(id, new PresentationView("entity_block", NAME, values));
		}
		PendingContentChatController.Current current(MarkerId id) {
			MarkerSnapshot marker = live.get(id);
			return new PendingContentChatController.Current(epoch, view, ready, marker != null,
				marker == null ? null : PendingContentChatController.MarkerIdentity.of(marker), allowed,
				inventoryAuthorization, projections.getOrDefault(id, PresentationView.empty()), tracking.get(id));
		}
		PendingContentChatController production(int limit, Consumer<Component> sink) {
			return new PendingContentChatController(limit, this::current,
				ContentChatComposer.nameJsonDecoder(RegistryAccess.EMPTY),
				ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY), ignored -> ContentChatTemplate.DEFAULT_TEMPLATE, sink);
		}
		PresentationS2CPacket created(MarkerId id, PresentationReceiptContent content, String author) {
			return PresentationS2CPacket.created(81, 1, 1, live.get(id), author, NAME, content, name());
		}
		boolean begin(PresentationReceiptContent receipt) { return controller.begin(created(ID, receipt, "Author"), true); }
	}

	/** Lifecycle expectations don't depend on the production composer's internals. */
	private static class LifecycleFormatter implements PendingContentChatController.Formatter {
		Runnable onName = () -> {}, onProperties = () -> {};
		int compositions;
		@Override public Optional<Component> name(String json) { onName.run(); return Optional.of(Component.literal("Decoded")); }
		@Override public Optional<Component> properties(Component author, Component target, PresentationView view,
			List<PresentationPropertyRef> refs) {
			compositions++; onProperties.run(); return Optional.of(Component.literal("Complete"));
		}
		@Override public Optional<Component> inventory(Component author, Component target, ClientInventory.EntryView entry) {
			return Optional.of(Component.literal("Complete"));
		}
	}

	private static MarkerSnapshot snapshot(MarkerId id, long arrival) {
		return new MarkerSnapshot(id, new UUID(1, 2),
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), "entity_block", "attention",
			new MarkerAnchor(1, 2, 3), arrival, 100);
	}
	private static PresentationSection name() {
		return new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text(NAME_JSON)), false);
	}
	private static PresentationSection namedCustom(String selected, boolean annotated, boolean stale) {
		return new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text(NAME_JSON),
			PresentationBasic.CUSTOM_NAME, new PresentationValue.Text(selected)), stale,
			annotated ? Map.of(CUSTOM, "attention") : Map.of());
	}
	private static PresentationSection health(PresentationValue maximum) {
		Map<String, PresentationValue> fields = new LinkedHashMap<>(name().fields());
		fields.put(PresentationBasic.HEALTH, new PresentationValue.NumberValue(7));
		if (maximum != null) fields.put(PresentationBasic.MAX_HEALTH, maximum);
		return new PresentationSection(BASIC, 1, fields, false, Map.of(HEALTH, "danger"));
	}
	private static ClientInventory.EntryView entry(long count, boolean fallback, InventoryS2CPacket.Status quality, String ping) {
		return new ClientInventory.EntryView("selected", "minecraft:gunpowder", "item.minecraft.gunpowder", null,
			count, fallback, quality, ping);
	}
	private static ClientInventory.Tracking tracking(MarkerId id, ClientInventory.EntryView entry, boolean complete,
		boolean grey, long baseline, InventoryS2CPacket.Status status) {
		return new ClientInventory.Tracking(id, status, grey, complete, baseline, 1, 1, List.of(entry));
	}
	private static String flatten(Component component) {
		StringBuilder text = new StringBuilder();
		if (component.getContents() instanceof PlainTextContents plain) text.append(plain.text());
		if (component.getContents() instanceof TranslatableContents translated) {
			text.append(translated.getKey());
			for (Object arg : translated.getArgs()) text.append(arg instanceof Component child ? flatten(child) : arg);
		}
		for (Component sibling : component.getSiblings()) text.append(flatten(sibling));
		return text.toString();
	}
	private static int countKey(Component component, String key) {
		int count = 0;
		if (component.getContents() instanceof TranslatableContents translated) {
			if (key.equals(translated.getKey())) count++;
			for (Object arg : translated.getArgs()) if (arg instanceof Component child) count += countKey(child, key);
		}
		for (Component sibling : component.getSiblings()) count += countKey(sibling, key);
		return count;
	}
	private static String countArgument(Component component) {
		if (component.getContents() instanceof TranslatableContents translated) {
			if (ContentChatTemplate.ITEM_COUNT_KEY.equals(translated.getKey())) return String.valueOf(translated.getArgs()[1]);
			for (Object arg : translated.getArgs()) if (arg instanceof Component child) {
				String found = countArgument(child); if (found != null) return found;
			}
		}
		for (Component sibling : component.getSiblings()) {
			String found = countArgument(sibling); if (found != null) return found;
		}
		return null;
	}
}
