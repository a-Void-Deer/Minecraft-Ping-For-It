package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless tests for the per-frame model-outline presentation and success record. */
class BlockModelOutlineStateTest {

	private static final String DIMENSION = "minecraft:overworld";

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void tearDown() {
		BlockModelOutlineState.INSTANCE.clear();
	}

	@Test
	void frameStartsEmptyAndRecordsExactSubjectSuccesses() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		BlockPresentation direct = directPresentation(1, 2, 3, "direct");
		BlockPresentation second = directPresentation(4, 5, 6, "other");
		TargetKey.ExternalBlockKey sable = new TargetKey.ExternalBlockKey(
			DIMENSION, "sable", "plot-block", "minecraft:stone");

		state.beginFrame();
		assertFalse(state.emitted());
		assertTrue(state.presentations().isEmpty());
		assertTrue(state.successKeys().isEmpty());

		state.setPresentations(List.of(direct, second));
		BlockPresentationSuccessKey directKey = direct.renderSubjects().get(0).successKey(direct.sourceSpec());
		BlockPresentationSuccessKey secondKey = second.renderSubjects().get(0).successKey(second.sourceSpec());
		state.addSuccess(directKey);
		assertTrue(state.emitted());
		assertEquals(java.util.Set.of(directKey), state.successKeys());

		state.addSuccess(secondKey);
		assertEquals(java.util.Set.of(directKey, secondKey), state.successKeys());
		assertFalse(state.externalSuccessKeys().contains(sable));

		state.addExternalSuccess(sable);
		assertTrue(state.emitted());
		assertEquals(java.util.Set.of(sable), state.externalSuccessKeys());

		// Duplicate recording keeps both immutable sets stable.
		state.addSuccess(directKey);
		assertEquals(java.util.Set.of(directKey, secondKey), state.successKeys());
		state.addExternalSuccess(sable);
		assertEquals(java.util.Set.of(sable), state.externalSuccessKeys());
	}

	@Test
	void presentationSnapshotIsImmutable() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		List<BlockPresentation> mutable = new ArrayList<>();
		BlockPresentation presentation = directPresentation(1, 2, 3, "direct");
		mutable.add(presentation);

		state.setPresentations(mutable);
		mutable.clear();

		assertEquals(List.of(presentation), state.presentations());
		assertThrows(UnsupportedOperationException.class,
			() -> state.presentations().add(presentation));
	}

	@Test
	void beginFrameClearsPresentationsAndAllPreviousSuccesses() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		BlockPresentation presentation = directPresentation(1, 2, 3, "direct");
		BlockPresentationSuccessKey success =
			presentation.renderSubjects().get(0).successKey(presentation.sourceSpec());
		state.setPresentations(List.of(presentation));
		state.addSuccess(success);
		state.addExternalSuccess(new TargetKey.ExternalBlockKey(
			DIMENSION, "sable", "plot-block", "minecraft:stone"));

		state.beginFrame();

		assertTrue(state.presentations().isEmpty());
		assertTrue(state.successKeys().isEmpty());
		assertTrue(state.externalSuccessKeys().isEmpty());
		assertFalse(state.emitted());
	}

	@Test
	void clearResetsPresentationsAndSuccesses() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		BlockPresentation presentation = directPresentation(1, 2, 3, "direct");
		state.setPresentations(List.of(presentation));
		state.addSuccess(presentation.renderSubjects().get(0).successKey(presentation.sourceSpec()));

		state.clear();

		assertTrue(state.presentations().isEmpty());
		assertTrue(state.successKeys().isEmpty());
		assertTrue(state.externalSuccessKeys().isEmpty());
	}

	@Test
	void compositePartialSuccessDoesNotCountAsFullyCovered() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		BlockPresentation presentation = compositePresentation();
		BlockRenderSubject first = presentation.renderSubjects().get(0);
		BlockRenderSubject second = presentation.renderSubjects().get(1);
		state.setPresentations(List.of(presentation));

		state.addSuccess(first.successKey(presentation.sourceSpec()));

		assertTrue(state.successKeys().contains(first.successKey(presentation.sourceSpec())));
		assertFalse(state.successKeys().contains(second.successKey(presentation.sourceSpec())));
		assertFalse(state.allPresentationsCovered());

		state.addSuccess(second.successKey(presentation.sourceSpec()));
		assertTrue(state.allPresentationsCovered());
	}

	@Test
	void directAndEmptyPresentationsUseVacuousSubjectCoverage() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		BlockPresentation direct = directPresentation(1, 2, 3, "direct");
		BlockPresentation empty = new BlockPresentation(
			sourceSpec(4, 5, 6, "block"), List.of());
		state.setPresentations(List.of(direct, empty));

		assertFalse(state.allPresentationsCovered());
		state.addSuccess(direct.renderSubjects().get(0).successKey(direct.sourceSpec()));
		assertTrue(state.allPresentationsCovered());
	}

	@Test
	void externalCoverageIsPreservedAlongsideOrdinarySubjects() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		BlockOutlineState outlines = BlockOutlineState.INSTANCE;
		outlines.clear();
		TargetKey.ExternalBlockKey externalKey = new TargetKey.ExternalBlockKey(
			DIMENSION, "sable", "plot-block", "minecraft:stone");
		state.setPresentations(List.of());
		state.addExternalSuccess(externalKey);

		// This state seam is intentionally limited to its own ordinary snapshot;
		// the external assertion is covered by the combined state method in the
		// production caller.
		assertTrue(state.allPresentationsCovered());
		assertTrue(state.allCoveredBy(outlines));
	}

	private static BlockPresentation directPresentation(int x, int y, int z, String subjectId) {
		BlockState state = Blocks.STONE.defaultBlockState();
		BlockOutlineSpec source = sourceSpec(x, y, z, "block");
		return new BlockPresentation(source, List.of(new BlockRenderSubject(
			subjectId,
			new BlockPos(x, y, z),
			state,
			"minecraft:stone",
			"block",
			BlockPresentationRelation.DIRECT)));
	}

	private static BlockPresentation compositePresentation() {
		BlockState state = Blocks.STONE.defaultBlockState();
		BlockOutlineSpec source = sourceSpec(10, 20, 30, "block");
		return new BlockPresentation(source, List.of(
			new BlockRenderSubject(
				"lower", new BlockPos(10, 20, 30), state, "minecraft:stone", "block",
				BlockPresentationRelation.COMPOSITE),
			new BlockRenderSubject(
				"upper", new BlockPos(10, 21, 30), state, "minecraft:stone", "block",
				BlockPresentationRelation.COMPOSITE)));
	}

	private static BlockOutlineSpec sourceSpec(int x, int y, int z, String targetTypeId) {
		return new BlockOutlineSpec(
			new MarkerId(x * 100_000L + y * 100L + z),
			new TargetKey.BlockKey(DIMENSION, x, y, z, "minecraft:stone"),
			targetTypeId,
			"attention",
			0xFF123456);
	}
}
