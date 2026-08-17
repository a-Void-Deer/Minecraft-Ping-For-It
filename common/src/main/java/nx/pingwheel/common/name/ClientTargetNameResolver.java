package nx.pingwheel.common.name;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;

/**
 * Client-only presentation resolver for the target frozen in a ping
 * interaction.
 *
 * <p>This resolver receives a stable {@link Target} identity and reads the
 * current client world only to compose a local display component.  It never
 * raycasts, changes interaction state, or supplies data to the server.  The
 * composition rules intentionally mirror {@link MinecraftTargetNameResolver}
 * through {@link TargetNameComposer}.
 */
public final class ClientTargetNameResolver {

	/**
	 * Testable seam for live entity/block adapters.  Values returned by the seam
	 * are already composed display components; the resolver only supplies the
	 * location and unknown fallbacks.
	 */
	public interface Lookup {
		Optional<Component> entity(Target.EntityTarget target);

		Optional<Component> block(Target.BlockTarget target);
	}

	private final Lookup lookup;

	/** Creates a resolver backed by the current Minecraft client world. */
	public ClientTargetNameResolver() {
		this(new MinecraftLookup());
	}

	/** Creates a resolver with an injectable live-state lookup seam. */
	public ClientTargetNameResolver(Lookup lookup) {
		this.lookup = Objects.requireNonNull(lookup, "lookup");
	}

	/**
	 * Resolves a frozen target for presentation.  Any unavailable or malformed
	 * live state becomes the localized unknown component without logging target
	 * data.
	 */
	public Component resolve(Target target) {
		return resolve(target, lookup);
	}

	/** Pure dispatch seam used by focused tests and alternate client adapters. */
	public static Component resolve(Target target, Lookup lookup) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(lookup, "lookup");

		try {
			return switch (target) {
				case Target.LocationTarget ignored -> TargetNameComposer.here();
				case Target.EntityTarget entity -> lookup.entity(entity)
					.map(Objects::requireNonNull)
					.orElseGet(TargetNameComposer::unknown);
				case Target.BlockTarget block -> lookup.block(block)
					.map(Objects::requireNonNull)
					.orElseGet(TargetNameComposer::unknown);
			};
		} catch (RuntimeException unavailable) {
			// Rendering must remain safe when a client-side registry/entity is
			// changing during a frame.  Do not include names or target identity in
			// a diagnostic; the presentation fallback needs no log at all.
			return TargetNameComposer.unknown();
		}
	}

	private static final class MinecraftLookup implements Lookup {
		@Override
		public Optional<Component> entity(Target.EntityTarget target) {
			Minecraft game = CommonClient.Game;

			if (game == null || game.level == null || !sameDimension(game.level, target.dimensionId())) {
				return Optional.empty();
			}

			if (!(target.locator() instanceof EntityLocator.UUID uuidLocator)) {
				return Optional.empty();
			}

			Entity entity = GameContext.getEntity(uuidLocator.value());

			if (entity == null || entity.isRemoved() || !entity.isAlive()) {
				return Optional.empty();
			}

			if (entity instanceof Player player) {
				return Optional.of(Component.literal(player.getGameProfile().getName()));
			}

			if (entity instanceof ItemEntity itemEntity) {
				ItemStack stack = itemEntity.getItem();
				Component customName = stack.get(DataComponents.CUSTOM_NAME);
				Component baseName = Component.translatable(stack.getDescriptionId());

				return Optional.of(customName == null
					? baseName
					: TargetNameComposer.compose(customName, baseName));
			}

			Component customName = entity.getCustomName();
			Component baseName = entity.getType().getDescription();

			return Optional.of(customName == null
				? baseName
				: TargetNameComposer.compose(customName, baseName));
		}

		@Override
		public Optional<Component> block(Target.BlockTarget target) {
			Minecraft game = CommonClient.Game;

			if (game == null || game.level == null || !sameDimension(game.level, target.dimensionId())) {
				return Optional.empty();
			}

			Level level = game.level;
			BlockPos position = new BlockPos(target.x(), target.y(), target.z());

			if (!level.isLoaded(position)) {
				return Optional.empty();
			}

			ResourceLocation capturedId = ResourceLocation.tryParse(target.blockRegistryId());

			if (capturedId == null) {
				return Optional.empty();
			}

			BlockState state = level.getBlockState(position);
			ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

			if (currentId == null || !currentId.equals(capturedId)) {
				return Optional.empty();
			}

			Component baseName = state.getBlock().getName();

			if (level.getBlockEntity(position) instanceof Nameable nameable && nameable.hasCustomName()) {
				return Optional.of(TargetNameComposer.compose(nameable.getCustomName(), baseName));
			}

			return Optional.of(baseName);
		}

		private static boolean sameDimension(Level level, String targetDimensionId) {
			return level.dimension().location().toString().equals(targetDimensionId);
		}
	}
}
