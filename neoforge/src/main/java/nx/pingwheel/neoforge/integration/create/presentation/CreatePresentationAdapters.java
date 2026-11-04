package nx.pingwheel.neoforge.integration.create.presentation;

import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import org.jetbrains.annotations.Nullable;

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
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.domain.Target;

/**
 * NeoForge registration hook. The loader checks the Create mod ID before
 * reflectively loading this class; these factories additionally fail closed on
 * untested runtime mod versions. Only ServerSource links the Create collector.
 */
public final class CreatePresentationAdapters {
	private CreatePresentationAdapters() {}

	/** Compatibility entry for the common server's optional reflection boundary. */
	public static @Nullable PresentationAdapter server(MinecraftServer server) {
		return server(server, Diagnostics.server());
	}

	public static @Nullable PresentationAdapter server(MinecraftServer server, int minUpdateIntervalTicks) {
		return server(server, minUpdateIntervalTicks, Diagnostics.server());
	}

	/** Compatibility entry for the common client's optional reflection boundary. */
	public static @Nullable PresentationAdapter client() {
		return client(Diagnostics.client());
	}

	/** Return null if Create is absent/untested; register the result only if non-null. */
	public static @Nullable PresentationAdapter server(MinecraftServer server, DiagnosticLogger diagnostics) {
		return server(server, CreatePresentationAdapter.MIN_UPDATE_INTERVAL_TICKS, diagnostics);
	}

	/** Server config may increase the sampling interval without editing the manifest. */
	public static @Nullable PresentationAdapter server(MinecraftServer server, int minUpdateIntervalTicks, DiagnosticLogger diagnostics) {
		Objects.requireNonNull(server, "server");
		if (minUpdateIntervalTicks < 1) {
			throw new IllegalArgumentException("invalid Create minimum update interval");
		}
		if (!CreatePresentationAvailability.available(diagnostics)) {
			return null;
		}
		return new CreatePresentationAdapter(new ServerSource(server, diagnostics), minUpdateIntervalTicks, diagnostics);
	}

	/** Client manifest only: no server, world lookup, collector or render dependency. */
	public static @Nullable PresentationAdapter client(DiagnosticLogger diagnostics) {
		return CreatePresentationAvailability.available(diagnostics)
			? new CreatePresentationAdapter(null, CreatePresentationAdapter.MIN_UPDATE_INTERVAL_TICKS, diagnostics) : null;
	}

	record SourceWorld(Object value, String dimensionId) {
	}

	/** Provider-resolved preview binding; the local position and member gate stay detached. */
	record PreviewBinding(BlockPos position, java.util.function.Predicate<BlockPos> memberGate) {
		PreviewBinding {
			Objects.requireNonNull(position, "position");
		}
	}

	/** Current active committed source; an external read always requires positive membership. */
	record CommittedBinding(BlockPos position, java.util.function.Predicate<BlockPos> memberGate) {
		CommittedBinding {
			Objects.requireNonNull(position, "position");
			Objects.requireNonNull(memberGate, "memberGate");
		}
	}

	interface SourceAccess {
		default DiagnosticLogger diagnostics() { return Diagnostics.server(); }
		boolean serverThread();
		boolean available();
		@Nullable SourceWorld resolveWorld(String dimensionId);
		/** Legacy test seam only; committed collection never resolves content through this observation. */
		default ExternalBlockServerProvider.ObservationResult observeExternal(
			SourceWorld world, Target.ExternalBlockTarget target) {
			return new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
		}
		/** No legacy observation fallback: missing scoped-source support is unavailable. */
		default @Nullable CommittedBinding resolveCommittedExternal(SourceWorld world, Target.ExternalBlockTarget target) {
			return null;
		}
		/** Candidate-only provider source resolution; never materializes or mutates provider state. */
		@Nullable PreviewBinding resolvePreviewExternal(SourceWorld world, Target.ExternalBlockTarget candidate);
		boolean matchesOrdinary(SourceWorld world, BlockPos position, String expectedRegistryId);
		CreatePresentationAdapter.@Nullable Observation collect(SourceWorld world, BlockPos position,
			Set<String> demand, PresentationAdapter.CaptureBudget budget);
		/** Older access implementations remain compatible but cannot drop an external member gate. */
		default CreatePresentationAdapter.@Nullable Observation collect(SourceWorld world, BlockPos position,
			Set<String> demand, PresentationAdapter.CaptureBudget budget,
			java.util.function.@Nullable Predicate<BlockPos> memberGate) {
			return memberGate == null ? collect(world, position, demand, budget) : null;
		}
		CreatePresentationAdapter.@Nullable Observation collectPreview(SourceWorld world, BlockPos position,
			Set<String> demand, PresentationAdapter.CaptureBudget budget,
			java.util.function.Predicate<BlockPos> memberGate);
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

