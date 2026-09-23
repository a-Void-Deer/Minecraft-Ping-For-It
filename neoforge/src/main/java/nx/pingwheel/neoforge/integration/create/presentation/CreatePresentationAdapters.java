package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import nx.pingwheel.common.presentation.PresentationAdapter;

/**
 * NeoForge registration hook. The loader checks the Create mod ID before
 * reflectively loading this class; these factories additionally fail closed on
 * untested runtime mod versions. Only ServerSource links the Create collector.
 */
public final class CreatePresentationAdapters {
	private CreatePresentationAdapters() {}

	/** Return null if Create is absent/untested; register the result only if non-null. */
	public static PresentationAdapter server(MinecraftServer server) {
		return server(server, CreatePresentationAdapter.MIN_UPDATE_INTERVAL_TICKS);
	}

	/** Server config may increase the sampling interval without editing the manifest. */
	public static PresentationAdapter server(MinecraftServer server, int minUpdateIntervalTicks) {
		Objects.requireNonNull(server, "server");
		if (minUpdateIntervalTicks < 1) {
			throw new IllegalArgumentException("invalid Create minimum update interval");
		}
		if (!CreatePresentationAvailability.available()) {
			return null;
		}
		return new CreatePresentationAdapter(new ServerSource(server), minUpdateIntervalTicks);
	}

	/** Client manifest only: no server, world lookup, collector or render dependency. */
	public static PresentationAdapter client() {
		return CreatePresentationAvailability.available() ? new CreatePresentationAdapter() : null;
	}

	private static final class ServerSource implements CreatePresentationAdapter.Source {
		private final MinecraftServer server;

		private ServerSource(MinecraftServer server) {
			this.server = server;
		}

		@Override
		public CreatePresentationAdapter.Observation observe(PresentationAdapter.DetachedTarget target,
			Set<String> demand, PresentationAdapter.CaptureBudget budget) {
			// No world lookup or optional-mod resolution on an ineligible request.
			if (budget.remaining() < 1 || !server.isSameThread()
				|| (target.locator() != null && !target.locator().isEmpty())) {
				return null;
			}
			if (target.dimension() == null || target.dimension().length() > 193
				|| target.registryId() == null || target.registryId().length() > 193) {
				return null;
			}
			if (!CreatePresentationAvailability.available()) {
				return null;
			}
			ResourceLocation dimension = ResourceLocation.tryParse(target.dimension());
			ResourceLocation expectedBlock = ResourceLocation.tryParse(target.registryId());
			if (dimension == null || expectedBlock == null) {
				return null;
			}
			// Account for target resolution and its state read, separately from
			// the collector's structural and handler work. The remaining budget
			// bounds every scan performed by the collector.
			if (budget.remaining() < 2 || !budget.scan()) {
				return null;
			}
			// A whole-block snapshot, not a contraption or other opaque locator.
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
			if (level == null) {
				return null;
			}
			BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
			if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()
				|| !level.hasChunkAt(pos)
				|| !expectedBlock.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()))) {
				return null;
			}
			int maxWork = Math.min(budget.remaining(), CreatePresentationCollector.DEFAULT_LIMITS.maxWork());
			CreateSamplingLimits defaults = CreatePresentationCollector.DEFAULT_LIMITS;
			CreateSamplingLimits limits = new CreateSamplingLimits(defaults.maxStructureBlocks(),
				defaults.maxSlots(), defaults.maxTanks(), defaults.maxRegistryIds(), maxWork,
				defaults.maxOutputBytes());
			CreatePresentationCollector.Sample sampled = CreatePresentationCollector.capture(level, pos,
				demand.contains(CreatePresentationAdapter.SPEED),
				demand.contains(CreatePresentationAdapter.HAS_NETWORK),
				demand.contains(CreatePresentationAdapter.OVERSTRESSED),
				demand.contains(CreatePresentationAdapter.STRESS),
				demand.contains(CreatePresentationAdapter.CAPACITY),
				demand.contains(CreatePresentationAdapter.INVENTORY),
				demand.contains(CreatePresentationAdapter.FLUID), limits);
			for (int scan = 0; scan < sampled.workUsed(); scan++) {
				if (!budget.scan()) {
					return null;
				}
			}
			if (!sampled.sourcePresent()) {
				return null;
			}
			CreatePresentationCollector.Kinetic kinetic = sampled.kinetic();
			CreatePresentationAdapter.Speed speed = kinetic.effectiveSpeed() != null
				&& kinetic.theoreticalSpeed() != null && kinetic.moving() != null
				? new CreatePresentationAdapter.Speed(kinetic.effectiveSpeed(),
					kinetic.theoreticalSpeed(), kinetic.moving()) : null;
			return new CreatePresentationAdapter.Observation(speed,
				kinetic.hasNetwork(), kinetic.overStressed(),
				kinetic.networkStress() == null ? null : kinetic.networkStress().doubleValue(),
				kinetic.networkCapacity() == null ? null : kinetic.networkCapacity().doubleValue(),
				asSummary(sampled.items()), asSummary(sampled.fluids()));
		}

		private static CreatePresentationAdapter.Summary asSummary(BoundedCreateSummary.Summary sampled) {
			return new CreatePresentationAdapter.Summary(sampled.counts(), sampled.partial(),
				sampled.scanned(), sampled.available());
		}
	}
}
