package nx.pingwheel.common.marker;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.outline.BlockOutlineSelection;
import nx.pingwheel.common.client.outline.BlockPresentationResolverRegistry;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class DoubleChestCreationTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static PresentationS2CPacket wire(PresentationS2CPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try { packet.write(buf); var result = PresentationS2CPacket.readSafe(buf); assertFalse(result.isCorrupt()); return result; }
		finally { buf.release(); }
	}
	private static void receive(ClientMarkerStore client, ServerMarker marker) {
		var section = new PresentationSection("minecraft:basic", 1, Map.of("minecraft:target.name", new PresentationValue.Text("{\"text\":\"Chest\"}")), false);
		var packet = wire(PresentationS2CPacket.created(1, 1, 1, MarkerSnapshot.from(marker), "Owner",
			PresentationPropertyRef.root("minecraft:basic", "minecraft:target.name"), section));
		client.onCreated(packet.snapshot(), marker.arrivalTick());
	}
	@Test void actualProductionFactoryFreezesPairAcrossWireWinnersAudienceCancellationExpiryAndSplitRejoin() throws Exception {
		for (var block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) {
			var f = new DoubleChestFixture(block, Direction.NORTH); var store = new ServerMarkerStore(new MarkerIdSource());
			var field = ServerCore.class.getDeclaredField("MARKER_STORE"); field.setAccessible(true); @Nullable Object previous = field.get(null);
			field.set(null, store);
			try {
				var factory = ServerCore.class.getDeclaredMethod("markerService", MinecraftServer.class); factory.setAccessible(true);
				var service = (MarkerCreationService) factory.invoke(null, f.server);
				UUID a = DoubleChestFixture.OWNER, b = DoubleChestFixture.OTHER, outsider = new UUID(3, 3);
				var first = service.create(a, f.target(f.left), "attention", 1, 30, List.of(a, b, outsider)).creation().orElseThrow().marker();
				var second = service.create(b, f.target(f.right), "danger", 2, 20, List.of(a, b)).creation().orElseThrow().marker();
				TargetKey key = first.targetKey(); assertInstanceOf(TargetKey.DoubleChestKey.class, key); assertEquals(key, second.targetKey());
				var pair = (TargetKey.DoubleChestKey) key;
				assertNotEquals(new BlockPos(pair.firstX(), pair.firstY(), pair.firstZ()), f.right, "winning hit deliberately is not the canonical first member");
				assertEquals(f.target(f.right), second.target()); assertEquals(new MarkerAnchor(f.right.getX() + .5, f.right.getY() + .5, f.right.getZ() + .5), second.anchor());
				assertEquals("entity_block", second.targetType().id()); assertEquals(2, store.size());
				assertEquals(second.id(), store.winnerFor(key, a).orElseThrow().id()); assertEquals(first.id(), store.winnerFor(key, outsider).orElseThrow().id());
				var client = new ClientMarkerStore(0); receive(client, first); receive(client, second);
				var winner = wire(PresentationS2CPacket.winner(1, 1, key, Optional.of(second.id()))); client.onWinnerChanged(winner.targetKey(), winner.winnerId());
				@Unmodifiable Map<TargetKey.BlockKey, nx.pingwheel.common.client.outline.BlockOutlineSpec> specs =
					BlockOutlineSelection.select(client.visibleWinnersInDimension("minecraft:overworld"), PingTypeCatalog.builtIn());
				assertEquals(List.of(TargetKey.from(second.target())), List.copyOf(specs.keySet()));
				var spec = specs.values().iterator().next();
				assertEquals(2, new BlockPresentationResolverRegistry().resolve(f.client, spec).renderSubjects().size());
				f.states.put(f.left, f.states.get(f.left).setValue(ChestBlock.TYPE, ChestType.SINGLE));
				assertEquals(1, new BlockPresentationResolverRegistry().resolve(f.client, spec).renderSubjects().size());
				assertEquals(key, store.find(second.id()).orElseThrow().targetKey()); assertEquals(key, client.marker(second.id()).orElseThrow().targetKey());
				var single = service.create(a, f.target(f.left), "attention", 3, 40, List.of(a)).creation().orElseThrow().marker();
				assertInstanceOf(TargetKey.BlockKey.class, single.targetKey());
				f.states.put(f.left, f.states.get(f.left).setValue(ChestBlock.TYPE, ChestType.LEFT));
				assertEquals(key, new MinecraftTargetGroupingResolver(f.server).resolve(a, f.target(f.left)));
				assertEquals(2, new BlockPresentationResolverRegistry().resolve(f.client, spec).renderSubjects().size());
				client.expireFallback(20); assertEquals(first.id(), client.winnerId(key).orElseThrow(), "missing removal falls back across opposite hits");
				assertEquals(MarkerRemovalResult.Status.NOT_OWNER, store.removeOwned(a, second.id()).status());
				var removal = store.removeOwned(b, second.id()); assertEquals(first.id(), store.winnerFor(key, a).orElseThrow().id());
				for (var change : removal.winnerChanges()) { var p = wire(PresentationS2CPacket.winner(1, 1, change.targetKey(), change.currentWinner())); client.onWinnerChanged(p.targetKey(), p.winnerId()); }
				assertEquals(first.id(), client.winnerId(key).orElseThrow()); assertEquals(2, store.size());
				store.expire(30); assertTrue(store.find(first.id()).isEmpty()); assertTrue(store.find(single.id()).isPresent());
				f.unavailable.add(f.right);
				var unavailablePair = service.create(a, f.target(f.left), "attention", 31, 50, List.of(a)).creation().orElseThrow().marker();
				assertEquals(TargetKey.from(f.target(f.left)), unavailablePair.targetKey(), "missing sibling cannot reject an otherwise valid ordinary ping");
			} finally { field.set(null, previous); }
		}
	}
	@Test void unavailableGroupingIsExactAndExternalOrUnrelatedTargetsNeverLookUpOrdinaryWorld() {
		var f = new DoubleChestFixture(Blocks.CHEST, Direction.NORTH); var resolver = new MinecraftTargetGroupingResolver(f.server);
		var external = Target.ExternalBlockTarget.committed("minecraft:overworld", "sable:blocks", "stable", "minecraft:chest", "locator", true);
		assertEquals(TargetKey.from(external), resolver.resolve(DoubleChestFixture.OWNER, external));
		var door = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:oak_door");
		assertEquals(TargetKey.from(door), resolver.resolve(DoubleChestFixture.OWNER, door)); assertEquals(0, f.chunkLookups);
		var target = f.target(f.left); assertEquals(TargetKey.from(target), resolver.resolve(new UUID(9, 9), target));
		assertEquals(0, f.chunkLookups);
		f.unavailable.add(f.right); assertEquals(TargetKey.from(target), resolver.resolve(DoubleChestFixture.OWNER, target));
		assertFalse(f.reads.contains(f.right)); assertEquals(0, f.blockEntities);
		f.unavailable.clear(); f.states.clear();
		assertEquals(TargetKey.from(target), resolver.resolve(DoubleChestFixture.OWNER, target));
	}
	@Test void nativeGroupingRejectsWrongDimensionAndUnsupportedPlatformDefaultsToExact() {
		var f = new DoubleChestFixture(Blocks.CHEST, Direction.NORTH); var resolver = new MinecraftTargetGroupingResolver(f.server);
		var wrong = new Target.BlockTarget("minecraft:the_nether", f.left.getX(), f.left.getY(), f.left.getZ(), "minecraft:chest");
		assertEquals(TargetKey.from(wrong), resolver.resolve(DoubleChestFixture.OWNER, wrong)); assertEquals(0, f.chunkLookups);
		var vault = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "create:item_vault");
		var platform = new nx.pingwheel.common.config.TestPlatformContextService();
		assertEquals(TargetKey.from(vault), platform.groupVaultTarget(f.world, vault));
	}
}
