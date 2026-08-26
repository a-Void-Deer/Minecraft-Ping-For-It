package nx.pingwheel.common.integration.sable.server;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * The Sable client/server locator wire value.  It is intentionally consumed
 * only by the isolated Sable adapter; the common marker model treats it as an
 * opaque bounded string.
 */
public record SableExternalBlockLocator(UUID subLevelId, int x, int y, int z) {

	public static final int MAX_LENGTH = 128;
	private static final int MAX_ABSOLUTE_COORDINATE = 30_000_000;

	public SableExternalBlockLocator {
		if (subLevelId == null) {
			throw new NullPointerException("subLevelId");
		}

		if (!coordinateInBounds(x) || !coordinateInBounds(y) || !coordinateInBounds(z)) {
			throw new IllegalArgumentException("locator coordinate is outside the supported bound");
		}
	}

	public SableExternalBlockLocator(UUID subLevelId, BlockPos position) {
		this(subLevelId, position.getX(), position.getY(), position.getZ());
	}

	public BlockPos blockPos() {
		return new BlockPos(x, y, z);
	}

	public String encode() {
		String value = subLevelId + "/" + x + "," + y + "," + z;

		if (value.length() > MAX_LENGTH) {
			throw new IllegalStateException("encoded locator exceeds its bound");
		}

		return value;
	}

	/**
	 * Parses only the bounded UUID/xyz form emitted by {@link #encode()}.
	 * Malformed, non-canonical, whitespace-containing, and oversized values are
	 * rejected without throwing to the caller.
	 */
	public static Optional<SableExternalBlockLocator> parse(String value) {
		if (value == null || value.isBlank() || value.length() > MAX_LENGTH || !value.equals(value.trim())) {
			return Optional.empty();
		}

		int slash = value.indexOf('/');
		if (slash <= 0 || slash != value.lastIndexOf('/') || slash + 1 >= value.length()) {
			return Optional.empty();
		}

		String uuidPart = value.substring(0, slash);
		String coordinates = value.substring(slash + 1);

		if (uuidPart.length() != 36 || coordinates.indexOf(',') <= 0
			|| coordinates.lastIndexOf(',') <= coordinates.indexOf(',')
			|| coordinates.lastIndexOf(',') == coordinates.length() - 1) {
			return Optional.empty();
		}

		String[] parts = coordinates.split(",", -1);
		if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
			return Optional.empty();
		}

		try {
			UUID uuid = UUID.fromString(uuidPart);
			int x = Integer.parseInt(parts[0]);
			int y = Integer.parseInt(parts[1]);
			int z = Integer.parseInt(parts[2]);
			SableExternalBlockLocator locator = new SableExternalBlockLocator(uuid, x, y, z);

			return locator.encode().equals(value) ? Optional.of(locator) : Optional.empty();
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	private static boolean coordinateInBounds(int coordinate) {
		return Math.abs((long) coordinate) <= MAX_ABSOLUTE_COORDINATE;
	}
}
