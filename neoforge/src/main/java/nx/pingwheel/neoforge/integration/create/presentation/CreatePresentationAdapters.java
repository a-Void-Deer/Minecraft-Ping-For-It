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
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.domain.Target;

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

	record SourceWorld(Object value, String dimensionId) {
	}

	interface SourceAccess {
		boolean serverThread();
		boolean available();
		SourceWorld resolveWorld(String dimensionId);
		ExternalBlockServerProvider.ObservationResult observeExternal(
			SourceWorld world, Target.ExternalBlockTarget target);
		boolean matchesOrdinary(SourceWorld world, BlockPos position, String expectedRegistryId);
		CreatePresentationAdapter.Observation collect(SourceWorld world, BlockPos position,
			Set<String> demand, PresentationAdapter.CaptureBudget budget);
	}

	static boolean validExternalMetadata(PresentationAdapter.DetachedTarget target,
		Target.ExternalBlockTarget external) {
		return target != null && external != null && external.isCommitted()
			&& "block".equals(target.kind())
			&& target.locator() != null && target.locator().isEmpty()
			&& target.x() == 0 && target.y() == 0 && target.z() == 0
			&& external.dimensionId().equals(target.dimension())
			&& external.expectedBlockRegistryId().equals(target.registryId());
	}

	static CreatePresentationAdapter.Observation observeSource(
		PresentationAdapter.DetachedTarget target, Set<String> demand,
		PresentationAdapter.CaptureBudget budget, SourceAccess access) {
		if (target == null || demand == null || budget == null || access == null
			|| budget.remaining() < 1 || !access.serverThread()) return null;
		Target.ExternalBlockTarget external = target.externalBlock();
		if (external != null && !validExternalMetadata(target, external)) return null;
		if (target.dimension() == null || target.dimension().length() > 193
			|| target.dimension().isBlank() || target.registryId() == null
			|| target.registryId().length() > 193 || target.registryId().isBlank()
			|| (external == null && target.locator() != null && !target.locator().isEmpty())
			|| (external == null && !"block".equals(target.kind())
				&& !"entity_block".equals(target.kind()))) return null;
		if (!access.available()) return null;
		ResourceLocation dimension = ResourceLocation.tryParse(target.dimension());
		ResourceLocation expectedBlock = ResourceLocation.tryParse(target.registryId());
		if (dimension == null || expectedBlock == null || budget.remaining() < 2 || !budget.scan()) return null;
		SourceWorld world = access.resolveWorld(dimension.toString());
		if (world == null) return null;
		BlockPos position;
		if (external != null) {
			ExternalBlockServerProvider.ObservationResult observed = access.observeExternal(world, external);
			if (!(observed instanceof ExternalBlockServerProvider.ObservationResult.Available available)) {
				return null;
			}
			position = available.observation().position();
		} else {
			position = new BlockPos(target.x(), target.y(), target.z());
			if (!access.matchesOrdinary(world, position, expectedBlock.toString())) return null;
		}
		return access.collect(world, position, Set.copyOf(demand), budget);
	}

	private static final class ServerSource implements CreatePresentationAdapter.Source {
		private final MinecraftServer server;

		private ServerSource(MinecraftServer server) {
			this.server = server;
		}

		@Override
		public CreatePresentationAdapter.Observation observe(PresentationAdapter.DetachedTarget target,
			Set<String> demand, PresentationAdapter.CaptureBudget budget) {
			return observeSource(target, demand, budget, new SourceAccess() {
				@Override
				public boolean serverThread() { return server.isSameThread(); }

				@Override
				public boolean available() { return CreatePresentationAvailability.available(); }

				@Override
				public SourceWorld resolveWorld(String dimensionId) {
					ResourceLocation dimension = ResourceLocation.tryParse(dimensionId);
					if (dimension == null) return null;
					ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
					return level == null ? null : new SourceWorld(level, dimensionId);
				}

				@Override
				public ExternalBlockServerProvider.ObservationResult observeExternal(
					SourceWorld world, Target.ExternalBlockTarget target) {
					return ExternalBlockServerProviders.registry().observeBlock(server,
						(ServerLevel) world.value(), target);
				}

				@Override
				public boolean matchesOrdinary(SourceWorld world, BlockPos position, String expectedRegistryId) {
					ServerLevel level = (ServerLevel) world.value();
					return position.getY() >= level.getMinBuildHeight()
						&& position.getY() < level.getMaxBuildHeight()
						&& level.hasChunkAt(position)
						&& expectedRegistryId.equals(BuiltInRegistries.BLOCK.getKey(
							level.getBlockState(position).getBlock()).toString());
				}

				@Override
				public CreatePresentationAdapter.Observation collect(SourceWorld world, BlockPos position,
					Set<String> demand, PresentationAdapter.CaptureBudget budget) {
					return collectAt((ServerLevel) world.value(), position, demand, budget);
				}
			});
		}

		private CreatePresentationAdapter.Observation collectAt(ServerLevel level, BlockPos pos,
			Set<String> demand, PresentationAdapter.CaptureBudget budget) {
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
			return observation(sampled);
		}

		private CreatePresentationAdapter.Observation observation(CreatePresentationCollector.Sample sampled) {
			if (!sampled.sourcePresent()) return null;
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