	static CreatePresentationAdapter.@Nullable Observation observeSource(
		PresentationAdapter.DetachedTarget target, Set<String> demand,
		PresentationAdapter.CaptureBudget budget, SourceAccess access) {
		if (target == null || demand == null || budget == null || access == null
			|| budget.remaining() < 1 || !access.serverThread()) return null;
		Target.@Nullable ExternalBlockTarget external = target.externalBlock();
		if (external != null && !validExternalMetadata(target, external)) return null;
		if (target.dimension() == null || target.dimension().length() > 193
			|| target.dimension().isBlank() || target.registryId() == null
			|| target.registryId().length() > 193 || target.registryId().isBlank()
			|| (external == null && target.locator() != null && !target.locator().isEmpty())
			|| (external == null && !"block".equals(target.kind())
				&& !"entity_block".equals(target.kind()))) return null;
		if (!access.available()) return null;
		@Nullable ResourceLocation dimension = ResourceLocation.tryParse(target.dimension());
		@Nullable ResourceLocation expectedBlock = ResourceLocation.tryParse(target.registryId());
		if (dimension == null || expectedBlock == null || budget.remaining() < 2 || !budget.scan()) return null;
		@Nullable SourceWorld world = access.resolveWorld(dimension.toString());
		if (world == null) return null;
		if (external != null) {
			@Nullable CommittedBinding binding = access.resolveCommittedExternal(world, external);
			if (binding == null || !admitted(binding.memberGate(), binding.position(), access.diagnostics())
				|| !access.matchesOrdinary(world, binding.position(), expectedBlock.toString())) return null;
			return access.collect(world, binding.position(), Set.copyOf(demand), budget, binding.memberGate());
		}
		BlockPos position = new BlockPos(target.x(), target.y(), target.z());
		if (!access.matchesOrdinary(world, position, expectedBlock.toString())) return null;
		return access.collect(world, position, Set.copyOf(demand), budget);
	}

	/** Original detached candidate metadata only; physical coordinates never enter this check. */
	static boolean validPreviewExternalMetadata(PresentationAdapter.DetachedTarget target,
		Target.ExternalBlockTarget external) {
		return target != null && external != null && external.isCandidate()
			&& "block".equals(target.kind())
			&& target.locator() != null && target.locator().isEmpty()
			&& target.x() == 0 && target.y() == 0 && target.z() == 0
			&& external.dimensionId().equals(target.dimension())
			&& external.expectedBlockRegistryId().equals(target.registryId());
	}

