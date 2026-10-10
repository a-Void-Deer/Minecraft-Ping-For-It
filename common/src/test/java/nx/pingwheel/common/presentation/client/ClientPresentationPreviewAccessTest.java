package nx.pingwheel.common.presentation.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.preview.ClientPresentationPreview;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientPresentationPreviewAccessTest {
	@Test void realOfferMetadataGrantsNothingBeforeResetAndMaskIsExactTypedSectionOnly() {
		List<IPacket> sent = new ArrayList<>(); var client = new ClientPresentation(sent::add);
		assertTrue(client.previewAccess("entity").isEmpty()); client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(73, Map.of(PresentationBasic.ID, List.of(
			new PresentationField(PresentationBasic.HEALTH, PresentationField.Kind.NUMBER, true, 0, "Server health"),
			new PresentationField(PresentationBasic.NAME, PresentationField.Kind.NUMBER, true, 0, "Wrong kind")),
			InventoryPresentation.ADAPTER_ID, InventoryPresentation.INSTANCE.fields()),
			Map.of(PresentationBasic.ID, 1, InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA))));
		assertTrue(client.previewAccess("entity").isEmpty(), "offer defaults are not a grant");
		assertTrue(client.reset(PresentationS2CPacket.reset(73, 1, Map.of("entity", Map.of(PresentationBasic.ID,
			Set.of(PresentationBasic.HEALTH, PresentationBasic.NAME, "test:unknown"), InventoryPresentation.ADAPTER_ID, Set.of(InventoryPresentation.ITEMS))))));
		var access = client.previewAccess("entity").orElseThrow();
		assertEquals(Set.of(PresentationBasic.HEALTH), access.fields(PresentationBasic.ID));
		assertFalse(access.adapters().containsKey(InventoryPresentation.ADAPTER_ID));
		assertEquals(73, access.epoch()); assertEquals(1, access.view());
		assertTrue(client.previewAccess("block").orElseThrow().adapters().isEmpty());
		assertTrue(client.previewAccess("unknown").isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> access.adapters().clear());
		assertThrows(UnsupportedOperationException.class, () -> access.adapters().get(PresentationBasic.ID).fields().clear());
		assertTrue(client.reset(PresentationS2CPacket.reset(73, 2, Map.of())));
		assertTrue(client.previewAccess("entity").orElseThrow().adapters().isEmpty());
		assertEquals(Set.of(PresentationBasic.HEALTH), access.fields(PresentationBasic.ID), "old detached metadata snapshot is inert");
		client.close(); assertTrue(client.previewAccess("entity").isEmpty()); assertEquals(1, sent.size(), "access reads send nothing");
	}
	@Test void realResetRevokesLocalGetterDemandAndOffViewResponseWithoutRereadOrNewRead() {
		var client = new ClientPresentation(packet -> {}); client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(73, Map.of(PresentationBasic.ID, PresentationBasic.fields()), Map.of(PresentationBasic.ID, 1))));
		Object level = new Object(); Target target = new Target.EntityTarget("minecraft:overworld", new java.util.UUID(0, 19));
		var context = new PreviewFieldAccess.ReadContext() {
			public Object levelIdentity() { return level; }
			public String dimensionId() { return target.dimensionId(); }
			public long tick() { return 0; }
		};
		List<Set<String>> reads = new ArrayList<>(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		PreviewFieldAccess reader = new PreviewFieldAccess() {
			public String adapterId() { return PresentationBasic.ID; }
			public Map<String, Outcome> observe(Target observed, Set<String> demand, ReadContext readContext) {
				reads.add(demand); return demand.contains(PresentationBasic.HEALTH) ? Map.of(PresentationBasic.HEALTH,
					new Observed(new PreviewObservation(new PresentationValue.NumberValue(0), PreviewObservation.Origin.CLIENT_SYNCED, 0, false))) : Map.of();
			}
		};
		Object token = new Object(); var preview = new ClientPresentationPreview(client::previewAccess, () -> context,
			List.of(reader), (t, type) -> Optional.empty(), sent::add);
		preview.begin(new ClientPresentationPreview.Binding(token, target, "entity", level));
		assertTrue(reads.isEmpty()); assertTrue(sent.isEmpty(), "unready OFFER cannot read or request defaults");
		assertTrue(client.reset(PresentationS2CPacket.reset(73, 1, Map.of("entity", Map.of(PresentationBasic.ID,
			Set.of(PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH))))));
		preview.tick(); assertEquals(List.of(Set.of(PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH)), reads);
		var pending = sent.getFirst(); assertEquals(Set.of(PresentationBasic.MAX_HEALTH), pending.fields());
		assertEquals(new PresentationValue.NumberValue(0), preview.intent(token, PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.HEALTH), null).orElseThrow().observedValue());
		int before = reads.size(); assertTrue(client.reset(PresentationS2CPacket.reset(73, 2, Map.of())));
		preview.tick(); preview.tick(); assertTrue(preview.projection().orElseThrow().fields().isEmpty());
		assertEquals(before, reads.size(), "revoked fields never reach local reader again");
		assertEquals(List.of(PresentationPreviewC2SPacket.Kind.READ, PresentationPreviewC2SPacket.Kind.CANCEL), sent.stream().map(PresentationPreviewC2SPacket::kind).toList());
		assertFalse(preview.accept(PresentationPreviewS2CPacket.result(pending, new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.MAX_HEALTH, new PresentationValue.NumberValue(20)), false))));
		assertTrue(preview.intent(token, PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.HEALTH), null).isEmpty());
		assertTrue(client.store().sections(1).isEmpty(), "provisional data never populates the marker store");
	}

	@Test void childOnlyResetFencesPendingResponseAndRetainedCacheCannotReintroduceDeniedEntry() {
		var client = new ClientPresentation(packet -> {}); client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(73, Map.of(PresentationBasic.ID, PresentationBasic.fields()), Map.of(PresentationBasic.ID, 1))));
		var mask = Map.of("block", Map.of(PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)));
		assertTrue(client.reset(PresentationS2CPacket.reset(73, 1, mask)));
		Object level = new Object(), token = new Object();
		Target target = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:stone");
		var context = new PreviewFieldAccess.ReadContext() {
			public Object levelIdentity() { return level; }
			public String dimensionId() { return target.dimensionId(); }
			public long tick() { return 0; }
		};
		var child = new PresentationPropertyRef(PresentationBasic.ID, PresentationBasic.BLOCK_STATE, List.of("lit"));
		var root = PresentationPropertyRef.root(child.adapterId(), child.fieldId());
		var section = new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.BLOCK_STATE,
			new PresentationValue.RecordValue(Map.of("lit", new PresentationValue.Flag(true), "facing", new PresentationValue.Text("north")))), false,
			Map.of(child, "danger"));
		List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		var cached = new java.util.concurrent.atomic.AtomicReference<ClientPresentationPreview.RetainedSnapshot>();
		var preview = new ClientPresentationPreview(client::previewAccess, () -> context, List.of(),
			(t, type) -> Optional.ofNullable(cached.get()), sent::add);
		preview.begin(new ClientPresentationPreview.Binding(token, target, "block", level));
		var oldRequest = sent.getFirst();
		var denies = new java.util.LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack()); denies.put("block", List.of(child));
		assertTrue(client.reset(PresentationS2CPacket.reset(73, 2, mask, denies)));
		cached.set(new ClientPresentationPreview.RetainedSnapshot(target, "block", 0, Map.of(PresentationBasic.ID, section)));
		assertFalse(preview.accept(PresentationPreviewS2CPacket.result(oldRequest,
			new PresentationSection(PresentationBasic.ID, 1, section.fields(), false))));
		var shown = preview.projection().orElseThrow();
		assertEquals(section.fields().get(root.fieldId()), shown.property(root).orElseThrow().value());
		assertEquals(PreviewObservation.Origin.RETAINED_MARKER, shown.property(root).orElseThrow().origin());
		assertTrue(shown.property(child).isEmpty()); assertTrue(preview.intent(token, child, "danger").isEmpty());
		assertFalse(nx.pingwheel.common.presentation.preview.PreviewPropertyEntries.of(shown).stream().anyMatch(entry -> child.equals(entry.ref())));
		assertTrue(client.store().sections(1).isEmpty());
	}
}
