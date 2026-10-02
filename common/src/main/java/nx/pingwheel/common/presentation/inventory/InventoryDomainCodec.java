package nx.pingwheel.common.presentation.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import nx.pingwheel.common.presentation.source.CaptureResult;

/** Code-owned detached encoding; it never uses SECTION values or doubles for counts. */
public final class InventoryDomainCodec {
	public static final String ID = "pingforit:inventory.observation.v1";
	public static final int MAX_BYTES = 8192;
	public record Item(InventoryScanner.Key key, long count, String label, String displayJson, boolean stripped) {
		public Item {
			Objects.requireNonNull(key);
			Objects.requireNonNull(label);
			if (count < 0 || utf8(label) > 1024 || (displayJson != null && utf8(displayJson) > 4096))
				throw new IllegalArgumentException("inventory observation bounds");
		}
	}
	private InventoryDomainCodec() {}

	/** Empty bytes mean an actually observed empty slot, not an unavailable read. */
	public static CaptureResult.OpaqueValue encode(Item item) {
		if (item == null) return new CaptureResult.OpaqueValue(new byte[0]);
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				text(out, item.key().itemId(), 256);
				text(out, item.key().componentsKey(), 256);
				out.writeLong(item.count());
				text(out, item.label(), 1024);
				out.writeBoolean(item.displayJson() != null);
				if (item.displayJson() != null) text(out, item.displayJson(), 4096);
				out.writeBoolean(item.stripped());
			}
			if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("inventory observation size");
			return new CaptureResult.OpaqueValue(bytes.toByteArray());
		} catch (IOException impossible) { throw new IllegalStateException(impossible); }
	}

	public static Item decode(CaptureResult.OpaqueValue value) {
		byte[] bytes = value.bytes();
		if (bytes.length == 0) return null;
		if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("inventory observation size");
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			InventoryScanner.Key key = new InventoryScanner.Key(text(in, 256), text(in, 256));
			long count = in.readLong();
			String label = text(in, 1024);
			String json = in.readBoolean() ? text(in, 4096) : null;
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
}
