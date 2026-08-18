package nx.pingwheel.common.client.marker;

import java.util.Objects;
import java.util.Optional;

import lombok.Getter;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.math.MathUtils;
import nx.pingwheel.common.math.ScreenPos;
import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.render.WorldRenderContext;
import org.jetbrains.annotations.Nullable;

import static nx.pingwheel.common.CommonClient.Game;

/**
 * The per-frame, render-facing view of one active {@link ClientMarker}.
 *
 * <p>Modeled on the legacy {@code PingView}, but backed by the immutable
 * authoritative {@link ClientMarker}: the backing payload is replaced in place
 * when the same {@link nx.pingwheel.common.domain.MarkerId} is re-applied with
 * a new snapshot, so the view instance survives across frames while the marker
 * data stays current. There is no client-side expiry logic here — marker
 * removal and fallback expiry are owned by the {@link ClientMarkerStore}, and
 * {@link MarkerOverlayState} drops the view when the marker disappears.
 *
 * <p>Every world render update:
 * <ul>
 *   <li>starts entity targets at the authoritative {@link MarkerAnchor} and
 *       remembers the latest live interpolated point;</li>
 *   <li>resolves the owner's {@link PlayerInfo} from the current connection;</li>
 *   <li>for an entity target, resolves the live entity in the current
 *       dimension via {@link GameContext#getEntity} and follows its current
 *       position; a live {@link ItemEntity} copies its item stack while the
 *       item icon config is enabled;</li>
 *   <li>keeps the latest live point when the entity is absent, unloaded, or
 *       removed, using the anchor only until the entity first resolves live;</li>
 *   <li>recomputes the screen position, distance, and scale render fields
 *       with the same world-to-screen, distance, and scale formulas the
 *       legacy ping view used.</li>
 * </ul>
 *
 * <p>The displayed target name is the decoded authoritative name kept in step
 * with the {@link nx.pingwheel.common.name.ClientTargetNameStore} by
 * {@link MarkerOverlayState} (see {@link #replaceTargetName}); until a name
 * has been applied it is the unknown fallback. The name is a plain component
 * and never carries a ping-type or team color.
 *
 * <p>The dimension identity is the stable string resource id of the marker's
 * target ({@code minecraft:overworld} and friends), not a numeric hash.
 *
 * <p>Colors:
 * <ul>
 *   <li>{@link #getPingColor()} is the opaque (full alpha) outline color of
 *       the {@link nx.pingwheel.common.domain.PingType} resolved from the
 *       built-in catalog, falling back to white for an unknown ping type
 *       id;</li>
 *   <li>{@link #getTeamColor()} remains the owner's team color for player
 *       head/team presentation only.</li>
 * </ul>
 *
 * <p>Class initialization stays free of Minecraft/config statics so pure sync
 * tests never touch game state; the client config is only read when
 * {@link #update} actually runs inside the game.
 */
public final class MarkerView {

	private static final int WHITE = 0xFFFFFFFF;
	private static final PingTypeCatalog BUILT_IN_PING_TYPES = PingTypeCatalog.builtIn();

	private ClientMarker marker;

	@Getter
	private @Nullable PlayerInfo playerInfo;
	@Getter
	private @Nullable ItemStack itemStack;
	@Getter
	private @Nullable ScreenPos screenPos;
	@Getter
	private double distance;
	@Getter
	private float scale;

	private Vec3 pos;
	private boolean hasPresentationPosition;
	private final EntityMarkerPositionTracker entityPositionTracker = new EntityMarkerPositionTracker();

	/**
	 * The displayed target name, decoded from the authoritative name store by
	 * {@link MarkerOverlayState}; the unknown fallback until then.
	 */
	private Component targetName = TargetNameComposer.unknown();

	MarkerView(ClientMarker marker) {
		this.marker = Objects.requireNonNull(marker, "marker");
		this.pos = anchorPosition(marker.anchor());
	}

	/**
	 * Returns the last position presented by a completed render update. An
	 * unrendered view deliberately has no position here: its constructor anchor
	 * is only the cancellation fallback until the view has been presented.
	 */
	Optional<Vec3> presentationPosition() {
		return this.hasPresentationPosition ? Optional.of(this.pos) : Optional.empty();
	}

	/**
	 * Checks the payload identity and dimension expected by a caller using the
	 * cached presentation position.
	 */
	boolean matchesTarget(Target expectedTarget, String expectedDimension) {
		Objects.requireNonNull(expectedTarget, "expectedTarget");
		Objects.requireNonNull(expectedDimension, "expectedDimension");

		return this.marker.target().equals(expectedTarget)
			&& this.marker.target().dimensionId().equals(expectedDimension);
	}

