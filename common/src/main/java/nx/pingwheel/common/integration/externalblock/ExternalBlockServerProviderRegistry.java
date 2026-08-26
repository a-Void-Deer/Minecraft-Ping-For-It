package nx.pingwheel.common.integration.externalblock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
