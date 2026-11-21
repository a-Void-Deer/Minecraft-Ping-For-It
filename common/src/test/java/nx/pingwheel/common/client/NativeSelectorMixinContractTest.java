package nx.pingwheel.common.client;

import com.google.gson.JsonParser;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.MouseHandler;
import nx.pingwheel.common.mixin.KeyboardHandlerMixin;
import nx.pingwheel.common.mixin.MouseHandlerMixin;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Checks real mapped callback ABI and effective registration, not a live Mixin transformation. */
class NativeSelectorMixinContractTest {
	@Test void absoluteMoveAndRawScrollHooksMatchPrivateMinecraftCallbacks() throws Exception {
		ClassNode bridge = new ClassNode();
		try (var bytes = MouseHandlerMixin.class.getResourceAsStream("MouseHandlerMixin.class")) {
			assertNotNull(bytes); new ClassReader(bytes).accept(bridge, 0);
		}
		for (String callback : List.of("onMove", "onScroll")) {
			var method = MouseHandler.class.getDeclaredMethod(callback, long.class, double.class, double.class);
			assertTrue(Modifier.isPrivate(method.getModifiers())); assertEquals(void.class, method.getReturnType());
			var hook = bridge.methods.stream().filter(value -> value.name.equals("pingforit$selector" + (callback.equals("onMove") ? "Move" : "Scroll")))
				.findFirst().orElseThrow();
			assertEquals("(JDDLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", hook.desc);
			AnnotationNode inject = annotations(hook.visibleAnnotations, hook.invisibleAnnotations).stream()
				.filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
			assertEquals(List.of(callback + "(JDD)V"), value(inject, "method"));
			var at = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
			assertEquals(callback.equals("onMove") ? "TAIL" : "HEAD", value(at, "value"));
			assertEquals(callback.equals("onScroll") ? Boolean.TRUE : null, value(inject, "cancellable"), "vanilla absolute bookkeeping must not be cancelled");
		}
		for (String method : List.of("grabMouse", "releaseMouse", "cursorEntered", "setIgnoreFirstMove"))
			assertEquals(void.class, MouseHandler.class.getDeclaredMethod(method).getReturnType());
		for (String field : List.of("accumulatedDX", "accumulatedDY")) {
			assertEquals(double.class, MouseHandler.class.getDeclaredField(field).getType());
			assertTrue(bridge.fields.stream().anyMatch(value -> value.name.equals(field) && value.desc.equals("D")));
		}
		assertFalse(bridge.fields.stream().anyMatch(field -> field.name.equals("xpos") || field.name.equals("ypos")),
			"scoped disabled release leaves vanilla's centered position bookkeeping intact");
		assertTrue(WheelMouseCapture.MouseState.class.isAssignableFrom(MouseHandlerMixin.class));
	}
	@Test void cursorEntryHookUsesOwnershipAwareDelegateWithoutCancellingVanillaIgnoreFirstMove() throws Exception {
		ClassNode bridge = readClass(MouseHandlerMixin.class, "MouseHandlerMixin.class");
		var entryHooks = bridge.methods.stream().filter(method -> annotations(method.visibleAnnotations, method.invisibleAnnotations).stream()
			.anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
				&& ((List<?>) value(annotation, "method")).contains("cursorEntered"))).toList();
		assertEquals(1, entryHooks.size()); var hook = entryHooks.getFirst();
		assertEquals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", hook.desc);
		var inject = annotations(hook.visibleAnnotations, hook.invisibleAnnotations).stream()
			.filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
		assertEquals(List.of("cursorEntered"), value(inject, "method"));
		assertEquals("HEAD", value((AnnotationNode) ((List<?>) value(inject, "at")).getFirst(), "value"));
		assertNull(value(inject, "cancellable"), "vanilla must still set ignoreFirstMove");
		var calls = new ArrayList<MethodInsnNode>();
		for (var instruction : hook.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call);
		assertTrue(calls.stream().anyMatch(call -> call.owner.equals("nx/pingwheel/common/client/ClientPingRuntime")
			&& call.name.equals("onMouseCursorEntered") && call.desc.equals("()V")));
		assertFalse(calls.stream().anyMatch(call -> call.name.equals("onMouseCaptureChanged")), "entry is not a capture reset");
		assertEquals(void.class, ClientPingRuntime.class.getDeclaredMethod("onMouseCursorEntered").getReturnType());
		var resets = bridge.methods.stream().filter(method -> annotations(method.visibleAnnotations, method.invisibleAnnotations).stream()
			.anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
				&& ((List<?>) value(annotation, "method")).contains("setIgnoreFirstMove"))).toList();
		assertEquals(1, resets.size());
		assertTrue(java.util.stream.StreamSupport.stream(resets.getFirst().instructions.spliterator(), false)
			.anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.name.equals("onMouseCaptureChanged")),
			"explicit resets retain the old re-prime delegate");
	}
	@Test void releaseRedirectTargetsTheActualVanillaWarpThenModeCallAndGrabGuardKeepsRawPressDelivery() throws Exception {
		ClassNode vanilla = readClass(MouseHandler.class, "MouseHandler.class");
		ClassNode bridge = readClass(MouseHandlerMixin.class, "MouseHandlerMixin.class");
		var release = vanilla.methods.stream().filter(method -> method.name.equals("releaseMouse") && method.desc.equals("()V"))
			.findFirst().orElseThrow();
		var calls = new ArrayList<MethodInsnNode>();
		for (var instruction : release.instructions) if (instruction instanceof MethodInsnNode call
			&& call.owner.equals("com/mojang/blaze3d/platform/InputConstants") && call.name.equals("grabOrReleaseMouse")) calls.add(call);
		assertEquals(1, calls.size()); assertEquals("(JIDD)V", calls.getFirst().desc);
		var redirected = bridge.methods.stream().filter(method -> method.name.equals("pingforit$releaseCursor")).findFirst().orElseThrow();
		assertEquals("(JIDD)V", redirected.desc);
		var redirect = annotations(redirected.visibleAnnotations, redirected.invisibleAnnotations).stream()
			.filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")).findFirst().orElseThrow();
		assertEquals(List.of("releaseMouse"), value(redirect, "method"));
		var at = (AnnotationNode) value(redirect, "at");
		assertEquals("INVOKE", value(at, "value"));
		assertEquals("Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(JIDD)V", value(at, "target"));

		ClassNode input = readClass(com.mojang.blaze3d.platform.InputConstants.class, "InputConstants.class");
		var modeAndWarp = input.methods.stream().filter(method -> method.name.equals("grabOrReleaseMouse")).findFirst().orElseThrow();
		var nativeCalls = new ArrayList<String>();
		for (var instruction : modeAndWarp.instructions) if (instruction instanceof MethodInsnNode call
			&& call.owner.equals("org/lwjgl/glfw/GLFW")) nativeCalls.add(call.name);
		assertEquals(List.of("glfwSetCursorPos", "glfwSetInputMode"), nativeCalls,
			"the scoped disabled release retains the actual 1.21.1 position-before-mode ordering");
		assertEquals(void.class, WheelMouseCapture.class.getDeclaredMethod("releaseCursor", long.class, int.class, double.class, double.class).getReturnType());
		for (String boundary : List.of("before", "after")) {
			var scopeHook = bridge.methods.stream().filter(method -> method.name.equals("pingforit$" + boundary + "Release")).findFirst().orElseThrow();
			var scopeInject = annotations(scopeHook.visibleAnnotations, scopeHook.invisibleAnnotations).stream()
				.filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
			assertEquals(List.of("releaseMouse"), value(scopeInject, "method"));
			assertEquals(boundary.equals("before") ? "HEAD" : "TAIL", value((AnnotationNode) ((List<?>) value(scopeInject, "at")).getFirst(), "value"));
			assertTrue(java.util.stream.StreamSupport.stream(scopeHook.instructions.spliterator(), false)
				.anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals("nx/pingwheel/common/client/WheelMouseCapture")
					&& call.name.equals(boundary + "VanillaRelease")), "nested ordinary releases have their own call scope");
		}

		var guard = bridge.methods.stream().filter(method -> method.name.equals("pingforit$keepSelectorMouseFree")).findFirst().orElseThrow();
		var pressRedirect = annotations(guard.visibleAnnotations, guard.invisibleAnnotations).stream()
			.filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")).findFirst().orElseThrow();
		assertEquals(List.of("onPress(JIII)V"), value(pressRedirect, "method"));
		assertEquals("Lnet/minecraft/client/MouseHandler;grabMouse()V", value((AnnotationNode) value(pressRedirect, "at"), "target"));
		assertEquals("(Lnet/minecraft/client/MouseHandler;)V", guard.desc);
		var press = vanilla.methods.stream().filter(method -> method.name.equals("onPress") && method.desc.equals("(JIII)V"))
			.findFirst().orElseThrow();
		var pressCalls = new ArrayList<String>();
		for (var instruction : press.instructions) if (instruction instanceof MethodInsnNode call
			&& (call.owner.equals("net/minecraft/client/MouseHandler") && call.name.equals("grabMouse")
				|| call.owner.equals("net/minecraft/client/KeyMapping") && call.name.equals("set"))) pressCalls.add(call.name);
		assertEquals(List.of("grabMouse", "set"), pressCalls, "a vanilla auto-grab precedes raw key delivery");
		assertFalse(bridge.methods.stream().flatMap(method -> annotations(method.visibleAnnotations, method.invisibleAnnotations).stream())
			.filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
				&& Boolean.TRUE.equals(value(annotation, "cancellable")))
			.anyMatch(annotation -> ((List<?>) value(annotation, "method")).stream()
				.anyMatch(method -> method.toString().startsWith("onPress"))),
			"raw presses/releases must still reach KeyMapping, including a mouse-bound ping release");
	}

	@Test void selectionTogglesObserveThePhysicalActionAndPreserveTheNativeClickArgument() throws Exception {
		assertNativeToggleHooks(KeyboardHandler.class, KeyboardHandlerMixin.class, "keyPress", "(JIIII)V", 5);
		assertNativeToggleHooks(MouseHandler.class, MouseHandlerMixin.class, "onPress", "(JIII)V", 4);
	}

	private static void assertNativeToggleHooks(Class<?> handler, Class<?> mixin, String callbackName,
		String callbackSignature, int physicalActionSlot) throws Exception {
		String keyType = "Lcom/mojang/blaze3d/platform/InputConstants$Key;";
		String clickSignature = "(" + keyType + ")V";
		String keyMapping = "net/minecraft/client/KeyMapping";
		var callback = readClass(handler, handler.getSimpleName() + ".class").methods.stream()
			.filter(method -> method.name.equals(callbackName) && method.desc.equals(callbackSignature))
			.findFirst().orElseThrow();
		assertEquals(1, calls(callback).stream().filter(call -> call.owner.equals(keyMapping)
			&& call.name.equals("click") && call.desc.equals(clickSignature)).count());

		var bridge = readClass(mixin, mixin.getSimpleName() + ".class");
		var begin = method(bridge, "pingforit$beginToggleInput");
		assertEquals(List.of(callbackName + callbackSignature), value(inject(begin), "method"));
		assertEquals("HEAD", atValue(begin), "the origin must be observed before any UI or loader consumes the event");
		assertNull(value(inject(begin), "cancellable"));
		assertEquals(callbackSignature.replace(")V", "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"), begin.desc);
	assertTrue(java.util.stream.StreamSupport.stream(begin.instructions.spliterator(), false)
			.anyMatch(insn -> insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == physicalActionSlot),
			"observation must read GLFW's action parameter, not the button, key code, or modifiers");
		assertTrue(calls(begin).stream().anyMatch(call -> call.owner.equals("nx/pingwheel/common/util/ToggleInputState")
			&& call.name.equals("beginCallback") && call.desc.equals("(IZZ)V")));

		var claim = method(bridge, "pingforit$claimToggleInput");
		var modify = annotations(claim.visibleAnnotations, claim.invisibleAnnotations).stream()
			.filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;"))
			.findFirst().orElseThrow();
		assertEquals(List.of(callbackName + callbackSignature), value(modify, "method"));
		assertEquals("(" + keyType + ")" + keyType, claim.desc);
		var click = (AnnotationNode) value(modify, "at");
		assertEquals("INVOKE", value(click, "value"));
		assertEquals("L" + keyMapping + ";click" + clickSignature, value(click, "target"));
		assertTrue(calls(claim).stream().anyMatch(call -> call.owner.equals("nx/pingwheel/common/util/ToggleInputState")
			&& call.name.equals("claimMappingClick") && call.desc.equals("(Z)Z")));
		assertTrue(calls(claim).stream().anyMatch(call -> call.owner.equals("nx/pingwheel/common/util/InputUtils")
			&& call.name.equals("handleTogglePress") && call.desc.equals(clickSignature)));
		// Pass through the native key, including scancodes and macOS mouse normalization.
		@Unmodifiable List<@Nullable AbstractInsnNode> returnSources = java.util.stream.StreamSupport.stream(claim.instructions.spliterator(), false)
			.filter(insn -> insn.getOpcode() == Opcodes.ARETURN).map(insn -> insn.getPrevious()).toList();
		assertFalse(returnSources.isEmpty());
		assertTrue(returnSources.stream().allMatch(insn -> insn instanceof VarInsnNode load
			&& load.getOpcode() == Opcodes.ALOAD && load.var == 1));
	}
	@Test void lookInputHooksReadPreUpdateCoordinatesAndBridgeOnlyAfterVanillaMove() throws Exception {
		ClassNode bridge = readClass(MouseHandlerMixin.class, "MouseHandlerMixin.class");
		var begin = method(bridge, "pingforit$lookInputBegin");
		var end = method(bridge, "pingforit$lookInputEnd");

		for (var hook : List.of(begin, end)) {
			assertEquals("(JDDLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", hook.desc);
			var inject = inject(hook);
			assertEquals(List.of("onMove(JDD)V"), value(inject, "method"));
			assertNull(value(inject, "cancellable"), "vanilla look bookkeeping must still run");
		}
		assertEquals("HEAD", atValue(begin));
		assertEquals("TAIL", atValue(end));

		var beginCalls = calls(begin);
		for (String accessor : List.of("xpos", "ypos")) {
			assertTrue(beginCalls.stream().anyMatch(call -> call.owner.equals("net/minecraft/client/MouseHandler")
				&& call.name.equals(accessor) && call.desc.equals("()D")),
				"the delta uses the coordinate before vanilla updates it: " + accessor);
		}
		assertTrue(beginCalls.stream().anyMatch(call -> call.owner.equals("nx/pingwheel/common/client/input/LookInputTracker")
			&& call.name.equals("degreesFromPixels") && call.desc.equals("(DDZ)D")));
		assertTrue(beginCalls.stream().anyMatch(call -> call.name.equals("onLookInputReset")),
			"a focus loss resets the look baseline");
		assertFalse(beginCalls.stream().anyMatch(call -> call.name.equals("onLookInput")),
			"nothing is published before the vanilla callback completes");

		for (var hook : List.of(begin, end)) {
			assertFalse(fieldReads(hook).stream().anyMatch(read -> read.name.equals("accumulatedDX")
				|| read.name.equals("accumulatedDY")),
				"one callback must not reuse the per-frame accumulated movement");
		}

		var endCalls = calls(end);
		assertTrue(endCalls.stream().anyMatch(call -> call.owner.equals("nx/pingwheel/common/CommonClient")
			&& call.name.equals("onLookInput") && call.desc.equals("(DD)V")));
		assertFalse(endCalls.stream().anyMatch(call -> call.name.equals("onMouseMove")),
			"the selector's absolute-position hook remains separate");

		var selector = method(bridge, "pingforit$selectorMove");
		assertEquals("TAIL", atValue(selector));
		assertTrue(calls(selector).stream().anyMatch(call -> call.owner.equals("nx/pingwheel/common/CommonClient")
			&& call.name.equals("onMouseMove") && call.desc.equals("(JDD)V")));

		for (String field : List.of("ignoreFirstMove", "mouseGrabbed")) {
			assertTrue(bridge.fields.stream().anyMatch(fieldNode -> fieldNode.name.equals(field)
				&& fieldNode.desc.equals("Z")), field);
		}
		for (String resetHook : List.of("pingforit$beforeCapture", "pingforit$reprime", "pingforit$cursorEntered")) {
			assertTrue(calls(method(bridge, resetHook)).stream().anyMatch(call -> call.name.equals("onLookInputReset")),
				resetHook + " starts a fresh look baseline");
		}
	}
	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
	}
	private static AnnotationNode inject(MethodNode method) {
		return annotations(method.visibleAnnotations, method.invisibleAnnotations).stream()
			.filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
			.findFirst().orElseThrow();
	}
	private static String atValue(MethodNode method) {
		return (String) value((AnnotationNode) ((List<?>) value(inject(method), "at")).getFirst(), "value");
	}
	private static List<MethodInsnNode> calls(MethodNode method) {
		var calls = new ArrayList<MethodInsnNode>();
		for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call);
		return calls;
	}
	private static List<FieldInsnNode> fieldReads(MethodNode method) {
		var reads = new ArrayList<FieldInsnNode>();
		for (var instruction : method.instructions) if (instruction instanceof FieldInsnNode field) reads.add(field);
		return reads;
	}
	private static ClassNode readClass(Class<?> type, String name) throws Exception {
		ClassNode node = new ClassNode();
		try (var bytes = type.getResourceAsStream(name)) { assertNotNull(bytes); new ClassReader(bytes).accept(node, 0); }
		return node;
	}
	@Test void allEffectiveManifestsRegisterEachHookExactlyOnceOnItsCorrectSide() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		while (!Files.isRegularFile(root.resolve("settings.gradle")) && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) root = root.getParent();
		for (String module : List.of("common", "forge", "neoforge")) {
			var json = JsonParser.parseString(Files.readString(root.resolve(module + "/src/main/resources/pingforit.mixins.json"))).getAsJsonObject();
			var common = json.getAsJsonArray("mixins").asList().stream().map(value -> value.getAsString()).toList();
			var client = json.getAsJsonArray("client").asList().stream().map(value -> value.getAsString()).toList();
			assertEquals(1, common.stream().filter("BaseContainerLockAccessor"::equals).count());
			assertFalse(client.contains("BaseContainerLockAccessor"));
			for (String entry : List.of("MouseHandlerMixin", "KeyboardHandlerMixin", "ClientLevelPreviewAccessor", "BlockStatePredictionPreviewAccessor")) {
				assertEquals(1, client.stream().filter(entry::equals).count(), module + " " + entry);
				assertFalse(common.contains(entry));
			}
		}
	}
	private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
		List<AnnotationNode> all = new ArrayList<>(); if (visible != null) all.addAll(visible); if (invisible != null) all.addAll(invisible); return all;
	}
	private static @Nullable Object value(AnnotationNode annotation, String key) {
		if (annotation.values == null) return null;
		for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
		return null;
	}
}
