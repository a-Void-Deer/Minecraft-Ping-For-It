package nx.pingwheel.common.client.outline;

/**
 * Main-thread-confined per-frame bookkeeping for the entity-outline pass.
 *
 * <p>{@link #beginFrame()} starts a fresh frame: it advances the monotonic
 * {@link #frameId()} and clears the {@code emitted} and
 * {@code requestSucceeded} flags. The CommonClient bridge marks
 * {@link #markRequestSucceeded(boolean)} once per frame after the cached
 * reflection {@code requestOutlineEffect()} probe, and the runner marks
 * {@link #markEmitted()} whenever a source reported
 * {@link EntityBlockGeometryOutcome#RENDERED}. {@link #clear()} is used when
 * leaving a server so stale flags never leak into a later connection.</p>
 *
 * <p>Thread safety: main-thread render pass only, exactly like the outline
 * state singletons it complements.</p>
 */
public final class EntityOutlineFrameState {

	public static final EntityOutlineFrameState INSTANCE = new EntityOutlineFrameState();

	private long frameId;
	private boolean emitted;
	private boolean requestSucceeded;

	private EntityOutlineFrameState() {}

	/**
	 * Starts a fresh frame: advances the frame id and clears both flags.
	 * Called once per world render frame before the entity-outline pass runs.
	 */
	public void beginFrame() {
		frameId++;
		emitted = false;
		requestSucceeded = false;
	}

	/** Monotonic render-frame identity for sources that need a hard aggregate budget. */
	public long frameId() {
		return frameId;
	}

	/** Records that an entity-outline source emitted geometry this frame. */
	public void markEmitted() {
		emitted = true;
	}

	/** Records whether the {@code requestOutlineEffect()} probe succeeded this frame. */
	public void markRequestSucceeded(boolean succeeded) {
		requestSucceeded = succeeded;
	}

	/** Whether an entity-outline source emitted geometry this frame. */
	public boolean emitted() {
		return emitted;
	}

	/** Whether the {@code requestOutlineEffect()} probe succeeded this frame. */
	public boolean requestSucceeded() {
		return requestSucceeded;
	}

	/** Drops the per-frame record (used when leaving a server); the next frame starts fresh. */
	public void clear() {
		beginFrame();
	}
}
