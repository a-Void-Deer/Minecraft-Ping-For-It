package nx.pingwheel.common.client.outline;

import net.minecraft.resources.ResourceLocation;

/**
 * Validates the stable identifiers used by the internal geometry seam.
 *
 * <p>The bound is intentionally conservative for diagnostics and keeps an
 * adapter from supplying an unbounded string. The Minecraft parser remains
 * the authority for the namespace/path character grammar.</p>
 */
final class EntityBlockGeometrySourceIds {
	static final String INVALID = "<invalid>";
	static final String UNAVAILABLE = "<unavailable>";

	private static final int MAX_LENGTH = 256;

	private EntityBlockGeometrySourceIds() {}

	static String validate(String candidate) {
		if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
			return null;
		}

		int separator = candidate.indexOf(':');
		if (separator <= 0 || separator != candidate.lastIndexOf(':')
			|| separator == candidate.length() - 1) {
			return null;
		}

		ResourceLocation parsed = ResourceLocation.tryParse(candidate);
		if (parsed == null) {
			return null;
		}

		String canonical = parsed.toString();
		return canonical.equals(candidate) ? canonical : null;
	}

	static String require(String candidate) {
		String validated = validate(candidate);
		if (validated == null) {
			throw new IllegalArgumentException("source id must be a namespaced ResourceLocation");
		}
		return validated;
	}
}