	/**
	 * Candidate preview path. The provider resolves the current local position
	 * and member gate; the original detached identity is never replaced by
	 * physical coordinates, and no member outside that gate is read.
	 */
	static CreatePresentationAdapter.@Nullable Observation observePreviewSource(
		PresentationAdapter.DetachedTarget target, Set<String> demand,
		PresentationAdapter.CaptureBudget budget, SourceAccess access) {
		if (target == null || demand == null || budget == null || access == null
			|| budget.remaining() < 1 || !access.serverThread()) return null;
		Target.@Nullable ExternalBlockTarget external = target.externalBlock();
		if (external == null || !validPreviewExternalMetadata(target, external)) return null;
		if (target.dimension() == null || target.dimension().length() > 193
			|| target.dimension().isBlank() || target.registryId() == null
			|| target.registryId().length() > 193 || target.registryId().isBlank()) return null;
		if (!access.available()) return null;
		@Nullable ResourceLocation dimension = ResourceLocation.tryParse(target.dimension());
		@Nullable ResourceLocation expectedBlock = ResourceLocation.tryParse(target.registryId());
		if (dimension == null || expectedBlock == null || budget.remaining() < 2 || !budget.scan()) return null;
		@Nullable SourceWorld world = access.resolveWorld(dimension.toString());
		if (world == null) return null;
		@Nullable PreviewBinding binding = access.resolvePreviewExternal(world, external);
		if (binding == null || !admitted(binding.memberGate(), binding.position(), access.diagnostics())
			|| !access.matchesOrdinary(world, binding.position(), expectedBlock.toString())) return null;
		return access.collectPreview(world, binding.position(), Set.copyOf(demand), budget, binding.memberGate());
	}

	private static boolean admitted(java.util.function.@Nullable Predicate<BlockPos> memberGate, BlockPos position, DiagnosticLogger diagnostics) {
		if (memberGate == null) return true;
		try { return memberGate.test(position); }
		catch (RuntimeException | LinkageError unavailable) {
			CreatePresentationDiagnostics.failure(diagnostics,
				"create/presentation-member-gate", "member-rejected", () -> "position=" + position, unavailable);
			return false;
		}
	}

	private static final class ServerSource implements CreatePresentationAdapter.Source, SourceAccess {
		private final MinecraftServer server;
		private final DiagnosticLogger diagnostics;

		private ServerSource(MinecraftServer server, DiagnosticLogger diagnostics) {
			this.server = server;
			this.diagnostics = diagnostics;
		}
		@Override public DiagnosticLogger diagnostics() { return diagnostics; }

		@Override
		public CreatePresentationAdapter.@Nullable Observation observe(PresentationAdapter.DetachedTarget target,
			Set<String> demand, PresentationAdapter.CaptureBudget budget) {
			return observeSource(target, demand, budget, this);
		}

		@Override
		public CreatePresentationAdapter.@Nullable Observation observePreview(PresentationAdapter.DetachedTarget target,
			Set<String> demand, PresentationAdapter.CaptureBudget budget) {
			return target != null && target.externalBlock() != null
				? observePreviewSource(target, demand, budget, this)
				: observe(target, demand, budget);
		}

		@Override
		public boolean serverThread() { return server.isSameThread(); }

		@Override
		public boolean available() { return CreatePresentationAvailability.available(diagnostics); }

		@Override
		public @Nullable SourceWorld resolveWorld(String dimensionId) {
			@Nullable ResourceLocation dimension = ResourceLocation.tryParse(dimensionId);
			if (dimension == null) return null;
			@Nullable ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
			return level == null ? null : new SourceWorld(level, dimensionId);
		}

		@Override
		public @Nullable CommittedBinding resolveCommittedExternal(SourceWorld world, Target.ExternalBlockTarget target) {
			ServerLevel level = (ServerLevel) world.value();
			@Nullable ResolvedBlockReadSource resolved = MinecraftBlockReadSources.resolveCommittedReadSource(level, target)
				.orElse(null);
			if (resolved == null || resolved.level() != level
				|| !(resolved.descriptor().target() instanceof Target.ExternalBlockTarget bound)
				|| !MinecraftBlockReadSources.sameReadBinding(bound, target)) return null;
			Target.BlockTarget physical = resolved.descriptor().blockTarget();
			if (!physical.dimensionId().equals(target.dimensionId())
				|| !physical.blockRegistryId().equals(target.expectedBlockRegistryId())) return null;
			return new CommittedBinding(new BlockPos(physical.x(), physical.y(), physical.z()), resolved::containsMember);
		}

