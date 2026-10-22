package nx.pingwheel.common.integration.sable.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.ExternalBlockReferenceIndex;
import nx.pingwheel.common.marker.MarkerAnchor;

import static org.junit.jupiter.api.Assertions.*;

/** Production Content resolver/stable-entry seams, not a duplicated provider model or fake ServerLevel. */
class SableBlockReadSourceTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final UUID SUBLEVEL = UUID.fromString("66e0ecf2-00af-47f8-8b1e-faa82bc1d27b");
	private static final UUID STABLE = UUID.fromString("c4b31d39-2e3b-4c28-bb64-3a181df68415");
	private static final BlockPos POSITION = new BlockPos(100, 64, -200);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void previewKeepsOriginalIdentityAndSeparateDetachedPhysicalBinding() throws Exception {
		RecordingAccess access = new RecordingAccess();
		Target.ExternalBlockTarget candidate = candidate("sable", DIMENSION, "minecraft:chest", locator());
		var source = SableExternalBlockServerProvider.resolvePreviewDescriptor(access.parent, DIMENSION, candidate, access)
			.orElseThrow();
		assertSame(candidate, source.target());
		assertTrue(((Target.ExternalBlockTarget) source.target()).isCandidate());
		assertEquals(new Target.BlockTarget(DIMENSION, 100, 64, -200, "minecraft:chest"), source.blockTarget());
		assertEquals(new MarkerAnchor(5, 6, 7), source.validationAnchor());
		assertNotEquals(source.blockTarget().x() + 0.5, source.validationAnchor().x());
		assertEquals(SUBLEVEL.toString(), source.subLevelId());
		assertEquals(1, access.stateReads);
	}

	@Test
	void malformedIdentityRejectsBeforeProviderAccess() throws Exception {
		RecordingAccess access = new RecordingAccess();
		for (Target.ExternalBlockTarget target : java.util.List.of(
			candidate("other", DIMENSION, "minecraft:chest", locator()),
			candidate("sable", "minecraft:the_nether", "minecraft:chest", locator()),
			candidate("sable", DIMENSION, "not valid!", locator()),
			candidate("sable", DIMENSION, "minecraft:chest", "malformed"),
			Target.ExternalBlockTarget.committed(DIMENSION, "sable", STABLE.toString(), "minecraft:chest", locator(), true))) {
			assertTrue(SableExternalBlockServerProvider.resolvePreviewDescriptor(access.parent, DIMENSION, target, access).isEmpty());
		}
		assertEquals(0, access.lookups);
		assertEquals(0, access.stateReads);
	}

	@Test
	void positiveSameSublevelContainmentAndLoadedIdentityPrecedeAnyContentRead() throws Exception {
		for (int failure = 0; failure < 5; failure++) {
			RecordingAccess access = new RecordingAccess();
			switch (failure) {
				case 0 -> access.present = false;
				case 1 -> access.current = false;
				case 2 -> access.containing = null;
				case 3 -> access.containing = UUID.randomUUID();
				case 4 -> access.loaded = false;
			}
			assertTrue(SableExternalBlockServerProvider.resolvePreviewDescriptor(access.parent, DIMENSION,
				candidate("sable", DIMENSION, "minecraft:chest", locator()), access).isEmpty());
			assertEquals(0, access.stateReads, "unavailable membership must not read the root content");
		}
		RecordingAccess replaced = new RecordingAccess();
		replaced.state = Blocks.STONE.defaultBlockState();
		assertTrue(SableExternalBlockServerProvider.resolvePreviewDescriptor(replaced.parent, DIMENSION,
			candidate("sable", DIMENSION, "minecraft:chest", locator()), replaced).isEmpty());
		RecordingAccess missingPose = new RecordingAccess();
		missingPose.anchor = null;
		assertTrue(SableExternalBlockServerProvider.resolvePreviewDescriptor(missingPose.parent, DIMENSION,
			candidate("sable", DIMENSION, "minecraft:chest", locator()), missingPose).isEmpty());
		assertEquals(1, missingPose.anchorReads);
		assertEquals(0, replaced.anchorReads);
		replaced.state = Blocks.AIR.defaultBlockState();
		assertTrue(SableExternalBlockServerProvider.resolvePreviewDescriptor(replaced.parent, DIMENSION,
			candidate("sable", DIMENSION, "minecraft:chest", locator()), replaced).isEmpty());
	}

	@Test
	void multiMemberGateNeverAcceptsNeighborFromAnotherSublevel() throws Exception {
		RecordingAccess access = new RecordingAccess();
		assertTrue(SableExternalBlockServerProvider.contentMember(access.parent, access.sublevel, SUBLEVEL, POSITION, access));
		access.containing = UUID.randomUUID();
		assertFalse(SableExternalBlockServerProvider.contentMember(access.parent, access.sublevel, SUBLEVEL, POSITION.east(), access));
		assertEquals(0, access.stateReads);
		access.containing = null;
		assertFalse(SableExternalBlockServerProvider.contentMember(access.parent, access.sublevel, SUBLEVEL, POSITION.east(), access));
	}

	@Test
	void committedContentFollowsCurrentTrackingPointAndReleasedLeaseCannotResolve() throws Exception {
		RecordingAccess access = new RecordingAccess();
		Target.ExternalBlockTarget committed = Target.ExternalBlockTarget.committed(
			DIMENSION, "sable", STABLE.toString(), "minecraft:chest", "obsolete-unparseable-locator", true);
		var references = new ExternalBlockReferenceIndex();
		var lease = references.prepare(new ExternalBlockReferenceIndex.LocatorKey("sable", "old", "minecraft:chest", true), STABLE::toString);
		assertTrue(references.commit(lease));
		Entry entry = new Entry(STABLE);
		Map<String, Entry> entries = new HashMap<>(Map.of(STABLE.toString(), entry));
		AtomicInteger reads = new AtomicInteger();
		SableExternalBlockServerProvider.CurrentLiveResolver<java.util.Optional<nx.pingwheel.common.integration.externalblock.BlockReadSource>> resolver = (id, pos) -> {
			reads.incrementAndGet();
			return SableExternalBlockServerProvider.resolveContentDescriptor(access.parent, DIMENSION, committed,
				new SableExternalBlockLocator(id, pos), access);
		};
		var source = SableExternalBlockServerProvider.observeStableEntry(committed, entries, references::references,
			entry, true, SUBLEVEL, new Vector3d(100.5, 64.5, -199.5), resolver).orElseThrow();
		assertSame(committed, source.target());
		assertEquals(POSITION, new BlockPos(source.blockTarget().x(), source.blockTarget().y(), source.blockTarget().z()));
		assertEquals(1, references.references(STABLE.toString()));
		assertEquals(Map.of(STABLE.toString(), entry), entries);
		assertTrue(references.release(STABLE.toString(), entries::remove));
		assertNull(SableExternalBlockServerProvider.observeStableEntry(committed, entries, references::references,
			entry, true, SUBLEVEL, new Vector3d(100.5, 64.5, -199.5), resolver));
		assertEquals(1, reads.get());
		assertEquals(0, references.size());
	}

	private static String locator() { return new SableExternalBlockLocator(SUBLEVEL, POSITION).encode(); }
	private static Target.ExternalBlockTarget candidate(String provider, String dimension, String registry, String locator) {
		return Target.ExternalBlockTarget.candidate(dimension, provider, registry, locator, true);
	}
	private record Entry(UUID trackingId) implements SableExternalBlockServerProvider.StableEntryView {}
	private static final class RecordingAccess implements SableExternalBlockServerProvider.ContentAccess {
		final Object parent = new Object(), sublevel = new Object();
		boolean present = true, current = true, loaded = true;
		UUID containing = SUBLEVEL;
		BlockState state = Blocks.CHEST.defaultBlockState();
		MarkerAnchor anchor = new MarkerAnchor(5, 6, 7);
		int lookups, stateReads, anchorReads;
		@Override public Object subLevel(Object parent, UUID id) { lookups++; return present && SUBLEVEL.equals(id) ? sublevel : null; }
		@Override public boolean current(Object parent, Object subLevel, UUID id) { return current && parent == this.parent && subLevel == sublevel && SUBLEVEL.equals(id); }
		@Override public UUID containing(Object parent, BlockPos pos) { return containing; }
		@Override public boolean loaded(Object parent, BlockPos pos) { return loaded; }
		@Override public BlockState state(Object parent, BlockPos pos) { stateReads++; return state; }
		@Override public MarkerAnchor anchor(Object subLevel, BlockPos pos) { anchorReads++; return anchor; }
	}
}
