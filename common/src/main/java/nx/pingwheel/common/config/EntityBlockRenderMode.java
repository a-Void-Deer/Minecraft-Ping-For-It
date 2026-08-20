package nx.pingwheel.common.config;

import java.util.Locale;

/**
 * Selects which geometry sources an {@code entity_block} outline may use.
 *
 * <p>This is a local client setting. It is deliberately not part of any
 * marker packet or server configuration state.</p>
 */
public enum EntityBlockRenderMode {
	ALL,
	COMPATIBLE,
	VOXEL_SHAPE_ONLY;

	/**
	 * Parses the persisted/display form used by the existing enum settings.
	 * Unknown values are returned as {@code null} so client-config validation
	 * can recover them to {@link #COMPATIBLE}.
	 */
	public static EntityBlockRenderMode get(String name) {
		if (name == null) {
			return null;
		}

		for (EntityBlockRenderMode mode : values()) {
			if (mode.name().equalsIgnoreCase(name)) {
				return mode;
			}
		}

		return null;
	}

	/**
	 * Returns the safe runtime value for a possibly old or malformed config.
	 */
	public static EntityBlockRenderMode effective(EntityBlockRenderMode mode) {
		return mode == null ? COMPATIBLE : mode;
	}

	@Override
	public String toString() {
		return super.toString().toLowerCase(Locale.ROOT);
	}
}
