package nx.pingwheel.common.presentation.preview;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Static loader/resource evidence only; this does not claim live transport or mixin application. */
class PresentationPreviewWiringTest {
	private static Path root() {
		Path path = Path.of("").toAbsolutePath();
		while (path != null && !Files.isDirectory(path.resolve("common/src"))) path = path.getParent();
		if (path == null) throw new IllegalStateException("repository root unavailable");
		return path;
	}
	private static String source(String path) throws IOException { return Files.readString(root().resolve(path)); }
	@Test void predictionGuardsAreClientOnlyInEveryMainConfigWithoutRemovingInventoryLock() throws IOException {
		for (String module : List.of("common", "forge", "neoforge")) {
			var config = JsonParser.parseString(source(module + "/src/main/resources/pingforit.mixins.json")).getAsJsonObject();
			var client = config.getAsJsonArray("client").asList().stream().map(value -> value.getAsString()).collect(Collectors.toList());
			var common = config.getAsJsonArray("mixins").asList().stream().map(value -> value.getAsString()).collect(Collectors.toSet());
			for (String guard : Set.of("ClientLevelPreviewAccessor", "BlockStatePredictionPreviewAccessor")) {
				assertEquals(1, client.stream().filter(guard::equals).count(), module);
				assertFalse(common.contains(guard), "dedicated servers must not load client prediction classes");
			}
			assertTrue(common.contains("BaseContainerLockAccessor"));
		}
	}
	@Test void realLoaderRegistrationsUsePreviewCodecsAndCommonThreadHandlers() throws IOException {
		String fabric = source("fabric/src/main/java/nx/pingwheel/fabric/FabricMain.java");
		assertTrue(fabric.contains("PayloadTypeRegistry.playC2S().register(PresentationPreviewC2SPacket.PACKET_TYPE, PRESENTATION_PREVIEW_C2S_CODEC)"));
		assertTrue(fabric.contains("PayloadTypeRegistry.playS2C().register(PresentationPreviewS2CPacket.PACKET_TYPE, PRESENTATION_PREVIEW_S2C_CODEC)"));
		assertTrue(fabric.contains("server.execute(() -> CommonServer.INSTANCE.onPresentationPreview(server, player, packet))"));
		assertTrue(source("fabric/src/main/java/nx/pingwheel/fabric/FabricClient.java").contains(
			"context.client().execute(() -> CommonClient.INSTANCE.onPresentationPreview(packet))"));
		String forge = source("forge/src/main/java/nx/pingwheel/forge/ForgeMain.java");
		assertTrue(forge.contains("ChannelBuilder.named(PresentationPreviewC2SPacket.PACKET_ID)"));
		assertTrue(forge.contains("ChannelBuilder.named(PresentationPreviewS2CPacket.PACKET_ID)"));
		assertTrue(forge.contains("CHANNEL_MAP.put(PresentationPreviewC2SPacket.PACKET_ID, PRESENTATION_PREVIEW_CHANNEL_C2S)"));
		assertTrue(forge.contains("CHANNEL_MAP.put(PresentationPreviewS2CPacket.PACKET_ID, PRESENTATION_PREVIEW_CHANNEL_S2C)"));
		assertTrue(forge.contains("registerPacketHandler(PRESENTATION_PREVIEW_CHANNEL_C2S, PresentationPreviewC2SPacket::readSafe, CommonServer.INSTANCE::onPresentationPreview)"));
		assertTrue(source("forge/src/main/java/nx/pingwheel/forge/ForgeClient.java").contains(
			"registerPacketHandler(PRESENTATION_PREVIEW_CHANNEL_S2C, PresentationPreviewS2CPacket::readSafe, CommonClient.INSTANCE::onPresentationPreview)"));
		String neo = source("neoforge/src/main/java/nx/pingwheel/neoforge/NeoMain.java");
		assertTrue(neo.contains("registrar.playToServer(PresentationPreviewC2SPacket.PACKET_TYPE, PRESENTATION_PREVIEW_C2S_CODEC"));
		assertTrue(neo.contains("registrar.playToClient(PresentationPreviewS2CPacket.PACKET_TYPE, PRESENTATION_PREVIEW_S2C_CODEC"));
		assertTrue(neo.contains("context.enqueueWork(() -> CommonClient.INSTANCE.onPresentationPreview(payload))"));
		assertTrue(neo.contains("context.enqueueWork(() -> CommonServer.INSTANCE.onPresentationPreview(context.player().getServer(), (ServerPlayer) context.player(), payload))"));
		for (String text : List.of(fabric, neo)) {
			assertTrue(text.contains("StreamCodec.ofMember(PresentationPreviewC2SPacket::write, PresentationPreviewC2SPacket::readSafe)"));
			assertTrue(text.contains("StreamCodec.ofMember(PresentationPreviewS2CPacket::write, PresentationPreviewS2CPacket::readSafe)"));
		}
	}
	@Test void stopAndDisconnectDropPreviewQueueAndIngressHasNoMarkerMutation() throws IOException {
		assertTrue(source("fabric/src/main/java/nx/pingwheel/fabric/FabricMain.java").contains("ServerLifecycleEvents.SERVER_STOPPED.register(CommonServer.INSTANCE::onServerStopped)"));
		for (String path : List.of("forge/src/main/java/nx/pingwheel/forge/ForgeMain.java", "neoforge/src/main/java/nx/pingwheel/neoforge/NeoMain.java"))
			assertTrue(source(path).contains("(ServerStoppedEvent event) -> CommonServer.INSTANCE.onServerStopped(event.getServer())"));
		String server = source("common/src/main/java/nx/pingwheel/common/presentation/minecraft/PresentationServer.java");
		assertTrue(server.contains("previews.disconnect(player)")); assertTrue(server.contains("previews.reset()"));
		String core = source("common/src/main/java/nx/pingwheel/common/core/ServerCore.java");
		int start = core.indexOf("public static void onPresentationPreview(");
		assertTrue(start >= 0); String handler = core.substring(start, core.indexOf("\n\t}", start));
		assertTrue(handler.contains("PresentationServer.preview(server, player, packet)"));
		assertFalse(handler.contains("markerStore(")); assertFalse(handler.contains("ensureMarkerStore(")); assertFalse(handler.contains(".create("));
	}
}
