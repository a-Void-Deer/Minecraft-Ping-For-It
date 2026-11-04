package nx.pingwheel.common.presentation.minecraft;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
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
		for (Target target : List.<Target>of(new Target.BlockTarget("minecraft:overworld",1,64,3,"minecraft:chest"),
			Target.ExternalBlockTarget.committed("minecraft:overworld","sable","lease","minecraft:chest","路径".repeat(1000),true))) {
			var marker = store.create(owner,target,type,type.defaultPingType(),new MarkerAnchor(1,64,3),0,10000,List.of(owner)).marker();
			var basic = new PresentationSection(PresentationBasic.ID,1,Map.of(PresentationBasic.NAME,new PresentationValue.Text("chest")),false);
			var ref = PresentationPropertyRef.root(PresentationBasic.ID,PresentationBasic.NAME);
			int bytes = InventoryInitialPayload.basicBytes(basic);
			int bound = InventoryInitialPayload.bodyUpper(marker,"owner",ref,PresentationReceiptContent.suppressed(),bytes);
			var packet = InventoryInitialPayload.create(1,1,1,marker,"owner",ref,PresentationReceiptContent.suppressed(),basic,bytes);
			assertTrue(packet.encodedCreatedBodyBytes() <= bound);
			assertTrue(bound < 10000, "reserve from the real immutable inputs, not the absolute 1 MiB wire cap");
		}
	}
}
