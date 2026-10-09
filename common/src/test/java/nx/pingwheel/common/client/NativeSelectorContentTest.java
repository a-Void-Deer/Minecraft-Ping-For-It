package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.domain.BlockFace;
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

	private static void assertKineticValue(Component label, String key, String... args) {
		Component value = label.getSiblings().getLast();
		assertInstanceOf(TranslatableContents.class, value.getContents());
		TranslatableContents contents = (TranslatableContents) value.getContents();
		assertEquals("presentation.pingforit.format." + key, contents.getKey());
		assertArrayEquals(args, contents.getArgs());
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
}
