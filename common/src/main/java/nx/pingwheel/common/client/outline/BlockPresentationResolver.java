package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves a live block target into zero or more render subjects.
 *
 * <p>Returning {@link BlockPresentationResolution#UNHANDLED} leaves the
 * target available to the next resolver. A handled result, including one with
 * zero subjects, ends resolution.</p>
 */
public interface BlockPresentationResolver {
	/** Stable namespaced id used for deterministic registration. */
	String id();

	BlockPresentationResolution resolve(BlockPresentationContext context);

	/** Alias for callers that describe the resolver id as its unique id. */
	default String uniqueId() {
		return id();
	}

	/** Small adapter useful for built-ins and focused tests. */
	static BlockPresentationResolver of(
		String id,
		Function<BlockPresentationContext, BlockPresentationResolution> resolve
	) {
		String stableId = EntityBlockGeometrySourceIds.require(id);
		Objects.requireNonNull(resolve, "resolve");

		return new BlockPresentationResolver() {
			@Override
			public String id() {
				return stableId;
			}

			@Override
			public BlockPresentationResolution resolve(BlockPresentationContext context) {
				return resolve.apply(context);
			}
		};
	}
}
