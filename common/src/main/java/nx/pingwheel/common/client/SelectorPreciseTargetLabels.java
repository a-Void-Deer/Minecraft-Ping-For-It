package nx.pingwheel.common.client;

import java.util.Objects;
import java.util.function.Function;

import net.minecraft.network.chat.Component;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.domain.Target;

/**
 * Detail-line labels for the native selector's Precise target-type entries.
 *
 * <p>Each enabled Precise leaf publishes its fixed type key as the node label;
 * this client-side resolver adds the captured candidate's own display name as
 * a second line. The resolver is bound to the exact immutable
 * {@link SpatialSelectorSession.Snapshot} that is being painted, so the name
 * shown under a leaf always belongs to the candidate version that same
 * snapshot exposes. It never samples the session again, so a newer live
 * publication cannot retarget an older paint, and an exit tail keeps the name
 * it was painted with.</p>
 *
 * <p>Only a choice that actually carries a captured candidate is resolved:
 * a disabled, reserved, navigation or ordinary choice has no entry in the
 * snapshot's {@code preciseFrame} and returns no detail without invoking the
 * supplied naming function. The candidate's real
 * {@link SpatialSelectorSession.CapturedTarget#resolvedTarget()} target is
 * named; a broad slot never relabels the candidate to its slot type. The
 * returned component is copied into detached paint data, so later mutation of
 * a live component cannot change an already painted node.</p>
 */
public final class SelectorPreciseTargetLabels {

	private static final String PRECISE_CHOICE_PREFIX = "precise:";

	private final SpatialSelectorSession.Snapshot snapshot;
	private final Function<Target, Component> names;

	/**
	 * @param snapshot the exact paint snapshot whose candidate names this
	 *                 resolver exposes; it is never re-sampled
	 * @param names    the localized naming function, normally
	 *                 {@code ClientTargetNameResolver::resolve}
	 */
	public SelectorPreciseTargetLabels(SpatialSelectorSession.Snapshot snapshot,
		Function<Target, Component> names) {
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
		this.names = Objects.requireNonNull(names, "names");
	}

	/**
	 * Resolves one Precise leaf's captured candidate name.
	 *
	 * @return a detached copy of the candidate's display component, or
	 *         {@code null} when the choice is not an enabled Precise leaf of
	 *         this snapshot; the naming function is not called in that case
	 */
	public Component detail(SpatialController.ChoiceView choice) {
		if (choice == null || choice.id() == null || !choice.id().startsWith(PRECISE_CHOICE_PREFIX)) return null;
		SpatialSelectorSession.PreciseFrame frame = snapshot.preciseFrame();
		if (frame == null) return null;
		SpatialSelectorSession.CapturedTarget candidate = frame.choices().get(choice.id());
		if (candidate == null) return null;
		Component resolved = names.apply(candidate.resolvedTarget().target());
		return resolved == null ? null : resolved.copy();
	}
}
