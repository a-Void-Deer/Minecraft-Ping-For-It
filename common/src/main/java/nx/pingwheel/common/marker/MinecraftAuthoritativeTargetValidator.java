package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;

/**
 * The Minecraft 1.21.1 server adapter of {@link AuthoritativeTargetValidator}.
 *
 * <p>Every request is validated against live server state:
 * <ul>
 *   <li>the requester UUID must resolve to an online {@link ServerPlayer};</li>
 *   <li>the requested dimension id must exactly equal the requester's current
 *       {@code ServerLevel#dimension().location()} string; there is no
 *       cross-dimension tracking;</li>
 *   <li>an entity target must exist, be alive, and not be removed in the
 *       requester's level; its entity type id is derived from
 *       {@code BuiltInRegistries.ENTITY_TYPE} and its anchor is the server-side
 *       entity position. When the target is a player and player tracking is
 *       disabled, the target is normalized to a {@link Target.LocationTarget}
 *       at the authoritative current position with
 *       {@link TargetMatchContext#none()}; otherwise the entity target is
 *       preserved with its entity type context;</li>
 *   <li>a block target requires the chunk to be loaded and the current block
 *       registry id to exactly equal the captured id; {@code BlockState}-only
 *       changes remain valid and the anchor is the block center;</li>
 *   <li>a location target is already finite and anchors exactly.</li>
 * </ul>
 *
 * <p>For every accepted target the requester's eye-to-anchor distance must not
 * exceed the configured server max range, otherwise the verdict is
 * {@link MarkerRejectReason#OUT_OF_RANGE}. Gone/dead/replaced targets and
 * dimension mismatches produce {@link MarkerRejectReason#TARGET_GONE}; a
 * malformed captured registry key produces
 * {@link MarkerRejectReason#INVALID_REQUEST}. Client-supplied names, colors,
 * ping types, and target classification are never trusted or even visible to
 * this validator: the normalized identity and the match context are derived
 * exclusively from server state.
 *
 * <p>Only server-safe common imports are used; this class never references
 * client-only or loader-specific types and never mutates any store state.
 */
public final class MinecraftAuthoritativeTargetValidator implements AuthoritativeTargetValidator {

	private final MinecraftServer server;
	private final int maxRange;
	private final boolean playerTrackingEnabled;

	public MinecraftAuthoritativeTargetValidator(MinecraftServer server, int maxRange, boolean playerTrackingEnabled) {
		this.server = Objects.requireNonNull(server, "server");

		if (maxRange < 0) {
			throw new IllegalArgumentException("maxRange must be non-negative: " + maxRange);
		}

		this.maxRange = maxRange;
		this.playerTrackingEnabled = playerTrackingEnabled;
	}

	@Override
	public AuthoritativeTargetValidation validate(UUID requester, Target requestedTarget) {
		Objects.requireNonNull(requester, "requester");
		Objects.requireNonNull(requestedTarget, "requestedTarget");

		ServerPlayer requesterPlayer = server.getPlayerList().getPlayer(requester);

		if (requesterPlayer == null) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.INVALID_REQUEST);
		}

		ServerLevel level = requesterPlayer.serverLevel();
		String dimensionId = level.dimension().location().toString();

		if (!dimensionId.equals(requestedTarget.dimensionId())) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE);
		}

		return switch (requestedTarget) {
			case Target.EntityTarget entity -> validateEntity(requesterPlayer, level, dimensionId, entity);
			case Target.BlockTarget block -> validateBlock(requesterPlayer, level, dimensionId, block);
			case Target.LocationTarget location -> validateLocation(requesterPlayer, dimensionId, location);
		};
	}

	/**
	 * Validates an entity target in the requester's own level. Because the
	 * lookup happens in the requester's {@link ServerLevel} and the dimension
	 * id was already checked, an entity that moved to another dimension is
	 * simply not found and is reported as {@link MarkerRejectReason#TARGET_GONE}.
	 */
	private AuthoritativeTargetValidation validateEntity(
		ServerPlayer requester, ServerLevel level, String dimensionId, Target.EntityTarget requested
	) {
		Entity entity = level.getEntity(requested.entityId());

		if (entity == null || !entity.isAlive() || entity.isRemoved()) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE);
		}

		Vec3 position = entity.position();

		if (entity instanceof Player && !playerTrackingEnabled) {
			// Never trust the client's classification: with player tracking
			// disabled a player target is authoritatively normalized to a pure
			// location at the server-side current position.
			return validateLocation(requester, dimensionId,
				new Target.LocationTarget(dimensionId, position.x, position.y, position.z));
		}

		ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());

		if (typeKey == null) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.INVALID_REQUEST);
		}

		MarkerAnchor anchor = new MarkerAnchor(position.x, position.y, position.z);

		if (outOfRange(requester, anchor)) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.OUT_OF_RANGE);
		}

		return AuthoritativeTargetValidation.accepted(new ValidatedMarkerTarget(
			new Target.EntityTarget(dimensionId, entity.getUUID()),
			TargetMatchContext.entityType(typeKey.toString()),
			anchor));
	}

	/**
	 * Validates a block target: the chunk must be loaded, and the block at the
	 * captured position must still have the captured block registry id. A
	 * {@code BlockState} change on the same block type stays valid because only
	 * the registry id participates in identity.
	 */
	private AuthoritativeTargetValidation validateBlock(
		ServerPlayer requester, ServerLevel level, String dimensionId, Target.BlockTarget requested
	) {
		ResourceLocation capturedId = ResourceLocation.tryParse(requested.blockRegistryId());

		if (capturedId == null) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.INVALID_REQUEST);
		}

		BlockPos position = new BlockPos(requested.x(), requested.y(), requested.z());

		if (!level.isLoaded(position)) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE);
		}

		BlockState state = level.getBlockState(position);
		ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

		if (currentId == null || !currentId.equals(capturedId)) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE);
		}

		MarkerAnchor anchor = new MarkerAnchor(
			position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);

		if (outOfRange(requester, anchor)) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.OUT_OF_RANGE);
		}

		return AuthoritativeTargetValidation.accepted(new ValidatedMarkerTarget(
			new Target.BlockTarget(dimensionId, requested.x(), requested.y(), requested.z(), currentId.toString()),
			TargetMatchContext.none(),
			anchor));
	}

	/**
	 * Validates a pure location target: the captured coordinates are already
	 * guaranteed finite by {@link Target.LocationTarget}, and the anchor is the
	 * exact location.
	 */
	private AuthoritativeTargetValidation validateLocation(
		ServerPlayer requester, String dimensionId, Target.LocationTarget requested
	) {
		MarkerAnchor anchor = new MarkerAnchor(requested.x(), requested.y(), requested.z());

		if (outOfRange(requester, anchor)) {
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.OUT_OF_RANGE);
		}

		return AuthoritativeTargetValidation.accepted(new ValidatedMarkerTarget(
			new Target.LocationTarget(dimensionId, requested.x(), requested.y(), requested.z()),
			TargetMatchContext.none(),
			anchor));
	}

	/**
	 * Whether the requester's eye-to-anchor squared distance exceeds the
	 * configured server max range (squared).
	 */
	private boolean outOfRange(ServerPlayer requester, MarkerAnchor anchor) {
		Vec3 eye = requester.getEyePosition();
		double dx = eye.x - anchor.x();
		double dy = eye.y - anchor.y();
		double dz = eye.z - anchor.z();
		double distanceSquared = dx * dx + dy * dy + dz * dz;

		return distanceSquared > (double) maxRange * maxRange;
	}
}
