package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.marker.MinecraftAuthoritativeTargetValidator;
import nx.pingwheel.common.name.MinecraftTargetNameResolver;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.PresentationPropertyPingTypes;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.minecraft.PresentationServer;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

/** Minecraft-facing inventory ingress. All world handles stay on the active server thread. */
public final class InventoryServer {

	private static MinecraftServer active;
	private static InventoryBackend backend;
	private static long epochSeed = 1;

	private InventoryServer() {}

	public static void activate(MinecraftServer server) {
		if (active == server) return;
		reset();
		active = server;
		epochSeed = Math.addExact(epochSeed, 2048);
		var runtime = new InventoryRuntime(input -> InventoryMinecraftSources.resolve(server, input), ServerConfig.HANDLER.getConfig().getInventory().pendingMemoryBytes());
		backend = new InventoryBackend(new Host(server, runtime), runtime, epochSeed);
	}

	public static void reset() {
		if (backend != null) backend.close();
		backend = null;
		active = null;
	}

	public static void handle(MinecraftServer server, ServerPlayer player, InventoryC2SPacket packet) {
		if (!server.isSameThread() || player.serverLevel().getServer() != server) return;
		activate(server);
		backend.handle(player.getUUID(), packet, server.getTickCount(), ServerConfig.HANDLER.getConfig().getInventory());
	}

	public static void tick(MinecraftServer server) {
		if (!server.isSameThread()) return;
		activate(server);
		backend.tick(server.getTickCount(), ServerConfig.HANDLER.getConfig().getInventory());
	}

	public static void disconnect(UUID player) {
		if (backend != null) backend.disconnect(player);
	}
	public static void remove(MarkerId marker) { if (backend != null) backend.remove(marker); }

