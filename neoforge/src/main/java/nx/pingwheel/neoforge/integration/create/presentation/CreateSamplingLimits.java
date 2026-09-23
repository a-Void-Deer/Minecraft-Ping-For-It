package nx.pingwheel.neoforge.integration.create.presentation;

/** Pure numeric sampling limits; safe to test without loading Create. */
public record CreateSamplingLimits(int maxStructureBlocks, int maxSlots, int maxTanks,
	int maxRegistryIds, int maxWork, int maxOutputBytes) {
	public CreateSamplingLimits {
		if (maxStructureBlocks < 1 || maxStructureBlocks > 256 || maxSlots < 1 || maxSlots > 512
			|| maxTanks < 1 || maxTanks > 64 || maxRegistryIds < 1 || maxRegistryIds > 64
			|| maxWork < 1 || maxWork > 1024 || maxOutputBytes < 1 || maxOutputBytes > 8192) {
			throw new IllegalArgumentException("invalid Create sampling budget");
		}
	}

	/** Check before multiplying or touching any part position. */
	public boolean permitsShape(int x, int y, int z, int remainingWork) {
		int maximum = Math.min(maxStructureBlocks, remainingWork);
		return x > 0 && y > 0 && z > 0 && x <= maximum && y <= maximum && z <= maximum
			&& (long) x * y * z <= maximum;
	}
}