	/**
	 * Replaces the backing payload when the same marker id is re-applied with
	 * a newer snapshot, and drops the stale owner/item presentation state.
	 *
	 * <p>The displayed target name is deliberately preserved: it stays
	 * independently authoritative through the
	 * {@link nx.pingwheel.common.name.ClientTargetNameStore} and the
	 * {@link MarkerOverlayState} name sync, so replacing a payload whose name
	 * JSON is unchanged keeps the decoded name instead of flashing the unknown
	 * fallback for a frame.
	 */
	void replacePayload(ClientMarker marker) {
		Objects.requireNonNull(marker, "marker");

		if (!sameEntityIdentity(this.marker.target(), marker.target())) {
			this.entityPositionTracker.reset();
		}

		this.marker = marker;
		this.playerInfo = null;
		this.itemStack = null;
		this.pos = anchorPosition(marker.anchor());
		this.hasPresentationPosition = false;
	}

	/**
	 * Replaces the displayed target name without rebuilding the view or its
	 * payload; the same view instance survives across frames.
	 */
	void replaceTargetName(Component targetName) {
		this.targetName = Objects.requireNonNull(targetName, "targetName");
	}

	/**
	 * Recomputes the render state for this frame from the backing marker and
	 * the live world.
	 *
	 * <p>The item stack and player info are reset at the start of every update:
	 * a stale item icon or head from a previous frame (or a replaced payload)
	 * is never carried over unless the current live entity/connection supplies
	 * it again.
	 */
	void update(WorldRenderContext ctx) {
		final var config = ClientConfig.HANDLER.getConfig();

		final var anchor = anchorPosition(this.marker.anchor());
		this.pos = anchor;
		this.itemStack = null;

		final var connection = Game.getConnection();
		this.playerInfo = connection != null ? connection.getPlayerInfo(this.marker.owner()) : null;

		final var target = this.marker.target();

		if (target instanceof Target.EntityTarget entityTarget) {
			final var entity = GameContext.getEntity(entityTarget.locator());
			Vec3 livePosition = null;

			if (entity != null && !entity.isRemoved()) {
				if (entity.getType() == EntityType.ITEM && config.isItemIconVisible()) {
					this.itemStack = ((ItemEntity)entity).getItem().copy();
				}

				livePosition = EntityMarkerPoint.forLiveEntity(entity, ctx.tickDelta);
			}

			this.pos = toVec3(this.entityPositionTracker.resolve(
				toPosition(anchor),
				livePosition == null ? null : toPosition(livePosition)));
		} else {
			this.entityPositionTracker.reset();
		}

		this.screenPos = MathUtils.worldToScreen(this.pos, ctx.modelViewMatrix, ctx.projectionMatrix);
		this.distance = ctx.camera.getPosition().distanceTo(this.pos);
		this.calculateScale(config);
		this.hasPresentationPosition = true;
	}

	/**
	 * The stable string resource id of the marker target's dimension.
	 */
	public String getDimension() {
		return this.marker.target().dimensionId();
	}

	/**
	 * The displayed target name: the decoded authoritative name applied by the
	 * overlay state, or the unknown fallback. Plain text, never a ping-type or
	 * team color.
	 */
	public Component getTargetName() {
		return this.targetName;
	}

	/**
	 * The opaque outline color of the marker's ping type, resolved through the
	 * built-in catalog; white when the ping type id is unknown.
	 */
	public int getPingColor() {
		return BUILT_IN_PING_TYPES.findById(this.marker.pingTypeId())
			.map(pingType -> 0xFF000000 | pingType.outlineColor())
			.orElse(WHITE);
	}

	/**
	 * The owner's team color for player head/team presentation; white when the
	 * owner, team, or team color is unavailable.
	 */
	public int getTeamColor() {
		if (this.playerInfo == null || this.playerInfo.getTeam() == null) {
			return WHITE;
		}

		final var teamColor = this.playerInfo.getTeam().getColor().getColor();

		if (teamColor == null) {
			return WHITE;
		}

		return (255 << 24) | teamColor;
	}

	private void calculateScale(ClientConfig config) {
		final var scale = 2.0 / Math.pow(this.distance, 0.3);
		final var pingSize = config.getPingSize() / 100f;

		this.scale = (float)Math.max(1.0, scale) * 0.5f * pingSize;
	}

	private static Vec3 anchorPosition(MarkerAnchor anchor) {
		return new Vec3(anchor.x(), anchor.y(), anchor.z());
	}

	private static boolean sameEntityIdentity(Target first, Target second) {
		return first instanceof Target.EntityTarget firstEntity
			&& second instanceof Target.EntityTarget secondEntity
			&& firstEntity.equals(secondEntity);
	}

	private static EntityMarkerPositionTracker.Position toPosition(Vec3 position) {
		return new EntityMarkerPositionTracker.Position(position.x, position.y, position.z);
	}

	private static Vec3 toVec3(EntityMarkerPositionTracker.Position position) {
		return new Vec3(position.x(), position.y(), position.z());
	}
}
