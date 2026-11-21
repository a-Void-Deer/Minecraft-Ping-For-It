package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Test-only bridge that feeds already-built ordinary presentations through the
 * existing package-private frame preparation. It does not add a production
 * setter or bypass the one-resolution-per-frame gate.
 */
@NotNullByDefault
final class BlockModelOutlineTestSupport {

	private BlockModelOutlineTestSupport() {}

	static void prepare(BlockModelOutlineState state, BlockPresentation... presentations) {
		List<BlockOutlineSpec> specs = new ArrayList<>(presentations.length);
		Map<BlockOutlineSpec, BlockPresentation> bySpec = new LinkedHashMap<>();

		for (BlockPresentation presentation : presentations) {
			BlockOutlineSpec sourceSpec = presentation.sourceSpec();
			specs.add(sourceSpec);
			bySpec.put(sourceSpec, presentation);
		}

		state.preparePresentations(specs, List.of(), spec -> {
			@Nullable BlockPresentation presentation = bySpec.get(spec);

			if (presentation == null) {
				throw new AssertionError("unexpected ordinary spec " + spec);
			}

			return presentation;
		}, spec -> {
			throw new AssertionError("unexpected external spec " + spec);
		});
	}
}
