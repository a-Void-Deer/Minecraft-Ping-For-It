package nx.pingwheel.common.presentation.minecraft;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryInitialPayloadTest {
	@Test void pureBasicSizeMatchesTheSingleRealCodecIncludingNestedAnnotationsAndUtf() {
		String field = PresentationBasic.NAME;
		var nested = new PresentationValue.RecordValue(Map.of("名字", new PresentationValue.Text("水💧"),
			"nested", new PresentationValue.RecordValue(Map.of("value", new PresentationValue.NumberValue(5))),
			"sequence", new PresentationValue.Sequence(List.of(new PresentationValue.Text("x"), new PresentationValue.Flag(true)))));
		var section = new PresentationSection(PresentationBasic.ID, 1, Map.of(field, nested), false,
			Map.of(PresentationPropertyRef.root(PresentationBasic.ID, field), "attention",
				new PresentationPropertyRef(PresentationBasic.ID, field, List.of("nested", "value")), "danger"));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			PresentationCodec.write(buffer, section);
			assertEquals(buffer.readableBytes(), InventoryInitialPayload.basicBytes(section));
			assertTrue(InventoryInitialPayload.constructionWorkspace(buffer.readableBytes()) < 1_048_576);
		} finally { buffer.release(); }
	}
	@Test void oversizedSemanticValueIsRejectedByPurePlanningWithoutAnEncodingProbe() {
		var record = new PresentationValue.RecordValue(Map.of("a", new PresentationValue.Text("x".repeat(1024)),
			"b", new PresentationValue.Text("x".repeat(1024)), "c", new PresentationValue.Text("x".repeat(1024)),
			"d", new PresentationValue.Text("x".repeat(1024))));
		var section = new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, record), false);
		assertThrows(IllegalArgumentException.class, () -> InventoryInitialPayload.basicBytes(section));
	}
	@Test void deterministicEnvelopeBoundCoversOrdinaryAndLargeExternalLocatorWithoutAOneMiBReservation() {
		var store = new ServerMarkerStore(new MarkerIdSource());
		var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		var owner = new java.util.UUID(805,1);
		var emptyGrant = new nx.pingwheel.common.presentation.MarkerContentGrant(
			nx.pingwheel.common.presentation.PresentationGrant.denied(), null);
		var richGrant = new nx.pingwheel.common.presentation.MarkerContentGrant(
			new nx.pingwheel.common.presentation.PresentationGrant(
				Map.of(InventoryPresentation.ADAPTER_ID, new nx.pingwheel.common.presentation.PresentationGrant.AdapterGrant(
					InventoryPresentation.SCHEMA, Map.of(
						InventoryPresentation.ITEMS, nx.pingwheel.common.presentation.PresentationField.Kind.RECORD,
						InventoryPresentation.FLUIDS, nx.pingwheel.common.presentation.PresentationField.Kind.RECORD))),
				Set.of(new nx.pingwheel.common.presentation.PresentationPropertyRef(InventoryPresentation.ADAPTER_ID,
						InventoryPresentation.ITEMS, List.of("first")),
					new nx.pingwheel.common.presentation.PresentationPropertyRef(InventoryPresentation.ADAPTER_ID,
						InventoryPresentation.ITEMS, List.of("second")))), null);
		for (Target target : List.<Target>of(new Target.BlockTarget("minecraft:overworld",1,64,3,"minecraft:chest"),
			Target.ExternalBlockTarget.committed("minecraft:overworld","sable","lease","minecraft:chest","路径".repeat(1000),true))) {
			var marker = store.create(owner,target,type,type.defaultPingType(),new MarkerAnchor(1,64,3),0,10000,List.of(owner)).marker();
			var basic = new PresentationSection(PresentationBasic.ID,1,Map.of(PresentationBasic.NAME,new PresentationValue.Text("chest")),false);
			var ref = PresentationPropertyRef.root(PresentationBasic.ID,PresentationBasic.NAME);
			int bytes = InventoryInitialPayload.basicBytes(basic);
			int emptyBound = InventoryInitialPayload.bodyUpper(marker,"owner",ref,emptyGrant,PresentationReceiptContent.suppressed(),bytes);
			int richBound = InventoryInitialPayload.bodyUpper(marker,"owner",ref,richGrant,PresentationReceiptContent.suppressed(),bytes);
			assertEquals(emptyBound + nx.pingwheel.common.network.PresentationGrantCodec.encodedSize(richGrant)
					- nx.pingwheel.common.network.PresentationGrantCodec.encodedSize(emptyGrant), richBound,
				"the deterministic bound pays the complete grant including fields and children");
			var emptyPacket = InventoryInitialPayload.create(1,1,1,marker,"owner",ref,emptyGrant,PresentationReceiptContent.suppressed(),basic,bytes);
			assertTrue(emptyPacket.encodedCreatedBodyBytes() <= emptyBound);
			var richPacket = InventoryInitialPayload.create(1,1,1,marker,"owner",ref,richGrant,PresentationReceiptContent.suppressed(),basic,bytes);
			assertTrue(richPacket.encodedCreatedBodyBytes() <= richBound);
			long emptyWorkspace = InventoryInitialPayload.constructionWorkspace(bytes) + 4096L + 8L * emptyBound;
			long richWorkspace = InventoryInitialPayload.constructionWorkspace(bytes) + 4096L + 8L * richBound;
			assertTrue(richWorkspace > emptyWorkspace, "paid construction workspace grows with the grant-bearing body bound");
			assertTrue(richBound < 10000, "reserve from the real immutable inputs, not the absolute 1 MiB wire cap");
		}
	}
}
