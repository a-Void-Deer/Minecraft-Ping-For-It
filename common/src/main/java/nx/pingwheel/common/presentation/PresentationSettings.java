package nx.pingwheel.common.presentation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * Server-side presentation policy: one independent field-selector rule set per
 * existing target type plus the global capture budget, interval and permission
 * overrides. A missing or unknown target-type rule set fails closed, and a
 * global structural failure denies every type instead of widening access.
 */
public final class PresentationSettings {
	/** The exact existing target type IDs, in catalog order. */
	public static final List<String> TARGET_TYPE_IDS =
		List.of("dropped_item", "entity", "entity_block", "block", "location");

	private static final int MAX_SELECTORS = 128;
	private static final int MAX_OVERRIDES = 128;
	private static final int MAX_SCAN_BUDGET = 4096;
	private static final int MAX_INTERVAL_TICKS = 72000;

	private Map<String, RuleSet> targetTypes = new LinkedHashMap<>();
	private int minUpdateIntervalTicks = 10;
	private int scanBudget = 256;
	private Map<String, Integer> permissionLevels = Map.of();
	private Map<String, Integer> updateIntervals = Map.of();

	private transient boolean invalid;

	/** Every existing target type starts with an empty allow list, so manifest defaults apply. */
	public static PresentationSettings serverDefaults() {
		PresentationSettings settings = new PresentationSettings();
		for (String id : TARGET_TYPE_IDS) settings.targetTypes.put(id, RuleSet.allowByDefault());
		return settings;
	}

	/**
	 * Interim alias used only until the client retains no local field policy;
	 * the server controls every compatible value, so a client instance carries
	 * the same per-type defaults.
	 */
	public static PresentationSettings clientDefaults() {
		return serverDefaults();
	}

	public static boolean isKnownTargetType(String targetTypeId) {
		return targetTypeId != null && TARGET_TYPE_IDS.contains(targetTypeId);
	}

	/**
	 * Invalid deny selectors deny only their own target type; missing types and
	 * global structural failures are durable deny-all states.
	 */
	public void validate() {
		boolean denyAll = false;

		if (targetTypes == null) {
			targetTypes = new LinkedHashMap<>();
			denyAll = true;
			LOGGER.warn("Presentation target-type policy missing; denying all presentation fields");
		}

		Map<String, RuleSet> checked = new LinkedHashMap<>();
		for (String id : TARGET_TYPE_IDS) {
			RuleSet rules = targetTypes.get(id);
			if (rules == null) {
				rules = RuleSet.denyAll();
				LOGGER.warn("Presentation policy for target type {} missing; denying its fields", id);
			} else {
				rules.validate();
			}
			checked.put(id, rules);
		}
		if (targetTypes.size() > TARGET_TYPE_IDS.size())
			LOGGER.warn("Unknown presentation target-type policy entries ignored");
		targetTypes = checked;

		if (permissionLevels == null || permissionLevels.size() > MAX_OVERRIDES) {
			denyAll = true;
			permissionLevels = Map.of();
			LOGGER.warn("Invalid presentation permission overrides; denying all presentation fields");
		} else {
			Map<String, Integer> checkedLevels = new TreeMap<>();
			for (var entry : permissionLevels.entrySet()) {
				if (!validId(entry.getKey()) || entry.getValue() == null) {
					denyAll = true;
					LOGGER.warn("Invalid presentation permission override; denying all presentation fields");
					continue;
				}
				int level = Math.clamp(entry.getValue(), 0, 4);
				if (level != entry.getValue()) LOGGER.warn("Presentation permission level clamped");
				checkedLevels.put(entry.getKey(), level);
			}
			permissionLevels = Map.copyOf(checkedLevels);
		}

		if (updateIntervals == null || updateIntervals.size() > MAX_OVERRIDES) {
			updateIntervals = Map.of();
			LOGGER.warn("Invalid presentation update intervals; using declared adapter intervals");
		} else {
			Map<String, Integer> checkedIntervals = new TreeMap<>();
			for (var entry : updateIntervals.entrySet()) {
				if (!validId(entry.getKey()) || entry.getValue() == null) {
					LOGGER.warn("Invalid presentation update interval ignored");
					continue;
				}
				checkedIntervals.put(entry.getKey(), Math.clamp(entry.getValue(), 1, MAX_INTERVAL_TICKS));
			}
			updateIntervals = Map.copyOf(checkedIntervals);
		}

		int oldInterval = minUpdateIntervalTicks;
		int oldBudget = scanBudget;
		minUpdateIntervalTicks = Math.clamp(minUpdateIntervalTicks, 1, MAX_INTERVAL_TICKS);
		scanBudget = Math.clamp(scanBudget, 0, MAX_SCAN_BUDGET);
		if (oldInterval != minUpdateIntervalTicks) LOGGER.warn("Presentation minUpdateIntervalTicks clamped to {}", minUpdateIntervalTicks);
		if (oldBudget != scanBudget) LOGGER.warn("Presentation scanBudget clamped to {}", scanBudget);

		if (denyAll) {
			// A global structural failure must not become allow-by-default after a
			// reload: only the rule sets persist, so make the denial durable in every
			// target type. A scoped invalid selector still denies only its own type.
			for (String id : TARGET_TYPE_IDS) {
				checked.put(id, RuleSet.denyAll());
			}
			targetTypes = checked;
		}
		invalid |= denyAll;
	}

