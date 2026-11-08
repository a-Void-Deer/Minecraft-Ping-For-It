package nx.pingwheel.common.render;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.mixin.GameRendererMixin;
import nx.pingwheel.common.mixin.GuiMixin;
import nx.pingwheel.common.monitor.MonitorHudLabels;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Actual mapped bytecode ABI, not a claim of loader launch or Mixin application. */
@NotNullByDefault
class InventoryMonitorHudAbiTest {
	private static final String RENDER = "(Lnet/minecraft/client/DeltaTracker;Z)V";
	private static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphics";

	@Test void actualRenderHasOneTypedGraphicsStoreBetweenProjectionAndEveryGuiPass() throws IOException {
		var renderer = read(GameRenderer.class);
		var render = renderer.methods.stream().filter(method -> method.name.equals("render") && method.desc.equals(RENDER)).findFirst().orElseThrow();
		var graphicsLocals = new HashSet<Integer>();
		for (var local : render.localVariables) if (local.desc.equals("L" + GRAPHICS + ";")) graphicsLocals.add(local.index);
		assertEquals(1, graphicsLocals.size(), "implicit type-only discriminator must have a unique GuiGraphics local");
		int constructions = 0;
		var stores = new ArrayList<Integer>();
		var calls = new ArrayList<MethodInsnNode>();
		for (var instruction : render.instructions) {
			if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(GRAPHICS)) constructions++;
			if (instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ASTORE && graphicsLocals.contains(variable.var))
				stores.add(render.instructions.indexOf(variable));
			if (instruction instanceof MethodInsnNode call) calls.add(call);
		}
		assertEquals(1, constructions); assertEquals(1, stores.size());
		int store = stores.getFirst();
		for (String before : List.of("renderLevel", "setProjectionMatrix", "applyModelViewMatrix")) {
			assertTrue(calls.stream().anyMatch(call -> call.name.equals(before) && render.instructions.indexOf(call) < store), before);
		}
		for (String after : List.of("renderConfusionOverlay", "renderItemActivationAnimation", "renderSavingIndicator")) {
			assertTrue(calls.stream().anyMatch(call -> call.name.equals(after) && render.instructions.indexOf(call) > store), after);
		}
		assertTrue(calls.stream().anyMatch(call -> (call.name.equals("renderWithTooltip")
			|| call.name.equals("drawScreen") && (call.owner.equals("net/minecraftforge/client/ForgeHooksClient")
				|| call.owner.equals("net/neoforged/neoforge/client/ClientHooks"))) && render.instructions.indexOf(call) > store),
			"native screen pass, including the configured loader's screen-event bridge");
		assertTrue(calls.stream().anyMatch(call -> call.owner.equals("net/minecraft/client/gui/Gui") && call.name.equals("render")
			&& render.instructions.indexOf(call) > store));
		assertTrue(calls.stream().anyMatch(call -> call.owner.equals("net/minecraft/client/gui/screens/Overlay") && call.name.equals("render")
			&& render.instructions.indexOf(call) > store));
	}

	@Test void hookIsStrictTypeOnlyStoreAndReturnsTheOriginalGraphicsWhileFrameHeadRemainsSeparate() throws IOException {
		var bridge = read(GameRendererMixin.class);
		var store = bridge.methods.stream().filter(method -> annotations(method).stream()
			.anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;"))).findFirst().orElseThrow();
		assertEquals("(L" + GRAPHICS + ";Lnet/minecraft/client/DeltaTracker;Z)L" + GRAPHICS + ";", store.desc);
		var annotation = annotations(store).stream().filter(value -> value.desc.endsWith("/ModifyVariable;")).findFirst().orElseThrow();
		assertEquals(List.of("render" + RENDER), value(annotation, "method"));
		assertEquals(1, value(annotation, "require")); assertEquals(1, value(annotation, "allow"));
		for (String discriminator : List.of("ordinal", "index", "name")) assertNull(value(annotation, discriminator));
		var at = (AnnotationNode) value(annotation, "at");
		assertNotNull(at); assertEquals("STORE", value(at, "value")); assertNull(value(at, "shift"));
		var returns = new ArrayList<VarInsnNode>();
		for (var instruction : store.instructions) if (instruction.getOpcode() == Opcodes.ARETURN) {
			assertInstanceOf(VarInsnNode.class, instruction.getPrevious());
			returns.add((VarInsnNode) instruction.getPrevious());
		}
		assertEquals(1, returns.size()); assertEquals(Opcodes.ALOAD, returns.getFirst().getOpcode());
		assertEquals(1, returns.getFirst().var, "return the original graphics parameter, never create a second GUI buffer");
		assertTrue(bridge.methods.stream().anyMatch(method -> calls(method).stream().anyMatch(call -> call.name.equals("onRenderFrame"))
			&& annotations(method).stream().filter(value -> value.desc.endsWith("/Inject;")).anyMatch(value -> {
				var points = (List<?>) value(value, "at");
				return points != null && "HEAD".equals(value((AnnotationNode) points.getFirst(), "value"));
			})));
		assertFalse(read(GuiMixin.class).methods.stream().flatMap(method -> calls(method).stream()).anyMatch(call -> call.name.equals("onRenderMonitor")),
			"the old late HUD paint must not draw a second top layer");
	}

	@Test void nativeFontPassDisablesDepthWritesSoLaterTotemAndGuiGeometryCanCoverIt() throws IOException {
		// This is the concrete native state contract the early hook relies on:
		// unlike NORMAL text, SEE_THROUGH has a color-only write mask. A late
		// totem can be behind the text's Z and must still draw on top of it.
		var renderTypes = read(RenderType.class);
		var text = renderTypes.methods.stream().filter(method -> {
			for (var instruction : method.instructions) if (instruction instanceof LdcInsnNode literal && "text_see_through".equals(literal.cst)) return true;
			return false;
		}).findFirst().orElseThrow();
		for (String state : List.of("NO_DEPTH_TEST", "COLOR_WRITE")) {
			assertTrue(java.util.stream.StreamSupport.stream(text.instructions.spliterator(), false)
				.anyMatch(instruction -> instruction instanceof FieldInsnNode field && field.name.equals(state)), state);
		}
		ClassNode painter;
		try (var bytes = InventoryMonitorHud.class.getResourceAsStream("InventoryMonitorHud$NativePainter.class")) {
			assertNotNull(bytes); painter = new ClassNode(); new ClassReader(bytes).accept(painter, 0);
		}
		assertTrue(painter.methods.stream().anyMatch(method -> java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
			.anyMatch(instruction -> instruction instanceof FieldInsnNode field && field.owner.equals("net/minecraft/client/gui/Font$DisplayMode")
				&& field.name.equals("SEE_THROUGH"))));
	}

	@Test void resourceReloadInstallsNewLanguageIdentityAndClientManifestsKeepTheHookOffDedicatedPaths() throws IOException {
		var reload = read(LanguageManager.class).methods.stream().filter(method -> method.name.equals("onResourceManagerReload")).findFirst().orElseThrow();
		assertTrue(calls(reload).stream().anyMatch(call -> call.owner.equals("net/minecraft/client/resources/language/ClientLanguage") && call.name.equals("loadFrom")));
		assertTrue(calls(reload).stream().anyMatch(call -> call.owner.equals("net/minecraft/locale/Language") && call.name.equals("inject")));
		Path root = Path.of("").toAbsolutePath();
		while (!Files.isRegularFile(root.resolve("settings.gradle"))) root = java.util.Objects.requireNonNull(root.getParent());
		for (String module : List.of("common", "forge", "neoforge")) {
			var json = JsonParser.parseString(Files.readString(root.resolve(module + "/src/main/resources/pingforit.mixins.json"))).getAsJsonObject();
			assertEquals(1, json.getAsJsonArray("client").asList().stream().filter(entry -> entry.getAsString().equals("GameRendererMixin")).count());
			assertFalse(json.getAsJsonArray("mixins").asList().stream().anyMatch(entry -> entry.getAsString().equals("GameRendererMixin")));
		}
	}

	@Test void executableProductionGateBytecodeRequiresWorldHudAndLoadedGameButNotScreenAbsence() throws ReflectiveOperationException, IOException {
		// Execute copied production methods with only the terminal native draw
		// replaced. This verifies actual gates/read order without a window/GL or
		// initializing the full CommonClient lifecycle. It is not Mixin apply.
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		Class<?> probe = executableGates();
		var game = allocate(Minecraft.class);
		var options = Minecraft.class.getDeclaredField("options"); options.setAccessible(true); options.set(game, allocate(Options.class));
		var font = Minecraft.class.getDeclaredField("font"); font.setAccessible(true);
		font.set(game, new Font(ignored -> { throw new AssertionError("gate-only probe must not request glyphs"); }, false));
		var ready = Minecraft.class.getDeclaredField("gameLoadFinished"); ready.setAccessible(true);
		var world = allocate(ClientLevel.class);
		var player = allocate(LocalPlayer.class);
		game.screen = new Screen(Component.literal("covered monitor")) {};
		var graphics = allocate(GuiGraphics.class);
		var instance = probe.getConstructor().newInstance();
		probe.getField("minecraft").set(instance, game);
		probe.getField("Game").set(null, game);
		probe.getField("monitorViewport").set(null, new InventoryMonitorHud.Viewport(427, 240));
		var prepared = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(), ClientMonitorState.Status.OFF,
			ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, null, null), MonitorHudLabels.DEFAULT);
		assertFalse(prepared.rows().isEmpty());
		var hook = probe.getMethod("pingforit$renderMonitorBelowGui", GuiGraphics.class, DeltaTracker.class, boolean.class);
		for (int mask = 0; mask < 64; mask++) {
			boolean loaded = (mask & 1) != 0, renderLevel = (mask & 2) != 0, hasWorld = (mask & 4) != 0;
			boolean hasPlayer = (mask & 8) != 0, hideGui = (mask & 16) != 0, hasRows = (mask & 32) != 0;
			ready.setBoolean(game, loaded); game.level = hasWorld ? world : null; game.player = hasPlayer ? player : null;
			game.options.hideGui = hideGui;
			probe.getField("monitorModel").set(null, hasRows ? prepared : MonitorDisplayModel.EMPTY);
			NativeDraw.calls = 0;
			assertSame(graphics, hook.invoke(instance, graphics, DeltaTracker.ZERO, renderLevel));
			assertEquals(loaded && renderLevel && hasWorld && hasPlayer && !hideGui && hasRows ? 1 : 0, NativeDraw.calls,
				"native gate truth table mask=" + mask + "; open screen never hides the bottom pass");
		}
	}

	/** Terminal test port only; actual Font submission and GL remain unexercised. */
	@NotNullByDefault
	public static final class NativeDraw {
		static int calls;
		public static void draw(GuiGraphics graphics, Font font, MonitorDisplayModel model, InventoryMonitorHud.Viewport viewport) { calls++; }
	}

	private static Class<?> executableGates() throws IOException, IllegalAccessException {
		String name = "nx/pingwheel/common/render/InventoryMonitorGateProbe";
		var node = new ClassNode();
		node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC; node.name = name; node.superName = "java/lang/Object";
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "minecraft", "Lnet/minecraft/client/Minecraft;", null, null));
		var common = read(CommonClient.class);
		for (var field : common.fields) if (List.of("Game", "monitorModel", "monitorViewport").contains(field.name))
			node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field.name, field.desc, null, null));
		var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		constructor.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(constructor);
		for (var method : List.of(read(GameRendererMixin.class).methods.stream().filter(value -> value.name.equals("pingforit$renderMonitorBelowGui")).findFirst().orElseThrow(),
			common.methods.stream().filter(value -> value.name.equals("onRenderMonitor")).findFirst().orElseThrow())) {
			method.access = Opcodes.ACC_PUBLIC; method.visibleAnnotations = null; method.invisibleAnnotations = null;
			for (var instruction : method.instructions.toArray()) {
				if (instruction instanceof FieldInsnNode field) {
					if (field.name.equals("INSTANCE") && field.owner.equals("nx/pingwheel/common/CommonClient")) {
						method.instructions.set(field, new VarInsnNode(Opcodes.ALOAD, 0));
					} else if (field.owner.equals("nx/pingwheel/common/CommonClient") || field.owner.equals("nx/pingwheel/common/mixin/GameRendererMixin")) field.owner = name;
				} else if (instruction instanceof MethodInsnNode call) {
					if (call.owner.equals("nx/pingwheel/common/CommonClient") && call.name.equals("onRenderMonitor")) call.owner = name;
					if (call.owner.equals("nx/pingwheel/common/render/InventoryMonitorHud") && call.name.equals("draw"))
						call.owner = "nx/pingwheel/common/render/InventoryMonitorHudAbiTest$NativeDraw";
				}
			}
			node.methods.add(method);
		}
		var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); node.accept(writer);
		return MethodHandles.lookup().defineClass(writer.toByteArray());
	}

	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
	}

	private static ClassNode read(Class<?> type) throws IOException {
		var node = new ClassNode();
		try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
			assertNotNull(bytes); new ClassReader(bytes).accept(node, 0);
		}
		return node;
	}

	private static List<MethodInsnNode> calls(MethodNode method) {
		var calls = new ArrayList<MethodInsnNode>();
		for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call);
		return calls;
	}

	private static List<AnnotationNode> annotations(MethodNode method) {
		var result = new ArrayList<AnnotationNode>();
		if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
		if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
		return result;
	}

	private static @Nullable Object value(AnnotationNode annotation, String name) {
		if (annotation.values != null) for (int index = 0; index < annotation.values.size(); index += 2)
			if (annotation.values.get(index).equals(name)) return annotation.values.get(index + 1);
		return null;
	}
}
