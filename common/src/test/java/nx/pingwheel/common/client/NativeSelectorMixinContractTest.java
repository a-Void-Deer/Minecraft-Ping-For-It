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