		@Override
		public @Nullable PreviewBinding resolvePreviewExternal(SourceWorld world, Target.ExternalBlockTarget candidate) {
			ServerLevel level = (ServerLevel) world.value();
			@Nullable ResolvedBlockReadSource resolved = MinecraftBlockReadSources.resolvePreviewReadSource(level, candidate)
				.orElse(null);
			if (resolved == null || !(resolved.descriptor().target() instanceof Target.ExternalBlockTarget bound)
				|| !MinecraftBlockReadSources.sameReadBinding(bound, candidate)) return null;
			Target.BlockTarget physical = resolved.descriptor().blockTarget();
			if (!physical.dimensionId().equals(candidate.dimensionId())
				|| !physical.blockRegistryId().equals(candidate.expectedBlockRegistryId())) return null;
			return new PreviewBinding(new BlockPos(physical.x(), physical.y(), physical.z()), resolved::containsMember);
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
		public CreatePresentationAdapter.@Nullable Observation collect(SourceWorld world, BlockPos position,
			Set<String> demand, PresentationAdapter.CaptureBudget budget) {
			return collectAt((ServerLevel) world.value(), position, demand, budget, null);
		}

		@Override
		public CreatePresentationAdapter.@Nullable Observation collect(SourceWorld world, BlockPos position,
			Set<String> demand, PresentationAdapter.CaptureBudget budget,
			java.util.function.@Nullable Predicate<BlockPos> memberGate) {
			return collectAt((ServerLevel) world.value(), position, demand, budget, memberGate);
		}

		@Override
		public CreatePresentationAdapter.@Nullable Observation collectPreview(SourceWorld world, BlockPos position,
			Set<String> demand, PresentationAdapter.CaptureBudget budget,
			java.util.function.Predicate<BlockPos> memberGate) {
			return collectAt((ServerLevel) world.value(), position, demand, budget, memberGate);
		}

		private CreatePresentationAdapter.@Nullable Observation collectAt(ServerLevel level, BlockPos pos,
			Set<String> demand, PresentationAdapter.CaptureBudget budget,
			java.util.function.@Nullable Predicate<BlockPos> memberGate) {
			int maxWork = Math.min(budget.remaining(), CreatePresentationCollector.DEFAULT_LIMITS.maxWork());
			CreateSamplingLimits defaults = CreatePresentationCollector.DEFAULT_LIMITS;
			CreateSamplingLimits limits = new CreateSamplingLimits(defaults.maxStructureBlocks(),
				defaults.maxSlots(), defaults.maxTanks(), defaults.maxRegistryIds(), maxWork,
				defaults.maxOutputBytes());
			// The derived available-capacity field reuses this single capture's raw
			// stress/capacity pair; it never asks for a second observation and
			// publishes nothing by itself.
			boolean derivedRawPair = demand.contains(CreatePresentationAdapter.AVAILABLE_CAPACITY);
			CreatePresentationCollector.Sample sampled = CreatePresentationCollector.capture(level, pos,
				demand.contains(CreatePresentationAdapter.SPEED),
				demand.contains(CreatePresentationAdapter.HAS_NETWORK),
				demand.contains(CreatePresentationAdapter.OVERSTRESSED),
				demand.contains(CreatePresentationAdapter.STRESS) || derivedRawPair,
				demand.contains(CreatePresentationAdapter.CAPACITY) || derivedRawPair,
				demand.contains(CreatePresentationAdapter.INVENTORY),
				demand.contains(CreatePresentationAdapter.FLUID), limits, memberGate, diagnostics);
			for (int scan = 0; scan < sampled.workUsed(); scan++) {
				if (!budget.scan()) {
					return null;
				}
			}
			return observation(sampled);
		}

		private CreatePresentationAdapter.@Nullable Observation observation(CreatePresentationCollector.Sample sampled) {
			if (!sampled.sourcePresent()) return null;
			CreatePresentationCollector.Kinetic kinetic = sampled.kinetic();
			CreatePresentationAdapter.@Nullable Speed speed = kinetic.effectiveSpeed() != null
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
