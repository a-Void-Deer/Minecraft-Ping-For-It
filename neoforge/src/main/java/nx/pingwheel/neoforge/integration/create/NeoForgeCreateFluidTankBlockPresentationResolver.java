package nx.pingwheel.neoforge.integration.create;

import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import nx.pingwheel.common.client.outline.BlockPresentationContext;
import nx.pingwheel.common.client.outline.BlockPresentationRelation;
import nx.pingwheel.common.client.outline.BlockPresentationResolution;
import nx.pingwheel.common.client.outline.BlockPresentationResolver;
import nx.pingwheel.common.client.outline.BlockPresentationResolverRegistry;
import nx.pingwheel.common.client.outline.BlockRenderSubject;
import nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAvailability;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Controller fluid/boiler BER supplements, but never covers, the tank's per-cell baked models. */
@NotNullByDefault
public final class NeoForgeCreateFluidTankBlockPresentationResolver implements BlockPresentationResolver {

	public static final String ID = "pingforit:create_fluid_tank_presentation";
	private static @Nullable BlockPresentationResolverRegistry.Registration registration;
	private final Reader reader;
	@FunctionalInterface
	@NotNullByDefault
	interface Reader { Optional<CreateFluidTankTopology.Topology> read(BlockPresentationContext context); }

	public NeoForgeCreateFluidTankBlockPresentationResolver() { this(NeoForgeCreateFluidTankBlockPresentationResolver::readClient); }
	NeoForgeCreateFluidTankBlockPresentationResolver(Reader reader) { this.reader = reader; }

	public static synchronized void register() {
		if (registration == null && CreatePresentationAvailability.available())
			registration = BlockPresentationResolverRegistry.INSTANCE.register(new NeoForgeCreateFluidTankBlockPresentationResolver());
	}
	public static synchronized String registrationState() {
		return registration == null ? "not-registered" : registration.accepted() ? "registered" : "rejected";
	}
	@Override public String id() { return ID; }
	@Override public BlockPresentationResolution resolve(BlockPresentationContext context) {
		if (!CreateFluidTankTopology.TANK_ID.equals(context.sourceSpec().blockKey().blockRegistryId())) return BlockPresentationResolution.UNHANDLED;
		var topology = reader.read(context);
		if (topology.isEmpty()) return context.directResolution();
		var verified = topology.get();
		var subjects = new ArrayList<BlockRenderSubject>(verified.cells().size());
		for (var cell : verified.cells()) {
			var offset = cell.position().subtract(verified.controller());
			subjects.add(new BlockRenderSubject("tank/" + offset.getX() + "/" + offset.getY() + "/" + offset.getZ(),
				cell.position(), cell.state(), CreateFluidTankTopology.TANK_ID, context.sourceSpec().targetTypeId(), BlockPresentationRelation.COMPOSITE));
		}
		return BlockPresentationResolution.handled(subjects);
	}
	private static Optional<CreateFluidTankTopology.Topology> readClient(BlockPresentationContext context) {
		if (!(context.world() instanceof ClientLevel level)) return Optional.empty();
		return CreateFluidTankTopology.resolve(level, (x, z) -> level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false), context.sourcePos());
	}
}
