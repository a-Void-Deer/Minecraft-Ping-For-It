package nx.pingwheel.common.presentation.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.presentation.source.CaptureResult;

/** Code-owned detached encoding; it never uses SECTION values or doubles for counts. */
public final class InventoryDomainCodec {
	/** The code-owned observation allowance adopted by this domain codec. */
	public static final CaptureResult.OpaqueCodec CODEC = CaptureResult.OpaqueCodec.INVENTORY_OBSERVATION_V1;
	/** The fragment codec id; it equals the adopted token id. */
	public static final String ID = CODEC.id();
	/** Total observation envelope: the absolute display budget plus its bounded entry metadata. */
	public static final int MAX_BYTES = CODEC.bound();
	/** Absolute display JSON bound: the largest legal display budget. */
	public static final int MAX_DISPLAY_BYTES = InventoryLimits.MAX_DISPLAY_BYTES;
	public record Item(InventoryScanner.Key key, long count, String label, String displayJson, boolean stripped) {
		public Item {
			Objects.requireNonNull(key);
			Objects.requireNonNull(label);
			if (count < 0 || utf8(label) > 1024 || (displayJson != null && utf8(displayJson) > MAX_DISPLAY_BYTES))
				throw new IllegalArgumentException("inventory observation bounds");
		}
	}
	private InventoryDomainCodec() {}

	/** Empty bytes mean an actually observed empty slot, not an unavailable read. */
	public static CaptureResult.OpaqueValue encode(Item item) {
		if (item == null) return CaptureResult.OpaqueValue.forCodec(CODEC, new byte[0]);
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				text(out, item.key().itemId(), 256);
				text(out, item.key().componentsKey(), 256);
				out.writeLong(item.count());
				text(out, item.label(), 1024);
				out.writeBoolean(item.displayJson() != null);
				if (item.displayJson() != null) display(out, item.displayJson());
				out.writeBoolean(item.stripped());
			}
			if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("inventory observation size");
			return CaptureResult.OpaqueValue.forCodec(CODEC, bytes.toByteArray());
		} catch (IOException impossible) { throw new IllegalStateException(impossible); }
	}

	public static Item decode(CaptureResult.OpaqueValue value) {
		Objects.requireNonNull(value, "value");
		int size = value.size();
		if (size == 0) return null;
		if (size > MAX_BYTES) throw new IllegalArgumentException("inventory observation size");
		byte[] bytes = value.bytes();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			InventoryScanner.Key key = new InventoryScanner.Key(text(in, 256), text(in, 256));
			long count = in.readLong();
			String label = text(in, 1024);
			String json = in.readBoolean() ? display(in) : null;
			Item item = new Item(key, count, label, json, in.readBoolean());
			if (in.available() != 0) throw new IllegalArgumentException("trailing observation");
			return item;
		} catch (IOException failure) { throw new IllegalArgumentException("corrupt observation", failure); }
	}
	private static int utf8(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
	private static void text(DataOutputStream out, String value, int limit) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > limit) throw new IllegalArgumentException("observation text");
		out.writeShort(bytes.length);
		out.write(bytes);
	}
	private static String text(DataInputStream in, int limit) throws IOException {
		int size = in.readUnsignedShort();
		if (size > limit || size > in.available()) throw new IllegalArgumentException("observation text");
		return new String(in.readNBytes(size), StandardCharsets.UTF_8);
	}
	/** The display field is the only wide text: its non-negative int length is bounded and checked before allocation. */
	private static void display(DataOutputStream out, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_DISPLAY_BYTES) throw new IllegalArgumentException("observation display");
		out.writeInt(bytes.length);
		out.write(bytes);
	}
	private static String display(DataInputStream in) throws IOException {
		int size = in.readInt();
		if (size < 0 || size > MAX_DISPLAY_BYTES || size > in.available())
			throw new IllegalArgumentException("observation display");
		return new String(in.readNBytes(size), StandardCharsets.UTF_8);
	}
}
