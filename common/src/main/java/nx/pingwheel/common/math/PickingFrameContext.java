package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.sable.SableEntityFrames;
import nx.pingwheel.common.integration.sable.client.SablePickingAccess;
import org.jetbrains.annotations.NotNullByDefault;

/** Synchronous, query-local frozen frame lookup; never part of captured asynchronous data. */
@NotNullByDefault
public final class PickingFrameContext implements AutoCloseable {
	@NotNullByDefault
	public interface Access {
		Scope openScope();
		RaycastSelection normalize(BlockHitResult hit);
		EntityFrameResolution entityFrame(Entity entity, EntityRaycastWork work);
	}
	@NotNullByDefault
	public static final class Unavailable extends RuntimeException {
		public Unavailable(Throwable cause) { super("picking frame capability unavailable", cause); }
	}
	@FunctionalInterface
	@NotNullByDefault
	public interface Scope extends AutoCloseable { @Override void close(); }

	private final Function<ClipContext, BlockHitResult> clipper;
	private final Access access;
	private boolean closed;

	PickingFrameContext(Function<ClipContext, BlockHitResult> clipper, Access access) {
		this.clipper = Objects.requireNonNull(clipper); this.access = Objects.requireNonNull(access);
	}
	public static PickingFrameContext open(ClientLevel level) {
		if (ModContext.HasSable) {
			try { return new PickingFrameContext(level::clip, new SablePickingAccess(level)); }
			catch (RuntimeException | LinkageError unavailable) {
				return new PickingFrameContext(level::clip, fallback(true));
			}
		}
		return new PickingFrameContext(level::clip, fallback(false));
	}

	private static Access fallback(boolean unknownBlockFrame) {
		return new Access() {
			@Override public Scope openScope() { return () -> {}; }
			@Override public RaycastSelection normalize(BlockHitResult hit) {
				return new RaycastSelection(hit, Optional.empty(),
					unknownBlockFrame && hit.getType() == HitResult.Type.BLOCK
						? Optional.empty() : Optional.of(hit.getLocation()), Optional.empty());
			}
			@Override public EntityFrameResolution entityFrame(Entity entity, EntityRaycastWork work) {
				return unknownBlockFrame && nx.pingwheel.common.integration.aeronautics.AeroRotorScope.matches(entity)
					? EntityFrameResolution.unavailable() : SableEntityFrames.logical(entity, work);
			}
		};
	}
	public RaycastSelection clip(ClipContext context) {
		if (closed) throw new IllegalStateException("picking frame is closed");
		BlockHitResult hit;
		try (Scope scope = access.openScope()) { hit = clipper.apply(context); }
		catch (Unavailable unavailable) {
			// The failing supplier's scope was popped before the unscoped native retry.
			hit = clipper.apply(context);
		}
		return access.normalize(hit);
	}
	public EntityFrameResolution entityFrame(Entity entity, EntityRaycastWork work) {
		if (closed) throw new IllegalStateException("picking frame is closed");
		return access.entityFrame(entity, work);
	}
	@Override public void close() { closed = true; }
}
