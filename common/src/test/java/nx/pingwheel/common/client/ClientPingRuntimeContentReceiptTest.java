package nx.pingwheel.common.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.chat.ContentChatComposer;
import nx.pingwheel.common.chat.ContentChatTemplate;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationKineticFormat;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationPropertySelection;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationReceiptProjector;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.render.SpatialOverlayRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real packet-dispatched runtime/stores/composer; only game effects and world/input access are ports. */
class ClientPingRuntimeContentReceiptTest {
 private static final String BASIC = PresentationBasic.ID;
 private static final String EXTRA = "test:machine";
 private static final String FIRST = "test:first", SECOND = "test:second";
 private static final MarkerId ID = new MarkerId(42);
 private static final PresentationPropertyRef NAME = PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
 private static final PresentationPropertyRef HEALTH = PresentationPropertyRef.root(BASIC, PresentationBasic.HEALTH);
 private static final PresentationPropertyRef CUSTOM = PresentationPropertyRef.root(BASIC, PresentationBasic.CUSTOM_NAME);
 private static final PresentationPropertyRef EXTRA_FIRST = PresentationPropertyRef.root(EXTRA, FIRST);
 private static final PresentationPropertyRef EXTRA_SECOND = PresentationPropertyRef.root(EXTRA, SECOND);
 private static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
 private static final MarkerSnapshot SNAPSHOT = new MarkerSnapshot(ID, new UUID(1, 2), TARGET, "entity_block", "attention",
  new MarkerAnchor(1, 2, 3), 1, 100);
 private static final Set<String> BASIC_FIELDS = Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME,
  PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH);

 @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

 @Test void ordinaryIsImmediateOnceButPropertyBasicReplacesItAndSoundIsNotDelayed() {
  Fixture f = new Fixture(0);
  f.created(PresentationReceiptContent.whole(), basic(true));
  assertEquals(1, f.feedback.ordinary.size()); assertEquals(1, f.feedback.sounds.size());
  f.created(2, SNAPSHOT, "Replay", PresentationReceiptContent.whole(), basic(true));
  assertEquals(1, f.feedback.ordinary.size()); assertEquals(1, f.feedback.sounds.size());
  MarkerSnapshot other = new MarkerSnapshot(new MarkerId(43), SNAPSHOT.owner(), TARGET, "entity_block", "attention",
   SNAPSHOT.anchor(), 1, 100);
  f.created(1, other, "Selected owner", PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
  assertEquals(1, f.feedback.ordinary.size(), "selected content must not show the old whole line");
  assertEquals(2, f.feedback.sounds.size()); assertEquals(1, f.feedback.content.size());
  assertTrue(f.feedback.content.getFirst().getString().contains("Selected owner"));
  f.section(2, other.id(), basic(true));
  f.created(3, other, "Replay owner", PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
  assertEquals(1, f.feedback.content.size());
  assertTrue(f.runtime.store().renderMarkers().isEmpty(), "zero visual duration does not prevent receipt completion");
  f.assertNoSourceOrNetworkWork();
 }

 @Test void nullableSelectionStaysWholeAndMixedSelectionCarriesOnlyTheAnnotatedRef() {
  // Nullable-only: the real projector keeps the ordinary whole receipt, so the
  // server-shaped unannotated value inserts, sounds, and chats exactly once.
  Fixture whole = new Fixture(0);
  var wholeContent = PresentationReceiptProjector.project(
   List.of(PresentationPropertySelection.of(NAME)), false, false, true, ref -> true);
  assertEquals(PresentationReceiptContent.Kind.WHOLE, wholeContent.kind());
  whole.created(wholeContent, basic(false));
  assertEquals(1, whole.feedback.sounds.size());
  assertEquals(1, whole.feedback.ordinary.size());
  assertTrue(whole.feedback.content.isEmpty());
  assertTrue(whole.runtime.store().marker(ID).isPresent(), "the accepted nullable initial inserts the marker");
  assertEquals(new PresentationValue.Text("{\"text\":\"Chest\"}"), whole.presentation.view(ID).property(NAME));

  // Mixed: only the annotated ref is content authority; the initial is accepted
  // with the nullable value present and unannotated, the whole line stays
  // suppressed, and exactly one content line appears when its data arrives.
  Fixture mixed = new Fixture(0);
  var mixedContent = PresentationReceiptProjector.project(
   List.of(PresentationPropertySelection.of(NAME), PresentationPropertySelection.of(CUSTOM, "request")),
   false, false, true, ref -> true);
  assertEquals(PresentationReceiptContent.Kind.PROPERTIES, mixedContent.kind());
  assertEquals(List.of(CUSTOM), mixedContent.selectedRefs());
  mixed.created(mixedContent, basic(false));
  assertEquals(1, mixed.feedback.sounds.size());
  assertTrue(mixed.feedback.ordinary.isEmpty(), "a content receipt never shows the whole line");
  assertTrue(mixed.feedback.content.isEmpty(), "the annotated value has not arrived yet");
  mixed.section(2, ID, new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME,
   new PresentationValue.Text("{\"text\":\"Chest\"}"), PresentationBasic.CUSTOM_NAME,
   new PresentationValue.Text("Custom")), false, Map.of(CUSTOM, "request")));
  assertEquals(1, mixed.feedback.content.size());
  assertTrue(hasArgument(mixed.feedback.content.getFirst(), "Custom"));
  mixed.section(3, ID, new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME,
   new PresentationValue.Text("{\"text\":\"Chest\"}"), PresentationBasic.CUSTOM_NAME,
   new PresentationValue.Text("Custom")), false, Map.of(CUSTOM, "request")));
  assertEquals(1, mixed.feedback.content.size(), "a repeated section cannot emit a second content line");
  assertEquals(1, mixed.feedback.sounds.size());
  whole.assertNoSourceOrNetworkWork(); mixed.assertNoSourceOrNetworkWork();
 }

 @Test void nonBasicSelectionsArriveSeparatelyAndOnlyTheCompleteCurrentSetEmits() {
  Fixture f = new Fixture(0);
  var content = PresentationReceiptContent.properties(List.of(HEALTH, EXTRA_FIRST, EXTRA_SECOND));
  f.created(content, basic(false));
  assertEquals(1, f.feedback.sounds.size()); assertTrue(f.feedback.ordinary.isEmpty());
  f.section(1, ID, extra(Map.of(FIRST, new PresentationValue.NumberValue(12)), Map.of(EXTRA_FIRST, "danger")));
  f.section(2, ID, basic(true));
  f.tick();
  assertTrue(f.feedback.content.isEmpty(), "one arrived non-Basic ref must not produce a partial list");
  f.section(2, ID, extra(Map.of(FIRST, new PresentationValue.NumberValue(15), SECOND, new PresentationValue.NumberValue(24)),
   Map.of(EXTRA_FIRST, "danger", EXTRA_SECOND, "request")));
  assertEquals(1, f.feedback.content.size());
  assertTrue(hasArgument(f.feedback.content.getFirst(), "15"));
  assertTrue(hasArgument(f.feedback.content.getFirst(), "24"));
  f.section(3, ID, extra(Map.of(FIRST, new PresentationValue.NumberValue(16), SECOND, new PresentationValue.NumberValue(25)),
   Map.of(EXTRA_FIRST, "danger", EXTRA_SECOND, "request")));
  assertEquals(1, f.feedback.content.size()); assertEquals(1, f.feedback.sounds.size());
  f.assertNoSourceOrNetworkWork();
 }

 @Test void multiPropertyReceiptUsesTheLocalizedOuterAndEntryTemplatesThroughTheRuntimeWiring() throws IOException {
  Map<String, String> merged = new LinkedHashMap<>();
  Set<String> selectedLocaleKeys = new HashSet<>();
  try (InputStream stream = ClientPingRuntimeContentReceiptTest.class.getClassLoader()
   .getResourceAsStream("assets/pingforit/lang/zh_cn.json")) {
   assertNotNull(stream);
   Language.loadFromJson(stream, (key, value) -> { merged.put(key, value); selectedLocaleKeys.add(key); });
  }
  PingType attention = type("attention"), danger = type("danger");
  String selectedEntryOverride = ContentChatTemplate.entryOverrideKey(attention);
  merged.put(selectedEntryOverride, "override {type} {content}");
  selectedLocaleKeys.add(selectedEntryOverride);
  // The merged language carries fallback-locale values; the selected-locale-only
  // presence check must never select this en_us-only entry override.
  merged.put(ContentChatTemplate.entryOverrideKey(danger), "en-only {type} {content}");

  Language previous = Language.getInstance();
  Language.inject(new MapLanguage(merged));
  try {
   Fixture f = new Fixture(200, ClientPingRuntime.contentChatTemplates(selectedLocaleKeys::contains));
   f.created(PresentationReceiptContent.properties(List.of(EXTRA_FIRST, EXTRA_SECOND)), basic(false));
   assertEquals(1, f.feedback.sounds.size());
   assertTrue(f.feedback.ordinary.isEmpty(), "a content receipt never shows the whole line");
   assertTrue(f.feedback.content.isEmpty(), "the selected values have not arrived yet");
   assertTrue(f.runtime.store().marker(ID).isPresent(), "the accepted initial inserts the marker");

   f.section(1, ID, extra(Map.of(FIRST, new PresentationValue.NumberValue(12)), Map.of(EXTRA_FIRST, "attention")));
   f.tick();
   assertTrue(f.feedback.content.isEmpty(), "a partial property list never emits");

   f.section(2, ID, extra(Map.of(FIRST, new PresentationValue.NumberValue(12), SECOND, new PresentationValue.NumberValue(15)),
    Map.of(EXTRA_FIRST, "attention", EXTRA_SECOND, "danger")));
   assertEquals(1, f.feedback.content.size(), "the complete set emits exactly one content message");
   Component message = f.feedback.content.getFirst();
   String text = message.getString();
   assertTrue(text.startsWith("Owner: 请求对 Chest 的以下内容执行操作："),
    () -> "the localized outer template owns the list grammar: " + text);
   assertTrue(text.contains("override 注意"), () -> "the selected-locale entry override is used: " + text);
   assertFalse(text.contains("en-only"), () -> "an en_us-only fallback override is never inherited: " + text);
   assertTrue(text.contains("危险 Second：15"), () -> "an absent override falls back to the localized entry: " + text);
   assertEquals(1, occurrences(text, "注意"));
   assertEquals(1, occurrences(text, "危险"));
   assertEquals(1, occurrences(text, "Owner"));
   assertEquals(1, occurrences(text, "Chest"));
   assertOnlyTypesColored(message, null, Map.of("attention", attention, "danger", danger));

   f.section(3, ID, extra(Map.of(FIRST, new PresentationValue.NumberValue(13), SECOND, new PresentationValue.NumberValue(16)),
    Map.of(EXTRA_FIRST, "attention", EXTRA_SECOND, "danger")));
   f.tick();
   assertEquals(1, f.feedback.content.size(), "later updates never repeat the receipt");
   assertEquals(1, f.feedback.sounds.size());
   assertTrue(f.feedback.ordinary.isEmpty());
   f.assertNoSourceOrNetworkWork();
  } finally {
   Language.inject(previous);
  }
 }

 @Test void inventoryPreviewStatusAndPartialBaselineWaitThenComplete96EmitsOnlyOnce() {
  Fixture f = new Fixture(0);
  f.created(PresentationReceiptContent.inventory(), basic(false));
  assertEquals(1, f.feedback.sounds.size()); assertTrue(f.feedback.ordinary.isEmpty());
  long preview = f.inventory.open(TARGET, BlockFace.NORTH, "entity_block");
  f.runtime.onInventoryPacket(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, preview, null,
   2, 1, 1, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of(item(123, 1))).stamp(81, 1));
  assertEquals(123, f.inventory.preview(preview).entries().getFirst().count());
  f.sent.clear(); // Opening the explicit preview above, not receipt processing, owns that OPEN.
  f.runtime.onInventoryPacket(InventoryS2CPacket.status(7, 0, ID, 4, 1, 1,
   InventoryS2CPacket.Status.READY, 0).stamp(81, 1));
  assertTrue(f.feedback.content.isEmpty()); assertFalse(f.inventory.tracking(ID).complete());
  f.runtime.onInventoryPacket(data(InventoryS2CPacket.Kind.SNAPSHOT, 0, 2, 1, List.of(item(96, 1)), 1));
  assertTrue(f.feedback.content.isEmpty()); assertFalse(f.inventory.tracking(ID).complete());
  f.runtime.onInventoryPacket(data(InventoryS2CPacket.Kind.SNAPSHOT, 1, 2, 1, List.of(), 1));
  assertEquals(1, f.feedback.content.size());
  assertEquals("96", countArgument(f.feedback.content.getFirst()));
  f.runtime.onInventoryPacket(data(InventoryS2CPacket.Kind.STREAM, 0, 1, 2, List.of(item(97, 2)), 1));
  assertEquals(97, f.inventory.tracking(ID).entries().getFirst().count());
  f.created(2, SNAPSHOT, "Replay", PresentationReceiptContent.inventory(), basic(false));
  f.tick(); assertEquals(1, f.feedback.content.size()); assertEquals(1, f.feedback.sounds.size());
  f.assertNoSourceOrNetworkWork();
 }

 @Test void resetCancelsImmediatelyAndKnownSameIdReplayCannotRestartOnLateSection() {
  Fixture f = new Fixture(200);
  f.created(PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
  f.runtime.onPresentationPacket(PresentationS2CPacket.reset(81, 1, Map.of()));
  assertEquals(1, f.presentation.sessionView(), "rejected equal-view reset is inert");
  f.reset(2, BASIC_FIELDS);
  f.section(2, ID, basic(true), 1); // old view
  f.created(2, SNAPSHOT, "New owner", PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
  f.section(3, ID, basic(true), 2);
  f.tick(); assertTrue(f.feedback.content.isEmpty()); assertTrue(f.feedback.ordinary.isEmpty());
  assertEquals(1, f.feedback.sounds.size()); f.assertNoSourceOrNetworkWork();
 }

 @Test void expiredVisualRecordRejectsLateInventoryAndCannotRestart() {
  Fixture f = new Fixture(200);
  f.created(PresentationReceiptContent.inventory(), basic(false));
  f.runtime.onPresentationPacket(PresentationS2CPacket.removed(81, 1, ID, MarkerRemovalReason.EXPIRED));
  assertTrue(f.runtime.store().marker(ID).orElseThrow().isStale());
  f.runtime.onInventoryPacket(data(InventoryS2CPacket.Kind.SNAPSHOT, 0, 1, 1, List.of(item(96, 1)), 1));
  f.created(2, SNAPSHOT, "Replay", PresentationReceiptContent.inventory(), basic(false));
  assertTrue(f.feedback.content.isEmpty()); assertFalse(f.inventory.tracking(ID).complete());
  assertEquals(1, f.feedback.sounds.size()); f.assertNoSourceOrNetworkWork();
 }

 @Test void hardRemovalEvictionAndDisconnectFenceLateCompletion() {
  Fixture hard = new Fixture(200);
  hard.created(PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
  hard.runtime.applyRemoved(ID, MarkerRemovalReason.CANCELLED);
  hard.section(2, ID, basic(true));
  assertTrue(hard.runtime.store().marker(ID).isEmpty()); assertTrue(hard.feedback.content.isEmpty());

  Fixture evicted = new Fixture(200);
  evicted.created(PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
  evicted.runtime.store().clear(); evicted.presentation.evict(ID);
  evicted.tick();
  evicted.created(2, SNAPSHOT, "Replay", PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
  assertTrue(evicted.runtime.store().marker(ID).isEmpty()); assertTrue(evicted.feedback.content.isEmpty());

  Fixture disconnected = new Fixture(200);
  disconnected.created(PresentationReceiptContent.inventory(), basic(false));
  disconnected.runtime.close();
  disconnected.runtime.onInventoryPacket(data(InventoryS2CPacket.Kind.SNAPSHOT, 0, 1, 1, List.of(item(96, 1)), 1));
  disconnected.section(2, ID, basic(true));
  assertTrue(disconnected.feedback.content.isEmpty()); assertFalse(disconnected.presentation.ready());
  hard.assertNoSourceOrNetworkWork(); evicted.assertNoSourceOrNetworkWork(); disconnected.assertNoSourceOrNetworkWork();
 }

 @Test void fallbackStaleAndFinalDeletionCancelButShortVisualDeadlineDoesNot() {
  Fixture f = new Fixture(300);
  f.created(PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
  for (int i = 0; i < 139; i++) f.tick();
  assertTrue(f.runtime.store().marker(ID).orElseThrow().isStale());
  f.section(2, ID, basic(true));
  assertTrue(f.feedback.content.isEmpty(), "fallback stale records are not current server-live receipt inputs");
  for (int i = 139; i < 300; i++) f.tick();
  assertTrue(f.runtime.store().marker(ID).isEmpty());
  f.created(3, SNAPSHOT, "Replay", PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
  assertTrue(f.runtime.store().marker(ID).isEmpty(), "local final removal preserves the presentation tombstone");
  f.assertNoSourceOrNetworkWork();
 }

 @Test void authorIsRetainedWhileSameIdExternalLocatorAndAuthorRefreshUseCurrentValues() {
  Fixture f = new Fixture(0);
  Target first = Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "tracking", "minecraft:chest", "a", true);
  Target moved = Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "tracking", "minecraft:chest", "b", true);
  MarkerSnapshot original = new MarkerSnapshot(ID, SNAPSHOT.owner(), first, "entity_block", "attention", SNAPSHOT.anchor(), 1, 100);
  MarkerSnapshot refreshed = new MarkerSnapshot(ID, SNAPSHOT.owner(), moved, "entity_block", "attention", new MarkerAnchor(4, 5, 6), 1, 100);
  f.created(1, original, "Original author", PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
  f.created(2, refreshed, "Refreshed author", PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
  assertEquals("b", ((Target.ExternalBlockTarget) f.runtime.store().marker(ID).orElseThrow().target()).providerLocator());
  assertEquals(1, f.feedback.content.size());
  assertTrue(f.feedback.content.getFirst().getString().contains("Original author"));
  assertFalse(f.feedback.content.getFirst().getString().contains("Refreshed author"));
  assertEquals(1, f.feedback.sounds.size()); f.assertNoSourceOrNetworkWork();
 }

 @Test void missingAuthorizedNameWaitsButPolicyRevocationAndUnavailableGameCancel() {
  Fixture f = new Fixture(0);
  f.created(PresentationReceiptContent.properties(List.of(HEALTH)), healthOnly());
  assertTrue(f.feedback.content.isEmpty());
  f.section(2, ID, basic(true)); assertEquals(1, f.feedback.content.size());

  Fixture denied = new Fixture(200);
  denied.created(PresentationReceiptContent.inventory(), basic(false));
  assertEquals(Optional.of(true), denied.inventory.authorization(81, 1, "entity_block"));
  denied.runtime.onInventoryPacket(InventoryS2CPacket.policy(7, 81, 1, Set.of()));
  assertEquals(Optional.of(false), denied.inventory.authorization(81, 1, "entity_block"));
  denied.runtime.onInventoryPacket(InventoryS2CPacket.policy(7, 81, 1, Set.of("entity_block")));
  denied.runtime.onInventoryPacket(data(InventoryS2CPacket.Kind.SNAPSHOT, 0, 1, 1, List.of(item(96, 1)), 1));
  assertTrue(denied.feedback.content.isEmpty(), "re-allow does not restart a revoked pending receipt");
  assertTrue(denied.inventory.authorization(82, 1, "entity_block").isEmpty());
  assertTrue(denied.inventory.authorization(81, 2, "entity_block").isEmpty());

  Fixture absent = new Fixture(200);
  absent.created(PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
  absent.feedback.available = false; absent.tick(); absent.feedback.available = true;
  absent.section(2, ID, basic(true)); assertTrue(absent.feedback.content.isEmpty());
  f.assertNoSourceOrNetworkWork(); denied.assertNoSourceOrNetworkWork(); absent.assertNoSourceOrNetworkWork();
 }

 @Test void suppressedHasSoundButNoChatAndMalformedSelectionCannotPartiallyMutateRuntime() {
  Fixture f = new Fixture(200);
  f.created(PresentationReceiptContent.suppressed(), basic(true));
  assertEquals(1, f.feedback.sounds.size()); assertTrue(f.feedback.ordinary.isEmpty()); assertTrue(f.feedback.content.isEmpty());
  Fixture malformed = new Fixture(200);
  malformed.reset(2, Set.of(PresentationBasic.NAME));
  malformed.created(PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
  assertTrue(malformed.runtime.store().allMarkers().isEmpty());
  assertTrue(malformed.runtime.nameStore().find(ID).isEmpty());
  assertTrue(malformed.presentation.view(ID).sections().isEmpty());
  assertTrue(malformed.feedback.sounds.isEmpty()); assertTrue(malformed.feedback.content.isEmpty());
  f.assertNoSourceOrNetworkWork(); malformed.assertNoSourceOrNetworkWork();
 }

 @Test void currentNameAndCustomRawTextAreDecodedAndMissingAnnotationDoesNotComplete() {
  Fixture f = new Fixture(200);
  var custom = PresentationPropertyRef.root(BASIC, PresentationBasic.CUSTOM_NAME);
  f.created(PresentationReceiptContent.properties(List.of(custom)), basic(false));
  f.section(2, ID, new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME,
   new PresentationValue.Text("{\"text\":\"Named (Chest)\"}"), PresentationBasic.CUSTOM_NAME,
   new PresentationValue.Text("a{b}%s")), false));
  assertTrue(f.feedback.content.isEmpty(), "arrived values without annotations cannot complete");
  f.section(3, ID, new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME,
   new PresentationValue.Text("{\"text\":\"Named (Chest)\"}"), PresentationBasic.CUSTOM_NAME,
   new PresentationValue.Text("a{b}%s")), false, Map.of(custom, "request")));
  assertEquals(1, f.feedback.content.size());
  Component message = f.feedback.content.getFirst();
  assertTrue(message.getString().contains("Named (Chest)"));
  assertFalse(message.getString().contains("{\"text\""));
  assertTrue(hasArgument(message, "a{b}%s"), "untrusted custom text stays raw inside the localized quote component");
  assertEquals(1, translationCount(message, ContentChatTemplate.QUOTE_KEY));
  assertEquals(1, translationCount(message, "presentation.pingforit.type.request.display"));
  assertEquals(0, translationCount(message, "presentation.pingforit.type.attention.display"));
  f.assertNoSourceOrNetworkWork();
 }

 @Test void staleEpochViewAndRevisionCannotCompleteAndLongInventoryCountStaysExact() {
  Fixture f = new Fixture(200);
  f.created(PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
  f.runtime.onPresentationPacket(PresentationS2CPacket.section(82, 1, 2, ID, basic(true)));
  f.section(2, ID, basic(true), 0);
  f.section(1, ID, basic(true));
  assertTrue(f.feedback.content.isEmpty(), "unaccepted values cannot complete a receipt");
  assertNull(f.presentation.view(ID).property(HEALTH));
  f.section(2, ID, basic(true)); assertEquals(1, f.feedback.content.size());

  Fixture inventory = new Fixture(200);
  inventory.created(PresentationReceiptContent.inventory(), basic(false));
  long exact = 9_007_199_254_740_993L;
  inventory.runtime.onInventoryPacket(data(InventoryS2CPacket.Kind.SNAPSHOT, 0, 1, 1, List.of(item(exact, 1)), 0));
  assertTrue(inventory.feedback.content.isEmpty());
  inventory.runtime.onInventoryPacket(data(InventoryS2CPacket.Kind.SNAPSHOT, 0, 1, 1, List.of(item(exact, 1)), 1));
  assertEquals(Long.toString(exact), countArgument(inventory.feedback.content.getFirst()));
  assertEquals(exact, inventory.inventory.tracking(ID).entries().getFirst().count());
  f.assertNoSourceOrNetworkWork(); inventory.assertNoSourceOrNetworkWork();
 }

 @Test void optionalStressCapacityIsNotRequiredAndCurrentMaskNeverExposesDeniedCapacity() {
  Fixture f = new Fixture(200);
  f.runtime.onPresentationPacket(PresentationS2CPacket.reset(81, 2, Map.of("entity_block", Map.of(BASIC, BASIC_FIELDS,
   EXTRA, Set.of(PresentationKineticFormat.STRESS_FIELD)))));
  var stress = PresentationPropertyRef.root(EXTRA, PresentationKineticFormat.STRESS_FIELD);
  f.created(PresentationReceiptContent.properties(List.of(stress)), basic(false));
  f.section(1, ID, extra(Map.of(PresentationKineticFormat.STRESS_FIELD, new PresentationValue.NumberValue(12),
   PresentationKineticFormat.CAPACITY_FIELD, new PresentationValue.NumberValue(10)), Map.of(stress, "request")));
  assertEquals(1, f.feedback.content.size());
  assertEquals(1, translationCount(f.feedback.content.getFirst(), "presentation.pingforit.format.su"));
  assertEquals(0, translationCount(f.feedback.content.getFirst(), "presentation.pingforit.format.su_percent"));
  assertNull(f.presentation.view(ID).field(EXTRA, PresentationKineticFormat.CAPACITY_FIELD));
  f.assertNoSourceOrNetworkWork();
 }

 @Test void synchronizedSiblingSupersedesStalePendingWithoutLeakingItsLateValues() {
  Fixture f = new Fixture(300);
  f.created(PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
  // Exercise the onCreated cleanup barrier before the next tick reconciliation.
  f.runtime.store().expireFallback(139);
  assertTrue(f.runtime.store().marker(ID).orElseThrow().isStale());
  var sibling = new MarkerSnapshot(new MarkerId(43), SNAPSHOT.owner(), TARGET, "entity_block", "attention",
   SNAPSHOT.anchor(), 2, 101);
  f.created(1, sibling, "Sibling owner", PresentationReceiptContent.whole(), basic(false));
  assertTrue(f.runtime.store().marker(ID).isEmpty());
  f.section(2, ID, basic(true));
  f.created(3, SNAPSHOT, "Late owner", PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
  assertTrue(f.feedback.content.isEmpty());
  assertEquals(1, f.feedback.ordinary.size()); assertEquals(2, f.feedback.sounds.size());
  f.assertNoSourceOrNetworkWork();
 }

 private static final class Fixture {
  final List<IPacket> sent = new ArrayList<>();
  final Feedback feedback = new Feedback();
  final Access access = new Access();
  final ClientPresentation presentation = new ClientPresentation(sent::add, List.of(new ExtraAdapter()));
  final ClientInventory inventory = new ClientInventory(sent::add);
  final ClientPingRuntime runtime;
  Fixture(long visualDuration) { this(visualDuration, null); }
  Fixture(long visualDuration, ContentChatComposer.TemplateSource templates) {
   feedback.templates = templates;
   runtime = ClientPingRuntime.createForInteraction((key, color) -> fail("unexpected local error"), sent::add,
    ClientRateLimitPolicy.DEFAULT, () -> 0, target -> TargetValidation.valid(), access, presentation, inventory, null,
    snapshot -> visualDuration, feedback);
   presentation.tick(true);
   runtime.onPresentationPacket(PresentationS2CPacket.offer(81, Map.of(BASIC, PresentationBasic.fields(),
    EXTRA, new ExtraAdapter().fields()), Map.of(BASIC, 1, EXTRA, 1)));
   reset(1, BASIC_FIELDS);
   runtime.onInventoryPacket(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(10, 20, 3, 0)).stamp(81, 1));
   runtime.onInventoryPacket(InventoryS2CPacket.policy(7, 81, 1, Set.of("entity_block")));
   assertTrue(presentation.ready()); assertTrue(inventory.ready()); sent.clear();
  }
  void reset(long view, Set<String> basicFields) {
   runtime.onPresentationPacket(PresentationS2CPacket.reset(81, view,
    Map.of("entity_block", Map.of(BASIC, basicFields, EXTRA, Set.of(FIRST, SECOND,
     PresentationKineticFormat.STRESS_FIELD, PresentationKineticFormat.CAPACITY_FIELD)))));
  }
  void created(PresentationReceiptContent content, PresentationSection basic) { created(1, SNAPSHOT, "Owner", content, basic); }
  void created(long revision, MarkerSnapshot marker, String author, PresentationReceiptContent content, PresentationSection basic) {
   runtime.onPresentationPacket(PresentationS2CPacket.created(81, presentation.sessionView(), revision, marker, author, NAME, content, basic));
  }
  void section(long revision, MarkerId id, PresentationSection section) { section(revision, id, section, presentation.sessionView()); }
  void section(long revision, MarkerId id, PresentationSection section, long view) {
   runtime.onPresentationPacket(PresentationS2CPacket.section(81, view, revision, id, section));
  }
  void tick() { runtime.onTick(); }
  void assertNoSourceOrNetworkWork() { assertEquals(0, access.captures); assertTrue(sent.isEmpty(), "receipt callbacks never send commands"); }
 }

 private static final class Feedback implements ClientPingRuntime.PresentationReceiptFeedback {
  boolean available = true;
  ContentChatComposer.TemplateSource templates;
  final List<MarkerSnapshot> sounds = new ArrayList<>(), ordinary = new ArrayList<>();
  final List<Component> content = new ArrayList<>();
  public void play(MarkerSnapshot snapshot) { sounds.add(snapshot); }
  public void chat(String author, MarkerSnapshot marker, Component name) { ordinary.add(marker); }
  public boolean ready() { return available; }
  public Optional<Component> name(String json) { return ContentChatComposer.nameJsonDecoder(RegistryAccess.EMPTY).apply(json); }
  public Optional<Component> item(String json) { return ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(json); }
  public String template(PingType type) { return ContentChatTemplate.DEFAULT_TEMPLATE; }
  public ContentChatComposer.TemplateSource templates() {
   return templates == null ? this::template : templates;
  }
  public void content(Component message) { content.add(message); }
 }

 private static final class Access implements ClientPingRuntime.InteractionAccess {
  final Object world = new Object(); int captures;
  public Lifecycle lifecycle() { return new Lifecycle(world, "minecraft:overworld", null, true, true, false); }
  public Optional<CapturedRay> capturePressRay() { return Optional.empty(); }
  public void capture(InteractionToken token, CapturedRay ray, ClientPingRuntime.CaptureCompletion completion) { captures++; fail("receipt recaptured"); }
  public CancellationContext cancellation(CapturedRay ray) { throw new AssertionError("receipt cancellation read"); }
  public void syncMouse(PingInteractionPhase phase) {}
  public void disposeMouse(boolean screenTransition) {}
  public Optional<NativeSelectorInput.Frame> inputFrame() { return Optional.empty(); }
  public SpatialSelectorSession.ListGeometry listGeometry(SpatialOverlayRenderer.Style style) { throw new AssertionError("receipt selector read"); }
  public void resetInput() {}
  public void applyToggle(SelectorIntent.CaptureToggle toggle, long timeMillis) { fail("receipt toggle"); }
 }

 private static final class ExtraAdapter implements PresentationAdapter {
  public String adapterId() { return EXTRA; }
  public String modId() { return "test"; }
  public int schema() { return 1; }
  public int minUpdateIntervalTicks() { return 1; }
  public List<PresentationField> fields() { return List.of(new PresentationField(FIRST, PresentationField.Kind.NUMBER, true, 0, "First"),
   new PresentationField(SECOND, PresentationField.Kind.NUMBER, true, 0, "Second"),
   new PresentationField(PresentationKineticFormat.STRESS_FIELD, PresentationField.Kind.NUMBER, true, 0, "Stress"),
   new PresentationField(PresentationKineticFormat.CAPACITY_FIELD, PresentationField.Kind.NUMBER, true, 0, "Capacity")); }
  public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) { throw new AssertionError("client collected"); }
 }

 private static PresentationSection basic(boolean health) {
  Map<String, PresentationValue> fields = new LinkedHashMap<>();
  fields.put(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Chest\"}"));
  if (health) fields.putAll(healthOnly().fields());
  return new PresentationSection(BASIC, 1, fields, false, health ? Map.of(HEALTH, "request") : Map.of());
 }
 private static PresentationSection healthOnly() {
  return new PresentationSection(BASIC, 1, Map.of(PresentationBasic.HEALTH, new PresentationValue.NumberValue(7),
   PresentationBasic.MAX_HEALTH, new PresentationValue.NumberValue(20)), false, Map.of(HEALTH, "request"));
 }
 private static PresentationSection extra(Map<String, PresentationValue> fields, Map<PresentationPropertyRef, String> annotations) {
  return new PresentationSection(EXTRA, 1, fields, false, annotations);
 }
 private static InventoryS2CPacket.Entry item(long count, long revision) {
  return new InventoryS2CPacket.Entry("selected", "minecraft:gunpowder", "item.minecraft.gunpowder", null,
   count, revision, false, InventoryS2CPacket.Status.READY, 0, false, "take");
 }
 private static InventoryS2CPacket data(InventoryS2CPacket.Kind kind, int part, int total, long watermark,
  List<InventoryS2CPacket.Entry> entries, long view) {
  return InventoryS2CPacket.data(kind, 7, 0, ID, 4, 1, watermark, part, total, true,
   part == total - 1 ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UPDATING, 0, entries).stamp(81, view);
 }
 private static String countArgument(Component component) {
  if (component.getContents() instanceof TranslatableContents translated) {
   if (ContentChatTemplate.ITEM_COUNT_KEY.equals(translated.getKey())) return String.valueOf(translated.getArgs()[1]);
   for (Object arg : translated.getArgs()) if (arg instanceof Component child) {
    String found = countArgument(child); if (found != null) return found;
   }
  }
  for (Component sibling : component.getSiblings()) { String found = countArgument(sibling); if (found != null) return found; }
  return null;
 }
 private static boolean hasArgument(Component component, String expected) {
  if (component.getContents() instanceof TranslatableContents translated) {
   for (Object arg : translated.getArgs()) {
    if (arg instanceof Component child ? expected.equals(child.getString()) || hasArgument(child, expected)
     : expected.equals(String.valueOf(arg))) return true;
   }
  }
  return component.getSiblings().stream().anyMatch(child -> hasArgument(child, expected));
 }
 private static int translationCount(Component component, String key) {
  int count = 0;
  if (component.getContents() instanceof TranslatableContents translated) {
   if (key.equals(translated.getKey())) count++;
   for (Object arg : translated.getArgs()) if (arg instanceof Component child) count += translationCount(child, key);
  }
  for (Component sibling : component.getSiblings()) count += translationCount(sibling, key);
  return count;
 }
 private static PingType type(String id) {
  return PingTypeCatalog.builtIn().findById(id).orElseThrow();
 }
 private static int occurrences(String text, String value) {
  int count = 0;
  for (int index = text.indexOf(value); index >= 0; index = text.indexOf(value, index + value.length())) count++;
  return count;
 }
 /** Every component carries no effective color except its own annotation display word. */
 private static void assertOnlyTypesColored(Component component, Integer inherited, Map<String, PingType> annotations) {
  Integer effective = component.getStyle().getColor() == null
   ? inherited : Integer.valueOf(component.getStyle().getColor().getValue());
  PingType annotation = component.getContents() instanceof TranslatableContents contents
   ? annotations.values().stream().filter(candidate -> contents.getKey()
    .equals("presentation.pingforit.type." + candidate.id() + ".display")).findFirst().orElse(null) : null;
  if (annotation != null) assertEquals(annotation.textColor(), effective, "each annotation word has its own color");
  else assertNull(effective, () -> "unexpected effective color on: " + component.getContents());

  if (component.getContents() instanceof TranslatableContents contents)
   for (Object arg : contents.getArgs())
    if (arg instanceof Component child) assertOnlyTypesColored(child, effective, annotations);
  for (Component sibling : component.getSiblings()) assertOnlyTypesColored(sibling, effective, annotations);
 }
 private static final class MapLanguage extends Language {
  private final Map<String, String> translations;
  private MapLanguage(Map<String, String> translations) { this.translations = translations; }
  @Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
  @Override public boolean has(String key) { return translations.containsKey(key); }
  @Override public boolean isDefaultRightToLeft() { return false; }
  @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
 }
}
