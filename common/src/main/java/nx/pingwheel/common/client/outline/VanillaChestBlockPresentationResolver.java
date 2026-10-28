package nx.pingwheel.common.client.outline;

import java.util.List;
import nx.pingwheel.common.topology.VanillaDoubleChestTopology;
import org.jetbrains.annotations.NotNullByDefault;

/** Vanilla chest BER renders one half: neither subject covers the other. */
@NotNullByDefault
public final class VanillaChestBlockPresentationResolver implements BlockPresentationResolver {
	public static final String ID = "pingforit:vanilla_chest";

	@Override
	public String id() { return ID; }

	@Override
	public BlockPresentationResolution resolve(BlockPresentationContext context) {
		String registry = context.sourceSpec().blockKey().blockRegistryId();
		if (!VanillaDoubleChestTopology.supports(registry)) return BlockPresentationResolution.UNHANDLED;
		return VanillaDoubleChestTopology.resolve(context.sourcePos(), registry, context::loadedState)
			.map(pair -> BlockPresentationResolution.handled(List.of(
				new BlockRenderSubject("left", pair.leftPos(), pair.leftState(), registry,
					context.sourceSpec().targetTypeId(), BlockPresentationRelation.COMPOSITE),
				new BlockRenderSubject("right", pair.rightPos(), pair.rightState(), registry,
					context.sourceSpec().targetTypeId(), BlockPresentationRelation.COMPOSITE))))
			.orElseGet(context::directResolution);
	}
}
