package nx.pingwheel.neoforge.integration.create;

import com.google.gson.Gson;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Separate provider-only reflection baseline, NOT the common NativePort/runtime/ServerPlayer workload. */
@NotNullByDefault
class CertifiedVaultReflectionBaselineTest {
	private static volatile @Nullable Object escape;
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static byte @Unmodifiable [] methodBytes(MethodNode method) {
		var writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "GetterEvidence", null, "java/lang/Object", null);
		method.accept(writer); writer.visitEnd(); return writer.toByteArray();
	}
	@Test void actualArchiveGetterBodiesRetainOwnersAndNativeInventoryThroughProductionMinecraftMemberReflection() throws Exception {
		var original = archive(VAULT); var defined = new ClassNode(); new ClassReader(optional(VAULT)).accept(defined, 0);
		for (String name : new String[] {"getController", "isController", "getWidth", "getHeight", "getMainConnectionAxis", "getInventoryOfBlock"}) {
			var a = original.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
			var b = defined.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
			assertArrayEquals(methodBytes(a), methodBytes(b), "archive getter code/signature must not be replaced with a cheap fake");
		}
		try (var mods = new Mods("6.0.10-281"); var f = new VaultNativeFixture(Direction.Axis.X, 3, 9, BlockPos.ZERO)) {
			int address = 0;
			for (var entry : f.entities.entrySet()) {
				var storage = new ItemStackHandler(20);
				for (int slot = 0; slot < 20; slot++) storage.setStackInSlot(slot, new ItemStack(Items.STONE, 3_000_003 + address++));
				field(entry.getValue().getClass(), "inventory").set(entry.getValue(), storage);
				var getter = entry.getValue().getClass().getMethod("getInventoryOfBlock");
				assertEquals(VAULT, getter.getDeclaringClass().getName()); assertSame(storage, getter.invoke(entry.getValue()));
			}
			ClassLoader previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(f.loader);
			try {
				Class<?> type = f.loader.loadClass("nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess");
				@SuppressWarnings("unchecked") var found = (Optional<IPlatformInventoryService.Access>) type.getMethod("find", ServerLevel.class, BlockPos.class, Direction.class).invoke(null, f.serverWorld, BlockPos.ZERO, Direction.NORTH);
				var access = (IPlatformInventoryService.CertifiedSharedAccess) found.orElseThrow();
				assertEquals(InventorySharedRead.Route.CREATE_VAULT_LOCAL, access.sharedReadRoute()); assertEquals(1620, access.slots());
				var layout = access.snapshotLayout().orElseThrow(); assertEquals(81, layout.members().size());
				assertEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout));
				var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean(); assertTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
				// Metadata-only 32-operation loop isolates fixed getMethod/Field discovery cost.
				// It does not claim common authority, detachment, content getters or source-proof registry composition.
				for (int warmup = 0; warmup < 3; warmup++) metadata32(access, layout);
				f.inventoryLoaded = f.inventoryEntities = f.inventoryStates = 0; metadata32(access, layout);
				System.out.println("CERTIFIED_VAULT_JSON " + new Gson().toJson(Map.of("kind", "reflectionMetadataCounters", "operations", 32,
					"loaded", f.inventoryLoaded, "entities", f.inventoryEntities, "states", f.inventoryStates, "route", access.sharedReadRoute().name())));
				long[] bytes = new long[5], nanos = new long[5];
				for (int sample = 0; sample < 5; sample++) {
					long thread = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
					metadata32(access, layout); nanos[sample] = System.nanoTime() - start; bytes[sample] = bean.getThreadAllocatedBytes(thread) - before;
				}
				System.out.println("CERTIFIED_VAULT_JSON " + new Gson().toJson(Map.of("kind", "reflectionMetadataSamples", "jdk", System.getProperty("java.runtime.version"), "allocatedBytes", bytes, "elapsedNanos", nanos,
					"boundary", "production MinecraftWorld/MinecraftMember; unchanged real Create archive metadata/getInventoryOfBlock bodies; native inventory initialized; synthetic optional ancestors and ServerLevel; metadata-only 32 validations")));
				Arrays.sort(bytes); Arrays.sort(nanos); System.out.println("CERTIFIED_VAULT_JSON " + new Gson().toJson(Map.of("kind", "reflectionMetadataDistribution", "bytesMinMedianMax", new long[] {bytes[0], bytes[2], bytes[4]}, "nanosMinMedianMax", new long[] {nanos[0], nanos[2], nanos[4]})));
			} finally { Thread.currentThread().setContextClassLoader(previous); escape = null; }
		}
	}
	static void metadata32(IPlatformInventoryService.CertifiedSharedAccess access, nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout layout) {
		for (int operation = 0; operation < 32; operation++) {
			var result = access.validateSharedRead(layout); if (result != InventorySharedRead.Check.VALID) throw new AssertionError(result); escape = result;
		}
	}
}
