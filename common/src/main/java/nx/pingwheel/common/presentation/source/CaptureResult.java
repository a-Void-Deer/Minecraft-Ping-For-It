package nx.pingwheel.common.presentation.source;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationLimits;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;

/**
 * One detached, immutable observation produced by a bounded source step.
 *
 * <p>Availability, completeness, and consistency are independent facts and are
 * never inferred from one another: a complete non-atomic (eventual) sweep is a
 * valid result, and an exhausted budget is a scheduling defer rather than a
 * result. Coverage describes the truth of the whole sweep behind this
 * observation, not progress inside one page; {@code demandStamp} keeps the
 * observation bound to the demand it answered.
 *
 * <p>The payload is optional. An absent payload is never an empty snapshot: a
 * readable observation with no payload carries no data, while an empty
 * {@link SnapshotRecord} is an observed whole-unit replacement that happens to
 * have no fields. An unavailable or invalid source carries no payload at all.
 *
 * <p>{@code sourceVersion} is opaque observation evidence only, never a
 * protocol revision; the publication layer assigns fences and revisions.
 * {@code nextCursor} is bounded detached provider state, never a live world
 * object. Every value carried here is already detached: no game object, item
 * stack, component, or raw NBT may enter this type, and a missing key is never
 * synthesized into a zero.
 */
