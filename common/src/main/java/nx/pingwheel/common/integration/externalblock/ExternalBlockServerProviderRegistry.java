package nx.pingwheel.common.integration.externalblock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.Target;

/**
 * Deterministic registry and dispatch boundary for external block providers.
 * Providers are selected by their exact stable id; registration order is
 * retained and the first registration for an id wins.
 */
public final class ExternalBlockServerProviderRegistry {

	private final Map<String, ExternalBlockServerProvider> providers = new LinkedHashMap<>();

	/**
	 * Registers a provider. A duplicate id is ignored, which makes optional
	 * bootstrap registration idempotent without making provider ordering depend
	 * on unordered discovery.
	 */
	public synchronized void register(ExternalBlockServerProvider provider) {
		Objects.requireNonNull(provider, "provider");
		String id = Objects.requireNonNull(provider.providerId(), "provider.providerId");

		if (id.isBlank() || id.length() > Target.ExternalBlockTarget.MAX_IDENTIFIER_LENGTH) {
			throw new IllegalArgumentException("provider id must be non-blank and bounded");
		}

		providers.putIfAbsent(id, provider);
	}

	public synchronized boolean isEmpty() {
		return providers.isEmpty();
	}

	public synchronized List<ExternalBlockServerProvider> providers() {
		return List.copyOf(new ArrayList<>(providers.values()));
	}

	public synchronized ExternalBlockServerProvider find(String providerId) {
		return providerId == null ? null : providers.get(providerId);
	}

	public ExternalBlockServerProvider.ValidationResult validate(
		ServerLevel level, Target.ExternalBlockTarget candidate
	) {
		if (level == null || candidate == null || !candidate.isCandidate()) {
			return new ExternalBlockServerProvider.ValidationResult.Invalid();
		}

		ExternalBlockServerProvider provider = find(candidate.providerId());

		if (provider == null) {
			return new ExternalBlockServerProvider.ValidationResult.Invalid();
		}

		try {
			ExternalBlockServerProvider.ValidationResult result = provider.validate(level, candidate);
			return result == null
				? new ExternalBlockServerProvider.ValidationResult.Invalid()
				: result;
		} catch (RuntimeException | LinkageError ignored) {
			return new ExternalBlockServerProvider.ValidationResult.Invalid();
		}
	}

	public ExternalBlockServerProvider.MaterializationResult materialize(
		ServerLevel level, Target.ExternalBlockTarget candidate
	) {
		if (level == null || candidate == null || !candidate.isCandidate()) {
			return new ExternalBlockServerProvider.MaterializationResult.Invalid();
		}

		ExternalBlockServerProvider provider = find(candidate.providerId());

		if (provider == null) {
			return new ExternalBlockServerProvider.MaterializationResult.Invalid();
		}

		try {
			ExternalBlockServerProvider.MaterializationResult result = provider.materialize(level, candidate);
			return result == null
				? new ExternalBlockServerProvider.MaterializationResult.Invalid()
				: result;
		} catch (RuntimeException | LinkageError ignored) {
			return new ExternalBlockServerProvider.MaterializationResult.Invalid();
		}
	}

	public ExternalBlockServerProvider.RefreshResult refresh(
		ServerLevel level, Target.ExternalBlockTarget committed
	) {
		if (level == null || committed == null || !committed.isCommitted()) {
			return new ExternalBlockServerProvider.RefreshResult.Invalid();
		}

		ExternalBlockServerProvider provider = find(committed.providerId());

		if (provider == null) {
			return new ExternalBlockServerProvider.RefreshResult.Invalid();
		}

		try {
			ExternalBlockServerProvider.RefreshResult result = provider.refresh(level, committed);
			return result == null
				? new ExternalBlockServerProvider.RefreshResult.Invalid()
				: result;
		} catch (RuntimeException | LinkageError ignored) {
			return new ExternalBlockServerProvider.RefreshResult.Invalid();
		}
	}

	/** Candidate Content access is additive and never falls back to materialization/observation. */
	public java.util.Optional<ResolvedBlockReadSource> resolvePreviewReadSource(
		ServerLevel level, Target.ExternalBlockTarget candidate
	) {
		return resolveReadSource(level, candidate, true);
	}

	public java.util.Optional<ResolvedBlockReadSource> resolveCommittedReadSource(
		ServerLevel level, Target.ExternalBlockTarget committed
	) {
		return resolveReadSource(level, committed, false);
	}

