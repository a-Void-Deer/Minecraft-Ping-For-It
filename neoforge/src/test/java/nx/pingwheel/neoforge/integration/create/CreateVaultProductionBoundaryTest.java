package nx.pingwheel.neoforge.integration.create;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.outline.*;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes production lazy gates, reflection bridge, platform service, registry and creation factory. */
@NotNullByDefault
class CreateVaultProductionBoundaryTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static TargetKey group(VaultNativeFixture f, Target.BlockTarget target) throws Exception {
		ClassLoader previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(f.loader);
		try {
			var platform = f.loader.loadClass("nx.pingwheel.neoforge.platform.PlatformContextServiceImpl").getConstructor().newInstance();
			return (TargetKey) platform.getClass().getMethod("groupVaultTarget", net.minecraft.server.level.ServerLevel.class, Target.BlockTarget.class).invoke(platform, f.serverWorld, target);
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	private static BlockPresentationResolver resolver(VaultNativeFixture f) throws Exception {
		return (BlockPresentationResolver) f.loader.loadClass("nx.pingwheel.neoforge.integration.create.NeoForgeCreateVaultBlockPresentationResolver").getConstructor().newInstance();
	}
	private static BlockOutlineSpec spec(VaultNativeFixture f, BlockPos hit) {
		return new BlockOutlineSpec(new MarkerId(1), (TargetKey.BlockKey) TargetKey.from(f.target(hit)), "entity_block", "attention", -1);
	}
	@Test void testedArchiveActuallyDeclaresAllFixedMetadataMethodsAndControllerDimensionsAreLongitudinal() throws Exception {
		var vault = archive(VAULT);
		for (var signature : Map.of("getController", "()Lnet/minecraft/core/BlockPos;", "isController", "()Z", "getWidth", "()I",
			"getHeight", "()I", "getLastKnownPos", "()Lnet/minecraft/core/BlockPos;", "getMainConnectionAxis", "()Lnet/minecraft/core/Direction$Axis;").entrySet()) {
			var method = vault.methods.stream().filter(m -> m.name.equals(signature.getKey()) && m.desc.equals(signature.getValue())).findFirst().orElseThrow();
			assertEquals(Opcodes.ACC_PUBLIC, method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
		}
		var loader = new VaultOptionalHarness(); var type = loader.loadClass(VAULT);
		var entity = loader.member(new BlockPos(-4, 64, 9), Blocks.STONE.defaultBlockState(), null, 3, 9);
		assertEquals(9, type.getMethod("getHeight").invoke(entity)); assertEquals(3, type.getMethod("getWidth").invoke(entity));
		assertEquals(entity.getBlockPos(), type.getMethod("getController").invoke(entity));
		assertNull(type.getMethod("getLastKnownPos").invoke(entity), "null early-sync last position is permitted");
		assertThrows(NoSuchMethodException.class, () -> new CreateVaultTopology.Api(BlockEntity.class, Blocks.STONE.getClass(), BlockEntity.class));
	}
	@Test void everyOrdinaryHitUsesProductionPlatformAndExactArchiveGettersWithOnlyControllerExtents() throws Exception {
		try (var mods = new Mods("6.0.10-281")) {
			for (Axis axis : List.of(Axis.X, Axis.Z)) for (int width = 1; width <= 3; width++)
				try (var f = new VaultNativeFixture(axis, width, width * 3, new BlockPos(-17, 63, 15))) {
					var expected = new TargetKey.VaultKey("minecraft:overworld", -17, 63, 15, "create:item_vault");
					for (BlockPos hit : f.states.keySet()) assertEquals(expected, group(f, f.target(hit)));
					for (var entry : f.entities.entrySet()) if ((entry.getKey().getX() & 1) == 0) field(entry.getValue().getClass(), "lastKnownPos").set(entry.getValue(), entry.getKey());
					assertEquals(expected, group(f, f.target(f.root)), "mixed null and matching last-known positions remain valid");
					var wrong = new Target.BlockTarget("minecraft:the_nether", -17, 63, 15, "create:item_vault");
					assertEquals(TargetKey.from(wrong), group(f, wrong));
					var unrelated = new Target.BlockTarget("minecraft:overworld", -17, 63, 15, "minecraft:chest");
					assertEquals(TargetKey.from(unrelated), group(f, unrelated));
				}
		}
	}
	@Test void actualArchiveClientPacketBranchesSynchronizeControllerExtentsButNeverReadOrWriteInventory() throws Exception {
		var loader = new VaultOptionalHarness(); var root = new BlockPos(-4, 64, 9); var hit = root.offset(4, 2, 2);
		for (boolean controller : new boolean[] {true, false}) {
			var source = loader.member(controller ? root : hit, Blocks.STONE.defaultBlockState(), controller ? null : root, 3, 5);
			var type = source.getClass();
			var inventory = field(type, "inventory"); inventory.set(source, new net.neoforged.neoforge.items.ItemStackHandler(1) {
				@Override public net.minecraft.nbt.CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider registries) { throw new AssertionError("client packet serialized inventory"); }
			});
			var write = type.getDeclaredMethod("write", net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class, boolean.class); write.setAccessible(true);
			var read = type.getDeclaredMethod("read", net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class, boolean.class); read.setAccessible(true);
			var tag = new net.minecraft.nbt.CompoundTag(); write.invoke(source, tag, net.minecraft.core.RegistryAccess.EMPTY, true);
			assertFalse(tag.contains("Inventory")); assertEquals(!controller, tag.contains("Controller"));
			assertEquals(controller, tag.contains("Size")); assertEquals(controller, tag.contains("Length"));
			var received = loader.member(source.getBlockPos(), source.getBlockState(), null, 1, 1);
			inventory.set(received, new net.neoforged.neoforge.items.ItemStackHandler(1) {
				@Override public void deserializeNBT(net.minecraft.core.HolderLookup.Provider registries, net.minecraft.nbt.CompoundTag value) { throw new AssertionError("client packet deserialized inventory"); }
			});
			read.invoke(received, tag, net.minecraft.core.RegistryAccess.EMPTY, true);
			assertEquals(root, type.getMethod("getController").invoke(received)); assertNull(type.getMethod("getLastKnownPos").invoke(received));
			assertEquals(controller ? 3 : 1, type.getMethod("getWidth").invoke(received)); assertEquals(controller ? 5 : 1, type.getMethod("getHeight").invoke(received));
			// Actual archive serialize/deserialize calls remain in the method and would trip these guards.
		}
	}
	@Test void unsupportedOrAbsentCreateNeverLoadsAnOptionalClassAndDoesNotRegisterResolver() throws Exception {
		for (@Nullable String version : new String[] {null, "6.0.11", "0"}) try (var mods = new Mods(version)) {
			var loader = new VaultOptionalHarness(); var level = allocate(VaultNativeFixture.ServerWorld.class);
			var target = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "create:item_vault");
			ClassLoader previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
			try {
				var platform = loader.loadClass("nx.pingwheel.neoforge.platform.PlatformContextServiceImpl").getConstructor().newInstance();
				assertEquals(TargetKey.from(target), platform.getClass().getMethod("groupVaultTarget", net.minecraft.server.level.ServerLevel.class, Target.BlockTarget.class).invoke(platform, level, target));
			} finally { Thread.currentThread().setContextClassLoader(previous); }
			var resolver = loader.loadClass("nx.pingwheel.neoforge.integration.create.NeoForgeCreateVaultBlockPresentationResolver"); resolver.getMethod("register").invoke(null);
			assertEquals("not-registered", resolver.getMethod("registrationState").invoke(null)); assertEquals(0, loader.optionalLoads);
		}
	}
	@Test void productionMemberIdentityAndLiveWorldFailuresNeverAuthorizeAGroup() throws Exception {
		try (var mods = new Mods("6.0.10")) {
			for (String failure : List.of("removed", "wrong-world", "wrong-pos", "moved", "virtual", "wrong-type", "wrong-block", "missing-root", "missing-member", "controller-loop", "unloaded"))
				try (var f = new VaultNativeFixture(Axis.X, 2, 3, BlockPos.ZERO)) {
					BlockPos hit = new BlockPos(2, 1, 1); var entity = f.entities.get(hit);
					switch (failure) {
						case "removed" -> field(BlockEntity.class, "remove").setBoolean(entity, true);
						case "wrong-world" -> entity.setLevel(f.client);
						case "wrong-pos" -> field(BlockEntity.class, "worldPosition").set(entity, hit.above());
						case "moved" -> field(entity.getClass(), "lastKnownPos").set(entity, hit.above());
						case "virtual" -> field(entity.getClass().getSuperclass(), "virtualMode").setBoolean(entity, true);
						case "wrong-type" -> field(BlockEntity.class, "type").set(entity, net.minecraft.world.level.block.entity.BlockEntityType.CHEST);
						case "wrong-block" -> f.states.put(hit, Blocks.STONE.defaultBlockState());
						case "missing-root" -> f.entities.remove(BlockPos.ZERO);
						case "missing-member" -> f.entities.remove(new BlockPos(1, 1, 1));
						case "controller-loop" -> field(entity.getClass(), "controller").set(f.entities.get(f.root), hit);
						case "unloaded" -> f.unloaded.add(0L);
						default -> fail(failure);
					}
					assertEquals(TargetKey.from(f.target(hit)), group(f, f.target(hit)), failure);
					if (failure.equals("unloaded")) { assertEquals(0, f.stateReads); assertEquals(0, f.mapReads); }
				}
		}
	}
	@Test void clientProductionCacheAndExistingMapProduceIdenticalOrderedSubjectsAndRecoverPerFrame() throws Exception {
		try (var mods = new Mods("6.0.10"); var f = new VaultNativeFixture(Axis.Z, 3, 9, new BlockPos(15, -5, -17))) {
			f.clientEntities(); var registry = new BlockPresentationResolverRegistry(false); registry.register(resolver(f));
			var first = registry.resolve(f.client, spec(f, f.root));
			assertEquals(81, first.renderSubjects().size()); assertTrue(first.coverageRelations().isEmpty());
			for (BlockPos hit : f.states.keySet()) {
				var source = spec(f, hit); var actual = registry.resolve(f.client, source);
				assertSame(source, actual.sourceSpec()); assertEquals(first.renderSubjects(), actual.renderSubjects());
				assertTrue(actual.renderSubjects().stream().allMatch(s -> s.renderTargetTypeId().equals("entity_block") && s.relation() == BlockPresentationRelation.COMPOSITE));
				assertEquals(81, actual.renderSubjects().stream().map(BlockRenderSubject::subjectId).distinct().count());
			}
			var end = f.root.offset(2, 2, 8); var old = f.entities.remove(end);
			var direct = registry.resolve(f.client, spec(f, f.root)); assertEquals(1, direct.renderSubjects().size()); assertEquals(BlockPresentationRelation.DIRECT, direct.renderSubjects().getFirst().relation());
			f.entities.put(end, old); assertEquals(first.renderSubjects(), registry.resolve(f.client, spec(f, end)).renderSubjects());
			f.unloaded.add(net.minecraft.world.level.ChunkPos.asLong(f.root.getX() >> 4, f.root.getZ() >> 4)); f.stateReads = f.mapReads = 0;
			assertTrue(registry.resolve(f.client, spec(f, f.root)).isEmpty()); assertEquals(0, f.stateReads); assertEquals(0, f.mapReads);
			f.unloaded.clear(); assertEquals(81, registry.resolve(f.client, spec(f, f.root)).renderSubjects().size());
		}
	}
	@Test void driftedOptionalApiFallsBackThroughTheRealBridgeAndCommonRegistry() throws Exception {
		try (var mods = new Mods("6.0.10"); var f = new VaultNativeFixture(Axis.X, 2, 3, BlockPos.ZERO)) {
			// The real class is already loaded for fixture members; drift the metadata holder's
			// reflection shape with a fresh loader, not a fake topology algorithm.
			var loader = new VaultOptionalHarness(); loader.driftedApi = true;
			ClassLoader previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
			try {
				var platform = loader.loadClass("nx.pingwheel.neoforge.platform.PlatformContextServiceImpl").getConstructor().newInstance();
				assertEquals(TargetKey.from(f.target(f.root)), platform.getClass().getMethod("groupVaultTarget", net.minecraft.server.level.ServerLevel.class, Target.BlockTarget.class).invoke(platform, f.serverWorld, f.target(f.root)));
			} finally { Thread.currentThread().setContextClassLoader(previous); }
			f.clientEntities(); var registry = new BlockPresentationResolverRegistry(false);
			registry.register((BlockPresentationResolver) loader.loadClass("nx.pingwheel.neoforge.integration.create.NeoForgeCreateVaultBlockPresentationResolver").getConstructor().newInstance());
			assertEquals(BlockPresentationRelation.DIRECT, registry.resolve(f.client, spec(f, f.root)).renderSubjects().getFirst().relation());
		}
	}
	@Test void neoClientProductionRegistrationActivatesVaultOnlyForTestedCreateAndKeepsIndependentState() throws Exception {
		try (var mods = new Mods("6.0.10"); var f = new VaultNativeFixture(Axis.X, 2, 3, BlockPos.ZERO)) {
			Class.forName("nx.pingwheel.common.Global");
			var client = f.loader.loadClass("nx.pingwheel.neoforge.NeoClient");
			var load = client.getDeclaredMethod("loadCreateAdapters"); load.setAccessible(true); load.invoke(null);
			var resolver = f.loader.loadClass("nx.pingwheel.neoforge.integration.create.NeoForgeCreateVaultBlockPresentationResolver");
			try {
				assertEquals("registered", resolver.getMethod("registrationState").invoke(null));
				assertTrue(String.valueOf(field(client, "lastCreateVaultResolverState").get(null)).contains("registrationState=registered"));
				f.clientEntities(); assertEquals(12, BlockPresentationResolverRegistry.INSTANCE.resolve(f.client, spec(f, f.root)).renderSubjects().size());
				try (var unsupported = new Mods("6.0.11")) { load.invoke(null); assertEquals("unsupported-version", field(client, "lastCreateVaultResolverState").get(null)); }
				try (var absent = new Mods(null)) { load.invoke(null); assertEquals("not-detected", field(client, "lastCreateVaultResolverState").get(null)); }
			} finally {
				((BlockPresentationResolverRegistry.Registration) field(resolver, "registration").get(null)).close();
				field(resolver, "registration").set(null, null);
			}
		}
	}
	@Test void normalAndLateFallbackKeepOneVaultBatchAndOneRenderedSubjectNeverCoversItsSiblings() throws Exception {
		try (var mods = new Mods("6.0.10"); var f = new VaultNativeFixture(Axis.X, 2, 3, BlockPos.ZERO)) {
			f.clientEntities(); var registry = BlockPresentationResolverRegistry.INSTANCE;
			try (var registration = registry.register(resolver(f))) {
				var store = new ClientMarkerStore(0); var key = new TargetKey.VaultKey("minecraft:overworld", 0, 0, 0, "create:item_vault");
				var hit = new BlockPos(2, 1, 1); var target = f.target(hit);
				store.onCreated(new MarkerSnapshot(new MarkerId(1), VaultNativeFixture.OWNER, target, key, "entity_block", "attention", new MarkerAnchor(2.5, 1.5, 1.5), 1, 30), 0);
				store.onWinnerChanged(key, Optional.of(new MarkerId(1)));
				var outline = BlockOutlineState.INSTANCE; var models = BlockModelOutlineState.INSTANCE;
				try {
					outline.prepare(store, "minecraft:overworld"); models.beginFrame(); models.preparePresentations(f.client, outline);
					var batch = models.presentations(); assertEquals(12, batch.getFirst().renderSubjects().size()); assertTrue(batch.getFirst().coverageRelations().isEmpty());
					var presentation = batch.getFirst(); var controller = presentation.renderSubjects().getFirst();
					models.addSuccess(controller.successKey(presentation.sourceSpec()));
					assertFalse(models.allPresentationsCovered()); assertEquals(1, models.successKeys().size());
					for (var sibling : presentation.renderSubjects().subList(1, 12)) assertFalse(models.successKeys().contains(sibling.successKey(presentation.sourceSpec())));
					f.entities.remove(BlockPos.ZERO); models.preparePresentations(f.client, outline); assertSame(batch, models.presentations());
					models.beginFrame(); models.preparePresentations(f.client, outline); assertEquals(1, models.presentations().getFirst().renderSubjects().size());
				} finally { models.clear(); outline.clear(); }
			}
		}
	}
	@Test void compiledTopologyHasNoInventoryCapabilityNbtOrLoadingLookupRoute() throws Exception {
		for (String name : List.of("CreateVaultTopology", "CreateVaultTopology$MinecraftWorld", "CreateVaultTopology$Api", "CreateVaultTopology$ApiHolder")) {
			try (var input = getClass().getResourceAsStream(name + ".class")) {
				assertNotNull(input); var node = new ClassNode(); new ClassReader(input).accept(node, 0);
				for (var method : node.methods) for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
					assertFalse(List.of("getBlockEntity", "getInventoryOfBlock", "getSlots", "getStackInSlot", "getCapability", "saveWithFullMetadata", "saveWithoutMetadata", "getUpdateTag").contains(call.name), call.toString());
					assertFalse(call.owner.startsWith("net/minecraft/nbt/") || call.owner.startsWith("net/neoforged/neoforge/items/"), call.owner);
					assertFalse(call.name.equals("getChunk"), "server topology uses getChunkNow only; client FULL,false lookup is in its client-only resolver");
				}
			}
		}
	}
	private static PresentationS2CPacket wire(PresentationS2CPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try { packet.write(buf); var result = PresentationS2CPacket.readSafe(buf); assertFalse(result.isCorrupt()); return result; }
		finally { buf.release(); }
	}
	private static void receive(ClientMarkerStore client, ServerMarker marker) {
		var section = new PresentationSection("minecraft:basic", 1, Map.of("minecraft:target.name", new PresentationValue.Text("{\"text\":\"Vault\"}")), false);
		var packet = wire(PresentationS2CPacket.created(1, 1, 1, MarkerSnapshot.from(marker), "Owner", PresentationPropertyRef.root("minecraft:basic", "minecraft:target.name"), section));
		client.onCreated(packet.snapshot(), marker.arrivalTick());
	}
	@Test void actualCreationFactoryPlatformBridgeAndWireFreezeControllerWithoutChangingHitAnchorAudienceOrLifetime() throws Exception {
		try (var mods = new Mods("6.0.10"); var f = new VaultNativeFixture(Axis.X, 3, 5, new BlockPos(15, 64, 15))) {
			Class.forName("nx.pingwheel.common.Global"); // parent logging identity, outside the isolated platform service loader
			ClassLoader previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(f.loader);
			try {
				var core = f.loader.loadClass("nx.pingwheel.common.core.ServerCore"); var store = new ServerMarkerStore(new MarkerIdSource()); field(core, "MARKER_STORE").set(null, store);
				field(core, "SERVER_CONFIG").set(null, f.loader.loadClass("nx.pingwheel.common.config.ServerConfig").getConstructor().newInstance());
				var factory = core.getDeclaredMethod("markerService", MinecraftServer.class); factory.setAccessible(true);
				var service = (MarkerCreationService) factory.invoke(null, f.server);
				var end = f.root.offset(4, 2, 2); var outsider = new java.util.UUID(3, 3);
				var first = service.create(VaultNativeFixture.OWNER, f.target(f.root), "attention", 1, 30, List.of(VaultNativeFixture.OWNER, VaultNativeFixture.OTHER, outsider)).creation().orElseThrow().marker();
				var second = service.create(VaultNativeFixture.OTHER, f.target(end), "danger", 2, 20, List.of(VaultNativeFixture.OWNER, VaultNativeFixture.OTHER)).creation().orElseThrow().marker();
				assertInstanceOf(TargetKey.VaultKey.class, first.targetKey()); assertEquals(first.targetKey(), second.targetKey());
				assertEquals(f.target(end), second.target()); assertEquals(new MarkerAnchor(end.getX() + .5, end.getY() + .5, end.getZ() + .5), second.anchor());
				assertEquals(second.id(), store.winnerFor(first.targetKey(), VaultNativeFixture.OWNER).orElseThrow().id()); assertEquals(first.id(), store.winnerFor(first.targetKey(), outsider).orElseThrow().id());
				var client = new ClientMarkerStore(0); receive(client, first); receive(client, second);
				var winner = wire(PresentationS2CPacket.winner(1, 1, first.targetKey(), Optional.of(second.id()))); client.onWinnerChanged(winner.targetKey(), winner.winnerId());
				assertEquals(second.id(), client.winnerId(first.targetKey()).orElseThrow()); client.expireFallback(20); assertEquals(first.id(), client.winnerId(first.targetKey()).orElseThrow());
				f.entities.remove(f.root.offset(1, 1, 1));
				var fallback = service.create(VaultNativeFixture.OWNER, f.target(end), "attention", 3, 40, List.of(VaultNativeFixture.OWNER)).creation().orElseThrow().marker();
				assertEquals(TargetKey.from(f.target(end)), fallback.targetKey()); assertEquals(first.targetKey(), store.find(second.id()).orElseThrow().targetKey());
				assertEquals(MarkerRemovalResult.Status.NOT_OWNER, store.removeOwned(VaultNativeFixture.OWNER, second.id()).status()); store.removeOwned(VaultNativeFixture.OTHER, second.id());
				assertEquals(first.id(), store.winnerFor(first.targetKey(), VaultNativeFixture.OWNER).orElseThrow().id()); store.expire(30); assertTrue(store.find(first.id()).isEmpty()); assertTrue(store.find(fallback.id()).isPresent());
			} finally { Thread.currentThread().setContextClassLoader(previous); }
		}
	}
}
