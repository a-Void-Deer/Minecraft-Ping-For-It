package nx.pingwheel.common.integration.sable.server;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.marker.MarkerAnchor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class SablePhysicalMembershipTest {
	@Test void ordinaryMembershipUsesContainingQueryOnlyAndSeparatesAbsentFromFailedEvidence() throws Exception {
		var access = new Access(); var parent = new Object(); var pos = new BlockPos(1, 64, 3);
		assertEquals(ExternalBlockServerProvider.Membership.OUTSIDE, SableExternalBlockServerProvider.queryMembership(parent, pos, access));
		access.id = new UUID(1, 2);
		assertEquals(ExternalBlockServerProvider.Membership.inside(access.id.toString()), SableExternalBlockServerProvider.queryMembership(parent, pos, access));
		assertEquals(2, access.queries); access.failed = true;
		assertThrows(ReflectiveOperationException.class, () -> SableExternalBlockServerProvider.queryMembership(parent, pos, access));
		assertEquals(3, access.queries);
	}
	@NotNullByDefault
	private static final class Access implements SableExternalBlockServerProvider.ContentAccess {
		private @Nullable UUID id;
		private int queries;
		private boolean failed;
		@Override public @Nullable UUID containing(Object parent, BlockPos position) throws ReflectiveOperationException {
			queries++; if (failed) throw new ReflectiveOperationException("containing unavailable"); return id;
		}
		@Override public Object subLevel(Object parent, UUID id) { throw new AssertionError("membership must not resolve source"); }
		@Override public boolean current(Object parent, Object subLevel, UUID id) { throw new AssertionError("membership must not inspect source"); }
		@Override public boolean loaded(Object parent, BlockPos position) { throw new AssertionError("membership must not read source"); }
		@Override public BlockState state(Object parent, BlockPos position) { throw new AssertionError("membership must precede content"); }
		@Override public MarkerAnchor anchor(Object subLevel, BlockPos position) { throw new AssertionError("membership must not transform pose"); }
	}
}
