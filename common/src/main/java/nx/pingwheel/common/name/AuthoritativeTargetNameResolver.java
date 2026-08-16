package nx.pingwheel.common.name;

import java.util.UUID;

import nx.pingwheel.common.domain.Target;

/**
 * Derives the server-authoritative display name JSON for a validated marker
 * target.
 *
 * <p>The Minecraft 1.21.1 adapter is
 * {@link MinecraftTargetNameResolver}, which derives localized vanilla names,
 * custom names, and contained item names exclusively from live server state.
 * The {@code normalizedTarget} argument is the server's re-derived target
 * identity, never a client-supplied value, so a client can never influence the
 * produced name.
 *
 * <p>Callers must treat a null return or a thrown exception as a resolver
 * contract failure and fall back to a fail-safe name; the fail-safe is never
 * the normal path. Implementations must not mutate any store state and must
 * never log names, JSON, UUIDs, positions, or registry ids.
 */
@FunctionalInterface
public interface AuthoritativeTargetNameResolver {

	TargetNameJson resolveName(UUID requester, Target normalizedTarget);
}
