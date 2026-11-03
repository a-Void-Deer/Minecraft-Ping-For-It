package nx.pingwheel.neoforge.integration.create;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.MappedRegistry;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.client.IPlatformFluidRenderService;
import nx.pingwheel.common.platform.client.FluidSprites;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidDisplayCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidFreezer;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.client.ClientFluidDisplay;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact Create/embedded-Registrate archive chain plus production NeoForge ports; not a game/GPU launch. */
@NotNullByDefault
class CreateFluidPresentationArchiveTest {
	private static final String REGISTRATE = "com.simibubi.create.foundation.data.CreateRegistrate";
	private static final String BUILDER = "com.tterrag.registrate.builders.FluidBuilder";
	private static final String VIRTUAL = "com.simibubi.create.content.fluids.VirtualFluid";
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@Test void teaRegistrationReachesEmbeddedRegistrateFinalDescriptionAssignmentAndNativeStillTexture() throws Exception {
		var all = archive("com.simibubi.create.AllFluids");
		var init = all.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
		var instructions = init.instructions.toArray(); int tea = -1;
		for (int i = 0; i < instructions.length; i++) if (instructions[i] instanceof LdcInsnNode literal && "tea".equals(literal.cst)) tea = i;
		assertTrue(tea >= 0); var call = (MethodInsnNode) instructions[tea + 1];
		assertEquals(REGISTRATE.replace('.', '/'), call.owner); assertEquals("virtualFluid", call.name);
		assertEquals("(Ljava/lang/String;)Lcom/tterrag/registrate/builders/FluidBuilder;", call.desc);
		var registrate = archive(REGISTRATE);
		var virtual = registrate.methods.stream().filter(m -> m.name.equals("virtualFluid")
			&& m.desc.startsWith("(Ljava/lang/String;)")).findFirst().orElseThrow();
		var factory = java.util.Arrays.stream(virtual.instructions.toArray()).filter(InvokeDynamicInsnNode.class::isInstance)
			.map(InvokeDynamicInsnNode.class::cast).flatMap(d -> java.util.Arrays.stream(d.bsmArgs)).filter(org.objectweb.asm.Handle.class::isInstance)
			.map(org.objectweb.asm.Handle.class::cast).findFirst().orElseThrow();
		var lambda = registrate.methods.stream().filter(m -> m.name.equals(factory.getName())).findFirst().orElseThrow();
		assertTrue(java.util.Arrays.stream(lambda.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.owner.endsWith("VirtualFluidBuilder") && c.name.equals("<init>")));
		assertTrue(java.util.Arrays.stream(lambda.instructions.toArray()).filter(InvokeDynamicInsnNode.class::isInstance).map(InvokeDynamicInsnNode.class::cast)
			.flatMap(d -> java.util.Arrays.stream(d.bsmArgs)).anyMatch(a -> a instanceof org.objectweb.asm.Handle h && h.getName().equals("defaultFluidType")),
			"the virtual builder must use CreateRegistrate's native texture-bearing FluidType factory");
		var concat = java.util.Arrays.stream(lambda.instructions.toArray()).filter(InvokeDynamicInsnNode.class::isInstance).map(InvokeDynamicInsnNode.class::cast)
			.filter(d -> d.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")).findFirst().orElseThrow();
		assertEquals("fluid/\u0001_still", concat.bsmArgs[0]);
		var embedded = embeddedBuilder();
		var assignment = embedded.methods.stream().filter(m -> m.name.equals("makeTypeProperties")).findFirst().orElseThrow();
		var body = assignment.instructions.toArray();
		int prefix = -1;
		for (int i = 0; i < body.length; i++) if (body[i] instanceof LdcInsnNode literal && "fluid".equals(literal.cst)) prefix = i;
		assertTrue(prefix >= 0, "native non-block branch must explicitly use fluid, not block");
		var tailCalls = new ArrayList<MethodInsnNode>();
		for (int i = prefix; i < body.length; i++) if (body[i] instanceof MethodInsnNode c) tailCalls.add(c);
		assertEquals(List.of("getOwner", "getModid", "fromNamespaceAndPath", "makeDescriptionId", "descriptionId"), tailCalls.stream().map(c -> c.name).toList());
		assertEquals("net/neoforged/neoforge/fluids/FluidType$Properties", tailCalls.getLast().owner);
		assertTrue(java.util.Arrays.stream(embedded.methods.stream().filter(m -> m.name.equals("lambda$new$1")).findFirst().orElseThrow().instructions.toArray())
			.anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("makeTypeProperties")), "properties must flow into the registered FluidTypeFactory");
		var constructor = archive("com.simibubi.create.foundation.data.VirtualFluidBuilder").methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
		assertTrue(java.util.Arrays.stream(constructor.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(BUILDER.replace('.', '/')) && c.name.equals("<init>")));
		assertFalse(java.util.Arrays.stream(constructor.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("defaultBlock")),
			"virtual constructor does not synthesize a placeable block or take Registrate's block-name branch");
		String key = net.minecraft.Util.makeDescriptionId("fluid", ResourceLocation.fromNamespaceAndPath("create", "tea"));
		assertEquals("fluid.create.tea", key);
		try (var zip = new ZipFile(System.getProperty("pingforit.test.createArtifact"))) {
			for (String locale : List.of("en_us", "zh_cn")) {
				var json = JsonParser.parseString(text(zip, "assets/create/lang/" + locale + ".json")).getAsJsonObject();
				assertEquals(locale.equals("en_us") ? "Builder's Tea" : "建筑工茶饮", json.get(key).getAsString()); assertFalse(json.has("block.create.tea"));
			}
			assertNotNull(zip.getEntry("assets/create/textures/fluid/tea_still.png"));
			assertTrue(text(zip, "assets/minecraft/atlases/blocks.json").contains("fluid"));
		}
	}

