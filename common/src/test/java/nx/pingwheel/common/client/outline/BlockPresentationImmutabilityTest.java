package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockPresentationImmutabilityTest {

	private static final String OVERWORLD = "minecraft:overworld";

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void renderSubjectsAreCopiedAndImmutable() {
		BlockPos pos = new BlockPos(8, 64, 8);
		List<BlockRenderSubject> subjects = new ArrayList<>();
		subjects.add(subject("main", pos));

		BlockPresentation presentation = new BlockPresentation(spec(), subjects, List.of());
		subjects.clear();

		assertEquals(1, presentation.renderSubjects().size());
		assertThrows(UnsupportedOperationException.class,
			() -> presentation.renderSubjects().add(subject("extra", pos.above())));
	}

	private static BlockOutlineSpec spec() {
		return new BlockOutlineSpec(
			new MarkerId(8L),
			new TargetKey.BlockKey(OVERWORLD, 8, 64, 8, "minecraft:stone"),
			"entity_block",
			"attention",
			0xFF123456);
	}

	private static BlockRenderSubject subject(String id, BlockPos pos) {
		return new BlockRenderSubject(
			id, pos, Blocks.STONE.defaultBlockState(), "minecraft:stone",
			"entity_block", BlockPresentationRelation.COMPOSITE);
	}
}
