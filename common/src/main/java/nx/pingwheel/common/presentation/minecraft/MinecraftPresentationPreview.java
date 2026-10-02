package nx.pingwheel.common.presentation.minecraft;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.marker.AuthoritativeTargetValidation;
import nx.pingwheel.common.marker.MinecraftAuthoritativeTargetValidator;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import nx.pingwheel.common.presentation.preview.PresentationPreviewServer;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

/** Active-server thread bridge; no marker, materialization, inventory runtime or second sampler. */
final class MinecraftPresentationPreview implements PresentationPreviewServer.Host {
	private final MinecraftServer server;
	MinecraftPresentationPreview(MinecraftServer server) { this.server = server; }
	@Override public Optional<PresentationPreviewAccess> access(UUID player, String type) {
		return server.isSameThread() ? PresentationServer.previewAccess(player, type) : Optional.empty();
	}
	@Override public int intervalTicks(UUID player, String id) {
		PresentationAdapter adapter = PresentationServer.previewAdapter(id);
		return adapter == null ? Integer.MAX_VALUE : PresentationServer.previewSettings().interval(id, adapter.minUpdateIntervalTicks());
	}
	@Override public int scanBudget() { return PresentationServer.previewSettings().scanBudget(); }
	@Override public PresentationPreviewServer.Capture capture(UUID player, PresentationPreviewC2SPacket request,
		PresentationPreviewAccess authorized, PresentationAdapter.CaptureBudget budget) {
		return capture(request, authorized, PresentationServer.previewAdapter(request.adapterId()), budget,
			() -> {
				ServerConfig config = ServerConfig.HANDLER.getConfig();
				// Validation needs a name component but must never read a name that was not demanded.
				// This placeholder is used only in the verdict, never captured or published.
				return new MinecraftAuthoritativeTargetValidator(server, config.getPingDistance(), config.isPlayerTrackingEnabled(),
					(owner, target) -> TargetNameJsonCodec.UNKNOWN, ExternalBlockServerProviders.registry()).validate(player, request.target());
			}, (target, demand) -> PresentationServer.basic(server, player, target, demand));
	}
	/** Production admission/capture seam: ports expose reads, not marker mutation or cached values. */
	static PresentationPreviewServer.Capture capture(PresentationPreviewC2SPacket request, PresentationPreviewAccess access,
		PresentationAdapter adapter, PresentationAdapter.CaptureBudget budget, Supplier<AuthoritativeTargetValidation> validate,
		BiFunction<Target, Set<String>, PresentationSection> basic) {
		if (request == null || request.isCorrupt() || request.kind() != PresentationPreviewC2SPacket.Kind.READ
			|| access == null || adapter == null || adapter.deliveryMode() != PresentationAdapter.DeliveryMode.SECTION
			|| !adapter.adapterId().equals(request.adapterId()) || access.epoch() != request.epoch() || access.view() != request.view()
			|| !access.targetTypeId().equals(request.targetTypeId()) || !access.fields(request.adapterId()).containsAll(request.fields())
			|| access.adapters().get(request.adapterId()).schema() != adapter.schema()) return outcome(PresentationPreviewS2CPacket.Status.REJECTED);
		if (request.target() instanceof Target.ExternalBlockTarget external && external.isCandidate())
			return outcome(PresentationPreviewS2CPacket.Status.UNAVAILABLE); // No safe pre-commit provider read/materialization contract.
		if (!budget.scan()) return outcome(PresentationPreviewS2CPacket.Status.DEFERRED);
		var verdict = validate.get();
		if (verdict == null || !verdict.isAccepted()) return outcome(PresentationPreviewS2CPacket.Status.REJECTED);
		var validated = verdict.validatedTarget().orElseThrow();
		if (!request.target().equals(validated.normalizedTarget())) return outcome(PresentationPreviewS2CPacket.Status.REJECTED);
		var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()).resolve(validated.normalizedTarget(), validated.matchContext());
		if (!request.targetTypeId().equals(resolved.targetType().id())) return outcome(PresentationPreviewS2CPacket.Status.REJECTED);
		Set<String> demand = Set.copyOf(request.fields()); // Queue already intersected fresh and advertised access.
		PresentationSection section;
		if (adapter.adapterId().equals(PresentationBasic.ID)) {
			if (!budget.scan()) return outcome(PresentationPreviewS2CPacket.Status.DEFERRED);
			section = basic.apply(validated.normalizedTarget(), demand);
		} else {
			if (budget.remaining() == 0) return outcome(PresentationPreviewS2CPacket.Status.DEFERRED);
			section = adapter.collect(PresentationServer.detached(validated.normalizedTarget()), demand, budget);
		}
		section = PresentationServer.sanitize(adapter, section, demand);
		return section == null || section.stale() ? outcome(PresentationPreviewS2CPacket.Status.UNAVAILABLE)
			: new PresentationPreviewServer.Capture(section, PresentationPreviewS2CPacket.Status.RESULT);
	}
	private static PresentationPreviewServer.Capture outcome(PresentationPreviewS2CPacket.Status status) {
		return new PresentationPreviewServer.Capture(null, status);
	}
	@Override public boolean send(UUID playerId, PresentationPreviewS2CPacket packet) {
		if (!server.isSameThread()) return false;
		var player = server.getPlayerList().getPlayer(playerId);
		if (player == null) return false;
		IPlatformNetworkService.INSTANCE.sendToClient(packet, player);
		return true;
	}
}
