package nx.pingwheel.common.presentation.minecraft;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/** Pure preallocation bounds and one Basic encoding for inventory atomic initials. */
@NotNullByDefault
final class InventoryInitialPayload {
	static final PresentationSection EMPTY_BASIC = new PresentationSection(nx.pingwheel.common.presentation.PresentationBasic.ID, 1, Map.of(), true);
	private InventoryInitialPayload() {}

	/** No encoding, UTF buffers, sorted copies, record paths or native objects are constructed here. */
	static int basicBytes(PresentationSection section) {
		int size = utf(section.adapterId()) + varInt(section.schema()) + 1 + varInt(section.fields().size());
		for (var entry : section.fields().entrySet()) {
			int field = valueBytes(entry.getValue(), false);
			for (var annotation : section.annotations().entrySet())
				if (annotation.getKey().fieldId().equals(entry.getKey())) field += utf(annotation.getValue());
			if (field > PresentationCodec.MAX_FIELD_BYTES) throw new IllegalArgumentException("initial Basic field bound");
			size += utf(entry.getKey()) + varInt(field) + field;
			if (size > PresentationCodec.MAX_SECTION_BYTES) throw new IllegalArgumentException("initial Basic section bound");
		}
		return varInt(size) + size;
	}
	private static int valueBytes(PresentationValue value, boolean insideSequence) {
		int size = 1 + (insideSequence ? 0 : 1);
		return size + switch (value) {
			case PresentationValue.Text text -> utf(text.value());
			case PresentationValue.NumberValue ignored -> 8;
			case PresentationValue.Flag ignored -> 1;
			case PresentationValue.Sequence sequence -> {
				int children = varInt(sequence.values().size());
				for (var child : sequence.values()) { children += valueBytes(child, true); requireFieldBound(children); }
				yield children;
			}
			case PresentationValue.RecordValue record -> {
				int children = varInt(record.values().size());
				for (var entry : record.values().entrySet()) { children += utf(entry.getKey()) + valueBytes(entry.getValue(), insideSequence); requireFieldBound(children); }
				yield children;
			}
		};
	}
	/** Codec frame/field growth, UTF validation, tree/path objects and both Basic byte-array owners overlap. */
	static long constructionWorkspace(int basicBytes) { return 4096L + 64L * basicBytes; }

	/** Existing marker strings bound both target and frozen-key framing; no fixed 1 MiB reservation. */
	static int bodyUpper(ServerMarker marker, String owner, PresentationPropertyRef defaultRef,
		PresentationReceiptContent content, int basicBytes) {
		int size = 256 + 5 + basicBytes + utf(owner) + utf(marker.targetType().id()) + utf(marker.pingType().id())
			+ utf(defaultRef.adapterId()) + utf(defaultRef.fieldId()) + 5 + PresentationCodec.receiptContentEncodedSize(content);
		for (String key : defaultRef.recordPath()) size += utf(key);
		Target target = marker.target();
		size += utf(target.dimensionId()) + switch (target) {
			case Target.EntityTarget ignored -> 32;
			case Target.LocationTarget ignored -> 24;
			case Target.BlockTarget block -> 13 + utf(block.blockRegistryId());
			case Target.ExternalBlockTarget external -> 2 + utf(external.providerId()) + utf(external.stableTargetId())
				+ utf(external.expectedBlockRegistryId()) + utf(external.providerLocator());
		};
		TargetKey key = marker.targetKey();
		size += utf(key.dimensionId()) + switch (key) {
			case TargetKey.EntityKey ignored -> 32;
			case TargetKey.LocationKey ignored -> 24;
			case TargetKey.BlockKey block -> 13 + utf(block.blockRegistryId());
			case TargetKey.DoubleChestKey chest -> 25 + utf(chest.blockRegistryId());
			case TargetKey.VaultKey vault -> 13 + utf(vault.blockRegistryId());
			case TargetKey.CreateTankKey tank -> 13 + utf(tank.blockRegistryId());
			case TargetKey.ExternalBlockKey external -> 1 + utf(external.providerId()) + utf(external.stableTargetId())
				+ utf(external.expectedBlockRegistryId());
		};
		return size;
	}

	/** Caller owns construction admission before entering this method. No bounded() probe or second Basic encoding. */
	static PresentationS2CPacket create(long epoch, long view, long revision, ServerMarker marker, String owner,
		PresentationPropertyRef defaultRef, PresentationReceiptContent content, PresentationSection basic, int basicBytes) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(basicBytes, basicBytes));
		try {
			PresentationCodec.write(buffer, basic);
			if (buffer.readableBytes() != basicBytes) throw new IllegalStateException("initial Basic size mismatch");
			byte @Unmodifiable [] payload = new byte[basicBytes];
			buffer.readBytes(payload);
			return new PresentationS2CPacket(PresentationS2CPacket.Kind.CREATED, PresentationS2CPacket.VERSION,
				epoch, view, revision, Map.of(), Map.of(), Map.of(), Map.of(), MarkerSnapshot.from(marker), owner,
				defaultRef, content, payload, marker.id(), null, null, Optional.empty(), 0, null, null);
		} finally { buffer.release(); }
	}
	private static int utf(String value) { int bytes = ByteBufUtil.utf8Bytes(value); return varInt(bytes) + bytes; }
	private static void requireFieldBound(int size) { if (size > PresentationCodec.MAX_FIELD_BYTES) throw new IllegalArgumentException("initial Basic field bound"); }
	private static int varInt(int value) { int size = 1; while ((value & ~0x7f) != 0) { value >>>= 7; size++; } return size; }
}