	private record Host(MinecraftServer server, InventoryRuntime runtime) implements InventoryBackend.Host {
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) {
			return PresentationServer.inventoryPolicy(player).map(p -> new InventoryBackend.Policy(p.epoch(), p.view(), p.types()));
		}
		@Override public boolean authorized(SyncPublisher.Context context) { return policy(context.recipient()).map(p -> (p.epoch() + "/" + p.view()).equals(context.sessionView())).orElse(false); }
		@Override public boolean knows(UUID player, MarkerId marker) { return PresentationServer.inventoryKnows(player, marker); }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			return ServerCore.createInventory(server, server.getPlayerList().getPlayer(player), frozen, admission);
		}
		/**
		 * Annotation policy runs inside the runtime's provider-work preflight.
		 * It queries the actual provider-confirmed physical block's live tags,
		 * never a client-supplied expected registry or a placeholder position.
		 */
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) {
			ServerLevel level = levelFor(input.target().dimensionId());
			if (level == null) return false;
			var resolved = input.target() instanceof Target.ExternalBlockTarget external
				? (external.isCandidate() ? MinecraftBlockReadSources.resolvePreviewReadSource(level, external)
					: MinecraftBlockReadSources.resolveCommittedReadSource(level, external))
				: input.ordinaryTarget().flatMap(block -> MinecraftBlockReadSources.resolveOrdinary(level, block));
			if (resolved.isEmpty() || resolved.orElseThrow().level() != level) return false;
			var physical = resolved.orElseThrow().descriptor().blockTarget();
			BlockPos pos = new BlockPos(physical.x(), physical.y(), physical.z());
			if (!level.isLoaded(pos)) return false;
			var state = level.getBlockState(pos);
			if (state == null || state.isAir()) return false;
			String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			return id.equals(physical.blockRegistryId()) && PresentationPropertyPingTypes.builtIn().allows(pingType, id,
				BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock()).tags().map(tag -> tag.location().toString()).collect(java.util.stream.Collectors.toSet()));
		}

		/**
		 * Preview acceptance keeps the existing authenticated-session, field,
		 * range, nonallocating provider-validation and classification gates, and
		 * now also accepts a normalized external provider candidate. Nothing is
		 * materialized and no inventory is read here.
		 */
		@Override
		public Optional<InventoryBackend.Opened> open(UUID playerId, Target requested) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			// Entity/private inventory and committed external contexts require their separate owning contracts.
			boolean openable = requested instanceof Target.BlockTarget
				|| requested instanceof Target.ExternalBlockTarget external && external.isCandidate();
			if (player == null || !openable) return Optional.empty();
			ServerConfig config = ServerConfig.HANDLER.getConfig();
			var validation = new MinecraftAuthoritativeTargetValidator(server, config.getPingDistance(),
				config.isPlayerTrackingEnabled(), new MinecraftTargetNameResolver(server), ExternalBlockServerProviders.registry())
				.validate(playerId, requested);
			if (!validation.isAccepted()) return Optional.empty();
			var validated = validation.validatedTarget().orElseThrow();
			// The validator may correct a candidate's classification, but a
			// provider, dimension, expected registry or opaque locator drift is
			// rejected before any resolver or source access. Ordinary targets
			// keep their existing normalization behavior.
			if (requested instanceof Target.ExternalBlockTarget external && external.isCandidate()
				&& !InventoryBackend.sameCandidateNormalization(requested, validated.normalizedTarget()))
				return Optional.empty();
			var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.global())
				.resolve(validated.normalizedTarget(), validated.matchContext());
			int level = 0;
			for (int candidate = 1; candidate <= 4; candidate++) if (player.hasPermissions(candidate)) level = candidate;
			if (!InventoryPresentation.allowed(config.getPresentation(), playerId, level, resolved.targetType().id())
				.contains(InventoryPresentation.ITEMS)) return Optional.empty();
			return Optional.of(new InventoryBackend.Opened(resolved.target(),
				resolved.targetType().id(), resolved.targetType().defaultPingType().id()));
		}

		/**
		 * Binds a preview input to the materialized committed target through both
		 * current provider-confirmed physical read bindings. Ordinary identity is
		 * exact. An external candidate must resolve to the same current provider,
		 * sub-level, physical root block and registry as the committed target;
		 * neither the target equality nor the opaque locator alone is trusted, so
		 * same-registry candidates and different hit roots cannot cross-bind.
		 * Runs inside the runtime's provider-work preflight, never uncharged.
		 */
		@Override
		public Optional<InventorySourceInput> bindCommitted(InventorySourceInput previewInput, Target committed) {
			Target requested = previewInput == null ? null : previewInput.target();
			if (requested == null || committed == null) return Optional.empty();
			if (!(committed instanceof Target.ExternalBlockTarget committedExternal) || !committedExternal.isCommitted()) {
				return previewInput.ordinaryTarget().filter(block -> block.equals(committed))
					.map(block -> new InventorySourceInput(block, previewInput.readOwner(), previewInput.face()));
			}
			if (!(requested instanceof Target.ExternalBlockTarget candidate) || !candidate.isCandidate()
				|| !candidate.dimensionId().equals(committedExternal.dimensionId())
				|| !candidate.providerId().equals(committedExternal.providerId())
				|| !candidate.expectedBlockRegistryId().equals(committedExternal.expectedBlockRegistryId()))
				return Optional.empty();
			return runtime.preflight(() -> {
				ServerLevel level = levelFor(candidate.dimensionId());
				if (level == null) return Optional.empty();
				var preview = MinecraftBlockReadSources.resolvePreviewReadSource(level, candidate);
				var current = MinecraftBlockReadSources.resolveCommittedReadSource(level, committedExternal);
				if (preview.isEmpty() || current.isEmpty()) return Optional.empty();
				var previewBinding = preview.orElseThrow().descriptor();
				var committedBinding = current.orElseThrow().descriptor();
				if (!InventoryBackend.correspondingPhysicalBinding(previewBinding, committedBinding)) return Optional.empty();
				return Optional.of(new InventorySourceInput(committedExternal, previewInput.readOwner(), previewInput.face()));
			});
		}

		private ServerLevel levelFor(String dimensionId) {
			ResourceLocation dimension = ResourceLocation.tryParse(dimensionId);
			return dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		}

		@Override
		public int encodedBytes(InventoryS2CPacket packet) {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256, InventoryS2CPacket.MAX_FRAME_BYTES));
			try { packet.write(buffer); return buffer.readableBytes(); }
			finally { buffer.release(); }
		}

		@Override
		public void send(UUID playerId, InventoryS2CPacket packet) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("inventory recipient disconnected");
			IPlatformNetworkService.INSTANCE.sendToClient(packet, player);
		}
	}
}
