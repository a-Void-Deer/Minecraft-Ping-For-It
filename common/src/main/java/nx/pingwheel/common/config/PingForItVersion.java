package nx.pingwheel.common.config;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The configuration-version value object used by Ping For It.
 *
 * <p>Ping For It keeps its product marker in the {@code x.x.x-pfi-qualifier}
 * form, while Maven's comparator must see the equivalent
 * {@code x.x.x-qualifier} form. The original spelling is retained for JSON
 * and diagnostics.</p>
 */
public final class PingForItVersion implements Comparable<PingForItVersion> {
	private static final Pattern FORMAT = Pattern.compile(
		"\\A([0-9]+)\\.([0-9]+)\\.([0-9]+)-pfi-([A-Za-z0-9]+)\\z");

	private final String originalVersion;
	private final String normalizedVersion;
	private final MavenComparableVersion comparableVersion;

	public PingForItVersion(String originalVersion) {
		this.originalVersion = originalVersion;
		if (originalVersion == null) {
			throw new InvalidVersionException(null);
		}

		Matcher matcher = FORMAT.matcher(originalVersion);
		if (!matcher.matches()) {
			throw new InvalidVersionException(originalVersion);
		}

		normalizedVersion = matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3)
			+ "-" + matcher.group(4);
		comparableVersion = new MavenComparableVersion(normalizedVersion);
	}

	public static PingForItVersion parse(String originalVersion) {
		return new PingForItVersion(originalVersion);
	}

	public String originalVersion() {
		return originalVersion;
	}

	public String normalizedVersion() {
		return normalizedVersion;
	}

	@Override
	public int compareTo(PingForItVersion other) {
		Objects.requireNonNull(other, "other");
		return comparableVersion.compareTo(other.comparableVersion);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof PingForItVersion version
			&& comparableVersion.equals(version.comparableVersion);
	}

	@Override
	public int hashCode() {
		return comparableVersion.hashCode();
	}

	@Override
	public String toString() {
		return originalVersion;
	}

	public static final class InvalidVersionException extends IllegalArgumentException {
		public InvalidVersionException(String value) {
			super(
				"Invalid Ping For It config version " + String.valueOf(value)
					+ "; expected x.x.x-pfi-qualifier with three non-negative decimal components"
			);
		}
	}
}
