package nx.pingwheel.common.client;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.preview.ClientPresentationPreview;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import nx.pingwheel.common.render.SpatialInventoryView.Status;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import static org.junit.jupiter.api.Assertions.*;

class NativeSelectorContentTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static CapturedPingContext capture() {
		var snapshot = TargetSnapshotFactory.block("minecraft:overworld", 1, 2, 3, "minecraft:chest", true, BlockFace.NORTH);
		var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()).resolve(snapshot.target(), snapshot.matchContext());
		return new CapturedPingContext(new ActiveInteraction().begin(), resolved, nx.pingwheel.common.interaction.CapturedRay.defaultRay(),
			Optional.empty(), snapshot.blockHitFace());
	}
	private static void ready(ClientInventory inventory) {
		inventory.presentationReset(100, 1);
		inventory.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1));
		inventory.accept(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block")));
	}
	private static InventoryS2CPacket.Entry entry(String key, long count, boolean folded, long group) {
		return new InventoryS2CPacket.Entry(key, "minecraft:stone", "Stone", null, count, 1, folded,
			folded ? InventoryS2CPacket.Status.COMPONENT_TOO_LONG : null, group, folded, null);
	}
	@Test void realPreviewKeepsOpaqueRowsNullQualityAndFoldReplacementResetsWithoutFakeData() {
		List<IPacket> sent = new ArrayList<>(); var inventory = new ClientInventory(sent::add); ready(inventory);
		var capture = capture();
		var content = new NativeSelectorContent(capture, null, () -> inventory,
			ignored -> List.of(PingTypeCatalog.builtIn().findById("attention").orElseThrow()), ref -> Component.literal(ref.fieldId()), json -> null);
		content.begin(new Object()); long request = content.requestId();
		var target = new SpatialSelectorSession.CapturedTarget("ordinary", capture.resolvedTarget(), capture.blockHitFace(), Optional.empty());
		var fence = new SpatialSelectorSession.ContentFence(1, request, 1, "ordinary");
		var initial = content.read(target, fence);
		assertTrue(initial.inventory().rows().isEmpty()); assertEquals(Status.UPDATING, initial.inventory().status());
		inventory.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 9, 1, 1, 0, 1, true,
			InventoryS2CPacket.Status.READY, 0, List.of(entry("exact", 0, false, 1))).stamp(100, 1));
		var exact = content.read(target, fence); assertEquals(Status.READY, exact.inventory().rows().getFirst().quality());
		assertEquals(0L, exact.inventory().rows().getFirst().count());
		assertEquals(inventory.selectable(request).getFirst(), exact.inventory().rows().getFirst().reference());
		inventory.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 9, 1, 2, 0, 1, true,
			InventoryS2CPacket.Status.UNCERTAIN, 0, List.of(entry("folded", 7, true, 2))).stamp(100, 1));
		var folded = content.read(target, fence); assertTrue(folded.reset(), "same-baseline group replacement cannot leave a removed exact row in facade");
		assertEquals(List.of("folded"), folded.inventory().rows().stream().map(SpatialSelectorSession.InventoryRow::key).toList());
		assertEquals(Status.COMPONENT_TOO_LONG, folded.inventory().rows().getFirst().quality());
		content.close(); assertEquals(0, inventory.stats().previewChannels()); assertNull(content.read(target, fence));
	}
	@Test void abortReenteredDuringOpenClosesTheExactNewRequestWithoutRevival() {
		NativeSelectorContent[] owner = new NativeSelectorContent[1]; AtomicBoolean closeOnOpen = new AtomicBoolean();
		List<IPacket> sent = new ArrayList<>(); var inventory = new ClientInventory(packet -> {
			sent.add(packet); if (closeOnOpen.get() && packet instanceof InventoryC2SPacket p && p.kind() == InventoryC2SPacket.Kind.OPEN) owner[0].close();
		}); ready(inventory); var capture = capture();
		owner[0] = new NativeSelectorContent(capture, null, () -> inventory,
			ignored -> List.of(PingTypeCatalog.builtIn().findById("attention").orElseThrow()), ref -> Component.literal(ref.fieldId()), json -> null);
		closeOnOpen.set(true); owner[0].begin(new Object());
		assertEquals(0, inventory.stats().previewChannels());
		assertEquals(1, sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.CLOSE).count());
	}
	@Test void nullLabelIsEmptyAndUnknownKeyKeepsTranslationFallback() {
		var content = new NativeSelectorContent(capture(), null, () -> null, ignored -> List.of(),
			ref -> Component.literal(ref.fieldId()), json -> null);
		Component back = assertDoesNotThrow(() -> content.label(null));
		assertEquals("", back.getString(), "a defensive null label must resolve to an empty component");
		assertFalse(back.getContents() instanceof TranslatableContents, "a null label must not fabricate a translation key");
		Component unknown = content.label("pingforit.selector.unknown.key");
		assertInstanceOf(TranslatableContents.class, unknown.getContents(), "an unknown non-empty key keeps the translation fallback");
		assertEquals("pingforit.selector.unknown.key", ((TranslatableContents) unknown.getContents()).getKey());
	}
	@Test void controllerAutomaticBackEntryResolvesThroughTheContentAdapterAsLocalizedKey() {
		SpatialMenu root = SpatialMenu.of("root", SpatialMenu.Choice.branch("content", "content",
			SpatialMenu.of("content", SpatialMenu.Choice.leaf("content:items", "items", "ping:attention"))).withSector(0.0, 360.0));
		SpatialController controller = new SpatialController(root, new SpatialController.Tuning(36.0, 110.0, 200L, false, 500L));
		controller.start(0L);
		controller.movePhysical(0.0, -200.0, 10L);
		controller.tick(210L);
		assertEquals(2, controller.snapshot().menus().size(), "helper precondition: content entered");
		SpatialController.ChoiceView back = controller.snapshot().menus().get(1).choices().stream()
			.filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		assertEquals("pingforit.spatial.back", back.label(), "the controller-generated Back entry carries a localized key");
		var content = new NativeSelectorContent(capture(), null, () -> null, ignored -> List.of(),
			ref -> Component.literal(ref.fieldId()), json -> null);
		Component resolved = content.label(back.label());
		assertInstanceOf(TranslatableContents.class, resolved.getContents());
		assertEquals("pingforit.spatial.back", ((TranslatableContents) resolved.getContents()).getKey());
		controller.cancel();
	}

	private static final String CREATE = "create:presentation";
	private static final String SPEED = "create:kinetic.speed";
	private static final String STRESS = "create:kinetic.stress";
	private static final String CAPACITY = "create:kinetic.capacity";

	private static final class PreviewContext implements PreviewFieldAccess.ReadContext {
		private final Object level = new Object();
		private long tick;
		@Override public Object levelIdentity() { return level; }
		@Override public String dimensionId() { return "minecraft:overworld"; }
		@Override public long tick() { return tick; }
	}

	private static PresentationPreviewAccess previewAccess(Set<String> fields) {
		Map<String, PresentationField> descriptors = new LinkedHashMap<>();
		for (String field : fields)
			descriptors.put(field, new PresentationField(field, PresentationField.Kind.NUMBER, true, 0, field));
		return new PresentationPreviewAccess(1, 1, "entity_block",
			Map.of(CREATE, new PresentationPreviewAccess.Adapter(1, descriptors)));
	}

	private static PreviewFieldAccess previewReader(Map<String, Double> values, List<Set<String>> demands) {
		return new PreviewFieldAccess() {
			@Override public String adapterId() { return CREATE; }
			@Override public Map<String, Outcome> observe(Target target, Set<String> fields, ReadContext context) {
				demands.add(Set.copyOf(fields));
				Map<String, Outcome> result = new LinkedHashMap<>();
				for (String field : fields) {
					Double value = values.get(field);
					if (value != null) result.put(field, new Observed(new PreviewObservation(
						new PresentationValue.NumberValue(value), PreviewObservation.Origin.CLIENT_SYNCED,
						context.tick(), false)));
				}
				return result;
			}
		};
	}

	private static SpatialSelectorSession.CapturedTarget previewTarget(CapturedPingContext capture) {
		return new SpatialSelectorSession.CapturedTarget("ordinary", capture.resolvedTarget(),
			capture.blockHitFace(), Optional.empty());
	}

	private static SpatialSelectorSession.ContentFence previewFence(NativeSelectorContent content) {
		return new SpatialSelectorSession.ContentFence(1, content.requestId(), 1, "ordinary");
	}

	private static SpatialSelectorSession.Property projectedProperty(
		SpatialSelectorSession.ContentProjection<ClientInventory.PreviewEntryReference> projection, String fieldId) {
		return projection.properties().stream().filter(property -> property.ref().fieldId().equals(fieldId))
			.findFirst().orElseThrow();
	}

	private static SpatialSelectorSession.Property projectedEntry(
		SpatialSelectorSession.ContentProjection<ClientInventory.PreviewEntryReference> projection,
		String fieldId, List<String> recordPath) {
		return projection.properties().stream().filter(property -> property.ref().fieldId().equals(fieldId)
			&& property.ref().recordPath().equals(recordPath)).findFirst().orElseThrow();
	}

	private static void assertKineticValue(Component label, String key, String... args) {
		Component value = label.getSiblings().getLast();
		assertInstanceOf(TranslatableContents.class, value.getContents());
		TranslatableContents contents = (TranslatableContents) value.getContents();
		assertEquals("presentation.pingforit.format." + key, contents.getKey());
		assertArrayEquals(args, contents.getArgs());
	}

	private static PresentationPreviewAccess previewRecordAccess(String field) {
		return new PresentationPreviewAccess(1, 1, "entity_block", Map.of(CREATE,
			new PresentationPreviewAccess.Adapter(1,
				Map.of(field, new PresentationField(field, PresentationField.Kind.RECORD, true, 0, field)))));
	}

	private static PreviewFieldAccess previewValueReader(Map<String, PresentationValue> values) {
		return previewValueReader(CREATE, values);
	}

	private static PreviewFieldAccess previewValueReader(String adapterId, Map<String, PresentationValue> values) {
		return new PreviewFieldAccess() {
			@Override public String adapterId() { return adapterId; }
			@Override public Map<String, Outcome> observe(Target target, Set<String> fields, ReadContext context) {
				Map<String, Outcome> result = new LinkedHashMap<>();
				for (String field : fields) {
					PresentationValue value = values.get(field);
					if (value != null) result.put(field, new Observed(new PreviewObservation(value,
						PreviewObservation.Origin.CLIENT_SYNCED, context.tick(), false)));
				}
				return result;
			}
		};
	}

	private static PresentationValue speedRecord(double effectiveRpm, double theoreticalRpm, boolean moving) {
		return new PresentationValue.RecordValue(Map.of(
			"effective_rpm", new PresentationValue.NumberValue(effectiveRpm),
			"theoretical_rpm", new PresentationValue.NumberValue(theoreticalRpm),
			"moving", new PresentationValue.Flag(moving)));
	}

	/** One locally observed speed projection through the production content bridge. */
	private static final class SpeedPreview {
		private final List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		private final PreviewContext context = new PreviewContext();
		private final CapturedPingContext capture = capture();
		private final NativeSelectorContent content;
		private final SpatialSelectorSession.ContentProjection<ClientInventory.PreviewEntryReference> projection;

		SpeedPreview(PresentationValue speed) {
			var preview = new ClientPresentationPreview(type -> Optional.of(previewRecordAccess(SPEED)),
				() -> context, List.of(previewValueReader(Map.of(SPEED, speed))),
				(target, type) -> Optional.empty(), sent::add);
			content = new NativeSelectorContent(capture, preview, () -> null, ignored -> List.of(),
				ref -> Component.literal(ref.fieldId()), json -> null);
			content.begin(context.level);
			assertTrue(sent.isEmpty(), "a locally observed speed record needs no preview request");
			projection = content.read(previewTarget(capture), previewFence(content));
			assertNotNull(projection);
		}

		String label(List<String> recordPath) {
			var property = projectedEntry(projection, SPEED, recordPath);
			return content.label(property.labelKey()).getString();
		}

		void close() {
			content.close();
			assertTrue(sent.isEmpty(), "reading the local speed projection must not add another preview request");
		}
	}

	private static void withSpeedPreview(PresentationValue speed, Consumer<SpeedPreview> body) {
		SpeedPreview fixture = new SpeedPreview(speed);
		try {
			body.accept(fixture);
		} finally {
			fixture.close();
		}
	}

	@Test void realPreviewFormatsStressWithTheSameProjectionCapacityAndNoExtraRequest() {
		List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		var context = new PreviewContext();
		var capture = capture();
		Map<String, Double> values = new LinkedHashMap<>();
		values.put(STRESS, 12.0);
		values.put(CAPACITY, 10.0);
		var preview = new ClientPresentationPreview(type -> Optional.of(previewAccess(Set.of(STRESS, CAPACITY))),
			() -> context, List.of(previewReader(values, new ArrayList<>())),
			(target, type) -> Optional.empty(), sent::add);
		var content = new NativeSelectorContent(capture, preview, () -> null, ignored -> List.of(),
			ref -> Component.literal(ref.fieldId()), json -> null);
		content.begin(context.level);
		assertTrue(sent.isEmpty(), "a locally observed kinetic pair needs no preview request");
		var projection = content.read(previewTarget(capture), previewFence(content));
		assertNotNull(projection);
		var stress = projectedProperty(projection, STRESS);
		assertKineticValue(content.label(stress.labelKey()), "su_percent", "12", "120");
		var capacity = projectedProperty(projection, CAPACITY);
		assertKineticValue(content.label(capacity.labelKey()), "su", "10");
		assertTrue(sent.isEmpty(), "reading the projection must not add another preview request");
		content.close();
	}

	@Test void percentContextAppearsOnlyAfterTheAuthorizedCapacityIsAccepted() {
		List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		var context = new PreviewContext();
		var capture = capture();
		Map<String, Double> values = new LinkedHashMap<>();
		values.put(STRESS, 3.0);
		var preview = new ClientPresentationPreview(type -> Optional.of(previewAccess(Set.of(STRESS, CAPACITY))),
			() -> context, List.of(previewReader(values, new ArrayList<>())),
			(target, type) -> Optional.empty(), sent::add);
		var content = new NativeSelectorContent(capture, preview, () -> null, ignored -> List.of(),
			ref -> Component.literal(ref.fieldId()), json -> null);
		content.begin(context.level);
		assertEquals(1, sent.size(), "the authorized but locally missing capacity is requested once");
		assertEquals(Set.of(CAPACITY), sent.getFirst().fields());
		var before = projectedProperty(content.read(previewTarget(capture), previewFence(content)), STRESS);
		assertKineticValue(content.label(before.labelKey()), "su", "3");
		assertTrue(preview.accept(PresentationPreviewS2CPacket.result(sent.getFirst(),
			new PresentationSection(CREATE, 1, Map.of(CAPACITY, new PresentationValue.NumberValue(8)), false))));
		var after = projectedProperty(content.read(previewTarget(capture), previewFence(content)), STRESS);
		assertKineticValue(content.label(after.labelKey()), "su_percent", "3", "37.5");
		assertEquals(1, sent.size(), "accepting the response and reading the projection adds no further request");
		content.close();
	}

	@Test void deniedCapacityIsNeitherDemandedNorInferredIntoThePercentContext() {
		List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		List<Set<String>> demands = new ArrayList<>();
		var context = new PreviewContext();
		var capture = capture();
		Map<String, Double> values = new LinkedHashMap<>();
		values.put(STRESS, 12.0);
		var preview = new ClientPresentationPreview(type -> Optional.of(previewAccess(Set.of(STRESS))),
			() -> context, List.of(previewReader(values, demands)),
			(target, type) -> Optional.empty(), sent::add);
		var content = new NativeSelectorContent(capture, preview, () -> null, ignored -> List.of(),
			ref -> Component.literal(ref.fieldId()), json -> null);
		content.begin(context.level);
		assertTrue(sent.isEmpty());
		var stress = projectedProperty(content.read(previewTarget(capture), previewFence(content)), STRESS);
		assertKineticValue(content.label(stress.labelKey()), "su", "12");
		assertTrue(sent.isEmpty(), "no capacity request may be inferred outside the accepted mask");
		assertTrue(demands.stream().allMatch(demand -> demand.equals(Set.of(STRESS))),
			"the reader is never asked for a field outside the accepted mask");
		content.close();
	}

	@Test void realPreviewFormatsRootSpeedAsLocalizedEffectiveRpmForDrivenReversedAndStoppedRecords() throws IOException {
		withEnglishTranslations(() -> {
			withSpeedPreview(speedRecord(128, 128, true), speed -> {
				assertEquals("create:kinetic.speed: 128 RPM", speed.label(List.of()));
				assertEquals("effective_rpm: 128", speed.label(List.of("effective_rpm")),
					"a nested record entry keeps its typed scalar value instead of the RPM display form");
			});
			withSpeedPreview(speedRecord(-64, -64, true), speed ->
				assertEquals("create:kinetic.speed: -64 RPM", speed.label(List.of()),
					"a reversed rotation keeps its signed effective RPM"));
			withSpeedPreview(speedRecord(0, 32, false), speed -> {
				assertEquals("create:kinetic.speed: 0 RPM", speed.label(List.of()),
					"a stopped overstressed network shows the observed zero effective RPM, not the record size");
				assertEquals("theoretical_rpm: 32", speed.label(List.of("theoretical_rpm")));
				assertEquals("moving: No", speed.label(List.of("moving")));
			});
		});
	}

	@Test void deniedRpmChildrenAreOmittedFromLocalAndServerMenusButRootStillFormatsRpm() throws IOException {
		withEnglishTranslations(() -> {
			for (boolean local : List.of(true, false)) {
				var context = new PreviewContext(); var capture = capture();
				var speed = speedRecord(32, 64, true);
				var effective = new PresentationPropertyRef(CREATE, SPEED, List.of("effective_rpm"));
				var theoretical = new PresentationPropertyRef(CREATE, SPEED, List.of("theoretical_rpm"));
				var access = new PresentationPreviewAccess(1, 1, "entity_block", Map.of(CREATE,
					new PresentationPreviewAccess.Adapter(1, Map.of(SPEED,
						new PresentationField(SPEED, PresentationField.Kind.RECORD, true, 0, "Speed")))), Set.of(effective, theoretical));
				List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
				var preview = new ClientPresentationPreview(type -> Optional.of(access), () -> context,
					local ? List.of(previewValueReader(CREATE, Map.of(SPEED, speed))) : List.of(),
					(target, type) -> Optional.empty(), sent::add);
				var content = new NativeSelectorContent(capture, preview, () -> null, ignored -> List.of(ping("attention")),
					ref -> Component.literal(ref.fieldId()), json -> null);
				content.begin(context.level);
				if (!local) assertTrue(preview.accept(PresentationPreviewS2CPacket.result(sent.getFirst(),
					new PresentationSection(CREATE, 1, Map.of(SPEED, speed), false))));
				var shown = content.read(previewTarget(capture), previewFence(content));
				assertEquals(List.of(List.of(), List.of("moving")), shown.properties().stream().map(property -> property.ref().recordPath()).toList());
				assertEquals(speed, projectedEntry(shown, SPEED, List.of()).observedValue());
				assertEquals("create:kinetic.speed: 32 RPM", content.label(projectedEntry(shown, SPEED, List.of()).labelKey()).getString());
				assertTrue(content.intent(effective, "attention").isEmpty());
				assertTrue(content.intent(theoretical, "attention").isEmpty());
				assertTrue(content.intent(PresentationPropertyRef.root(CREATE, SPEED), "attention").isPresent());
				content.close();
			}
		});
	}

	@Test void speedWithoutNumericEffectiveRpmKeepsTheExistingGenericRecordSummary() throws IOException {
		withEnglishTranslations(() -> {
			// The HUD's established format logic falls through to the generic
			// record summary when effective_rpm is absent or not numeric, so the
			// preview keeps that fallback instead of inventing a 0 RPM value.
			withSpeedPreview(new PresentationValue.RecordValue(Map.of(
				"theoretical_rpm", new PresentationValue.NumberValue(32),
				"moving", new PresentationValue.Flag(true))), speed -> {
				assertEquals("create:kinetic.speed: 2", speed.label(List.of()));
				assertEquals("theoretical_rpm: 32", speed.label(List.of("theoretical_rpm")));
				assertEquals("moving: Yes", speed.label(List.of("moving")));
			});
			withSpeedPreview(new PresentationValue.RecordValue(Map.of(
				"effective_rpm", new PresentationValue.Text("fast"),
				"theoretical_rpm", new PresentationValue.NumberValue(32),
				"moving", new PresentationValue.Flag(true))), speed ->
				assertEquals("create:kinetic.speed: 3", speed.label(List.of()),
					"a malformed effective_rpm keeps the record summary rather than fabricating an RPM value"));
		});
	}

	@Test void inventoryItemsCarryTakeWhileRegularPropertiesKeepThePropertyPolicy() {
		List<IPacket> sent = new ArrayList<>(); var inventory = new ClientInventory(sent::add); ready(inventory);
		var context = new PreviewContext();
		var capture = capture();
		Map<String, Double> values = new LinkedHashMap<>();
		values.put(STRESS, 12.0);
		var preview = new ClientPresentationPreview(type -> Optional.of(previewAccess(Set.of(STRESS))),
			() -> context, List.of(previewReader(values, new ArrayList<>())),
			(target, type) -> Optional.empty(), sent::add);
		var content = new NativeSelectorContent(capture, preview, () -> inventory,
			ignored -> List.of(PingTypeCatalog.builtIn().findById("attention").orElseThrow()),
			ignored -> List.of(PingTypeCatalog.builtIn().findById("attention").orElseThrow(),
				PingTypeCatalog.builtIn().findById("take").orElseThrow()),
			ref -> Component.literal(ref.fieldId()), json -> null);
		content.begin(context.level);
		long request = content.requestId();
		inventory.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 9, 1, 1, 0, 1, true,
			InventoryS2CPacket.Status.READY, 0, List.of(entry("exact", 1, false, 1))).stamp(100, 1));
		var projection = content.read(previewTarget(capture), previewFence(content));
		assertNotNull(projection);
		var property = projectedProperty(projection, STRESS);
		assertEquals(List.of("attention"), property.allowedTypes().stream().map(type -> type.id()).toList(),
			"a regular property keeps the property policy without the inventory-only take");
		assertEquals(List.of("attention", "take"),
			projection.inventory().allowedItemTypes().stream().map(type -> type.id()).toList(),
			"the inventory item menu uses the inventory item policy");
		content.close();
	}

	@Test void legacyConstructorSharesThePropertyPolicyWithInventoryItems() {
		List<IPacket> sent = new ArrayList<>(); var inventory = new ClientInventory(sent::add); ready(inventory);
		var capture = capture();
		var content = new NativeSelectorContent(capture, null, () -> inventory,
			ignored -> List.of(PingTypeCatalog.builtIn().findById("request").orElseThrow()),
			ref -> Component.literal(ref.fieldId()), json -> null);
		content.begin(new Object());
		long request = content.requestId();
		inventory.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 9, 1, 1, 0, 1, true,
			InventoryS2CPacket.Status.READY, 0, List.of(entry("exact", 1, false, 1))).stamp(100, 1));
		var projection = content.read(previewTarget(capture), previewFence(content));
		assertNotNull(projection);
		assertEquals(List.of("request"), projection.inventory().allowedItemTypes().stream().map(type -> type.id()).toList(),
			"the legacy constructor keeps passing the original shared policy");
		content.close();
	}

	private static final String BASIC = "minecraft:basic";
	private static final String BLOCK_STATE = "minecraft:block.state";
	private static final SpatialSelectorSession.ListGeometry SELECTOR_GEOMETRY =
		new SpatialSelectorSession.ListGeometry(4, 180, 17, 23, 19, 85);

	private static PingType ping(String id) { return PingTypeCatalog.builtIn().findById(id).orElseThrow(); }

	private static PresentationPreviewAccess blockStateAccess() {
		return new PresentationPreviewAccess(1, 1, "entity_block", Map.of(BASIC,
			new PresentationPreviewAccess.Adapter(1, Map.of(BLOCK_STATE,
				new PresentationField(BLOCK_STATE, PresentationField.Kind.RECORD, true, 0, BLOCK_STATE)))));
	}

	private static NativeSelectorContent blockStateContent(Map<String, PresentationValue> state,
		PreviewContext context, CapturedPingContext capture, Consumer<PresentationPreviewC2SPacket> sender) {
		var preview = new ClientPresentationPreview(type -> Optional.of(blockStateAccess()), () -> context,
			List.of(previewValueReader(BASIC, Map.of(BLOCK_STATE, new PresentationValue.RecordValue(state)))),
			(target, type) -> Optional.empty(), sender);
		var content = new NativeSelectorContent(capture, preview, () -> null,
			ignored -> List.of(ping("attention"), ping("danger")), ref -> Component.literal(ref.fieldId()), json -> null);
		content.begin(context.level);
		return content;
	}

	/** One real content bridge and headless selector session over a locally observed block state. */
	private static final class BlockStateSession {
		final List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		final CapturedPingContext capture = capture();
		final PreviewContext context = new PreviewContext();
		final NativeSelectorContent content;
		final SpatialSelectorSession<ClientInventory.PreviewEntryReference> session;

		BlockStateSession(Map<String, PresentationValue> state) {
			content = blockStateContent(state, context, capture, sent::add);
			assertTrue(sent.isEmpty(), "a locally observed block state needs no preview request");
			session = new SpatialSelectorSession<>(previewTarget(capture), Map.of(),
				new SpatialSelectorSettings.Snapshot(30, 90, 120, 140, new BigDecimal("0.5"), false, 250, true, false),
				previewFence(content), (target, contentFence) -> content.read(target, contentFence), SELECTOR_GEOMETRY);
			session.open(0L);
		}

		SpatialController.MenuView menu() { return session.snapshot().radial().menus().getLast(); }
		String label(SpatialController.ChoiceView choice) { return content.label(choice.label()).getString(); }
	}

	private static void moveTo(SpatialSelectorSession<?> session, SpatialController.Point origin, double bearing,
		double distance, long now) {
		var pointer = session.snapshot().radial().pointer();
		double radians = Math.toRadians(bearing);
		session.moveGui(origin.x() + Math.sin(radians) * distance - pointer.x(),
			origin.y() - Math.cos(radians) * distance - pointer.y(), now);
	}

	private static void focus(SpatialSelectorSession<?> session, String choiceId, long now) {
		var menu = session.snapshot().radial().menus().getLast();
		var choice = menu.choices().stream().filter(value -> value.id().equals(choiceId)).findFirst().orElseThrow();
		moveTo(session, menu.origin(), choice.startDegrees() + choice.spanDegrees() / 2,
			session.snapshot().settings().stroke() * 2.0, now);
	}

	private static void enter(SpatialSelectorSession<?> session, String choiceId, long now) {
		int before = session.snapshot().radial().menus().size();
		focus(session, choiceId, now);
		session.tick(now + session.snapshot().settings().dwellMillis());
		assertEquals(before + 1, session.snapshot().radial().menus().size());
	}

	private static String firstNonBack(SpatialSelectorSession<?> session) {
		return session.snapshot().radial().menus().getLast().choices().stream()
			.filter(choice -> !choice.back()).findFirst().orElseThrow().id();
	}

	@Test void blockStateRecordBecomesAPureNavigationGroupWithoutAnyParentAction() {
		var fixture = new BlockStateSession(Map.of(
			"lit", new PresentationValue.Text("true"),
			"snowy", new PresentationValue.Text("false")));
		enter(fixture.session, "content", 10L);
		var parent = fixture.menu().choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow();
		assertTrue(parent.branch());
		assertNull(parent.action(), "the group parent carries no whole-record action");
		assertEquals("minecraft:block.state: 2", fixture.label(parent));
		focus(fixture.session, parent.id(), 150L);
		assertInstanceOf(SelectorIntent.None.class, fixture.session.releaseIntent(151L));
		fixture.content.close();
	}

	@Test void blockStateGroupChildrenKeepObservedRefsAndTypedReleases() {
		var fixture = new BlockStateSession(Map.of(
			"lit", new PresentationValue.Text("true"),
			"snowy", new PresentationValue.Text("false")));
		enter(fixture.session, "content", 10L);
		enter(fixture.session, firstNonBack(fixture.session), 150L);
		var children = fixture.menu().choices().stream().filter(choice -> !choice.back()).toList();
		assertEquals(List.of("lit: true", "snowy: false"), children.stream().map(fixture::label).toList());
		assertEquals(ping("attention").outlineColor(), children.getFirst().outlineColor(),
			"a grouped state row keeps its default Ping Type outline color");
		var snowy = children.stream().filter(choice -> "snowy: false".equals(fixture.label(choice))).findFirst().orElseThrow();
		focus(fixture.session, snowy.id(), 300L);
		var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, fixture.session.releaseIntent(301L));
		assertEquals(new PresentationPropertyRef(BASIC, BLOCK_STATE, List.of("snowy")), intent.property().ref());
		assertEquals(new PresentationValue.Text("false"), intent.property().observedValue());
		assertEquals("attention", intent.property().pingTypeId(), "direct release keeps the property default annotation");
		fixture.content.close();
	}

	@Test void blockStateGroupedRowKeepsAttentionAndDangerAnnotations() {
		var fixture = new BlockStateSession(Map.of("snowy", new PresentationValue.Text("false")));
		enter(fixture.session, "content", 10L);
		enter(fixture.session, firstNonBack(fixture.session), 150L);
		enter(fixture.session, firstNonBack(fixture.session), 300L);
		var types = fixture.menu().choices().stream().filter(choice -> !choice.back()).toList();
		assertEquals(List.of("pingforit.ping_type.attention", "pingforit.ping_type.danger"),
			types.stream().map(SpatialController.ChoiceView::label).toList());
		assertEquals(ping("danger").outlineColor(), types.getLast().outlineColor());
		focus(fixture.session, types.getLast().id(), 450L);
		var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, fixture.session.releaseIntent(451L));
		assertEquals(new PresentationPropertyRef(BASIC, BLOCK_STATE, List.of("snowy")), intent.property().ref());
		assertEquals(new PresentationValue.Text("false"), intent.property().observedValue());
		assertEquals("danger", intent.property().pingTypeId());
		fixture.content.close();
	}

	@Test void blockStateGroupBackReturnsOneLevelAndTheFrozenRowIdentitySurvives() {
		var fixture = new BlockStateSession(Map.of("snowy", new PresentationValue.Text("false")));
		enter(fixture.session, "content", 10L);
		String parent = firstNonBack(fixture.session);
		enter(fixture.session, parent, 150L);
		String back = fixture.menu().choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow().id();
		focus(fixture.session, back, 200L);
		fixture.session.tick(400L);
		assertTrue(fixture.menu().menuId().endsWith(":content"), "Back returns one level to the content menu");
		assertEquals(parent, firstNonBack(fixture.session), "the group parent keeps its identity after the return");
		fixture.content.close();
	}

	@Test void emptyBlockStateRecordStaysADisabledRowInsteadOfADeadSelectableRoot() {
		var fixture = new BlockStateSession(Map.of());
		enter(fixture.session, "content", 10L);
		var choice = fixture.menu().choices().stream().filter(value -> !value.back()).findFirst().orElseThrow();
		assertTrue(choice.disabled(), "an empty record keeps the established disabled empty-group row");
		assertEquals("minecraft:block.state: 0", fixture.label(choice));
		focus(fixture.session, choice.id(), 150L);
		assertInstanceOf(SelectorIntent.None.class, fixture.session.releaseIntent(151L));
		fixture.content.close();
	}

	@Test void blockStateBridgeDropsTheRecordRootAndDeclaresTheGroupedRows() {
		var context = new PreviewContext();
		var capture = capture();
		var content = blockStateContent(Map.of(
			"lit", new PresentationValue.Text("true"),
			"snowy", new PresentationValue.Text("false")), context, capture, packet -> {});
		var projection = content.read(previewTarget(capture), previewFence(content));
		assertNotNull(projection);
		assertTrue(projection.properties().stream().noneMatch(property -> property.ref().isRoot()),
			"the record root is no longer a selectable property");
		var groupPath = projection.properties().getFirst().groupPath();
		assertEquals(1, groupPath.size());
		assertFalse(groupPath.getFirst().isBlank());
		assertTrue(projection.properties().stream().allMatch(property -> property.groupPath().equals(groupPath)));
		assertEquals(1, projection.groups().size());
		assertEquals(groupPath, projection.groups().getFirst().path());
		content.close();
	}

	@Test void createNamespaceRecordRootStaysSelectableInsideTheCreateGroup() {
		var context = new PreviewContext();
		var capture = capture();
		var preview = new ClientPresentationPreview(type -> Optional.of(previewRecordAccess(SPEED)), () -> context,
			List.of(previewValueReader(Map.of(SPEED, speedRecord(128, 128, true)))),
			(target, type) -> Optional.empty(), packet -> {});
		var content = new NativeSelectorContent(capture, preview, () -> null, ignored -> List.of(ping("attention")),
			ref -> Component.literal(ref.fieldId()), json -> null);
		content.begin(context.level);
		var projection = content.read(previewTarget(capture), previewFence(content));
		var root = projectedProperty(projection, SPEED);
		assertTrue(root.ref().isRoot());
		assertEquals(List.of(NativeSelectorContent.CREATE_GROUP_STEP), root.groupPath(),
			"a create-namespace record root joins the Create properties group");
		assertEquals(List.of(new SpatialSelectorSession.ContentGroup(root.groupPath(), NativeSelectorContent.CREATE_GROUP_LABEL_KEY)),
			projection.groups(), "the create record root declares the Create properties group");
		assertEquals(List.of("attention"), root.allowedTypes().stream().map(type -> type.id()).toList(),
			"the grouped record root stays a selectable property with its Ping Types");
		content.close();
	}

	private static final String NAME = "minecraft:target.name";
	private static final String ENTITY_TYPE = "minecraft:entity.type";
	private static final String HAS_NETWORK = "create:kinetic.has_network";
	private static final String OVERSTRESSED = "create:kinetic.overstressed";
	private static final String INVENTORY_SUMMARY = "create:inventory.summary";
	private static final String FLUID_SUMMARY = "create:fluid.summary";
	private static final String AVAILABLE_CAPACITY = "create:kinetic.available_capacity";

	private static PresentationField.Kind createKind(String field) {
		if (field.equals(SPEED) || field.equals(INVENTORY_SUMMARY) || field.equals(FLUID_SUMMARY))
			return PresentationField.Kind.RECORD;
		if (field.equals(HAS_NETWORK) || field.equals(OVERSTRESSED)) return PresentationField.Kind.FLAG;
		return PresentationField.Kind.NUMBER;
	}

	private static PresentationPreviewAccess createAccess(Set<String> createFields, Set<String> basicFields) {
		Map<String, PresentationPreviewAccess.Adapter> adapters = new LinkedHashMap<>();
		if (!createFields.isEmpty()) {
			Map<String, PresentationField> create = new LinkedHashMap<>();
			for (String field : createFields)
				create.put(field, new PresentationField(field, createKind(field), true, 0, field));
			adapters.put(CREATE, new PresentationPreviewAccess.Adapter(1, create));
		}
		if (!basicFields.isEmpty()) {
			Map<String, PresentationField> basic = new LinkedHashMap<>();
			for (String field : basicFields)
				basic.put(field, new PresentationField(field,
					field.equals(BLOCK_STATE) ? PresentationField.Kind.RECORD : PresentationField.Kind.TEXT, true, 0, field));
			adapters.put(BASIC, new PresentationPreviewAccess.Adapter(1, basic));
		}
		return new PresentationPreviewAccess(1, 1, "entity_block", adapters);
	}

	private static NativeSelectorContent createContent(Map<String, PresentationValue> values, PreviewContext context,
		CapturedPingContext capture, Consumer<PresentationPreviewC2SPacket> sender, ClientInventory inventory) {
		Set<String> createFields = new java.util.LinkedHashSet<>();
		Set<String> basicFields = new java.util.LinkedHashSet<>();
		values.keySet().forEach(field -> (field.startsWith("create:") ? createFields : basicFields).add(field));
		var preview = new ClientPresentationPreview(
			type -> Optional.of(createAccess(createFields, basicFields)), () -> context,
			List.of(previewValueReader(CREATE, values), previewValueReader(BASIC, values)),
			(target, type) -> Optional.empty(), sender);
		if (inventory != null) ready(inventory);
		var content = new NativeSelectorContent(capture, preview, () -> inventory,
			ignored -> List.of(ping("attention")), ref -> Component.literal(ref.fieldId()),
			json -> Component.literal("Chest"));
		content.begin(context.level);
		if (inventory != null) inventory.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, content.requestId(),
			null, 9, 1, 1, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of(entry("exact", 1, false, 1))).stamp(100, 1));
		return content;
	}

	/** One real content bridge and headless selector session over locally observed create content. */
	private static final class CreateContentSession {
		final List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		final CapturedPingContext capture = capture();
		final PreviewContext context = new PreviewContext();
		final NativeSelectorContent content;
		final SpatialSelectorSession<ClientInventory.PreviewEntryReference> session;

		CreateContentSession(Map<String, PresentationValue> values) { this(values, null); }

		CreateContentSession(Map<String, PresentationValue> values, ClientInventory inventory) {
			content = createContent(values, context, capture, sent::add, inventory);
			assertTrue(sent.isEmpty(), "locally observed create content needs no preview request");
			session = new SpatialSelectorSession<>(previewTarget(capture), Map.of(),
				new SpatialSelectorSettings.Snapshot(30, 90, 120, 140, new BigDecimal("0.5"), false, 250, true, false),
				previewFence(content), (target, contentFence) -> content.read(target, contentFence), SELECTOR_GEOMETRY);
			session.open(0L);
		}

		SpatialController.MenuView menu() { return session.snapshot().radial().menus().getLast(); }
		String label(SpatialController.ChoiceView choice) { return content.label(choice.label()).getString(); }
	}

	@Test void createNamespaceBridgeClassifiesEveryCreateFieldAndDescendantUnderCreateProperties() throws IOException {
		withEnglishTranslations(() -> {
			var context = new PreviewContext();
			var capture = capture();
			Map<String, PresentationValue> values = new LinkedHashMap<>();
			values.put(SPEED, speedRecord(128, 128, true));
			values.put(HAS_NETWORK, new PresentationValue.Flag(true));
			values.put(OVERSTRESSED, new PresentationValue.Flag(false));
			values.put(INVENTORY_SUMMARY, new PresentationValue.RecordValue(Map.of("minecraft:stone", new PresentationValue.NumberValue(3))));
			values.put(FLUID_SUMMARY, new PresentationValue.RecordValue(Map.of("minecraft:water", new PresentationValue.NumberValue(100))));
			values.put(NAME, new PresentationValue.Text("\"Chest\""));
			values.put(ENTITY_TYPE, new PresentationValue.Text("minecraft:chest"));
			values.put(BLOCK_STATE, new PresentationValue.RecordValue(Map.of("lit", new PresentationValue.Text("true"))));
			var content = createContent(values, context, capture, packet -> {}, null);
			var projection = content.read(previewTarget(capture), previewFence(content));
			assertNotNull(projection);
			var createGroup = projection.groups().stream()
				.filter(group -> group.labelKey().equals(NativeSelectorContent.CREATE_GROUP_LABEL_KEY)).findFirst().orElseThrow();
			assertEquals(List.of(NativeSelectorContent.CREATE_GROUP_STEP), createGroup.path());
			assertEquals(2, projection.groups().size(), "the block-state group and the Create group are both declared");
			for (var property : projection.properties()) {
				if (property.ref().fieldId().startsWith("create:"))
					assertEquals(createGroup.path(), property.groupPath(), property.ref()::toString);
				else if (!property.ref().fieldId().equals(BLOCK_STATE))
					assertTrue(property.groupPath().isEmpty(), property.ref()::toString);
			}
			assertTrue(projection.properties().stream().anyMatch(property -> property.ref().fieldId().equals(INVENTORY_SUMMARY)
				&& property.ref().recordPath().equals(List.of("minecraft:stone"))),
				"a registry-ID record descendant is projected under Create");
			assertEquals("Create properties...", content.label(createGroup.labelKey()).getString(),
				"the Create group label resolves through the real language fallback");
			content.close();
		});
	}

	@Test void createGroupIsPureNavigationWhileGenericFieldsAndTheInventoryListStayOutside() throws IOException {
		withEnglishTranslations(() -> {
			Map<String, PresentationValue> values = new LinkedHashMap<>();
			values.put(SPEED, speedRecord(128, 128, true));
			values.put(HAS_NETWORK, new PresentationValue.Flag(true));
			values.put(INVENTORY_SUMMARY, new PresentationValue.RecordValue(Map.of("minecraft:stone", new PresentationValue.NumberValue(3))));
			values.put(NAME, new PresentationValue.Text("\"Chest\""));
			values.put(ENTITY_TYPE, new PresentationValue.Text("minecraft:chest"));
			values.put(BLOCK_STATE, new PresentationValue.RecordValue(Map.of("lit", new PresentationValue.Text("true"))));
			var inventory = new ClientInventory(packet -> {});
			var fixture = new CreateContentSession(values, inventory);
			enter(fixture.session, "content", 10L);
			var top = fixture.menu().choices();
			assertEquals(List.of("Create properties...", "minecraft:block.state: 1", "minecraft:entity.type: minecraft:chest",
				"Chest", "Inventory items", "Back"), top.stream().map(fixture::label).toList(),
				"generic fields and the inventory list stay outside the Create group");
			var create = top.getFirst();
			assertTrue(create.branch());
			assertNull(create.action(), "the Create group parent carries no property intent");
			assertNull(create.outlineColor(), "the Create group parent carries no Ping Type color");
			enter(fixture.session, create.id(), 150L);
			assertEquals(List.of("create:inventory.summary: 1", "minecraft:stone: 3", "create:kinetic.has_network: Yes",
				"create:kinetic.speed: 128 RPM", "effective_rpm: 128", "moving: Yes", "theoretical_rpm: 128"),
				fixture.menu().choices().stream().filter(choice -> !choice.back()).map(fixture::label).toList());
			String back = fixture.menu().choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow().id();
			focus(fixture.session, back, 200L);
			fixture.session.tick(400L);
			assertTrue(fixture.menu().menuId().endsWith(":content"), "Back returns one level to the content menu");
			assertEquals(create.id(), fixture.menu().choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id(),
				"the Create parent keeps its stable identity after the return");
			fixture.content.close();
		});
	}

	@Test void createGroupParentReleaseCommitsNothingAndItsSpeedRootStaysSelectable() throws IOException {
		withEnglishTranslations(() -> {
			Map<String, PresentationValue> values = new LinkedHashMap<>();
			values.put(SPEED, speedRecord(128, 128, true));
			var groupRelease = new CreateContentSession(values);
			enter(groupRelease.session, "content", 10L);
			String parent = groupRelease.menu().choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
			focus(groupRelease.session, parent, 150L);
			assertInstanceOf(SelectorIntent.None.class, groupRelease.session.releaseIntent(151L),
				"releasing the focused Create group parent commits nothing");
			groupRelease.content.close();

			var rootRelease = new CreateContentSession(values);
			enter(rootRelease.session, "content", 10L);
			String create = rootRelease.menu().choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
			enter(rootRelease.session, create, 150L);
			var speed = rootRelease.menu().choices().stream()
				.filter(choice -> "create:kinetic.speed: 128 RPM".equals(rootRelease.label(choice))).findFirst().orElseThrow();
			focus(rootRelease.session, speed.id(), 300L);
			var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, rootRelease.session.releaseIntent(301L));
			assertEquals(new PresentationPropertyRef(CREATE, SPEED, List.of()), intent.property().ref());
			assertEquals(speedRecord(128, 128, true), intent.property().observedValue(),
				"the grouped speed root still commits its actual observed RPM record");
			assertEquals("attention", intent.property().pingTypeId());
			rootRelease.content.close();
		});
	}

	@Test void createGroupDeclarationIsStableAndAbsentWithoutCreateMembers() {
		Map<String, PresentationValue> values = new LinkedHashMap<>();
		values.put(SPEED, speedRecord(128, 128, true));
		var context = new PreviewContext();
		var capture = capture();
		var content = createContent(values, context, capture, packet -> {}, null);
		var first = content.read(previewTarget(capture), previewFence(content));
		assertNotNull(first);
		assertEquals(1, first.revision());
		assertNull(content.read(previewTarget(capture), previewFence(content)), "an unchanged grouped projection adds no revision");
		values.put(SPEED, speedRecord(64, 64, true));
		content.tick();
		var next = content.read(previewTarget(capture), previewFence(content));
		assertNotNull(next);
		assertEquals(2, next.revision());
		assertEquals(first.groups(), next.groups(), "the Create group keeps its stable declaration and identity");
		content.close();

		Map<String, PresentationValue> generic = new LinkedHashMap<>();
		generic.put(ENTITY_TYPE, new PresentationValue.Text("minecraft:chest"));
		var genericContext = new PreviewContext();
		var genericCapture = capture();
		var genericContent = createContent(generic, genericContext, genericCapture, packet -> {}, null);
		var plain = genericContent.read(previewTarget(genericCapture), previewFence(genericContent));
		assertTrue(plain.groups().isEmpty(), "content without a create-namespace member declares no group");
		assertTrue(plain.properties().stream().allMatch(property -> property.groupPath().isEmpty()));
		genericContent.close();
	}

	private static Map<String, PresentationValue> allCreateValues() {
		Map<String, PresentationValue> values = new LinkedHashMap<>();
		values.put(SPEED, speedRecord(128, 128, true));
		values.put(HAS_NETWORK, new PresentationValue.Flag(true));
		values.put(OVERSTRESSED, new PresentationValue.Flag(false));
		values.put(STRESS, new PresentationValue.NumberValue(12));
		values.put(CAPACITY, new PresentationValue.NumberValue(10));
		values.put(AVAILABLE_CAPACITY, new PresentationValue.NumberValue(-2));
		values.put(INVENTORY_SUMMARY, new PresentationValue.RecordValue(Map.of("minecraft:stone", new PresentationValue.NumberValue(3))));
		values.put(FLUID_SUMMARY, new PresentationValue.RecordValue(Map.of("minecraft:water", new PresentationValue.NumberValue(100))));
		return values;
	}

	@Test void createBridgeNestsOnlyTheKineticStressTrioUnderStress() throws IOException {
		withEnglishTranslations(() -> {
			var context = new PreviewContext();
			var capture = capture();
			var values = allCreateValues();
			values.put(NAME, new PresentationValue.Text("\"Chest\""));
			var content = createContent(values, context, capture, packet -> {}, null);
			var projection = content.read(previewTarget(capture), previewFence(content));
			assertNotNull(projection);
			var createGroup = projection.groups().stream()
				.filter(group -> group.labelKey().equals(NativeSelectorContent.CREATE_GROUP_LABEL_KEY)).findFirst().orElseThrow();
			var stressGroup = projection.groups().stream()
				.filter(group -> group.labelKey().equals(NativeSelectorContent.STRESS_GROUP_LABEL_KEY)).findFirst().orElseThrow();
			assertEquals(List.of(NativeSelectorContent.CREATE_GROUP_STEP), createGroup.path());
			assertEquals(List.of(NativeSelectorContent.CREATE_GROUP_STEP, NativeSelectorContent.STRESS_GROUP_STEP), stressGroup.path());
			assertEquals(2, projection.groups().size(), "Stress nests under Create without a third group");
			for (String field : List.of(STRESS, CAPACITY, AVAILABLE_CAPACITY))
				assertEquals(stressGroup.path(), projectedProperty(projection, field).groupPath(), field);
			for (String field : List.of(SPEED, HAS_NETWORK, OVERSTRESSED, INVENTORY_SUMMARY, FLUID_SUMMARY))
				assertEquals(createGroup.path(), projectedProperty(projection, field).groupPath(), field);
			assertTrue(projectedProperty(projection, NAME).groupPath().isEmpty(), "a generic field stays outside Create");
			assertEquals("Create properties...", content.label(createGroup.labelKey()).getString());
			assertEquals("Stress...", content.label(stressGroup.labelKey()).getString(),
				"the Stress group label resolves through the real language fallback");
			content.close();

			Map<String, PresentationValue> withoutStress = new LinkedHashMap<>();
			withoutStress.put(SPEED, speedRecord(64, 64, true));
			var plainContext = new PreviewContext();
			var plainCapture = capture();
			var plainContent = createContent(withoutStress, plainContext, plainCapture, packet -> {}, null);
			var plain = plainContent.read(previewTarget(plainCapture), previewFence(plainContent));
			assertEquals(List.of(new SpatialSelectorSession.ContentGroup(List.of(NativeSelectorContent.CREATE_GROUP_STEP),
				NativeSelectorContent.CREATE_GROUP_LABEL_KEY)), plain.groups(),
				"no visible stress member leaves no declared Stress group");
			plainContent.close();
		});
	}

	@Test void stressGroupShowsExactlyTheStressTrioWithReleasesAndAncestorBack() throws IOException {
		withEnglishTranslations(() -> {
			Map<String, PresentationValue> values = allCreateValues();

			var navigation = new CreateContentSession(values);
			enter(navigation.session, "content", 10L);
			String create = navigation.menu().choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
			enter(navigation.session, create, 150L);
			var createChildren = navigation.menu().choices().stream().filter(choice -> !choice.back()).toList();
			assertEquals(List.of("create:fluid.summary: 1", "minecraft:water: 100", "create:inventory.summary: 1",
				"minecraft:stone: 3", "Stress...", "create:kinetic.has_network: Yes", "create:kinetic.overstressed: No",
				"create:kinetic.speed: 128 RPM", "effective_rpm: 128", "moving: Yes", "theoretical_rpm: 128"),
				createChildren.stream().map(navigation::label).toList(),
				"only the stress trio nests; the network flags and every other Create field stay direct");
			var stress = createChildren.stream().filter(choice -> "Stress...".equals(navigation.label(choice))).findFirst().orElseThrow();
			assertTrue(stress.branch());
			assertNull(stress.action(), "the Stress group parent carries no property intent");
			assertNull(stress.outlineColor(), "the Stress group parent carries no Ping Type color");
			String createMenuId = navigation.menu().menuId();
			enter(navigation.session, stress.id(), 300L);
			assertEquals(List.of("create:kinetic.available_capacity: -2 SU", "create:kinetic.capacity: 10 SU",
				"create:kinetic.stress: 12 SU (120%)"), navigation.menu().choices().stream().filter(choice -> !choice.back())
				.map(navigation::label).toList(), "Stress contains exactly the three kinetic stress fields");
			String back = navigation.menu().choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow().id();
			focus(navigation.session, back, 400L);
			navigation.session.tick(600L);
			assertEquals(createMenuId, navigation.menu().menuId(), "Back returns one level to the Create menu");
			var stressAfterBack = navigation.menu().choices().stream()
				.filter(choice -> "Stress...".equals(navigation.label(choice))).findFirst().orElseThrow();
			assertEquals(stress.id(), stressAfterBack.id(), "the Stress parent keeps its stable identity after the return");
			navigation.content.close();

			var groupRelease = new CreateContentSession(values);
			enter(groupRelease.session, "content", 10L);
			String groupCreate = groupRelease.menu().choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
			enter(groupRelease.session, groupCreate, 150L);
			String groupStress = groupRelease.menu().choices().stream()
				.filter(choice -> "Stress...".equals(groupRelease.label(choice))).findFirst().orElseThrow().id();
			focus(groupRelease.session, groupStress, 300L);
			assertInstanceOf(SelectorIntent.None.class, groupRelease.session.releaseIntent(301L),
				"releasing the focused Stress group parent commits nothing");
			groupRelease.content.close();

			List<String> fields = List.of(AVAILABLE_CAPACITY, CAPACITY, STRESS);
			List<PresentationValue> expected = List.of(new PresentationValue.NumberValue(-2),
				new PresentationValue.NumberValue(10), new PresentationValue.NumberValue(12));
			for (int index = 0; index < fields.size(); index++) {
				var releaseFixture = new CreateContentSession(values);
				enter(releaseFixture.session, "content", 10L);
				String releaseCreate = releaseFixture.menu().choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
				enter(releaseFixture.session, releaseCreate, 150L);
				String releaseStress = releaseFixture.menu().choices().stream()
					.filter(choice -> "Stress...".equals(releaseFixture.label(choice))).findFirst().orElseThrow().id();
				enter(releaseFixture.session, releaseStress, 300L);
				var child = releaseFixture.menu().choices().stream().filter(choice -> !choice.back()).toList().get(index);
				focus(releaseFixture.session, child.id(), 450L);
				var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, releaseFixture.session.releaseIntent(451L));
				assertEquals(new PresentationPropertyRef(CREATE, fields.get(index), List.of()), intent.property().ref());
				assertEquals(expected.get(index), intent.property().observedValue());
				assertEquals("attention", intent.property().pingTypeId());
				releaseFixture.content.close();
			}
		});
	}

	@FunctionalInterface private interface ThrowingRunnable { void run(); }

	private static void withEnglishTranslations(ThrowingRunnable body) throws IOException {
		Map<String, String> english = new LinkedHashMap<>();
		try (InputStream stream = NativeSelectorContentTest.class.getClassLoader()
			.getResourceAsStream("assets/pingforit/lang/en_us.json")) {
			assertNotNull(stream);
			Language.loadFromJson(stream, english::put);
		}
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(english));
		try {
			body.run();
		} finally {
			Language.inject(previous);
		}
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
