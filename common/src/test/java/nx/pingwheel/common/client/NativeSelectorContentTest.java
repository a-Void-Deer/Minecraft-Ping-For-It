package nx.pingwheel.common.client;

import java.util.ArrayList;
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
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
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
}