	/** The effective field policy of one target type; unknown or missing fails closed. */
	public PresentationPolicy policyFor(String targetTypeId) {
		if (invalid || !isKnownTargetType(targetTypeId)) return denyAllPolicy();
		RuleSet rules = targetTypes == null ? null : targetTypes.get(targetTypeId);
		if (rules == null) return denyAllPolicy();
		try {
			return rules.policy();
		} catch (RuntimeException ex) {
			return denyAllPolicy();
		}
	}

	/** A detached copy of one target type's rule view; unknown or missing fails closed. */
	public RuleSet rulesFor(String targetTypeId) {
		if (invalid || !isKnownTargetType(targetTypeId)) return RuleSet.denyAll();
		RuleSet rules = targetTypes == null ? null : targetTypes.get(targetTypeId);
		return rules == null ? RuleSet.denyAll() : rules.copy();
	}

	/** Installs one validated rule set for a selected target type. */
	public void setRules(String targetTypeId, RuleSet rules) {
		if (!isKnownTargetType(targetTypeId) || rules == null)
			throw new IllegalArgumentException("unknown presentation target type");
		if (targetTypes == null) targetTypes = new LinkedHashMap<>();
		RuleSet stored = rules.copy();
		stored.validate();
		targetTypes.put(targetTypeId, stored);
	}

	/** A detached immutable view of all persisted target-type rule sets. */
	public Map<String, RuleSet> targetTypes() {
		Map<String, RuleSet> copy = new LinkedHashMap<>();
		if (targetTypes != null) targetTypes.forEach((id, rules) -> copy.put(id, rules.copy()));
		return Map.copyOf(copy);
	}

	/** Replaces every persisted field with a detached copy of {@code source}. */
	public void copyFrom(PresentationSettings source) {
		Objects.requireNonNull(source, "source");
		invalid = source.invalid;
		minUpdateIntervalTicks = source.minUpdateIntervalTicks;
		scanBudget = source.scanBudget;
		permissionLevels = source.permissionLevels == null ? Map.of() : Map.copyOf(source.permissionLevels);
		updateIntervals = source.updateIntervals == null ? Map.of() : Map.copyOf(source.updateIntervals);
		Map<String, RuleSet> rules = new LinkedHashMap<>();
		if (source.targetTypes != null) source.targetTypes.forEach((id, value) -> rules.put(id, value.copy()));
		targetTypes = rules;
	}

