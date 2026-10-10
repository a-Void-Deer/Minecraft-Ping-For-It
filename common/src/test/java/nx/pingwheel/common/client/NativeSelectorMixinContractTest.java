package nx.pingwheel.common.client;

import com.google.gson.JsonParser;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MouseHandler;
import nx.pingwheel.common.mixin.MouseHandlerMixin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
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
			.filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
			.anyMatch(annotation -> ((List<?>) value(annotation, "method")).stream()
				.anyMatch(method -> method.toString().startsWith("onPress"))),
			"raw presses/releases must still reach KeyMapping, including a mouse-bound ping release");
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
			for (String entry : List.of("MouseHandlerMixin", "ClientLevelPreviewAccessor", "BlockStatePredictionPreviewAccessor")) {
				assertEquals(1, client.stream().filter(entry::equals).count(), module + " " + entry);
				assertFalse(common.contains(entry));
			}
		}
	}
	private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
		List<AnnotationNode> all = new ArrayList<>(); if (visible != null) all.addAll(visible); if (invisible != null) all.addAll(invisible); return all;
	}
	private static Object value(AnnotationNode annotation, String key) {
		if (annotation.values == null) return null;
		for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
		return null;
	}
}