	private java.util.Optional<ResolvedBlockReadSource> resolveReadSource(
		ServerLevel level, Target.ExternalBlockTarget target, boolean preview
	) {
		if (level == null || target == null || target.isCandidate() != preview
			|| level.getServer() == null || !level.getServer().isSameThread()
			|| !target.dimensionId().equals(level.dimension().location().toString())) {
			return java.util.Optional.empty();
		}
		ExternalBlockServerProvider provider = find(target.providerId());
		try {
			var result = invokeReadSource(provider, level, target, preview);
			if (result == null || result.isEmpty()) return java.util.Optional.empty();
			var source = result.orElseThrow();
			var descriptor = source.descriptor();
			var physical = descriptor.blockTarget();
			if (source.level() != level || !BlockReadSource.sameTargetBinding(target, descriptor.target())
				|| !target.providerId().equals(descriptor.providerId())
				|| !source.containsMember(new BlockPos(physical.x(), physical.y(), physical.z()))) {
				return java.util.Optional.empty();
			}
			return result;
		} catch (RuntimeException | LinkageError unavailable) { return java.util.Optional.empty(); }
	}

	static java.util.Optional<ResolvedBlockReadSource> invokeReadSource(ExternalBlockServerProvider provider,
		ServerLevel level, Target.ExternalBlockTarget target, boolean preview) {
		if (provider == null || target == null || target.isCandidate() != preview) return java.util.Optional.empty();
		try {
			if (!target.providerId().equals(provider.providerId())) return java.util.Optional.empty();
			var result = preview ? provider.resolvePreviewReadSource(level, target)
				: provider.resolveCommittedReadSource(level, target);
			return result == null ? java.util.Optional.empty() : result;
		} catch (RuntimeException | LinkageError unavailable) { return java.util.Optional.empty(); }
	}

	/** Resolves and validates one current committed external block observation. */
	public ExternalBlockServerProvider.ObservationResult observeBlock(
		MinecraftServer server, ServerLevel level, Target.ExternalBlockTarget committed
	) {
		ExternalBlockServerProvider provider = committed == null ? null : find(committed.providerId());
		return observeBlockWithAccess(server, level, committed, provider,
			(ignored, target) -> provider.observeBlock(level, target), LIVE_ACCESS);
	}

	static ExternalBlockServerProvider.ObservationResult observeBlockWithAccess(
		Object serverToken, Object levelToken, Target.ExternalBlockTarget committed,
		ExternalBlockServerProvider provider, ObservationInvoker invoker, ObservationWorldAccess access
	) {
		if (committed == null || !committed.isCommitted()) {
			return new ExternalBlockServerProvider.ObservationResult.Invalid();
		}
		if (access == null || !access.isServerThread(serverToken)
			|| !access.sameServer(serverToken, levelToken)
			|| !access.sameDimension(levelToken, committed.dimensionId())) {
			return new ExternalBlockServerProvider.ObservationResult.Invalid();
		}
		if (provider == null || invoker == null) {
			return new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
		}
		ExternalBlockServerProvider.ObservationResult result = invokeObservation(invoker, levelToken, committed);
		return validateObservation(serverToken, levelToken, committed, result, access);
	}

	static ExternalBlockServerProvider.ObservationResult validateObservation(
		MinecraftServer server, ServerLevel level, Target.ExternalBlockTarget committed,
		ExternalBlockServerProvider.ObservationResult result
	) {
		return validateObservation(server, level, committed, result, LIVE_ACCESS);
	}

	static ExternalBlockServerProvider.ObservationResult validateObservation(
		Object serverToken, Object levelToken, Target.ExternalBlockTarget committed,
		ExternalBlockServerProvider.ObservationResult result, ObservationWorldAccess access
	) {
		if (committed == null || !committed.isCommitted()) {
			return new ExternalBlockServerProvider.ObservationResult.Invalid();
		}
		if (!(result instanceof ExternalBlockServerProvider.ObservationResult.Available available)) {
			return result == null
				? new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable() : result;
		}
		ExternalBlockServerProvider.BlockObservation observation = available.observation();
		if (observation == null || observation.level() != levelToken
			|| !access.sameServer(serverToken, levelToken)
			|| !access.sameDimension(levelToken, committed.dimensionId())
			|| !access.isServerThread(serverToken)
			|| !access.isLoaded(levelToken, observation.position())) {
			return new ExternalBlockServerProvider.ObservationResult.Invalid();
		}
		BlockState localState = access.state(levelToken, observation.position());
		var actualId = localState == null ? null : BuiltInRegistries.BLOCK.getKey(localState.getBlock());
		if (localState == null || localState.isAir() || actualId == null
			|| !committed.expectedBlockRegistryId().equals(actualId.toString())
			|| !localState.equals(observation.state())) {
			return new ExternalBlockServerProvider.ObservationResult.Invalid();
		}
		return available;
	}

	@FunctionalInterface
	interface ObservationInvoker {
		ExternalBlockServerProvider.ObservationResult observe(Object levelToken,
			Target.ExternalBlockTarget committed);
	}

