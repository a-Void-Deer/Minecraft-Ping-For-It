package nx.pingwheel.common.presentation.client;

import java.util.List;

/** UI-only extension point. Providers never acquire a store, sender or capture callback. */
@FunctionalInterface
public interface PresentationUiProvider {
	/** At most three short HUD lines; implementations may narrow the supplied view. */
	List<String> labels(PresentationView view);
}
