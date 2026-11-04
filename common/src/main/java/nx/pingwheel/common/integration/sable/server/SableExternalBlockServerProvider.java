package nx.pingwheel.common.integration.sable.server;

import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;

/**
 * Stub for the optional Sable server adapter for external (sub-level) blocks.
 *
 * <p>Platform-forced skip: Sable has no Minecraft 26.3 build (1.21.1 only, latest
 * 2.0.6), so every operation reports {@code Invalid} by design — final form, not a
 * pending port; see {@code docs/reviews/24-fidelity-constraint-audit.md} #18. The
 * provider stays registered but no Sable target can be validated, materialised or
 * refreshed.</p>
 *
 * <p>{@link #create()} is kept public/no-arg/static so
 * {@code ExternalBlockServerProviders} keeps working unchanged.</p>
 */
public final class SableExternalBlockServerProvider implements ExternalBlockServerProvider {

	public static final String PROVIDER_ID = "sable";

	private SableExternalBlockServerProvider() {
	}

	public static SableExternalBlockServerProvider create() {
		return new SableExternalBlockServerProvider();
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	public ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) {
		return new ValidationResult.Invalid();
	}

	@Override
	public MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget candidate) {
		return new MaterializationResult.Invalid();
	}

	@Override
	public RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
		return new RefreshResult.Invalid();
	}

	@Override
	public Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget target) {
		return Optional.empty();
	}

	@Override
	public void release(MinecraftServer server, Target.ExternalBlockTarget committed) {
	}

	@Override
	public void close(MinecraftServer server) {
	}
}
