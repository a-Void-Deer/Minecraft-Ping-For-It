package nx.pingwheel.common.presentation.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.presentation.source.CaptureResult;

/** Bounded detached fluid codec and exact-identity interning; native encoding is a separate synchronous boundary. */
@NotNullByDefault
public final class InventoryFluidCodec {
	public static final CaptureResult.OpaqueCodec CODEC = CaptureResult.OpaqueCodec.INVENTORY_FLUID_OBSERVATION_V1;
	public static final int MAX_BYTES = CODEC.bound(), MAX_CANONICAL_BYTES = 65536;
	public static final int MAX_DISPLAY_BYTES = InventoryLimits.MAX_DISPLAY_BYTES, MAX_LABEL_BYTES = 1024;
	private InventoryFluidCodec() {}

	/**
	 * Complete native persistent identity, already safely encoded by an admitted native freezer.
	 * Null canonical bytes mean identity encoding was refused AFTER a reliable fluid id and amount were read.
	 * Neither a failed getter nor overflow may be translated to this refusal.
	 */
	@NotNullByDefault
	public static final class Identity {
		private final byte @Nullable @Unmodifiable [] canonical;
		private Identity(byte @Nullable [] canonical) {
			if (canonical != null && (canonical.length == 0 || canonical.length > MAX_CANONICAL_BYTES))
				throw new IllegalArgumentException("fluid canonical bounds");
			this.canonical = canonical == null ? null : canonical.clone();
		}
		/** Complete safely bounded native canonical bytes, not a truncated encoding or a digest supplied by a provider. */
		public static Identity exact(byte[] canonical) { return new Identity(Objects.requireNonNull(canonical)); }
		public static Identity refused() { return new Identity(null); }
		public boolean aggregate() { return canonical == null; }
	}

	/**
	 * Observation-owned collision evidence, not a global cache. Caller reserves retained/growth and work
	 * from the ONE inventory Runtime before interning; these finite local guards do not replace its ledgers.
	 */
	@NotNullByDefault
	public static final class Catalog {
		private final Map<String, byte @Unmodifiable []> identities = new HashMap<>();
		private long retainedBytes;
		public long retainedBytes() { return retainedBytes; }
		public InventoryFluidValue intern(String fluidId, long amount, Identity identity, InventoryFluidValue.Display display) {
			requireId(fluidId); InventoryQuantities.require(amount); Objects.requireNonNull(identity); Objects.requireNonNull(display);
			if (!fluidId.equals(display.fluidId())) throw new IllegalArgumentException("fluid display identity");
			byte @Nullable @Unmodifiable [] canonical = identity.canonical;
			String digest = "";
			if (canonical != null) {
				digest = digest(canonical);
				byte @Nullable @Unmodifiable [] previous = identities.get(digest);
				if (previous != null && !Arrays.equals(previous, canonical)) throw new IllegalStateException("ambiguous fluid identity digest");
				if (previous == null) {
					long next = Math.addExact(retainedBytes, 256L + canonical.length);
					if (identities.size() == 256 || next > 262144) throw new IllegalStateException("fluid identity catalogue capacity");
					identities.put(digest, canonical); retainedBytes = next;
				}
			}
			return new InventoryFluidValue(new InventoryScanner.Key(InventoryResourceKind.FLUID, fluidId, digest), amount, display, canonical == null);
		}
	}
	private static String digest(byte[] canonical) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}

	/** Empty bytes mean a positively observed empty address. Unavailable reads must throw instead. */
	public static CaptureResult.OpaqueValue encode(@Nullable InventoryFluidValue value) {
		if (value == null) return CaptureResult.OpaqueValue.forCodec(CODEC, new byte[0]);
		try {
			var bytes = new ByteArrayOutputStream();
			try (var out = new DataOutputStream(bytes)) {
				out.writeByte(1); text(out, value.key().resourceId(), 256); text(out, value.key().componentsKey(), 256);
				out.writeLong(value.amount()); text(out, value.display().label(), MAX_LABEL_BYTES);
				out.writeBoolean(value.display().displayJson() != null);
				if (value.display().displayJson() != null) text(out, value.display().displayJson(), MAX_DISPLAY_BYTES);
				out.writeBoolean(value.aggregate());
			}
			if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("fluid observation bounds");
			return CaptureResult.OpaqueValue.forCodec(CODEC, bytes.toByteArray());
		} catch (IOException impossible) { throw new IllegalStateException(impossible); }
	}
	public static @Nullable InventoryFluidValue decode(CaptureResult.OpaqueValue value) {
		Objects.requireNonNull(value);
		if (value.codec() != CODEC) throw new IllegalArgumentException("fluid observation codec");
		if (value.size() == 0) return null;
		if (value.size() > MAX_BYTES) throw new IllegalArgumentException("fluid observation bounds");
		try (var in = new DataInputStream(new ByteArrayInputStream(value.bytes()))) {
			if (in.readUnsignedByte() != 1) throw new IllegalArgumentException("fluid observation version");
			String id = text(in, 256), components = text(in, 256); long amount = in.readLong();
			String label = text(in, MAX_LABEL_BYTES);
			@Nullable String display = bool(in) ? text(in, MAX_DISPLAY_BYTES) : null;
			var result = new InventoryFluidValue(new InventoryScanner.Key(InventoryResourceKind.FLUID, id, components), amount,
				new InventoryFluidValue.Display(id, label, display), bool(in));
			if (in.available() != 0) throw new IllegalArgumentException("trailing fluid observation");
			return result;
		} catch (IOException failure) { throw new IllegalArgumentException("corrupt fluid observation", failure); }
	}
	private static boolean bool(DataInputStream in) throws IOException {
		int value = in.readUnsignedByte(); if (value > 1) throw new IllegalArgumentException("fluid observation boolean"); return value == 1;
	}
	private static void text(DataOutputStream out, String value, int bound) throws IOException {
		requireText(value, bound); byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
	}
	private static String text(DataInputStream in, int bound) throws IOException {
		int size = in.readInt(); if (size < 0 || size > bound || size > in.available()) throw new IllegalArgumentException("fluid observation text");
		try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(in.readNBytes(size))).toString(); }
		catch (CharacterCodingException malformed) { throw new IllegalArgumentException("fluid observation UTF", malformed); }
	}
	static void requireText(String text, int bound) {
		Objects.requireNonNull(text);
		if (text.length() > bound) throw new IllegalArgumentException("fluid text bounds");
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isHighSurrogate(c)) { if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("fluid UTF"); }
			else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("fluid UTF");
		}
		if (text.getBytes(StandardCharsets.UTF_8).length > bound) throw new IllegalArgumentException("fluid text bounds");
	}
	static void requireId(String id) {
		requireText(id, 256);
		int colon = id.indexOf(':');
		if (colon <= 0 || colon == id.length() - 1) throw new IllegalArgumentException("fluid registry id");
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (i == colon || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || i > colon && c == '/') continue;
			throw new IllegalArgumentException("fluid registry id");
		}
	}
}
