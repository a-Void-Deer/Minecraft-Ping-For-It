package nx.pingwheel.common.presentation.inventory.minecraft;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

/** Hook ABI and effective server-side config guards, not a claim that Mixin ran in-game. */
class InventoryLockMixinContractTest {
	@Test void lockAccessorMatchesTheMappedPrivateFieldWithoutSerializingComponents() throws Exception {
		var field = BaseContainerBlockEntity.class.getDeclaredField("lockKey"); assertEquals(LockCode.class, field.getType());
		var method = BaseContainerLockAccessor.class.getDeclaredMethod("pingforit$inventoryLock"); assertEquals(LockCode.class, method.getReturnType());
		ClassNode node = new ClassNode();
		try (var bytes = BaseContainerLockAccessor.class.getResourceAsStream("BaseContainerLockAccessor.class")) { new ClassReader(bytes).accept(node, 0); }
		var accessor = node.methods.stream().filter(m -> m.name.equals("pingforit$inventoryLock")).findFirst().orElseThrow();
		assertEquals("()Lnet/minecraft/world/LockCode;", accessor.desc);
		var annotations = new java.util.ArrayList<org.objectweb.asm.tree.AnnotationNode>();
		if (accessor.visibleAnnotations != null) annotations.addAll(accessor.visibleAnnotations);
		if (accessor.invisibleAnnotations != null) annotations.addAll(accessor.invisibleAnnotations);
		assertTrue(annotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;") && a.values.contains("lockKey")));
	}
	@Test void allEffectiveConfigsRegisterServerSafeAccessor() throws Exception {
		Path root = Path.of("").toAbsolutePath(); while (!Files.isRegularFile(root.resolve("settings.gradle")) && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) root = root.getParent();
		for (String module : new String[] {"common", "forge", "neoforge"}) {
			var json = JsonParser.parseString(Files.readString(root.resolve(module + "/src/main/resources/pingforit.mixins.json"))).getAsJsonObject();
			assertTrue(json.getAsJsonArray("mixins").asList().stream().anyMatch(value -> value.getAsString().equals("BaseContainerLockAccessor")));
			assertFalse(json.getAsJsonArray("client").asList().stream().anyMatch(value -> value.getAsString().equals("BaseContainerLockAccessor")));
		}
	}
}
