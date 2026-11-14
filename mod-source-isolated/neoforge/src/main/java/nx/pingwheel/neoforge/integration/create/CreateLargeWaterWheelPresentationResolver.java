package nx.pingwheel.neoforge.integration.create;

import java.util.List;

import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelStructuralBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.client.outline.BlockPresentationContext;
import nx.pingwheel.common.client.outline.BlockPresentationRelation;
import nx.pingwheel.common.client.outline.BlockPresentationResolution;
import nx.pingwheel.common.client.outline.BlockPresentationResolver;
import nx.pingwheel.common.client.outline.BlockPresentationResolverRegistry;
import nx.pingwheel.common.client.outline.BlockRenderSubject;

/**
 * Presents Create's invisible large-water-wheel structural blocks through
 * their live terminal master block.
 *
 * <p>The resolver is loaded only across NeoForge's Create reflection boundary.
 * It deliberately follows Create's own structural-block relationship instead
 * of discovering a master through world, block-entity, or Flywheel searches.</p>
 */
public final class CreateLargeWaterWheelPresentationResolver implements BlockPresentationResolver {
	public static final String RESOLVER_ID = "pingforit:create_large_water_wheel_presentation";
	private static final String LARGE_WATER_WHEEL_ID = "create:large_water_wheel";

	private static BlockPresentationResolverRegistry.Registration registration;

	public CreateLargeWaterWheelPresentationResolver() {}

	/** Called reflectively after NeoForge has confirmed that Create is loaded. */
	public static synchronized void register() {
		if (registration != null) {
			return;
		}

		registration = BlockPresentationResolverRegistry.INSTANCE.register(
			new CreateLargeWaterWheelPresentationResolver());
	}

	/**
	 * The common presentation registry is process-lifetime and this resolver has
	 * no world, client, or backend state. Its retained registration therefore
	 * intentionally survives server-session teardown and is reused on the next
	 * session while Create remains loaded.
	 */
	public static synchronized void close() {
		if (registration != null) {
			registration.close();
			registration = null;
		}
	}

	/** Reflection-only lifecycle probe used by loader diagnostics. */
	public static synchronized String registrationState() {
		if (registration == null) {
			return "not-registered";
		}
		return registration.accepted() ? "registered" : "rejected";
	}

	@Override
	public String id() {
		return RESOLVER_ID;
	}

	@Override
	public BlockPresentationResolution resolve(BlockPresentationContext context) {
		BlockState sourceState = context.sourceState();
		if (sourceState == null || !(sourceState.getBlock() instanceof WaterWheelStructuralBlock)) {
			return BlockPresentationResolution.UNHANDLED;
		}

		WaterWheelStructuralBlock sourceBlock = (WaterWheelStructuralBlock) sourceState.getBlock();
		BlockPos sourcePos = context.sourcePos();
		if (!sourceBlock.stillValid(context.world(), sourcePos, sourceState, false)) {
			return BlockPresentationResolution.handled(List.of());
		}

		BlockPos masterPos = WaterWheelStructuralBlock.getMaster(context.world(), sourcePos, sourceState);
		if (masterPos == null) {
			return BlockPresentationResolution.handled(List.of());
		}

		BlockState masterState = context.world().getBlockState(masterPos);
		if (masterState == null || !(masterState.getBlock() instanceof LargeWaterWheelBlock)
			|| !LARGE_WATER_WHEEL_ID.equals(
				String.valueOf(BuiltInRegistries.BLOCK.getKey(masterState.getBlock())))) {
			return BlockPresentationResolution.handled(List.of());
		}

		return BlockPresentationResolution.handled(new BlockRenderSubject(
			"master",
			masterPos,
			masterState,
			LARGE_WATER_WHEEL_ID,
			"entity_block",
			BlockPresentationRelation.PROXY_TO_OWNER));
	}
}
