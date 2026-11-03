package nx.pingwheel.common.client.outline;

import java.util.Objects;

/**
 * Render-thread scoped seed override for the vanilla block model renderer.
 *
 * <p>The vanilla chunk renderer derives the random seed that selects
 * weighted/rotated model variants (turtle eggs, sea pickles, ...) from
 * {@code BlockState#getSeed(pos)} of the real block. The virtual
 * {@code BlockDisplay} model-glow route has no real block at the renderer's
 * seed computation site, so the glow may otherwise pick a different weighted
 * variant than the block in the world.
 *
 * <p>This utility lets {@link VirtualBlockDisplayRenderer} scope the live
 * {@code BlockState#getSeed(pos)} of the validated target around the vanilla
 * model render dispatch: while the scope is active, the
 * {@code ModelBlockRenderer} mixin substitutes the active seed for the
 * vanilla seed at every {@code RandomSource#setSeed} call; outside any scope,
 * {@link #resolve(long)} returns the caller's fallback unchanged, so all
 * vanilla rendering (real block displays, item renders, chunk building) is
 * unaffected.
 *
 * <p>Main render-thread scoped purpose: the seed is derived from a live
 * {@code BlockState} and a concrete {@code BlockPos} during the world render
 * pass and is only meaningful for the immediate dispatch. The ThreadLocal is
 * removed on outer scope exit so no stale seed can leak into later
 * (non-scoped) renders on the same thread. Nested scopes restore the prior
 * value, and {@link #runWithSeed(long, Runnable)} restores the thread state
 * even when the action throws. No logs, no global world/team state, no
 * mutations.
 */
public final class BlockModelRenderSeed {

	private static final ThreadLocal<Long> ACTIVE_SEED = new ThreadLocal<>();

	private BlockModelRenderSeed() {}

	/**
	 * Resolves the seed the vanilla model renderer should use for its next
	 * {@code RandomSource#setSeed} call: the active scoped seed when inside
	 * {@link #runWithSeed(long, Runnable)}, otherwise {@code fallback}
	 * unchanged (vanilla behavior, including the default constant 42).
	 */
	public static long resolve(long fallback) {
		Long active = ACTIVE_SEED.get();
		return active != null ? active : fallback;
	}

	/**
	 * Runs {@code action} with {@code seed} as the active model render seed,
	 * restoring the previous thread state — or removing the ThreadLocal when
	 * no scope was active — after the action completes or throws.
	 */
	public static void runWithSeed(long seed, Runnable action) {
		Objects.requireNonNull(action, "action");

		Long prior = ACTIVE_SEED.get();
		ACTIVE_SEED.set(seed);

		try {
			action.run();
		} finally {
			if (prior != null) {
				ACTIVE_SEED.set(prior);
			} else {
				ACTIVE_SEED.remove();
			}
		}
	}
}