public record CaptureResult(
	Optional<CapturePayload> payload,
	Coverage coverage,
	Availability availability,
	Completeness completeness,
	Consistency consistency,
	Optional<String> sourceVersion,
	Optional<BoundedCursor> nextCursor
) {

	public enum Availability {
		READABLE,
		UNAVAILABLE,
		INVALID
	}

	public enum Completeness {
		CONTINUE,
		COMPLETE,
		INCOMPLETE
	}

	public enum Consistency {
		VERIFIED,
		EVENTUAL,
		UNKNOWN
	}

	public CaptureResult {
		payload = Objects.requireNonNull(payload, "payload");
		coverage = Objects.requireNonNull(coverage, "coverage");
		availability = Objects.requireNonNull(availability, "availability");
		completeness = Objects.requireNonNull(completeness, "completeness");
		consistency = Objects.requireNonNull(consistency, "consistency");
		sourceVersion = requireOptionalToken(sourceVersion, "sourceVersion");
		nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
		if (availability != Availability.READABLE && payload.isPresent())
			throw new IllegalArgumentException("an unavailable or invalid source cannot carry a payload");
	}

	/**
	 * Truth of the whole sweep behind one result. {@code demandStamp} is the
	 * server-derived identity of the demand this sweep answered, so a completed
	 * narrow-range sweep is never reused for a different demand.
	 * {@code watermark} and {@code scanned} are bounded progress counters of the
	 * whole sweep; {@code expected} is the optionally known whole-sweep size,
	 * absent when the domain cannot bound it. Counters never decrease within one
	 * demand and are never synthesized from missing observations.
	 */
	public record Coverage(String demandStamp, long watermark, long scanned, OptionalLong expected) {
		public Coverage {
			demandStamp = SourceKey.requireToken(demandStamp, "demandStamp");
			Objects.requireNonNull(expected, "expected");
			if (watermark < 0L) throw new IllegalArgumentException("scan watermark must be non-negative");
			if (scanned < 0L) throw new IllegalArgumentException("scanned count must be non-negative");
			if (expected.isPresent() && expected.getAsLong() < 0L)
				throw new IllegalArgumentException("expected count must be non-negative when known");
		}
	}

	/**
	 * Bounded, opaque resume state owned by a provider. Only a detached token is
	 * retained: never a live world object, iterator, or inventory handle, and
	 * never source identity or a protocol revision.
	 */
	public record BoundedCursor(String token) {

		/** Engineering bound for one opaque provider cursor token; not a product value. */
		static final int MAX_TOKEN_BYTES = 1024;

		public BoundedCursor {
			Objects.requireNonNull(token, "token");
			if (token.isBlank()) throw new IllegalArgumentException("cursor token must not be blank");
			if (token.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES)
				throw new IllegalArgumentException("cursor token exceeds the cursor bound");
		}
	}

	/**
	 * One bounded observation payload. {@link SnapshotRecord} and
	 * {@link KeyedFragment} carry detached presentation values;
	 * {@link OpaqueKeyedFragment} carries bounded opaque domain bytes whose
	 * codec is identified in code. None of the forms may carry game objects,
	 * item stacks, components, or raw NBT.
	 */
	public sealed interface CapturePayload permits CaptureResult.SnapshotRecord, CaptureResult.KeyedFragment,
		CaptureResult.OpaqueKeyedFragment {}

	/**
	 * Whole-unit replacement of existing presentation fields. The map is copied
	 * on construction and bounded by the existing presentation field and
	 * record-value limits; an absent field stays absent rather than being zeroed.
	 */
	public record SnapshotRecord(Map<String, PresentationValue> fields) implements CapturePayload {
		public SnapshotRecord {
			Objects.requireNonNull(fields, "fields");
			if (fields.size() > PresentationLimits.MAX_FIELDS)
				throw new IllegalArgumentException("snapshot record exceeds the field bound");
			Map<String, PresentationValue> copy = new LinkedHashMap<>();
			for (Map.Entry<String, PresentationValue> entry : fields.entrySet()) {
				String fieldId = Objects.requireNonNull(entry.getKey(), "field id");
				PresentationIds.validate(fieldId);
				PresentationValue value = Objects.requireNonNull(entry.getValue(), "field value");
				PresentationLimits.validate(value);
				copy.put(fieldId, value);
			}
			fields = Map.copyOf(copy);
		}
	}

	/**
	 * Bounded page of keyed absolute domain values. Keys are opaque,
	 * server-canonical domain tokens (for example a future item or component
	 * hash token), not display strings; values are bounded presentation values.
	 * A missing key means unchanged or unknown, never zero, and an explicit zero
	 * value stays an explicit zero.
	 */
	public record KeyedFragment(Map<String, PresentationValue> entries) implements CapturePayload {

		/**
		 * Engineering bound for one fragment page. It is independent of the
		 * record-value entry bound so a whole inventory is never forced into one
		 * {@link PresentationValue.RecordValue}; it is not a product default.
		 */
		static final int MAX_ENTRIES = 256;

		public KeyedFragment {
			Objects.requireNonNull(entries, "entries");
			if (entries.size() > MAX_ENTRIES)
				throw new IllegalArgumentException("keyed fragment exceeds the page bound");
			Map<String, PresentationValue> copy = new LinkedHashMap<>();
			for (Map.Entry<String, PresentationValue> entry : entries.entrySet()) {
				String key = requireDomainKey(entry.getKey());
				PresentationValue value = Objects.requireNonNull(entry.getValue(), "fragment value");
				PresentationLimits.validate(value);
				copy.put(key, value);
			}
			entries = Map.copyOf(copy);
		}

		private static String requireDomainKey(String key) {
			Objects.requireNonNull(key, "domain key");
			if (key.isBlank()) throw new IllegalArgumentException("domain key must not be blank");
			if (key.getBytes(StandardCharsets.UTF_8).length > PresentationPropertyRef.MAX_KEY_BYTES)
				throw new IllegalArgumentException("domain key exceeds the key byte bound");
			return key;
		}
	}

	/**
	 * Deep-detached immutable opaque bytes. Construction copies the caller's
	 * array and every access returns a fresh copy, so neither the producer nor
	 * any consumer can mutate a carried payload. The generic layer never
	 * interprets these bytes: the owning codec identified by
	 * {@link OpaqueKeyedFragment#codecId()} defines their meaning and keeps its
	 * own stricter domain bounds.
	 */
	public record OpaqueValue(byte[] bytes) {

		/** Engineering guard for one opaque value; not a product or wire value. */
		static final int MAX_VALUE_BYTES = 8192;

		public OpaqueValue {
			Objects.requireNonNull(bytes, "bytes");
			if (bytes.length > MAX_VALUE_BYTES)
				throw new IllegalArgumentException("opaque value exceeds the value bound");
			bytes = bytes.clone();
		}

		/** A fresh copy; callers can never mutate the carried bytes. */
		@Override
		public byte[] bytes() {
			return bytes.clone();
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof OpaqueValue value && Arrays.equals(bytes, value.bytes);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(bytes);
		}
	}

	/**
	 * Bounded page of keyed opaque domain values, identified by the code-owned
	 * codec that produced them. Keys are bounded opaque server tokens and
	 * values are deep-detached bytes; the generic layer never interprets
	 * either. This form lets a domain carry lossless data, such as an exact
	 * integer or a display string beyond the presentation text bound, without
	 * widening the existing global presentation limits: the domain codec owns
	 * normalization and its own stricter encoding limits.
	 *
	 * <p>{@code codecId} names a code-registered encoding, not a dynamic plugin
	 * type. A missing key means unchanged or unknown, never zero, and an
	 * explicit value stays explicit.
	 */
	public record OpaqueKeyedFragment(String codecId, Map<String, OpaqueValue> entries) implements CapturePayload {

		/**
		 * Engineering bound for one opaque fragment page. It is independent of
		 * the record-value entry bound and of any domain page quota; it is not
		 * a product default.
		 */
		static final int MAX_ENTRIES = 256;

		public OpaqueKeyedFragment {
			codecId = SourceKey.requireToken(codecId, "codecId");
			Objects.requireNonNull(entries, "entries");
			if (entries.size() > MAX_ENTRIES)
				throw new IllegalArgumentException("opaque fragment exceeds the page bound");
			Map<String, OpaqueValue> copy = new LinkedHashMap<>();
			for (Map.Entry<String, OpaqueValue> entry : entries.entrySet()) {
				String key = SourceKey.requireToken(entry.getKey(), "opaque key");
				OpaqueValue value = Objects.requireNonNull(entry.getValue(), "opaque value");
				copy.put(key, value);
			}
			entries = Map.copyOf(copy);
		}
	}

	private static Optional<String> requireOptionalToken(Optional<String> token, String name) {
		Objects.requireNonNull(token, name);
		token.ifPresent(value -> SourceKey.requireToken(value, name));
		return token;
	}
}