	public PresentationSettings deepCopy() {
		PresentationSettings copy = new PresentationSettings();
		copy.copyFrom(this);
		return copy;
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

	public int getMinUpdateIntervalTicks() { return minUpdateIntervalTicks; }
	public Map<String, Integer> getPermissionLevels() { return permissionLevels; }
	public Map<String, Integer> getUpdateIntervals() { return updateIntervals; }

	public void setMinUpdateIntervalTicks(int value) { minUpdateIntervalTicks = value; validate(); }
	public void setScanBudget(int value) { scanBudget = value; validate(); }
	public void setPermissionLevels(Map<String, Integer> entries) { permissionLevels = entries; invalid = false; validate(); }
	public void setUpdateIntervals(Map<String, Integer> entries) { updateIntervals = entries; validate(); }

	/** Stable across map ordering and independent instances; poll this after external config edits. */
	public String fingerprint() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			append(digest, String.valueOf(invalid));
			append(digest, String.valueOf(minUpdateIntervalTicks));
			append(digest, String.valueOf(scanBudget));
			appendMap(digest, permissionLevels);
			appendMap(digest, updateIntervals);
			if (targetTypes == null) {
				append(digest, "null");
			} else {
				append(digest, String.valueOf(targetTypes.size()));
				new TreeMap<>(targetTypes).forEach((id, rules) -> {
					append(digest, id);
					rules.appendFingerprint(digest);
				});
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
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

	private static PresentationPolicy denyAllPolicy() {
		return new PresentationPolicy(List.of(), List.of("*:*"), true);
	}

	private static boolean validId(String id) {
		try {
			PresentationIds.validate(id);
			return true;
		} catch (RuntimeException ex) {
			return false;
		}
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

	/**
	 * One target type's independent allow/deny/whitelist-only rule view.
	 * Mutable for Gson and the atomic candidate flow; always installed through a
	 * detached validated copy.
	 */
	public static final class RuleSet {
		private List<String> white = List.of();
		private List<String> black = List.of();
		private boolean whitelistOnly;

		private transient boolean invalid;

		public RuleSet() {}

		public RuleSet(List<String> white, List<String> black, boolean whitelistOnly) {
			this.white = white == null ? List.of() : List.copyOf(white);
			this.black = black == null ? List.of() : List.copyOf(black);
			this.whitelistOnly = whitelistOnly;
		}

		public static RuleSet allowByDefault() {
			return new RuleSet();
		}

		/** Persisted deny-all form: an unmatched field is denied and everything matches. */
		public static RuleSet denyAll() {
			return new RuleSet(List.of(), List.of("*:*"), true);
		}

		private static boolean validSelector(String selector) {
			try {
				new PresentationPolicy(List.of(selector), List.of(), false);
				return true;
			} catch (RuntimeException ex) {
				return false;
			}
		}

		public void validate() {
			boolean denyAll = false;
			List<String> validWhite = new ArrayList<>();
			List<String> validBlack = new ArrayList<>();

			if (white == null || white.size() > MAX_SELECTORS || black == null || black.size() > MAX_SELECTORS) {
				denyAll = true;
				LOGGER.warn("Presentation selectors missing or over capacity; denying fields for this target type");
			} else {
				for (String selector : white) {
					if (validSelector(selector)) validWhite.add(selector);
					else LOGGER.warn("Invalid presentation allow selector ignored");
				}
				for (String selector : black) {
					if (validSelector(selector)) validBlack.add(selector);
					else {
						denyAll = true;
						LOGGER.warn("Invalid presentation deny selector; denying fields for this target type");
					}
				}
			}

			white = List.copyOf(validWhite);
			black = List.copyOf(validBlack);
			invalid |= denyAll;
			if (invalid) {
				// Serialize a durable deny-all state, even though the transient diagnostic flag
				// is reset by Gson when this file is loaded in a later process.
				white = List.of();
				black = List.of("*:*");
				whitelistOnly = true;
			}
		}

		public PresentationPolicy policy() {
			if (invalid) return denyAllPolicy();
			try {
				return new PresentationPolicy(white, black, whitelistOnly);
			} catch (RuntimeException ex) {
				return denyAllPolicy();
			}
		}

		public RuleSet copy() {
			RuleSet copy = new RuleSet();
			copy.white = white == null ? List.of() : List.copyOf(white);
			copy.black = black == null ? List.of() : List.copyOf(black);
			copy.whitelistOnly = whitelistOnly;
			copy.invalid = invalid;
			return copy;
		}

		void appendFingerprint(MessageDigest digest) {
			append(digest, String.valueOf(invalid));
			append(digest, String.valueOf(whitelistOnly));
			append(digest, white == null ? "null" : String.valueOf(white.size()));
			if (white != null) for (String selector : white) append(digest, selector);
			append(digest, black == null ? "null" : String.valueOf(black.size()));
			if (black != null) for (String selector : black) append(digest, selector);
		}

		public List<String> getWhite() { return white; }
		public List<String> getBlack() { return black; }
		public boolean isWhitelistOnly() { return whitelistOnly; }

		public void setWhite(List<String> entries) { white = entries; invalid = false; validate(); }
		public void setBlack(List<String> entries) { black = entries; invalid = false; validate(); }
		public void setWhitelistOnly(boolean value) { whitelistOnly = value; }

		@Override
		public boolean equals(Object other) {
			return other instanceof RuleSet rules
				&& whitelistOnly == rules.whitelistOnly
				&& invalid == rules.invalid
				&& java.util.Objects.equals(white, rules.white)
				&& java.util.Objects.equals(black, rules.black);
		}

		@Override
		public int hashCode() {
			return Objects.hash(white, black, whitelistOnly, invalid);
		}
	}
}