	interface ObservationWorldAccess {
		boolean sameServer(Object serverToken, Object levelToken);
		boolean sameDimension(Object levelToken, String expectedDimension);
		boolean isServerThread(Object serverToken);
		boolean isLoaded(Object levelToken, BlockPos position);
		BlockState state(Object levelToken, BlockPos position);
	}

	private static final ObservationWorldAccess LIVE_ACCESS = new ObservationWorldAccess() {
		@Override
		public boolean sameServer(Object serverToken, Object levelToken) {
			return levelToken instanceof ServerLevel level
				&& serverToken instanceof MinecraftServer server && level.getServer() == server;
		}

		@Override
		public boolean sameDimension(Object levelToken, String expectedDimension) {
			return levelToken instanceof ServerLevel level
				&& expectedDimension.equals(level.dimension().location().toString());
		}

		@Override
		public boolean isServerThread(Object serverToken) {
			return serverToken instanceof MinecraftServer server && server.isSameThread();
		}

		@Override
		public boolean isLoaded(Object levelToken, BlockPos position) {
			return levelToken instanceof ServerLevel level && level.isLoaded(position);
		}

		@Override
		public BlockState state(Object levelToken, BlockPos position) {
			return levelToken instanceof ServerLevel level ? level.getBlockState(position) : null;
		}
	};

	static ExternalBlockServerProvider.ObservationResult invokeObservation(
		ExternalBlockServerProvider provider, ServerLevel level, Target.ExternalBlockTarget committed
	) {
		return invokeObservation((ignored, target) -> provider.observeBlock(level, target), level, committed);
	}

	static ExternalBlockServerProvider.ObservationResult invokeObservation(
		ObservationInvoker invoker, Object level, Target.ExternalBlockTarget committed
	) {
		try {
			ExternalBlockServerProvider.ObservationResult result = invoker.observe(level, committed);
			return result == null
				? new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable()
				: result;
		} catch (RuntimeException | LinkageError ignored) {
			return new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
		}
	}

	public java.util.Optional<ExternalBlockServerProvider.ExternalBlockName> resolveName(
		ServerLevel level, Target.ExternalBlockTarget committed
	) {
		if (level == null || committed == null) {
			return java.util.Optional.empty();
		}

		ExternalBlockServerProvider provider = find(committed.providerId());

		if (provider == null) {
			return java.util.Optional.empty();
		}

		try {
			java.util.Optional<ExternalBlockServerProvider.ExternalBlockName> result =
				provider.resolveName(level, committed);
			return result == null ? java.util.Optional.empty() : result;
		} catch (RuntimeException | LinkageError ignored) {
			return java.util.Optional.empty();
		}
	}

	/** Forwards the authoritative range observation without exposing provider APIs to core code. */
	public void observeValidationDistance(
		ServerLevel level,
		Target.ExternalBlockTarget target,
		nx.pingwheel.common.marker.MarkerAnchor anchor,
		double distance,
		boolean withinRange
	) {
		if (level == null || target == null || anchor == null) {
			return;
		}

		ExternalBlockServerProvider provider = find(target.providerId());

		if (provider == null) {
			return;
		}

		try {
			provider.observeValidationDistance(level, target, anchor, distance, withinRange);
		} catch (RuntimeException | LinkageError ignored) {
			// Diagnostics must never change authoritative validation behavior.
		}
	}

	/** Releases one committed marker reference, if its provider is registered. */
	public void release(MinecraftServer server, Target.ExternalBlockTarget committed) {
		if (server == null || committed == null || !committed.isCommitted()) {
			return;
		}

		ExternalBlockServerProvider provider = find(committed.providerId());

		if (provider == null) {
			return;
		}

		try {
			provider.release(server, committed);
		} catch (RuntimeException | LinkageError ignored) {
			// Provider cleanup is deliberately fail-soft. A later server close can
			// still give the provider one final opportunity to release its index.
		}
	}

	/** Releases one committed marker reference and carries its marker id when available. */
	public void release(
		MinecraftServer server, Target.ExternalBlockTarget committed, String markerId
	) {
		if (server == null || committed == null || !committed.isCommitted()) {
			return;
		}

		ExternalBlockServerProvider provider = find(committed.providerId());

		if (provider == null) {
			return;
		}

		try {
			provider.release(server, committed, markerId);
		} catch (RuntimeException | LinkageError ignored) {
			// Provider cleanup is deliberately fail-soft, as in the legacy overload.
		}
	}

	/** Closes provider state for one server in deterministic registration order. */
	public void close(MinecraftServer server) {
		if (server == null) {
			return;
		}

		for (ExternalBlockServerProvider provider : providers()) {
			try {
				provider.close(server);
			} catch (RuntimeException | LinkageError ignored) {
				// Optional provider teardown must not destabilize server shutdown.
			}
		}
	}

	/** Removes all registrations. Intended for optional-integration bootstrap. */
	public synchronized void clear() {
		providers.clear();
	}
}
