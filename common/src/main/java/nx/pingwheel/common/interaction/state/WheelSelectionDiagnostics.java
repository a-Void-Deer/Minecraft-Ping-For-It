package nx.pingwheel.common.interaction.state;

/**
 * Client-supplied, presentation-only diagnostics for one committed wheel
 * selection (M6.5, requested by the log monitor).
 *
 * <p>The state machine appends them to the INFO {@code wheel select} line so a
 * single normal launch can prove that the sector under the cursor was resolved
 * with the expected GUI scale and cursor position - the usual source of
 * HiDPI/off-by-scale mistakes. They are read once per committed selection and
 * never per frame, carry no identity, position in the world, or name, and are
 * allowed to be absent ({@code null} from the supplier) when no client is
 * available.
 *
 * @param guiScale the current GUI scale factor ({@code Window#getGuiScale()})
 * @param mouseX   the cursor position in GUI-scaled coordinates, produced by the
 *                 same {@code MouseHandler#getScaledXPos(Window)} helper the wheel
 *                 hit test uses ({@code WheelHudRenderer#selectionFromMouse}) -
 *                 these are literally the coordinates that decided the sector
 * @param mouseY   the cursor position in GUI-scaled coordinates, same source as
 *                 {@code mouseX}
 */
public record WheelSelectionDiagnostics(int guiScale, double mouseX, double mouseY) {}
