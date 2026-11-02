package nx.pingwheel.common.client.outline;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Exercises the package-local production dispatcher without exposing it as a public mod API. */
@NotNullByDefault
public final class TankPresentationTestPort {

	private TankPresentationTestPort() {}
	@FunctionalInterface
	@NotNullByDefault
	public interface Renderer {
		boolean render(BlockRenderSubject subject, @Nullable BiConsumer<String, EntityBlockGeometryOutcome> observer);
	}
	public static void dispatch(BlockPresentation presentation, Consumer<BlockRenderSubject> success, Renderer renderer) {
		BlockPresentationSubjectDispatcher.dispatch(presentation, subject -> true, success, renderer::render);
	}
}
