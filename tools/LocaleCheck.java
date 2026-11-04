import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Offline locale consistency checker (B7).
 *
 * Single-file, zero-dependency: run with:
 *   java tools/LocaleCheck.java [--strict] [--json]
 *
 * Read-only. It never writes locale JSON or source files.
 *
 * Heuristics: most translation keys are built at runtime via
 * LanguageUtils.settings(...) / command(...) / of(...) / path(...), so this
 * tool collects literal calls plus a few statically derivable enumerations
 * (settings ids, categories, groups, ping types, config enum values). Keys
 * that are used but not statically recoverable can be listed in
 * tools/locale-allowlist.txt as "used-ok".
 */
public final class LocaleCheck {
	private static final String MOD_ID_DEFAULT = "pingforit";
	private static Path ROOT = Paths.get("").toAbsolutePath();
	private static Path LANG;
	private static Path ALLOWLIST;
	private static List<Path> SOURCE_ROOTS;
	private static final Pattern PLACEHOLDER = Pattern.compile("%[sd]|\\{[A-Za-z0-9_]+\\}");

	public static void main(String[] args) throws Exception {
		boolean strict = false;
		boolean json = false;
		Path selectedRoot = Paths.get("").toAbsolutePath();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--strict" -> strict = true;
				case "--json" -> json = true;
				case "--root" -> {
					if (i + 1 >= args.length) {
						System.err.println("LocaleCheck: --root requires a directory");
						printUsage();
						System.exit(2);
					}
					selectedRoot = Paths.get(args[++i]);
				}
				case "--help", "-h" -> { printUsage(); return; }
				default -> { System.err.println("LocaleCheck: unknown argument: " + args[i]); printUsage(); System.exit(2); }
			}
		}
		ROOT = selectedRoot.toAbsolutePath().normalize();
		LANG = ROOT.resolve("common/src/main/resources/assets/pingforit/lang");
		ALLOWLIST = ROOT.resolve("tools/locale-allowlist.txt");
		SOURCE_ROOTS = List.of(ROOT.resolve("common/src/main/java"), ROOT.resolve("fabric/src/client/java"));

		if (!Files.isDirectory(LANG)) {
			System.err.println("LocaleCheck: not at the repository root (missing " + LANG + ")");
			System.exit(2);
		}

		Allowlist allowlist = Allowlist.load(ALLOWLIST);
		String modId = readModId();
		List<LocaleFile> locales = loadLocales();
		LocaleFile baseline = locales.get(0);

		Set<String> used = new LinkedHashSet<>();
		Scan scanner = new Scan(modId, baseline.keys.keySet(), used);
		scanner.scan();
		used.addAll(allowlist.usedOk);

		Map<String, List<String>> missing = new LinkedHashMap<>();
		Map<String, List<String>> extra = new LinkedHashMap<>();
		Map<String, List<String>> duplicates = new LinkedHashMap<>();
		Map<String, List<String>> placeholder = new LinkedHashMap<>();
		if (!baseline.duplicates.isEmpty()) duplicates.put(baseline.name, baseline.duplicates);

		for (int i = 1; i < locales.size(); i++) {
			LocaleFile locale = locales.get(i);
			List<String> missingKeys = new ArrayList<>();
			for (String key : baseline.keys.keySet()) {
				if (!locale.keys.containsKey(key) && !allowlist.missingOk.getOrDefault(locale.name, Set.of()).contains(key)) {
					missingKeys.add(key);
				}
			}
			List<String> extraKeys = new ArrayList<>();
			for (String key : locale.keys.keySet()) {
				if (!baseline.keys.containsKey(key) && !allowlist.extraOk.getOrDefault(locale.name, Set.of()).contains(key)) {
					extraKeys.add(key);
				}
			}
			List<String> duplicateKeys = new ArrayList<>(locale.duplicates);
			List<String> placeholderKeys = new ArrayList<>();
			for (String key : baseline.keys.keySet()) {
				String expected = baseline.keys.get(key);
				String actual = locale.keys.get(key);
				if (actual == null) continue;
				Set<String> expectedP = placeholders(expected);
				Set<String> actualP = placeholders(actual);
				if (!expectedP.equals(actualP)) {
					placeholderKeys.add(key + " expected=" + expectedP + " actual=" + actualP);
				}
			}
			if (!missingKeys.isEmpty()) missing.put(locale.name, missingKeys);
			if (!extraKeys.isEmpty()) extra.put(locale.name, extraKeys);
			if (!duplicateKeys.isEmpty()) duplicates.put(locale.name, duplicateKeys);
			if (!placeholderKeys.isEmpty()) placeholder.put(locale.name, placeholderKeys);
		}

		List<String> deadKeys = new ArrayList<>();
		for (String key : baseline.keys.keySet()) if (!used.contains(key)) deadKeys.add(key);
		List<String> usedBaseline = new ArrayList<>();
		for (String key : baseline.keys.keySet()) if (used.contains(key)) usedBaseline.add(key);

		int problemCount = size(missing) + size(extra) + size(duplicates) + size(placeholder);

		if (json) {
			printJson(locales, usedBaseline, deadKeys, missing, extra, duplicates, placeholder, problemCount, allowlist);
		} else {
			printHuman(locales, usedBaseline, deadKeys, missing, extra, duplicates, placeholder, problemCount, allowlist);
		}

		if (strict && problemCount > 0) {
			System.exit(1);
		}
	}

	private static int size(Map<String, List<String>> map) {
		int total = 0;
		for (List<String> value : map.values()) total += value.size();
		return total;
	}

	private static void printUsage() {
		System.out.println("usage: java tools/LocaleCheck.java [--root <dir>] [--strict] [--json]");
		System.out.println("  --root    repository-like root to inspect (default: current directory)");
		System.out.println("  --strict  exit 1 when any un-allowlisted missing/extra/duplicate/placeholder-mismatch exists");
		System.out.println("  --json    machine-readable summary");
	}

	private static String readModId() throws IOException {
		Path global = ROOT.resolve("common/src/main/java/nx/pingwheel/common/Global.java");
		if (Files.isRegularFile(global)) {
			String text = Files.readString(global, StandardCharsets.UTF_8);
			Matcher m = Pattern.compile("\\bMOD_ID\\s*=\\s*\"([^\"]+)\"").matcher(text);
			if (m.find()) return m.group(1);
		}
		return MOD_ID_DEFAULT;
	}

	private static List<LocaleFile> loadLocales() throws IOException {
		List<LocaleFile> locales = new ArrayList<>();
		List<Path> files;
		try (var stream = Files.list(LANG)) {
			files = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
				.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
		}
		Path baseline = LANG.resolve("en_us.json");
		if (!Files.isRegularFile(baseline)) {
			throw new IOException("missing en_us.json in " + LANG);
		}
		locales.add(LocaleFile.load(baseline));
		for (Path file : files) {
			if (file.equals(baseline)) continue;
			locales.add(LocaleFile.load(file));
		}
		return locales;
	}

	private static Set<String> placeholders(String value) {
		Set<String> result = new TreeSet<>();
		Matcher m = PLACEHOLDER.matcher(value);
		while (m.find()) result.add(m.group());
		return result;
	}

	private static void printHuman(List<LocaleFile> locales, List<String> used, List<String> dead,
			Map<String, List<String>> missing, Map<String, List<String>> extra,
			Map<String, List<String>> duplicates, Map<String, List<String>> placeholder,
			int problems, Allowlist allowlist) {
		LocaleFile baseline = locales.get(0);
		System.out.println("LocaleCheck: baseline=" + baseline.name + " keys=" + baseline.keys.size()
			+ " locales=" + locales.size());
		System.out.println("[baseline] " + baseline.name + " keys=" + baseline.keys.size()
			+ " duplicate=" + baseline.duplicates.size());
		if (!baseline.duplicates.isEmpty()) {
			for (String key : baseline.duplicates) System.out.println("  duplicate " + baseline.name + " " + key);
		}
		for (int i = 1; i < locales.size(); i++) {
			LocaleFile locale = locales.get(i);
			System.out.println("[locale] " + locale.name + " keys=" + locale.keys.size()
				+ " missing=" + missing.getOrDefault(locale.name, List.of()).size()
				+ " extra=" + extra.getOrDefault(locale.name, List.of()).size()
				+ " duplicate=" + duplicates.getOrDefault(locale.name, List.of()).size()
				+ " placeholder-mismatch=" + placeholder.getOrDefault(locale.name, List.of()).size());
		}
		System.out.println("[used] used=" + used.size() + " dead=" + dead.size() + " (static literal + derivable only)");
		if (!dead.isEmpty()) {
			System.out.println("[dead] " + String.join(", ", dead));
		}
		if (!allowlist.usedOk.isEmpty()) {
			System.out.println("[allowlist] used-ok=" + allowlist.usedOk.size()
				+ " missing-ok=" + allowlist.missingCount() + " extra-ok=" + allowlist.extraCount());
		}
		if (problems == 0) {
			System.out.println("[problems] none (all differences are allowlisted)");
			return;
		}
		System.out.println("[problems] un-allowlisted=" + problems);
		for (var entry : missing.entrySet()) {
			for (String key : entry.getValue()) System.out.println("  missing " + entry.getKey() + " " + key);
		}
		for (var entry : extra.entrySet()) {
			for (String key : entry.getValue()) System.out.println("  extra " + entry.getKey() + " " + key);
		}
		for (var entry : duplicates.entrySet()) {
			for (String key : entry.getValue()) System.out.println("  duplicate " + entry.getKey() + " " + key);
		}
		for (var entry : placeholder.entrySet()) {
			for (String key : entry.getValue()) System.out.println("  placeholder-mismatch " + entry.getKey() + " " + key);
		}
	}

	private static void printJson(List<LocaleFile> locales, List<String> used, List<String> dead,
			Map<String, List<String>> missing, Map<String, List<String>> extra,
			Map<String, List<String>> duplicates, Map<String, List<String>> placeholder,
			int problems, Allowlist allowlist) {
		StringBuilder sb = new StringBuilder();
		sb.append('{');
		sb.append("\"baseline\":\"").append(escape(locales.get(0).name)).append("\",");
		sb.append("\"baselineKeys\":").append(locales.get(0).keys.size()).append(',');
		sb.append("\"locales\":{");
		for (int i = 1; i < locales.size(); i++) {
			LocaleFile locale = locales.get(i);
			if (i > 1) sb.append(',');
			sb.append('"').append(escape(locale.name)).append("\":{");
			sb.append("\"keys\":").append(locale.keys.size()).append(',');
			appendArray(sb, "missing", missing.getOrDefault(locale.name, List.of())); sb.append(',');
			appendArray(sb, "extra", extra.getOrDefault(locale.name, List.of())); sb.append(',');
			appendArray(sb, "duplicate", duplicates.getOrDefault(locale.name, List.of())); sb.append(',');
			appendArray(sb, "placeholderMismatch", placeholder.getOrDefault(locale.name, List.of()));
			sb.append('}');
		}
		sb.append("},");
		appendArray(sb, "used", used); sb.append(',');
		appendArray(sb, "dead", dead); sb.append(',');
		appendArray(sb, "baselineDuplicate", locales.get(0).duplicates); sb.append(',');
		sb.append("\"problems\":").append(problems).append(',');
		sb.append("\"allowlist\":{\"usedOk\":").append(allowlist.usedOk.size())
			.append(",\"missingOk\":").append(allowlist.missingCount())
			.append(",\"extraOk\":").append(allowlist.extraCount()).append('}');
		sb.append('}');
		System.out.println(sb);
	}

	private static void appendArray(StringBuilder sb, String name, List<String> values) {
		sb.append('"').append(name).append("\":[");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append('"').append(escape(values.get(i))).append('"');
		}
		sb.append(']');
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	// ------------------------------------------------------------------
	// locale file
	// ------------------------------------------------------------------
	private static final class LocaleFile {
		final String name;
		final LinkedHashMap<String, String> keys;
		final List<String> duplicates;

		LocaleFile(String name, LinkedHashMap<String, String> keys, List<String> duplicates) {
			this.name = name;
			this.keys = keys;
			this.duplicates = duplicates;
		}

		static LocaleFile load(Path file) throws IOException {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			LinkedHashMap<String, String> keys = new LinkedHashMap<>();
			List<String> duplicates = new ArrayList<>();
			parseObject(text, keys, duplicates);
			String name = file.getFileName().toString().replaceFirst("\\.json$", "");
			return new LocaleFile(name, keys, duplicates);
		}

		private static void parseObject(String text, LinkedHashMap<String, String> keys, List<String> duplicates) {
			int[] pos = {0};
			skipWs(text, pos);
			if (pos[0] < text.length() && text.charAt(pos[0]) == '\uFEFF') pos[0]++;
			skipWs(text, pos);
			expect(text, pos, '{');
			skipWs(text, pos);
			if (peek(text, pos) == '}') { pos[0]++; return; }
			while (true) {
				skipWs(text, pos);
				String key = parseString(text, pos);
				skipWs(text, pos);
				expect(text, pos, ':');
				skipWs(text, pos);
				String value = parseString(text, pos);
				if (keys.containsKey(key)) duplicates.add(key); else keys.put(key, value);
				skipWs(text, pos);
				if (peek(text, pos) == ',') { pos[0]++; continue; }
				expect(text, pos, '}');
				return;
			}
		}

		private static char peek(String text, int[] pos) {
			if (pos[0] >= text.length()) throw new IllegalArgumentException("unexpected EOF in JSON");
			return text.charAt(pos[0]);
		}

		private static void expect(String text, int[] pos, char c) {
			if (peek(text, pos) != c) throw new IllegalArgumentException("expected '" + c + "' at " + pos[0]);
			pos[0]++;
		}

		private static void skipWs(String text, int[] pos) {
			while (pos[0] < text.length() && Character.isWhitespace(text.charAt(pos[0]))) pos[0]++;
		}

		private static String parseString(String text, int[] pos) {
			expect(text, pos, '"');
			StringBuilder out = new StringBuilder();
			while (pos[0] < text.length()) {
				char c = text.charAt(pos[0]++);
				if (c == '"') return out.toString();
				if (c != '\\') { out.append(c); continue; }
				char e = text.charAt(pos[0]++);
				switch (e) {
					case '"' -> out.append('"');
					case '\\' -> out.append('\\');
					case '/' -> out.append('/');
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case 'u' -> {
						out.append((char) Integer.parseInt(text.substring(pos[0], pos[0] + 4), 16));
						pos[0] += 4;
					}
					default -> throw new IllegalArgumentException("bad escape \\" + e + " at " + (pos[0] - 1));
				}
			}
			throw new IllegalArgumentException("unterminated JSON string");
		}
	}

	// ------------------------------------------------------------------
	// allowlist
	// ------------------------------------------------------------------
	private static final class Allowlist {
		final Set<String> usedOk = new LinkedHashSet<>();
		final Map<String, Set<String>> missingOk = new LinkedHashMap<>();
		final Map<String, Set<String>> extraOk = new LinkedHashMap<>();

		static Allowlist load(Path file) throws IOException {
			Allowlist result = new Allowlist();
			if (!Files.isRegularFile(file)) return result;
			for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String line = raw;
				int comment = line.indexOf('#');
				if (comment >= 0) line = line.substring(0, comment);
				line = line.trim();
				if (line.isEmpty()) continue;
				String[] parts = line.split("\\s+");
				if (parts.length < 2) continue;
				switch (parts[0]) {
					case "used-ok" -> result.usedOk.add(parts[1]);
					case "missing-ok" -> {
						if (parts.length < 3) continue;
						result.missingOk.computeIfAbsent(parts[1], k -> new LinkedHashSet<>()).add(parts[2]);
					}
					case "extra-ok" -> {
						if (parts.length < 3) continue;
						result.extraOk.computeIfAbsent(parts[1], k -> new LinkedHashSet<>()).add(parts[2]);
					}
					default -> { }
				}
			}
			return result;
		}

		int missingCount() { return missingOk.values().stream().mapToInt(Set::size).sum(); }
		int extraCount() { return extraOk.values().stream().mapToInt(Set::size).sum(); }
	}

	// ------------------------------------------------------------------
	// source scan
	// ------------------------------------------------------------------
	private static final class Scan {
		final String modId;
		final Set<String> baselineKeys;
		final Set<String> used;
		final List<Path> javaFiles = new ArrayList<>();

		Scan(String modId, Set<String> baselineKeys, Set<String> used) {
			this.modId = modId;
			this.baselineKeys = baselineKeys;
			this.used = used;
		}

		void scan() throws IOException {
			for (Path root : SOURCE_ROOTS) {
				if (!Files.isDirectory(root)) continue;
				try (var stream = Files.walk(root)) {
					stream.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
				}
			}
			javaFiles.sort(Comparator.comparing(Path::toString));

			Map<Path, String> code = new LinkedHashMap<>();
			for (Path file : javaFiles) {
				code.put(file, stripComments(Files.readString(file, StandardCharsets.UTF_8)));
			}

			for (var entry : code.entrySet()) {
				scanLanguageCalls(entry.getKey(), entry.getValue());
				scanDirectKeys(entry.getValue());
			}

			scanSettingsCatalog(code);
			scanNavigation(code);
			scanGroups(code);
			scanPingTypes(code);
			scanEnumValues(code);
		}

		private void add(String key) {
			if (baselineKeys.contains(key)) used.add(key);
		}

		private void scanLanguageCalls(Path file, String src) {
			Pattern call = Pattern.compile("LanguageUtils\\.(settings|command|of|keyOf)\\s*\\(");
			Matcher m = call.matcher(src);
			while (m.find()) {
				String method = m.group(1);
				int open = src.indexOf('(', m.start());
				int close = matchingParen(src, open);
				if (close < 0) continue;
				String args = src.substring(open + 1, close);
				List<String> literals = literals(args);
				List<String> bases = new ArrayList<>();
				if (method.equals("settings") || method.equals("command")) {
					String category = method.equals("settings") ? "settings" : "command";
					for (String literal : literals) bases.add(category + "." + modId + "." + literal);
				} else {
					// of()/keyOf() take (category, path...) but literal arguments can also be
					// alternatives (e.g. of("value", flag ? "enabled" : "disabled")), so add
					// each remaining literal as its own candidate rather than joining them.
					if (!literals.isEmpty()) {
						String category = literals.get(0);
						for (int i = 1; i < literals.size(); i++) {
							bases.add(category + "." + modId + "." + literals.get(i));
						}
					}
				}
				for (String base : bases) add(base);
				String chained = src.substring(close + 1);
				addChainedPaths(chained, bases);
			}

			// var langX = LanguageUtils.command("..."); langX.path("...")...
			Pattern assignment = Pattern.compile("(?:var|final\\s+var|[A-Za-z_$][\\w$<>.]*)\\s+(\\w+)\\s*=\\s*LanguageUtils\\.(settings|command)\\s*\\(");
			Matcher am = assignment.matcher(src);
			while (am.find()) {
				String var = am.group(1);
				String method = am.group(2);
				int open = src.indexOf('(', am.start());
				int close = matchingParen(src, open);
				if (close < 0) continue;
				List<String> literals = literals(src.substring(open + 1, close));
				String category = method.equals("settings") ? "settings" : "command";
				List<String> bases = new ArrayList<>();
				for (String literal : literals) bases.add(category + "." + modId + "." + literal);
				for (String base : bases) add(base);
				Pattern use = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*\\.path\\s*\\(");
				Matcher um = use.matcher(src);
				while (um.find()) {
					int pOpen = src.indexOf('(', um.start());
					int pClose = matchingParen(src, pOpen);
					if (pClose < 0) continue;
					addChainedPaths(src.substring(pOpen - 6, pClose + 1), bases);
				}
			}
		}

		private void addChainedPaths(String text, List<String> bases) {
			if (bases.isEmpty()) return;
			Matcher m = Pattern.compile("\\.path\\s*\\(").matcher(text);
			while (m.find()) {
				int open = text.indexOf('(', m.start());
				int close = matchingParen(text, open);
				if (close < 0) continue;
				List<String> segments = literals(text.substring(open + 1, close));
				if (segments.isEmpty()) continue;
				for (String base : bases) {
					StringBuilder key = new StringBuilder(base);
					for (String segment : segments) key.append('.').append(segment);
					add(key.toString());
				}
			}
		}

		private void scanDirectKeys(String src) {
			Matcher m = Pattern.compile("\"((?:key|subtitles|settings|value|unit|command|notice|pingforit)\\.[A-Za-z0-9_.]+)\"").matcher(src);
			while (m.find()) add(m.group(1));
		}

		private void scanSettingsCatalog(Map<Path, String> code) {
			for (var entry : code.entrySet()) {
				if (!entry.getKey().toString().endsWith("SettingsCategoryCatalog.java")) continue;
				Matcher m = Pattern.compile("\\b[A-Z_]+\\s*\\(\\s*\"([a-z0-9_]+)\"\\s*\\)").matcher(entry.getValue());
				while (m.find()) {
					add("settings." + modId + "." + m.group(1));
					add("settings." + modId + "." + m.group(1) + ".tooltip");
				}
				// OptionUtils.ofEnum builds per-value tooltips via text.path(mode.toString(), "tooltip").
				for (String mode : List.of("all", "compatible", "voxel_shape_only")) {
					add("settings." + modId + ".entity_block_render_mode." + mode + ".tooltip");
				}
			}
		}

		private void scanNavigation(Map<Path, String> code) {
			for (var entry : code.entrySet()) {
				String text = entry.getValue();
				Matcher categories = Pattern.compile("\\b[A-Z_]+\\s*\\(\\s*Scope\\.[A-Z_]+\\s*,\\s*\"([a-z0-9_]+)\"\\s*\\)").matcher(text);
				while (categories.find()) {
					add("settings." + modId + ".category." + categories.group(1));
					add("settings." + modId + ".category." + categories.group(1) + ".button");
				}
			}
		}

		private void scanGroups(Map<Path, String> code) {
			for (var entry : code.entrySet()) {
				Matcher m = Pattern.compile("addSubgroup\\s*\\(\\s*\"([a-z0-9_]+)\"\\s*\\)").matcher(entry.getValue());
				while (m.find()) add("settings." + modId + ".group." + m.group(1));
			}
		}

		private void scanPingTypes(Map<Path, String> code) {
			for (var entry : code.entrySet()) {
				if (!entry.getKey().toString().endsWith("PingTypeCatalog.java")) continue;
				String text = entry.getValue();
				Matcher full = Pattern.compile("\"(pingforit\\.ping_type\\.[a-z0-9_.]+)\"").matcher(text);
				while (full.find()) add(full.group(1));
				Matcher type = Pattern.compile("new PingType\\(\\s*\"([a-z0-9_]+)\"").matcher(text);
				while (type.find()) {
					String id = type.group(1);
					add("pingforit.ping_type." + id);
					add("pingforit.ping_type." + id + ".phrase");
					add("pingforit.chat." + id + ".template.override");
				}
			}
		}

		private void scanEnumValues(Map<Path, String> code) {
			for (var entry : code.entrySet()) {
				String name = entry.getKey().getFileName().toString();
				if (!name.equals("ChannelMode.java") && !name.equals("PlayerInfoMode.java")
					&& !name.equals("TeamColorMode.java") && !name.equals("EntityBlockRenderMode.java")
					&& !name.equals("TeamContext.java")) continue;
				Matcher m = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\s*(?:,|;|\\()").matcher(entry.getValue());
				while (m.find()) {
					String constant = m.group(1);
					if (constant.equals("AUTO") || constant.equals("DISABLED") || constant.equals("GLOBAL")
						|| constant.equals("TEAM_ONLY") || constant.equals("HOLD") || constant.equals("ALWAYS")
						|| constant.equals("COMPACT") || constant.equals("FULL") || constant.equals("PING_ONLY")
						|| constant.equals("LABELS_ONLY") || constant.equals("ALL") || constant.equals("COMPATIBLE")
						|| constant.equals("VOXEL_SHAPE_ONLY") || constant.equals("VANILLA_TEAM")
						|| constant.equals("FTB_TEAMS") || constant.equals("VOICE_CHAT")) {
						add("value." + modId + "." + constant.toLowerCase(Locale.ROOT));
					}
				}
			}
		}
	}

	private static List<String> literals(String text) {
		List<String> result = new ArrayList<>();
		Matcher m = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(text);
		while (m.find()) result.add(unescape(m.group(1)));
		return result;
	}

	private static String unescape(String s) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '\\' || i + 1 >= s.length()) { out.append(c); continue; }
			char e = s.charAt(++i);
			switch (e) {
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'r' -> out.append('\r');
				case '"' -> out.append('"');
				case '\\' -> out.append('\\');
				default -> out.append(e);
			}
		}
		return out.toString();
	}

	private static int matchingParen(String s, int open) {
		int depth = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"') { i = skipString(s, i); continue; }
			if (c == '(') depth++;
			else if (c == ')') { depth--; if (depth == 0) return i; }
		}
		return -1;
	}

	private static int skipString(String s, int quote) {
		for (int i = quote + 1; i < s.length(); i++) {
			if (s.charAt(i) == '\\') { i++; continue; }
			if (s.charAt(i) == '"') return i;
		}
		return s.length() - 1;
	}

	/** Removes // and /* *\/ comments while preserving string/char literals. */
	private static String stripComments(String s) {
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			char d = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
			if (c == '"' || c == '\'') {
				int end = c == '"' ? skipString(s, i) : skipChar(s, i);
				out.append(s, i, Math.min(end + 1, s.length()));
				i = end + 1;
				continue;
			}
			if (c == '/' && d == '/') {
				while (i < s.length() && s.charAt(i) != '\n') i++;
				continue;
			}
			if (c == '/' && d == '*') {
				i += 2;
				while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
				i = Math.min(i + 2, s.length());
				continue;
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	private static int skipChar(String s, int quote) {
		for (int i = quote + 1; i < s.length(); i++) {
			if (s.charAt(i) == '\\') { i++; continue; }
			if (s.charAt(i) == '\'') return i;
		}
		return s.length() - 1;
	}
}
