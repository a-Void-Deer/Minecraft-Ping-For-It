package nx.pingwheel.common.name;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MinecraftServerEntityLookup;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * The Minecraft 1.21.1 server adapter of {@link AuthoritativeTargetNameResolver}.
 *
 * <p>Derives the authoritative display name for a <em>normalized</em> target
 * exclusively from live server state; client-supplied names never reach this
 * resolver. For every accepted target:
 * <ul>
 *   <li>the requester must resolve to an online {@link ServerPlayer} and the
 *       normalized target dimension must exactly equal the requester's current
 *       {@code ServerLevel} dimension (there is no cross-dimension name
 *       tracking);</li>
 *   <li>an entity target resolves its live entity by UUID. A
 *       {@link ServerPlayer} target yields the plain, unstyled profile name
 *       (never a ping-type color); an {@link ItemEntity} yields the contained
 *       item stack's custom name composed with the localized item base name,
 *       or just the localized item name; any other entity yields its custom
 *       name composed with the localized entity-type name, or just the
 *       localized entity-type name;</li>
 *   <li>a block target reads the current {@link BlockState} and yields the
 *       localized block name, composed with the custom name when the block
 *       entity implements {@link Nameable} and has one. A block type that no
 *       longer matches the normalized identity is treated as unavailable
 *       (the validator rejects such requests anyway, so this is fail-safe
 *       only);</li>
 *   <li>a location target yields the fixed {@link TargetNameComposer#here()}
 *       name.</li>
 * </ul>
 *
 * <p>Unavailable targets fall back to the
 * {@link TargetNameJsonCodec#UNKNOWN unknown} name payload. The only logging
 * happens on that fallback path and carries the target kind and a safe reason
 * class only — never names, JSON, UUIDs, positions, or registry ids.
 */
public final class MinecraftTargetNameResolver implements AuthoritativeTargetNameResolver {

	/**
	 * Safe fallback classifications; only these names (or an exception class
	 * simple name) may appear in the fallback debug log.
	 */
	private enum FallbackReason {
		REQUESTER_UNAVAILABLE,
		DIMENSION_MISMATCH,
		ENTITY_UNAVAILABLE,
		BLOCK_UNAVAILABLE,
		BLOCK_TYPE_CHANGED
	}

	/**
	 * A resolved component, or the safe reason it could not be derived.
	 */
	private record Resolution(Component component, FallbackReason reason) {

		static Resolution of(Component component) {
			return new Resolution(Objects.requireNonNull(component, "component"), null);
		}

		static Resolution unavailable(FallbackReason reason) {
			return new Resolution(null, Objects.requireNonNull(reason, "reason"));
		}
	}

	private final MinecraftServer server;

	public MinecraftTargetNameResolver(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server");
	}

	@Override
	public TargetNameJson resolveName(UUID requester, Target normalizedTarget) {
		Objects.requireNonNull(requester, "requester");
		Objects.requireNonNull(normalizedTarget, "normalizedTarget");

		try {
			Resolution resolution = resolve(requester, normalizedTarget);

			if (resolution.component() == null) {
				return fallback(normalizedTarget, resolution.reason().name());
			}

			return TargetNameJsonCodec.encode(resolution.component(), server.registryAccess());
		} catch (RuntimeException e) {
			return fallback(normalizedTarget, e.getClass().getSimpleName());
		}
	}

	private Resolution resolve(UUID requester, Target normalizedTarget) {
		ServerPlayer player = server.getPlayerList().getPlayer(requester);

		if (player == null) {
			return Resolution.unavailable(FallbackReason.REQUESTER_UNAVAILABLE);
		}

		ServerLevel level = player.serverLevel();
		String dimensionId = level.dimension().location().toString();

		if (!dimensionId.equals(normalizedTarget.dimensionId())) {
			return Resolution.unavailable(FallbackReason.DIMENSION_MISMATCH);
		}

		return switch (normalizedTarget) {
			case Target.EntityTarget entity -> resolveEntity(level, entity);
			case Target.BlockTarget block -> resolveBlock(level, block);
			// External target naming belongs to the later provider adapter. It is
			// deliberately not presented as a pure location name.
			case Target.ExternalBlockTarget ignored -> Resolution.unavailable(FallbackReason.BLOCK_UNAVAILABLE);
			case Target.LocationTarget ignored -> Resolution.of(TargetNameComposer.here());
		};
	}

	/**
	 * Names a live entity in the requester's own level. Because the lookup
	 * happens in that level and the dimension id was already checked, an
	 * entity that moved to another dimension is simply not found.
	 */
	private Resolution resolveEntity(ServerLevel level, Target.EntityTarget target) {
		MinecraftServerEntityLookup.Result lookup = MinecraftServerEntityLookup.find(level, target.locator());

		if (!lookup.accepted()) {
			return Resolution.unavailable(FallbackReason.ENTITY_UNAVAILABLE);
		}

		Entity entity = lookup.entity();

		if (entity instanceof ServerPlayer player) {
			// Plain profile name, deliberately unstyled: a player target never
			// inherits a ping-type or team color.
			return Resolution.of(Component.literal(player.getGameProfile().getName()));
		}

		if (entity instanceof ItemEntity itemEntity) {
			ItemStack stack = itemEntity.getItem();
			Component customName = stack.get(DataComponents.CUSTOM_NAME);
			Component baseName = Component.translatable(stack.getDescriptionId());

			return Resolution.of(customName != null
				? TargetNameComposer.compose(customName, baseName)
				: baseName);
		}

		Component customName = entity.getCustomName();
		Component baseName = entity.getType().getDescription();

		return Resolution.of(customName != null
			? TargetNameComposer.compose(customName, baseName)
			: baseName);
	}

	/**
	 * Names a block from its current {@link BlockState}. A {@code BlockState}
	 * change that keeps the block type uses the current state's name; a
	 * replaced block type is unavailable (the validator rejects such requests
	 * anyway, so this is fail-safe only).
	 */
	private Resolution resolveBlock(ServerLevel level, Target.BlockTarget target) {
		BlockPos position = new BlockPos(target.x(), target.y(), target.z());

		if (!level.isLoaded(position)) {
			return Resolution.unavailable(FallbackReason.BLOCK_UNAVAILABLE);
		}

		BlockState state = level.getBlockState(position);
		ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

		if (currentId == null || !currentId.toString().equals(target.blockRegistryId())) {
			return Resolution.unavailable(FallbackReason.BLOCK_TYPE_CHANGED);
		}

		Component baseName = state.getBlock().getName();

		if (level.getBlockEntity(position) instanceof Nameable nameable && nameable.hasCustomName()) {
			return Resolution.of(TargetNameComposer.compose(nameable.getCustomName(), baseName));
		}

		return Resolution.of(baseName);
	}

	/**
	 * Emits the single safe debug record for a fallback: target kind and the
	 * reason class only. Never names, JSON, UUIDs, positions, or registry ids.
	 */
	private TargetNameJson fallback(Target target, String reasonClass) {
		LOGGER.debug(() -> "authoritative target name unavailable, using unknown: kind={} reason={}".formatted(
			target.kind(), reasonClass));
		return TargetNameJsonCodec.UNKNOWN;
	}
}
