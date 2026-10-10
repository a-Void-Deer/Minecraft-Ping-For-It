package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.RulesView;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationChildPolicyPacketsTest {
	private static final PresentationPropertyRef CHILD = new PresentationPropertyRef("create:presentation",
		"create:kinetic.speed", List.of("effective_rpm"));
	private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }
	private static void header(FriendlyByteBuf buf) {
		buf.writeEnum(PresentationS2CPacket.Kind.RESET); buf.writeVarInt(5);
		buf.writeLong(91); buf.writeLong(2); buf.writeLong(0); buf.writeVarInt(0); // empty field mask
	}
	private static void rawChildren(FriendlyByteBuf buf, Map<String, List<PresentationPropertyRef>> children) {
		buf.writeVarInt(children.size());
		children.forEach((type, refs) -> {
			buf.writeUtf(type); buf.writeVarInt(refs.size()); refs.forEach(ref -> PresentationCodec.writePropertyRef(buf, ref));
		});
	}
	private static void rejectedReset(Consumer<FriendlyByteBuf> body) {
		var buf = buffer();
		try {
			header(buf); body.accept(buf);
			assertTrue(PresentationS2CPacket.readSafe(buf).isCorrupt());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}

	@Test void completeFiveTypeMapAndFieldMaskRoundTripWithoutInventingDefaults() {
		var children = new LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack());
		children.put("block", List.of(CHILD));
		var mask = Map.of("block", Map.of(CHILD.adapterId(), Set.of(CHILD.fieldId())));
		var packet = PresentationS2CPacket.reset(91, 2, mask, children);
		children.clear();
		var buf = buffer();
		try {
			packet.write(buf);
			assertEquals(packet.encodedResetBodyBytes(), buf.readableBytes());
			var decoded = PresentationS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt()); assertEquals(mask, decoded.mask());
			assertEquals(List.of(CHILD), decoded.childBlack().get("block"));
			assertTrue(decoded.childBlack().get("entity").isEmpty(), "explicit [] must not gain Create defaults");
			assertEquals(Set.copyOf(PresentationSettings.TARGET_TYPE_IDS), decoded.childBlack().keySet());
			assertThrows(UnsupportedOperationException.class, () -> decoded.childBlack().get("block").clear());
		} finally { buf.release(); }
	}

	@Test void resetRejectsMissingUnknownDuplicateRootAndOverCapacityChildMetadata() {
		rejectedReset(buf -> {}); // v4 RESET cannot decode as v5
		rejectedReset(buf -> buf.writeVarInt(0));
		var missing = new LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack()); missing.remove("entity");
		rejectedReset(buf -> rawChildren(buf, missing));
		var unknown = new LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack()); unknown.remove("entity"); unknown.put("unknown", List.of());
		rejectedReset(buf -> rawChildren(buf, unknown));
		rejectedReset(buf -> { buf.writeVarInt(5); for (int i = 0; i < 5; i++) { buf.writeUtf("block"); buf.writeVarInt(0); } });
		for (var refs : List.of(List.of(CHILD, CHILD), List.of(PresentationPropertyRef.root(CHILD.adapterId(), CHILD.fieldId())))) {
			var malformed = new LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack()); malformed.put("block", refs);
			rejectedReset(buf -> rawChildren(buf, malformed));
			assertThrows(IllegalArgumentException.class, () -> PresentationS2CPacket.reset(91, 2, Map.of(), malformed));
		}
		rejectedReset(buf -> { buf.writeVarInt(5); buf.writeUtf("block"); buf.writeVarInt(PresentationSettings.MAX_CHILD_BLACK_REFS + 1); });
		rejectedReset(buf -> { rawChildren(buf, PresentationS2CPacket.emptyChildBlack()); buf.writeByte(1); });
	}

	@Test void resetRejectsInvalidPathUtfDepthAndNoncanonicalMetadata() {
		for (Consumer<FriendlyByteBuf> ref : List.<Consumer<FriendlyByteBuf>>of(
			buf -> { buf.writeUtf("invalid"); buf.writeUtf(CHILD.fieldId()); buf.writeVarInt(1); buf.writeUtf("key"); },
			buf -> { buf.writeUtf(CHILD.adapterId()); buf.writeUtf(CHILD.fieldId()); buf.writeVarInt(1); buf.writeUtf(" "); },
			buf -> { buf.writeUtf(CHILD.adapterId()); buf.writeUtf(CHILD.fieldId()); buf.writeVarInt(5); },
			buf -> { buf.writeUtf(CHILD.adapterId()); buf.writeUtf(CHILD.fieldId()); buf.writeByte(0x81); buf.writeByte(0); buf.writeUtf("key"); },
			buf -> { buf.writeUtf(CHILD.adapterId()); buf.writeUtf(CHILD.fieldId()); buf.writeVarInt(1); buf.writeVarInt(1); buf.writeByte(0xff); })) {
			rejectedReset(buf -> { buf.writeVarInt(5); buf.writeUtf("block"); buf.writeVarInt(1); ref.accept(buf); });
		}
		rejectedReset(buf -> { buf.writeByte(0x85); buf.writeByte(0); });
		var old = buffer();
		try { old.writeEnum(PresentationS2CPacket.Kind.RESET); old.writeVarInt(4); old.writeLong(91); old.writeLong(2); old.writeLong(0);
			old.writeVarInt(0); rawChildren(old, PresentationS2CPacket.emptyChildBlack()); assertTrue(PresentationS2CPacket.readSafe(old).isCorrupt());
		} finally { old.release(); }
	}

	private static List<PresentationPropertyRef> maximalRefs() {
		String id = "a".repeat(64) + ":" + "b".repeat(128);
		List<PresentationPropertyRef> refs = new ArrayList<>();
		for (int i = 0; i < PresentationSettings.MAX_CHILD_BLACK_REFS; i++)
			refs.add(new PresentationPropertyRef(id, id, List.of((i + "x".repeat(128)).substring(0, 128), "y".repeat(128), "z".repeat(128), "q".repeat(128))));
		return refs;
	}
	@Test void maximalAcceptedRefsAndMaskFitForgeOneMiBWithoutTruncation() {
		var children = new LinkedHashMap<String, List<PresentationPropertyRef>>();
		var mask = new LinkedHashMap<String, Map<String, Set<String>>>();
		var fields = new java.util.LinkedHashSet<String>();
		for (int i = 0; i < 256; i++) fields.add("a".repeat(64) + ":" + (i + "b".repeat(128)).substring(0, 128));
		for (String type : PresentationSettings.TARGET_TYPE_IDS) {
			children.put(type, maximalRefs());
			var adapters = new LinkedHashMap<String, Set<String>>();
			var ordered = List.copyOf(fields);
			for (int i = 0; i < 32; i++) adapters.put("a".repeat(64) + ":" + (i + "b".repeat(128)).substring(0, 128),
				Set.copyOf(ordered.subList(i * 8, (i + 1) * 8)));
			mask.put(type, adapters);
		}
		var packet = PresentationS2CPacket.reset(91, 2, mask, children);
		var buf = buffer();
		try { packet.write(buf); assertTrue(buf.readableBytes() < 1048576); assertEquals(packet.encodedResetBodyBytes(), buf.readableBytes());
			var decoded = PresentationS2CPacket.readSafe(buf); assertFalse(decoded.isCorrupt()); assertEquals(children, decoded.childBlack()); assertEquals(mask, decoded.mask());
		} finally { buf.release(); }
	}

	@Test void policyV3CarriesCompleteChildListAndExplicitOptOut() {
		var rules = new LinkedHashMap<>(ServerPresentationPolicyS2CPacket.defaultRules());
		rules.put("block", new RulesView(List.of("create:*"), List.of("minecraft:*"), true, List.of(CHILD)));
		rules.put("entity", new RulesView(List.of(), List.of(), false, List.of()));
		var buf = buffer();
		try { new ServerPresentationPolicyS2CPacket(1, 2, Status.OK, true, rules).write(buf);
			var decoded = ServerPresentationPolicyS2CPacket.readSafe(buf); assertFalse(decoded.isCorrupt()); assertEquals(rules, decoded.rules());
		} finally { buf.release(); }
	}
	@Test void policyV3RejectsMalformedChildListsAndMissingOldWireList() {
		for (Consumer<FriendlyByteBuf> child : List.<Consumer<FriendlyByteBuf>>of(
			buf -> {}, buf -> { buf.writeVarInt(129); },
			buf -> { buf.writeVarInt(2); PresentationCodec.writePropertyRef(buf, CHILD); PresentationCodec.writePropertyRef(buf, CHILD); },
			buf -> { buf.writeVarInt(1); PresentationCodec.writePropertyRef(buf, PresentationPropertyRef.root(CHILD.adapterId(), CHILD.fieldId())); },
			buf -> { buf.writeVarInt(1); buf.writeUtf(CHILD.adapterId()); buf.writeUtf(CHILD.fieldId()); buf.writeVarInt(1); buf.writeVarInt(1); buf.writeByte(0xff); })) {
			var buf = buffer();
			try { buf.writeVarLong(1); buf.writeVarLong(2); buf.writeVarInt(Status.OK.ordinal()); buf.writeBoolean(true); buf.writeVarInt(5);
				buf.writeUtf("block"); buf.writeVarInt(0); buf.writeVarInt(0); buf.writeBoolean(false); child.accept(buf);
				assertTrue(ServerPresentationPolicyS2CPacket.readSafe(buf).isCorrupt());
			} finally { buf.release(); }
		}
		var old = buffer();
		try {
			old.writeVarLong(1); old.writeVarLong(2); old.writeVarInt(Status.OK.ordinal()); old.writeBoolean(true); old.writeVarInt(5);
			for (String type : PresentationSettings.TARGET_TYPE_IDS) {
				old.writeUtf(type); old.writeVarInt(0); old.writeVarInt(0); old.writeBoolean(false);
			}
			assertTrue(ServerPresentationPolicyS2CPacket.readSafe(old).isCorrupt(), "a complete old v2 rule frame cannot be reinterpreted as child-empty v3");
		} finally { old.release(); }
	}
	@Test void maximalPolicyViewFitsExistingTransportWithoutTruncation() {
		List<String> selectors = new ArrayList<>();
		for (int i = 0; i < 128; i++) selectors.add("a".repeat(64) + ":" + (i + "b".repeat(128)).substring(0, 128));
		var rules = new LinkedHashMap<String, RulesView>();
		for (String type : PresentationSettings.TARGET_TYPE_IDS) rules.put(type, new RulesView(selectors, selectors, false, maximalRefs()));
		var buf = buffer();
		try { new ServerPresentationPolicyS2CPacket(1, 2, Status.OK, true, rules).write(buf);
			assertTrue(buf.readableBytes() < 1048576); var decoded = ServerPresentationPolicyS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt()); assertEquals(rules, decoded.rules());
		} finally { buf.release(); }
	}

	@Test void oversizedBodiesRejectBeforeParsingAndPolicyBooleanIsStrict() {
		for (boolean presentation : List.of(true, false)) {
			var buf = buffer();
			try { buf.writeZero(1048577);
				assertTrue(presentation ? PresentationS2CPacket.readSafe(buf).isCorrupt() : ServerPresentationPolicyS2CPacket.readSafe(buf).isCorrupt());
			} finally { buf.release(); }
		}
		var buf = buffer();
		try { buf.writeVarLong(1); buf.writeVarLong(2); buf.writeVarInt(Status.OK.ordinal()); buf.writeByte(2);
			assertTrue(ServerPresentationPolicyS2CPacket.readSafe(buf).isCorrupt());
		} finally { buf.release(); }
	}
}
