package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public record EntityFrameResolution(Status status, Optional<EntityParentFrame> frame, Optional<UUID> subLevelId) {
	@NotNullByDefault
	public enum Status { WORLD, CONTAINED, UNAVAILABLE }

	public EntityFrameResolution {
		Objects.requireNonNull(status); Objects.requireNonNull(frame); Objects.requireNonNull(subLevelId);
		if (status == Status.UNAVAILABLE ? frame.isPresent() || subLevelId.isPresent()
			: frame.isEmpty() || (status == Status.CONTAINED) != subLevelId.isPresent()) {
			throw new IllegalArgumentException("frame and identity must match resolution status");
		}
		if (status == Status.WORLD) frame = Optional.of(EntityParentFrame.identity());
	}

	public static EntityFrameResolution world() {
		return new EntityFrameResolution(Status.WORLD, Optional.of(EntityParentFrame.identity()), Optional.empty());
	}
	public static EntityFrameResolution contained(EntityParentFrame frame, UUID id) {
		return new EntityFrameResolution(Status.CONTAINED, Optional.of(frame), Optional.of(id));
	}
	public static EntityFrameResolution unavailable() {
		return new EntityFrameResolution(Status.UNAVAILABLE, Optional.empty(), Optional.empty());
	}
}
