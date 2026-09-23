package nx.pingwheel.common.presentation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static nx.pingwheel.common.Global.LOGGER;

/** Persisted field selectors and server-side capture/permission limits. */
public final class PresentationSettings {
	private static final int MAX_SELECTORS = 128;
	private static final int MAX_OVERRIDES = 128;
	private static final int MAX_SCAN_BUDGET = 4096;
	private static final int MAX_INTERVAL_TICKS = 72000;
	private static final PresentationPolicy DENY_ALL = new PresentationPolicy(List.of(), List.of(), true);

	// Defaults stay empty so local deny selectors keep precedence; client call
	// sites pass the server-authorized manifest default explicitly.
	private List<String> white = List.of();
	private List<String> black = List.of();
	private boolean whitelistOnly;
	private int minUpdateIntervalTicks = 10;
	private int scanBudget = 256;
	private Map<String, Integer> permissionLevels = Map.of();
	private Map<String, Integer> updateIntervals = Map.of();

	private transient boolean invalid;

	/**
	 * Empty allow list: client call sites pass the manifest default, so a local
	 * deny selector still overrides a server-authorized compatible field.
	 */
	public static PresentationSettings clientDefaults() {
		PresentationSettings settings = new PresentationSettings();
		settings.white = List.of();
		return settings;
	}

	public static PresentationSettings serverDefaults() {
		PresentationSettings settings = new PresentationSettings();
		settings.white = List.of();
		return settings;
	}

	/** Invalid deny selectors or privilege overrides deny every field instead of widening access. */
	public void validate() {
		boolean denyAll = false;
		List<String> validWhite = new ArrayList<>();
		List<String> validBlack = new ArrayList<>();
		if (white == null || white.size() > MAX_SELECTORS || black == null || black.size() > MAX_SELECTORS) {
			denyAll = true;
			LOGGER.warn("Presentation selectors missing or over capacity; denying all presentation fields");
		} else {
			for (String selector : white) {
				if (validSelector(selector)) validWhite.add(selector);
				else LOGGER.warn("Invalid presentation allow selector ignored");
			}
			for (String selector : black) {
				if (validSelector(selector)) validBlack.add(selector);
				else {
					denyAll = true;
					LOGGER.warn("Invalid presentation deny selector; denying all presentation fields");
				}
			}
		}
		white = List.copyOf(validWhite);
		black = List.copyOf(validBlack);

		if (permissionLevels == null || permissionLevels.size() > MAX_OVERRIDES) {
			denyAll = true;
			permissionLevels = Map.of();
			LOGGER.warn("Invalid presentation permission overrides; denying all presentation fields");
		} else {
			Map<String, Integer> checked = new TreeMap<>();
			for (var entry : permissionLevels.entrySet()) {
				if (!validId(entry.getKey()) || entry.getValue() == null) {
					denyAll = true;
					LOGGER.warn("Invalid presentation permission override; denying all presentation fields");
					continue;
				}
				int level = Math.clamp(entry.getValue(), 0, 4);
				if (level != entry.getValue()) LOGGER.warn("Presentation permission level clamped");
				checked.put(entry.getKey(), level);
			}
			permissionLevels = Map.copyOf(checked);
		}

		if (updateIntervals == null || updateIntervals.size() > MAX_OVERRIDES) {
			updateIntervals = Map.of();
			LOGGER.warn("Invalid presentation update intervals; using declared adapter intervals");
		} else {
			Map<String, Integer> checked = new TreeMap<>();
			for (var entry : updateIntervals.entrySet()) {
				if (!validId(entry.getKey()) || entry.getValue() == null) {
					LOGGER.warn("Invalid presentation update interval ignored");
					continue;
				}
				checked.put(entry.getKey(), Math.clamp(entry.getValue(), 1, MAX_INTERVAL_TICKS));
			}
			updateIntervals = Map.copyOf(checked);
		}
		int oldInterval = minUpdateIntervalTicks;
		int oldBudget = scanBudget;
		minUpdateIntervalTicks = Math.clamp(minUpdateIntervalTicks, 1, MAX_INTERVAL_TICKS);
		scanBudget = Math.clamp(scanBudget, 0, MAX_SCAN_BUDGET);
		if (oldInterval != minUpdateIntervalTicks) LOGGER.warn("Presentation minUpdateIntervalTicks clamped to {}", minUpdateIntervalTicks);
		if (oldBudget != scanBudget) LOGGER.warn("Presentation scanBudget clamped to {}", scanBudget);
		invalid |= denyAll;
		if (invalid) {
			// Serialize a durable deny-all state, even though the transient diagnostic flag
			// is reset by Gson when this file is loaded in a later process.
			white = List.of();
			black = List.of("*:*");
			whitelistOnly = true;
		}
	}


