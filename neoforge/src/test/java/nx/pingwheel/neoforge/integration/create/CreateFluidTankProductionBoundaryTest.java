package nx.pingwheel.neoforge.integration.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production platform/creation/wire/client paths with actual archive metadata, not an installed game. */
@NotNullByDefault
class CreateFluidTankProductionBoundaryTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static TargetKey group(VaultOptionalHarness loader, net.minecraft.server.level.ServerLevel level, Target.BlockTarget target) throws Exception {
		ClassLoader previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
		try {
			var platform = loader.loadClass("nx.pingwheel.neoforge.platform.PlatformContextServiceImpl").getConstructor().newInstance();
			return (TargetKey) platform.getClass().getMethod("groupBlockTarget", net.minecraft.server.level.ServerLevel.class, Target.BlockTarget.class).invoke(platform, level, target);
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	private static TargetKey group(TankNativeFixture fixture, BlockPos hit) throws Exception { return group(fixture.loader, fixture.serverWorld, fixture.target(hit)); }
	private static BlockPresentationResolver resolver(VaultOptionalHarness loader) throws Exception {
		return (BlockPresentationResolver) resolverClass(loader).getConstructor().newInstance();
	}
	private static Class<?> resolverClass(VaultOptionalHarness loader) throws ClassNotFoundException {
		return loader.loadClass("nx.pingwheel.neoforge.integration.create.NeoForgeCreateFluidTankBlockPresentationResolver");
	}
	private static BlockOutlineSpec spec(TankNativeFixture fixture, BlockPos hit) {
		return new BlockOutlineSpec(new MarkerId(1), (TargetKey.BlockKey) TargetKey.from(fixture.target(hit)), "entity_block", "attention", -1);
	}

	@Test void archiveMetadataIsReadOnlyAndItsWidthAndVerticalAxisMatchTheProductionPrism() throws Exception {
		var tank = archive(TANK);
		for (var signature : Map.of("getController", "()Lnet/minecraft/core/BlockPos;", "isController", "()Z", "getWidth", "()I",
			"getHeight", "()I", "getLastKnownPos", "()Lnet/minecraft/core/BlockPos;", "getMainConnectionAxis", "()Lnet/minecraft/core/Direction$Axis;").entrySet()) {
			var method = tank.methods.stream().filter(m -> m.name.equals(signature.getKey()) && m.desc.equals(signature.getValue())).findFirst().orElseThrow();
			assertEquals(Opcodes.ACC_PUBLIC, method.access);
			for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call) assertTrue(call.owner.equals(tank.name) && call.name.equals("isController")
					|| call.owner.equals("net/minecraft/core/BlockPos") && List.of("getX", "getY", "getZ").contains(call.name), call.owner + "." + call.name);
				if (instruction instanceof FieldInsnNode field) assertTrue(field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC);
			}
		}
		var loader = new VaultOptionalHarness(); var type = loader.loadClass(TANK);
		var entity = loader.tankMember(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null, 3, 40);
		assertEquals(3, type.getMethod("getWidth").invoke(entity)); assertEquals(40, type.getMethod("getHeight").invoke(entity));
		assertEquals(net.minecraft.core.Direction.Axis.Y, type.getMethod("getMainConnectionAxis").invoke(entity));
		assertEquals(BlockPos.ZERO, type.getMethod("getController").invoke(entity)); assertNull(type.getMethod("getLastKnownPos").invoke(entity));
		assertThrows(NoSuchMethodException.class, () -> new CreateFluidTankTopology.Api(BlockEntity.class, Blocks.STONE.getClass(), BlockEntity.class));
	}

	@Test void everyLoadedMemberProducesOneControllerKeyAndIdenticalOrderedEntityBlockSubjects() throws Exception {
		try (var mods = new Mods("6.0.10-281")) {
			for (int width = 1; width <= 3; width++) try (var fixture = new TankNativeFixture(width, 5, new BlockPos(15, -5, -17))) {
				var expected = new TargetKey.CreateTankKey("minecraft:overworld", 15, -5, -17, "create:fluid_tank");
				for (BlockPos hit : fixture.states.keySet()) assertEquals(expected, group(fixture, hit));
				for (var entry : fixture.entities.entrySet()) if ((entry.getKey().getX() & 1) == 0) field(entry.getValue().getClass(), "lastKnownPos").set(entry.getValue(), entry.getKey());
				assertEquals(expected, group(fixture, fixture.root), "null or matching last known positions are valid");
				fixture.clientEntities(); var registry = new BlockPresentationResolverRegistry(false); registry.register(resolver(fixture.loader));
				var first = registry.resolve(fixture.client, spec(fixture, fixture.root));
				assertEquals(width * width * 5, first.renderSubjects().size()); assertTrue(first.coverageRelations().isEmpty());
				for (BlockPos hit : fixture.states.keySet()) {
					var source = spec(fixture, hit); var presentation = registry.resolve(fixture.client, source);
					assertSame(source, presentation.sourceSpec()); assertEquals(first.renderSubjects(), presentation.renderSubjects());
					assertEquals(hit, new BlockPresentationContext(fixture.client, presentation.sourceSpec()).sourcePos());
					assertTrue(presentation.renderSubjects().stream().allMatch(s -> s.renderTargetTypeId().equals("entity_block")
						&& s.expectedBlockRegistryId().equals("create:fluid_tank") && s.relation() == BlockPresentationRelation.COMPOSITE));
				}
				@Unmodifiable List<BlockPos> positions = first.renderSubjects().stream().map(BlockRenderSubject::blockPos).toList();
				assertEquals(positions.stream().sorted(java.util.Comparator.<BlockPos>comparingInt(p -> p.getX()).thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ())).toList(), positions);
				assertEquals(positions.size(), first.renderSubjects().stream().map(BlockRenderSubject::subjectId).distinct().count());
			}
		}
	}

	@Test void ordinaryBoilerModeRetainsGeometryWithoutCallingAnyContentOrCapabilityPort() throws Exception {
		try (var mods = new Mods("6.0.10"); var fixture = new TankNativeFixture(2, 4, BlockPos.ZERO)) {
			var expected = group(fixture, fixture.root);
			for (var entity : fixture.entities.values()) {
				var boiler = allocate(fixture.loader.loadClass(BOILER)); field(boiler.getClass(), "attachedEngines").setInt(boiler, 1);
				assertEquals(true, boiler.getClass().getMethod("isActive").invoke(boiler)); field(entity.getClass(), "boiler").set(entity, boiler);
			}
			assertEquals(expected, group(fixture, fixture.root.offset(1, 3, 1)));
			fixture.clientEntities(); var registry = new BlockPresentationResolverRegistry(false); registry.register(resolver(fixture.loader));
			assertEquals(16, registry.resolve(fixture.client, spec(fixture, fixture.root)).renderSubjects().size());
			// The fixture's content, handler and getControllerBE methods throw; no native fluid class is initialized.
		}
	}

	@Test void unsupportedCreateAndUnsupportedRegistryStopBeforeOptionalClassesOrWorldQueries() throws Exception {
		for (@Nullable String version : new String[] {null, "6.0.11", "6.0.10-282"}) try (var mods = new Mods(version)) {
			var loader = new VaultOptionalHarness(); var level = allocate(TankNativeFixture.ServerWorld.class);
			var target = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "create:fluid_tank");
			assertEquals(TargetKey.from(target), group(loader, level, target));
			var type = resolverClass(loader); type.getMethod("register").invoke(null);
			assertEquals("not-registered", type.getMethod("registrationState").invoke(null)); assertEquals(0, loader.optionalLoads);
		}
		try (var mods = new Mods("6.0.10")) {
			var loader = new VaultOptionalHarness(); var level = allocate(TankNativeFixture.ServerWorld.class);
			for (String id : List.of("create:creative_fluid_tank", "minecraft:stone", "mod:fluid_tank")) {
				var target = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, id);
				assertEquals(TargetKey.from(target), group(loader, level, target)); assertEquals(0, loader.optionalLoads);
			}
		}
	}

	@Test void wrongDimensionAndEveryFailedMemberIdentityOrControllerGateFallBackToTheExactHit() throws Exception {
		try (var mods = new Mods("6.0.10")) {
			for (String failure : List.of("removed", "wrong-world", "wrong-pos", "moved", "virtual", "wrong-type", "wrong-block", "stale-state",
				"missing-root", "pending-member", "controller-loop", "second-controller", "controller-changed", "width", "height", "unloaded"))
				try (var fixture = new TankNativeFixture(2, 3, new BlockPos(15, 64, 15))) {
					BlockPos hit = fixture.root.offset(1, 2, 1); var entity = fixture.entities.get(hit); var root = fixture.entities.get(fixture.root);
					switch (failure) {
						case "removed" -> field(BlockEntity.class, "remove").setBoolean(entity, true);
						case "wrong-world" -> entity.setLevel(fixture.client);
						case "wrong-pos" -> field(BlockEntity.class, "worldPosition").set(entity, hit.above());
						case "moved" -> field(entity.getClass(), "lastKnownPos").set(entity, hit.above());
						case "virtual" -> field(entity.getClass().getSuperclass(), "virtualMode").setBoolean(entity, true);
						case "wrong-type" -> field(BlockEntity.class, "type").set(entity, net.minecraft.world.level.block.entity.BlockEntityType.CHEST);
						case "wrong-block" -> fixture.states.put(fixture.root, Blocks.STONE.defaultBlockState());
						case "stale-state" -> field(BlockEntity.class, "blockState").set(root, Blocks.STONE.defaultBlockState());
						case "missing-root" -> fixture.entities.remove(fixture.root);
						case "pending-member" -> fixture.entities.remove(fixture.root.above());
						case "controller-loop" -> field(root.getClass(), "controller").set(root, hit);
						case "second-controller" -> field(entity.getClass(), "controller").set(fixture.entities.get(fixture.root.above()), null);
						case "controller-changed" -> field(fixture.entities.get(fixture.root.above()).getClass(), "controller").set(fixture.entities.get(fixture.root.above()), hit);
						case "width" -> field(root.getClass(), "width").setInt(root, 4);
						case "height" -> field(root.getClass(), "height").setInt(root, Integer.MAX_VALUE);
						case "unloaded" -> fixture.unloaded.add(net.minecraft.world.level.ChunkPos.asLong(0, 0));
						default -> fail(failure);
					}
					assertEquals(TargetKey.from(fixture.target(hit)), group(fixture, hit), failure);
					fixture.clientEntities(); var registry = new BlockPresentationResolverRegistry(false); registry.register(resolver(fixture.loader));
					if (failure.equals("wrong-world")) entity.setLevel(fixture.serverWorld);
					var direct = registry.resolve(fixture.client, spec(fixture, hit));
					assertEquals(1, direct.renderSubjects().size(), failure); assertEquals(hit, direct.renderSubjects().getFirst().blockPos());
					assertEquals(BlockPresentationRelation.DIRECT, direct.renderSubjects().getFirst().relation(), failure);
				}
			try (var fixture = new TankNativeFixture(1, 2, BlockPos.ZERO)) {
				var target = new Target.BlockTarget("minecraft:the_nether", 0, 0, 0, "create:fluid_tank");
				assertEquals(TargetKey.from(target), group(fixture.loader, fixture.serverWorld, target)); assertEquals(0, fixture.chunkReads);
			}
		}
	}

	@Test void missingMapEntriesUnloadedSiblingsAndApiDriftNeverPublishPrefixesAndNextFrameRecovers() throws Exception {
		try (var mods = new Mods("6.0.10"); var fixture = new TankNativeFixture(2, 3, new BlockPos(15, 64, 15))) {
			fixture.clientEntities(); var registry = new BlockPresentationResolverRegistry(false); registry.register(resolver(fixture.loader));
			var full = registry.resolve(fixture.client, spec(fixture, fixture.root)); assertEquals(12, full.renderSubjects().size());
			var end = fixture.root.offset(1, 2, 1); var old = fixture.entities.remove(end);
			assertEquals(BlockPresentationRelation.DIRECT, registry.resolve(fixture.client, spec(fixture, fixture.root)).renderSubjects().getFirst().relation());
			fixture.entities.put(end, old); assertEquals(full, registry.resolve(fixture.client, spec(fixture, fixture.root)));
			fixture.unloaded.add(net.minecraft.world.level.ChunkPos.asLong(1, 1));
			assertEquals(1, registry.resolve(fixture.client, spec(fixture, fixture.root)).renderSubjects().size());
			fixture.unloaded.clear(); assertEquals(full, registry.resolve(fixture.client, spec(fixture, fixture.root)));
			fixture.unloaded.add(net.minecraft.world.level.ChunkPos.asLong(0, 0)); fixture.stateReads = fixture.mapReads = 0;
			assertTrue(registry.resolve(fixture.client, spec(fixture, fixture.root)).isEmpty()); assertEquals(0, fixture.stateReads); assertEquals(0, fixture.mapReads);
			fixture.unloaded.clear(); var drifted = new VaultOptionalHarness(); drifted.driftedApi = true;
			fixture.entities.values().forEach(e -> e.setLevel(fixture.serverWorld));
			assertEquals(TargetKey.from(fixture.target(fixture.root)), group(drifted, fixture.serverWorld, fixture.target(fixture.root)));
			fixture.clientEntities(); var driftRegistry = new BlockPresentationResolverRegistry(false); driftRegistry.register(resolver(drifted));
			assertEquals(BlockPresentationRelation.DIRECT, driftRegistry.resolve(fixture.client, spec(fixture, fixture.root)).renderSubjects().getFirst().relation());
		}
	}

	@Test void neoClientRegistersTankLazilyAndKeepsTankAndVaultStateAndHandlesIndependent() throws Exception {
		try (var mods = new Mods("6.0.10"); var fixture = new TankNativeFixture(2, 3, BlockPos.ZERO)) {
			Class.forName("nx.pingwheel.common.Global"); var client = fixture.loader.loadClass("nx.pingwheel.neoforge.NeoClient");
			var load = client.getDeclaredMethod("loadCreateAdapters"); load.setAccessible(true); load.invoke(null);
			var type = resolverClass(fixture.loader);
			var vault = fixture.loader.loadClass("nx.pingwheel.neoforge.integration.create.NeoForgeCreateVaultBlockPresentationResolver");
			try {
				assertEquals("registered", type.getMethod("registrationState").invoke(null));
				assertTrue(String.valueOf(field(client, "lastCreateTankResolverState").get(null)).contains("registrationState=registered"));
				assertNotSame(field(type, "registration").get(null), field(vault, "registration").get(null));
				fixture.clientEntities(); assertEquals(12, BlockPresentationResolverRegistry.INSTANCE.resolve(fixture.client, spec(fixture, fixture.root)).renderSubjects().size());
				try (var unsupported = new Mods("6.0.11")) { load.invoke(null); assertEquals("unsupported-version", field(client, "lastCreateTankResolverState").get(null)); }
				try (var absent = new Mods(null)) { load.invoke(null); assertEquals("not-detected", field(client, "lastCreateTankResolverState").get(null)); }
			} finally {
				for (var resolverType : List.of(type, vault)) {
					((BlockPresentationResolverRegistry.Registration) field(resolverType, "registration").get(null)).close(); field(resolverType, "registration").set(null, null);
				}
			}
		}
	}

	@Test void controllerBerSuccessStillVisitsEveryCellModelAndUnrenderedCellsKeepExactShapeFallback() throws Exception {
		try (var mods = new Mods("6.0.10"); var fixture = new TankNativeFixture(2, 3, BlockPos.ZERO)) {
			fixture.clientEntities();
			try (var registration = BlockPresentationResolverRegistry.INSTANCE.register(resolver(fixture.loader))) {
				var store = new ClientMarkerStore(0); var key = new TargetKey.CreateTankKey("minecraft:overworld", 0, 0, 0, "create:fluid_tank");
				var hit = new BlockPos(1, 2, 1); var anchor = new MarkerAnchor(1.5, 2.5, 1.5);
				store.onCreated(new MarkerSnapshot(new MarkerId(1), VaultNativeFixture.OWNER, fixture.target(hit), key, "entity_block", "attention", anchor, 1, 30), 0);
				store.onWinnerChanged(key, Optional.of(new MarkerId(1)));
				var outline = BlockOutlineState.INSTANCE; var models = BlockModelOutlineState.INSTANCE;
				try {
					outline.prepare(store, "minecraft:overworld"); models.beginFrame(); models.preparePresentations(fixture.client, outline);
					var batch = models.presentations(); var presentation = batch.getFirst(); assertEquals(12, presentation.renderSubjects().size());
					var visited = new ArrayList<BlockPos>(); var missingModel = new BlockPos(1, 1, 1);
					TankPresentationTestPort.dispatch(presentation,
						subject -> models.addSuccess(subject.successKey(presentation.sourceSpec())), (subject, observer) -> {
							// Production dispatcher must not derive coverage from this controller BER success.
							if (observer != null) observer.accept(EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
								subject.blockPos().equals(fixture.root) ? EntityBlockGeometryOutcome.RENDERED : EntityBlockGeometryOutcome.EMPTY);
							visited.add(subject.blockPos()); return !subject.blockPos().equals(missingModel);
						});
					assertEquals(presentation.renderSubjects().stream().map(BlockRenderSubject::blockPos).toList(), visited);
					assertFalse(models.allPresentationsCovered());
					assertEquals(List.of(missingModel), presentation.renderSubjects().stream().filter(s -> !models.successKeys().contains(s.successKey(presentation.sourceSpec()))).map(BlockRenderSubject::blockPos).toList());
					fixture.renderReads = true; var vertices = new Vertices();
					BlockOutlineRenderer.render(fixture.client, allocate(net.minecraft.client.Camera.class), net.minecraft.world.phys.Vec3.ZERO,
						vertices, outline, batch, models.successKeys(), java.util.Set.of(), 0);
					assertEquals(24, vertices.positions.size(), "one missing cell model emits its own twelve native edges");
					assertTrue(vertices.positions.stream().allMatch(v -> v.x >= 1 && v.x <= 2 && v.y >= 1 && v.y <= 2 && v.z >= 1 && v.z <= 2));
					fixture.renderReads = false;
					models.clearSuccesses(); assertSame(batch, models.presentations());
					models.addSuccess(presentation.renderSubjects().getFirst().successKey(presentation.sourceSpec()));
					assertEquals(11, presentation.renderSubjects().stream().filter(s -> !models.successKeys().contains(s.successKey(presentation.sourceSpec()))).count());
					fixture.entities.remove(fixture.root); int reads = fixture.mapReads;
					models.preparePresentations(fixture.client, outline); assertSame(batch, models.presentations()); assertEquals(reads, fixture.mapReads);
					models.beginFrame(); models.preparePresentations(fixture.client, outline);
					var direct = models.presentations().getFirst().renderSubjects().getFirst();
					assertEquals(hit, direct.blockPos()); assertEquals(BlockPresentationRelation.DIRECT, direct.relation());
					fixture.renderReads = true; vertices.positions.clear();
					BlockOutlineRenderer.render(fixture.client, allocate(net.minecraft.client.Camera.class), net.minecraft.world.phys.Vec3.ZERO,
						vertices, outline, models.presentations(), java.util.Set.of(), java.util.Set.of(), 0);
					assertEquals(24, vertices.positions.size());
					assertTrue(vertices.positions.stream().allMatch(v -> v.x >= hit.getX() && v.x <= hit.getX() + 1 && v.y >= hit.getY()
						&& v.y <= hit.getY() + 1 && v.z >= hit.getZ() && v.z <= hit.getZ() + 1));
					fixture.renderReads = false;
					assertEquals(anchor, store.marker(new MarkerId(1)).orElseThrow().anchor());
				} finally { models.clear(); outline.clear(); }
			}
		}
	}

	@NotNullByDefault
	private static final class Vertices implements com.mojang.blaze3d.vertex.VertexConsumer {
		final List<net.minecraft.world.phys.Vec3> positions = new ArrayList<>();
		@Override public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) { positions.add(new net.minecraft.world.phys.Vec3(x, y, z)); return this; }
		@Override public com.mojang.blaze3d.vertex.VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
		@Override public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { return this; }
		@Override public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
		@Override public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
		@Override public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { return this; }
	}

	@Test void archiveTankBerIsControllerOnlyFluidOrBoilerSupplementNotTheCellBakedModel() throws Exception {
		var renderer = archive("com.simibubi.create.content.fluids.tank.FluidTankRenderer");
		var render = renderer.methods.stream().filter(m -> m.name.equals("renderSafe") && m.desc.startsWith("(L" + TANK.replace('.', '/') + ";")).findFirst().orElseThrow();
		var calls = new ArrayList<MethodInsnNode>(); for (var instruction : render.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call);
		assertEquals("isController", calls.getFirst().name); assertTrue(calls.stream().anyMatch(c -> c.name.equals("renderAsBoiler")));
		assertTrue(calls.stream().anyMatch(c -> c.name.equals("renderFluidBox")), "actual Create fluid rendering spans controller extents");
		assertFalse(calls.stream().anyMatch(c -> List.of("renderBatched", "tesselateBlock", "renderBlock", "getBlockModel").contains(c.name)), "tank BER does not own sibling cell baked models");
		var block = archive(TANK_BLOCK); assertTrue(block.methods.stream().noneMatch(m -> m.name.equals("getRenderShape")), "ordinary Block MODEL rendering is inherited");
	}

	@Test void compiledTopologyCannotReachInventoryCapabilitiesFluidOrNbtOrLoadingDiscovery() throws Exception {
		for (String name : List.of("CreateFluidTankTopology", "CreateFluidTankTopology$MinecraftWorld", "CreateFluidTankTopology$Api", "CreateFluidTankTopology$ApiHolder")) {
			try (var input = getClass().getResourceAsStream(name + ".class")) {
				assertNotNull(input); var node = new ClassNode(); new ClassReader(input).accept(node, 0);
				for (var method : node.methods) for (var instruction : method.instructions) {
					if (instruction instanceof MethodInsnNode call) {
						assertFalse(List.of("getBlockEntity", "getControllerBE", "getTankInventory", "getFluid", "getFluidInTank", "handlerForCapability", "getCapability", "getChunk", "getUpdateTag").contains(call.name), call.owner + "." + call.name);
						assertFalse(call.owner.startsWith("net/minecraft/nbt/") || call.owner.startsWith("net/neoforged/neoforge/fluids/")
							|| call.owner.endsWith("CreateFluidTankAccess") || call.owner.contains("Inventory"), call.owner);
					}
					if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode literal && literal.cst instanceof String text)
						assertFalse(List.of("boiler", "getTankInventory", "getControllerBE", "handlerForCapability", "getCapability", "getFluidLevel").contains(text), text);
				}
			}
		}
	}

	private static PresentationS2CPacket wire(PresentationS2CPacket packet) {
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try { packet.write(buffer); var received = PresentationS2CPacket.readSafe(buffer); assertFalse(received.isCorrupt()); return received; }
		finally { buffer.release(); }
	}
	private static void receive(ClientMarkerStore client, ServerMarker marker) {
		var section = new PresentationSection("minecraft:basic", 1, Map.of("minecraft:target.name", new PresentationValue.Text("{\"text\":\"Tank\"}")), false);
		var packet = wire(PresentationS2CPacket.created(1, 1, 1, MarkerSnapshot.from(marker), "Owner", PresentationPropertyRef.root("minecraft:basic", "minecraft:target.name"), section));
		client.onCreated(packet.snapshot(), marker.arrivalTick());
	}

	@Test void actualCreationWireAndWinnerFreezeControllerKeyButPreservePhysicalTargetAnchorAudienceAndLifetime() throws Exception {
		try (var mods = new Mods("6.0.10"); var fixture = new TankNativeFixture(3, 5, new BlockPos(15, 64, 15))) {
			Class.forName("nx.pingwheel.common.Global"); ClassLoader previous = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(fixture.loader);
			try {
				var core = fixture.loader.loadClass("nx.pingwheel.common.core.ServerCore"); var store = new ServerMarkerStore(new MarkerIdSource()); field(core, "MARKER_STORE").set(null, store);
				field(core, "SERVER_CONFIG").set(null, fixture.loader.loadClass("nx.pingwheel.common.config.ServerConfig").getConstructor().newInstance());
				var factory = core.getDeclaredMethod("markerService", MinecraftServer.class); factory.setAccessible(true);
				var service = (MarkerCreationService) factory.invoke(null, fixture.server); var end = fixture.root.offset(2, 4, 2); var outsider = new java.util.UUID(3, 3);
				var first = service.create(VaultNativeFixture.OWNER, fixture.target(fixture.root), "attention", 1, 30, List.of(VaultNativeFixture.OWNER, VaultNativeFixture.OTHER, outsider)).creation().orElseThrow().marker();
				var second = service.create(VaultNativeFixture.OTHER, fixture.target(end), "danger", 2, 20, List.of(VaultNativeFixture.OWNER, VaultNativeFixture.OTHER)).creation().orElseThrow().marker();
				assertInstanceOf(TargetKey.CreateTankKey.class, first.targetKey()); assertEquals(first.targetKey(), second.targetKey());
				assertEquals(fixture.target(end), second.target()); assertEquals(new MarkerAnchor(end.getX() + .5, end.getY() + .5, end.getZ() + .5), second.anchor());
				assertEquals(second.id(), store.winnerFor(first.targetKey(), VaultNativeFixture.OWNER).orElseThrow().id()); assertEquals(first.id(), store.winnerFor(first.targetKey(), outsider).orElseThrow().id());
				var client = new ClientMarkerStore(0); receive(client, first); receive(client, second);
				var winner = wire(PresentationS2CPacket.winner(1, 1, first.targetKey(), Optional.of(second.id()))); client.onWinnerChanged(winner.targetKey(), winner.winnerId());
				assertEquals(second.id(), client.winnerId(first.targetKey()).orElseThrow()); client.expireFallback(20); assertEquals(first.id(), client.winnerId(first.targetKey()).orElseThrow());
				var removed = fixture.entities.remove(fixture.root.offset(1, 1, 1));
				var fallback = service.create(VaultNativeFixture.OWNER, fixture.target(end), "attention", 3, 40, List.of(VaultNativeFixture.OWNER)).creation().orElseThrow().marker();
				assertEquals(TargetKey.from(fixture.target(end)), fallback.targetKey()); assertEquals(first.targetKey(), store.find(second.id()).orElseThrow().targetKey());
				fixture.entities.put(fixture.root.offset(1, 1, 1), removed); assertEquals(first.targetKey(), group(fixture, end));
				assertEquals(MarkerRemovalResult.Status.NOT_OWNER, store.removeOwned(VaultNativeFixture.OWNER, second.id()).status()); store.removeOwned(VaultNativeFixture.OTHER, second.id());
				assertEquals(first.id(), store.winnerFor(first.targetKey(), VaultNativeFixture.OWNER).orElseThrow().id()); store.expire(30); assertTrue(store.find(first.id()).isEmpty()); assertTrue(store.find(fallback.id()).isPresent());
			} finally { Thread.currentThread().setContextClassLoader(previous); }
		}
	}
}
