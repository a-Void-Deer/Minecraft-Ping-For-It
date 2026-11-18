package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The common source gate must not load chunks or promote pending block entities. */
@NotNullByDefault
class BlockPresentationContextTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void knownContainerSourceGatesUsePositiveLoadedStateBeforeRegistryOrResolverAccess() {
		for (String registry : List.of("minecraft:chest", "minecraft:trapped_chest", "create:item_vault", "create:fluid_tank")) {
			var f = new DoubleChestFixture(Blocks.CHEST, Direction.NORTH);
			var source = new BlockOutlineSpec(new MarkerId(1), new TargetKey.BlockKey("minecraft:overworld",
				f.left.getX(), f.left.getY(), f.left.getZ(), registry), "entity_block", "attention", 0xFF123456);
			var context = new BlockPresentationContext(f.client, source);
			assertSame(f.states.get(f.left), context.sourceState()); assertEquals(1, f.chunkLookups);
			assertEquals(0, f.ordinaryClientReads); assertEquals(0, f.blockEntities);
			var calls = new AtomicInteger(); var resolvers = new BlockPresentationResolverRegistry(false);
			resolvers.register(BlockPresentationResolver.of("test:never-unloaded", ignored -> {
				calls.incrementAndGet(); return BlockPresentationResolution.UNHANDLED;
			}));
			var fallback = resolvers.resolve(context);
			if (context.sourceBlockMatches()) {
				assertEquals(1, fallback.renderSubjects().size());
				assertEquals(BlockPresentationRelation.DIRECT, fallback.renderSubjects().getFirst().relation());
			} else {
				assertTrue(fallback.renderSubjects().isEmpty());
			}
			int callsBeforeUnavailable = calls.get();
			f.unavailable.add(f.left);
			assertSame(Blocks.AIR.defaultBlockState(), context.sourceState());
			assertTrue(context.directResolution().subjects().isEmpty());
			assertTrue(resolvers.resolve(context).renderSubjects().isEmpty());
			assertEquals(callsBeforeUnavailable, calls.get());
			assertEquals(0, f.ordinaryClientReads); assertEquals(0, f.blockEntities);
		}
	}

	@Test void replacedTankSourceStopsBeforeOptionalMetadataOrDirectFallbackCanClaimReplacement() {
		var f = new DoubleChestFixture(Blocks.CHEST, Direction.NORTH);
		var source = new BlockOutlineSpec(new MarkerId(1), new TargetKey.BlockKey("minecraft:overworld",
			f.left.getX(), f.left.getY(), f.left.getZ(), "create:fluid_tank"), "entity_block", "attention", 0xFF123456);
		var resolvers = new BlockPresentationResolverRegistry(false);
		var calls = new AtomicInteger();
		resolvers.register(BlockPresentationResolver.of("test:metadata", ignored -> {
			calls.incrementAndGet(); return BlockPresentationResolution.UNHANDLED;
		}));
		var context = new BlockPresentationContext(f.client, source);
		assertTrue(context.directResolution().subjects().isEmpty());
		assertTrue(resolvers.resolve(context).renderSubjects().isEmpty());
		assertEquals(0, calls.get(), "registry mismatch must precede optional metadata");
		assertEquals(0, f.ordinaryClientReads); assertEquals(0, f.blockEntities);
	}
}