	private static boolean validSelector(String selector) {
		try {
			new PresentationPolicy(List.of(selector), List.of(), false);
			return true;
		} catch (RuntimeException ex) {
			return false;
		}
	}

	private static boolean validId(String id) {
		try {
			PresentationIds.validate(id);
			return true;
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/** Recompile on access so external JSON edits and mutable/reflective setters cannot bypass validation. */
	public PresentationPolicy policy() {
		if (invalid) return DENY_ALL;
		try {
			return new PresentationPolicy(white, black, whitelistOnly);
		} catch (RuntimeException ex) {
			return DENY_ALL;
		}
	}

	/** The configured minimum cannot make an adapter poll faster than its manifest. */
	public int interval(String adapterId, int declared) {
		int floor = Math.max(1, declared);
		if (invalid || !validId(adapterId)) return MAX_INTERVAL_TICKS;
		Integer configured = updateIntervals == null ? null : updateIntervals.get(adapterId);
		return Math.max(floor, Math.max(Math.clamp(minUpdateIntervalTicks, 1, MAX_INTERVAL_TICKS),
			configured == null ? 1 : Math.clamp(configured, 1, MAX_INTERVAL_TICKS)));
	}

	/** Per-field overrides replace the manifest's vanilla level; the provider owns the final check. */
	public int permission(String fieldId, int declared) {
		if (invalid || !validId(fieldId)) return 5;
		Integer configured = permissionLevels == null ? null : permissionLevels.get(fieldId);
		return configured == null ? Math.clamp(declared, 0, 4) : Math.clamp(configured, 0, 4);
	}

	public int scanBudget() {
		return invalid ? 0 : Math.clamp(scanBudget, 0, MAX_SCAN_BUDGET);
	}

	/** Stable across map ordering and independent instances; poll this after external config edits. */
	public String fingerprint() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			append(digest, String.valueOf(invalid));
			append(digest, String.valueOf(whitelistOnly));
			append(digest, String.valueOf(minUpdateIntervalTicks));
			append(digest, String.valueOf(scanBudget));
			appendList(digest, white);
			appendList(digest, black);
			appendMap(digest, permissionLevels);
			appendMap(digest, updateIntervals);
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static void appendList(MessageDigest digest, List<String> entries) {
		append(digest, entries == null ? "null" : String.valueOf(entries.size()));
		if (entries != null) for (String entry : entries) append(digest, entry);
	}

	private static void appendMap(MessageDigest digest, Map<String, Integer> entries) {
		append(digest, entries == null ? "null" : String.valueOf(entries.size()));
		if (entries != null) new TreeMap<>(entries).forEach((key, value) -> {
			append(digest, key);
			append(digest, String.valueOf(value));
		});
	}

	private static void append(MessageDigest digest, String value) {
		byte[] bytes = (value == null ? "\0" : value).getBytes(StandardCharsets.UTF_8);
		digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof PresentationSettings settings
			&& fingerprint().equals(settings.fingerprint());
	}

	@Override
	public int hashCode() {
		return fingerprint().hashCode();
	}

	public List<String> getWhite() { return white; }
	public List<String> getBlack() { return black; }
	public boolean isWhitelistOnly() { return whitelistOnly; }
	public int getMinUpdateIntervalTicks() { return minUpdateIntervalTicks; }
	public int getScanBudget() { return scanBudget(); }
	public Map<String, Integer> getPermissionLevels() { return permissionLevels; }
	public Map<String, Integer> getUpdateIntervals() { return updateIntervals; }

	public void setWhite(List<String> entries) { white = entries; invalid = false; validate(); }
	public void setBlack(List<String> entries) { black = entries; invalid = false; validate(); }
	public void setWhitelistOnly(boolean value) { whitelistOnly = value; }
	public void setMinUpdateIntervalTicks(int value) { minUpdateIntervalTicks = value; validate(); }
	public void setScanBudget(int value) { scanBudget = value; validate(); }
	public void setPermissionLevels(Map<String, Integer> entries) { permissionLevels = entries; invalid = false; validate(); }
	public void setUpdateIntervals(Map<String, Integer> entries) { updateIntervals = entries; validate(); }
}