	@Test void actualCreateVirtualAirAndClientExtensionCrossProductionNeoForgeNameAndAtlasPorts() throws Exception {
		var loader = new NativeArchive();
		Class<?> properties = Class.forName("net.neoforged.neoforge.fluids.FluidType$Properties");
		Object props = properties.getMethod("create").invoke(null);
		properties.getMethod("descriptionId", String.class).invoke(props, net.minecraft.Util.makeDescriptionId("fluid", ResourceLocation.parse("create:tea")));
		var typeClass = loader.loadClass(REGISTRATE + "$1"); var constructor = typeClass.getDeclaredConstructor(properties, ResourceLocation.class, ResourceLocation.class); constructor.setAccessible(true);
		Object fluidType = constructor.newInstance(props, ResourceLocation.parse("create:fluid/tea_still"), ResourceLocation.parse("create:fluid/tea_flow"));
		Fluid fluid = loader.fluid(fluidType);
		assertTrue(fluid.defaultFluidState().createLegacyBlock().isAir(), "execute the actual VirtualFluid legacy-block body");
		var names = (IPlatformFluidDescriptionService) Class.forName("nx.pingwheel.neoforge.platform.PlatformFluidDescriptionServiceImpl").getConstructor().newInstance();
		assertEquals(new IPlatformFluidDescriptionService.Translation("fluid.create.tea", null), names.describeBase(fluid));
		assertTrue(ServiceLoader.load(IPlatformFluidDescriptionService.class).stream().anyMatch(p -> p.type() == names.getClass()));
		var extensionType = Class.forName("net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions");
		Object[] extension = new Object[1];
		var initialize = typeClass.getMethod("initializeClient", java.util.function.Consumer.class); initialize.setAccessible(true);
		initialize.invoke(fluidType, (java.util.function.Consumer<Object>) value -> extension[0] = value);
		assertEquals(ResourceLocation.parse("create:fluid/tea_still"), extensionType.getMethod("getStillTexture").invoke(extension[0]));
		var registry = field(Class.forName("net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager"), "FLUID_TYPE_EXTENSIONS");
		@SuppressWarnings("unchecked") var extensions = (Map<Object, Object>) registry.get(null); extensions.put(fluidType, extension[0]);
		var render = (IPlatformFluidRenderService) Class.forName("nx.pingwheel.neoforge.platform.client.PlatformFluidRenderServiceImpl").getConstructor().newInstance();
		// Service construction above occurs before a Minecraft instance or atlas exists.
		try (var atlas = new AtlasFixture(); var firstContents = teaContents(); var replacementContents = teaContents()) {
			var sprite = new Sprite(firstContents); atlas.put(ResourceLocation.parse("create:fluid/tea_still"), sprite);
			assertSame(sprite, render.stillSprite(fluid));
			assertSame(sprite, FluidSprites.stillSprite(fluid, render), "actual native still lookup yields a usable block-atlas sprite");
			var reloaded = new Sprite(replacementContents); atlas.put(ResourceLocation.parse("create:fluid/tea_still"), reloaded);
			assertSame(reloaded, render.stillSprite(fluid), "adapter must query the current atlas every time");
			assertTrue(ServiceLoader.load(IPlatformFluidRenderService.class).stream().anyMatch(p -> p.type() == render.getClass()));
		} finally { extensions.remove(fluidType); }
	}
	@Test void archiveNativeTeaDescriptionFlowsThroughServerFreezerAndStrictLocalizedClientProjection() throws Exception {
		var loader = new NativeArchive();
		Class<?> properties = Class.forName("net.neoforged.neoforge.fluids.FluidType$Properties");
		Object props = properties.getMethod("create").invoke(null);
		properties.getMethod("descriptionId", String.class).invoke(props, net.minecraft.Util.makeDescriptionId("fluid", ResourceLocation.parse("create:tea")));
		var constructor = loader.loadClass(REGISTRATE + "$1").getDeclaredConstructor(properties, ResourceLocation.class, ResourceLocation.class); constructor.setAccessible(true);
		Fluid fluid = loader.fluid(constructor.newInstance(props, ResourceLocation.parse("create:fluid/tea_still"), ResourceLocation.parse("create:fluid/tea_flow")));
		try (var registration = new FluidRegistration()) {
			((MappedRegistry<Fluid>) BuiltInRegistries.FLUID).createIntrusiveHolder(fluid);
			Registry.register(BuiltInRegistries.FLUID, ResourceLocation.parse("create:tea"), fluid);
			var frozen = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(fluid, null), 9007199254740993L),
				RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(),
				new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(1024), InventoryCatalogAdmission.workUpperBound(1024)));
			assertNotNull(frozen); assertEquals("fluid.create.tea", frozen.label()); assertEquals(9007199254740993L, frozen.amount()); assertFalse(frozen.aggregateRequired());
			assertNull(InventoryFluidDisplayCodec.decodeStrict(frozen.displayJson()).customName(), "base name is not a custom-name wire field");
			var previous = net.minecraft.locale.Language.getInstance();
			try (var zip = new ZipFile(System.getProperty("pingforit.test.createArtifact"))) {
				for (String locale : List.of("en_us", "zh_cn")) {
					var language = JsonParser.parseString(text(zip, "assets/create/lang/" + locale + ".json")).getAsJsonObject();
					net.minecraft.locale.Language.inject(new net.minecraft.locale.Language() {
						@Override public String getOrDefault(String key, String fallback) { return language.has(key) ? language.get(key).getAsString() : fallback; }
						@Override public boolean has(String key) { return language.has(key); }
						@Override public boolean isDefaultRightToLeft() { return false; }
						@Override public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text) { return net.minecraft.util.FormattedCharSequence.EMPTY; }
					});
					var display = ClientFluidDisplay.resolve(frozen.resourceId(), frozen.label(), frozen.displayJson());
					assertSame(fluid, display.fluid()); assertEquals(locale.equals("en_us") ? "Builder's Tea" : "建筑工茶饮", display.name().getString());
					assertEquals("fluid.create.tea", ((net.minecraft.network.chat.contents.TranslatableContents) display.name().getContents()).getKey(),
						"the client reconstructs an owned component using the same native base name");
				}
			} finally { net.minecraft.locale.Language.inject(previous); }
		}
	}
	private static net.minecraft.client.renderer.texture.SpriteContents teaContents() throws Exception {
		try (var zip = new ZipFile(System.getProperty("pingforit.test.createArtifact")); var input = zip.getInputStream(zip.getEntry("assets/create/textures/fluid/tea_still.png"))) {
			var image = com.mojang.blaze3d.platform.NativeImage.read(input);
			return new net.minecraft.client.renderer.texture.SpriteContents(ResourceLocation.parse("create:fluid/tea_still"),
				new net.minecraft.client.resources.metadata.animation.FrameSize(16, 16), image, net.minecraft.server.packs.resources.ResourceMetadata.EMPTY);
		}
	}
	@NotNullByDefault
	private static final class Sprite extends TextureAtlasSprite {
		Sprite(net.minecraft.client.renderer.texture.SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 16, 16, 0, 0); }
	}

	private static String text(ZipFile zip, String path) throws Exception {
		try (var input = zip.getInputStream(zip.getEntry(path))) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
	}
	private static ClassNode embeddedBuilder() throws Exception {
		try (var zip = new ZipFile(System.getProperty("pingforit.test.createArtifact"))) {
			var entry = zip.stream().filter(e -> e.getName().matches("META-INF/jarjar/Registrate-.*\\.jar")).findFirst().orElseThrow();
			try (var input = new ZipInputStream(zip.getInputStream(entry))) {
				for (var part = input.getNextEntry(); part != null; part = input.getNextEntry()) if (part.getName().equals(BUILDER.replace('.', '/') + ".class")) {
					var node = new ClassNode(); new ClassReader(input.readAllBytes()).accept(node, 0); return node;
				}
			}
		}
		throw new AssertionError("missing embedded FluidBuilder");
	}
	@NotNullByDefault
	private static final class NativeArchive extends ClassLoader {
		NativeArchive() { super(CreateFluidPresentationArchiveTest.class.getClassLoader()); }
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			try {
				var node = archive(name);
				if (name.equals(VIRTUAL)) {
					node.superName = Type.getInternalName(EmptyFluid.class); node.methods.removeIf(m -> !m.name.equals("createLegacyBlock")); node.fields.clear(); node.interfaces.clear();
				}
				var writer = new ClassWriter(0); node.accept(writer); byte[] bytes = writer.toByteArray(); return defineClass(name, bytes, 0, bytes.length);
			} catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
		}
		Fluid fluid(Object fluidType) throws Exception {
			Class<?> base = loadClass(VIRTUAL); String name = getClass().getName() + "$Fluid";
			var writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, Type.getInternalName(base), null);
			writer.visitAnnotation(Type.getDescriptor(NotNullByDefault.class), false).visitEnd();
			String descriptor = "Lnet/neoforged/neoforge/fluids/FluidType;";
			writer.visitField(Opcodes.ACC_PUBLIC, "nativeType", descriptor, null, null).visitEnd();
			var getter = writer.visitMethod(Opcodes.ACC_PUBLIC, "getFluidType", "()" + descriptor, null, null); getter.visitCode(); getter.visitVarInsn(Opcodes.ALOAD, 0);
			getter.visitFieldInsn(Opcodes.GETFIELD, name.replace('.', '/'), "nativeType", descriptor); getter.visitInsn(Opcodes.ARETURN); getter.visitMaxs(1, 1); getter.visitEnd();
			writer.visitEnd(); byte[] bytes = writer.toByteArray(); var generated = defineClass(name, bytes, 0, bytes.length);
			var fluid = (Fluid) allocate(generated); field(generated, "nativeType").set(fluid, fluidType);
			// Native FluidState's owner determines legacy dispatch; construct it using Fluid's real state definition.
			var definition = new net.minecraft.world.level.block.state.StateDefinition.Builder<Fluid, net.minecraft.world.level.material.FluidState>(fluid)
				.create(net.minecraft.world.level.material.Fluid::defaultFluidState, net.minecraft.world.level.material.FluidState::new);
			field(Fluid.class, "defaultFluidState").set(fluid, definition.any()); return fluid;
		}
	}
	@NotNullByDefault
	private static final class AtlasFixture implements AutoCloseable {
		private final java.lang.reflect.Field instance;
		private final @Nullable Object previous;
		private final TextureAtlas atlas = allocate(TextureAtlas.class);
		AtlasFixture() throws Exception {
			instance = field(Minecraft.class, "instance"); previous = instance.get(null);
			var minecraft = allocate(Minecraft.class); var manager = allocate(ModelManager.class); var set = allocate(AtlasSet.class);
			var entryClass = Class.forName("net.minecraft.client.resources.model.AtlasSet$AtlasEntry");
			var constructor = entryClass.getDeclaredConstructor(TextureAtlas.class, ResourceLocation.class); constructor.setAccessible(true);
			field(AtlasSet.class, "atlases").set(set, Map.of(TextureAtlas.LOCATION_BLOCKS, constructor.newInstance(atlas, ResourceLocation.parse("minecraft:blocks"))));
			field(ModelManager.class, "atlases").set(manager, set); field(Minecraft.class, "modelManager").set(minecraft, manager); instance.set(null, minecraft);
		}
		void put(ResourceLocation id, TextureAtlasSprite sprite) throws Exception { field(TextureAtlas.class, "texturesByName").set(atlas, Map.of(id, sprite)); }
		@Override public void close() throws Exception { instance.set(null, previous); }
	}
	/** Restore all mutated native registration structures rather than leaking Create into later tests. */
	@NotNullByDefault
	private static final class FluidRegistration implements AutoCloseable {
		private final Map<java.lang.reflect.Field, @Nullable Object> state = new java.util.HashMap<>();
		FluidRegistration() throws Exception {
			for (String name : List.of("byId", "toId", "byLocation", "byKey", "byValue", "registrationInfos")) {
				var field = field(MappedRegistry.class, name); Object value = field.get(BuiltInRegistries.FLUID);
				state.put(field, value instanceof List<?> list ? new ArrayList<>(list) : new java.util.IdentityHashMap<>((Map<?, ?>) value));
			}
			for (String name : List.of("frozen", "unregisteredIntrusiveHolders", "registryLifecycle")) {
				var field = field(MappedRegistry.class, name); state.put(field, field.get(BuiltInRegistries.FLUID));
			}
			field(MappedRegistry.class, "frozen").set(BuiltInRegistries.FLUID, false);
			field(MappedRegistry.class, "unregisteredIntrusiveHolders").set(BuiltInRegistries.FLUID, new java.util.IdentityHashMap<>());
		}
		@SuppressWarnings({"rawtypes", "unchecked"})
		@Override public void close() throws Exception {
			for (var entry : state.entrySet()) {
				@Nullable Object current = entry.getKey().get(BuiltInRegistries.FLUID);
				if (entry.getValue() instanceof List list) { ((List) current).clear(); ((List) current).addAll(list); }
				else if (entry.getValue() instanceof Map map) { ((Map) current).clear(); ((Map) current).putAll(map); }
				else entry.getKey().set(BuiltInRegistries.FLUID, entry.getValue());
			}
		}
	}
}
