package nx.pingwheel.neoforge.integration.create;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Real archive metadata methods, with only initialization-heavy optional ancestors replaced. */
@NotNullByDefault
final class VaultOptionalHarness extends ClassLoader {
	static final String VAULT = "com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity";
	static final String SMART = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity";
	static final String BLOCK = "com.simibubi.create.content.logistics.vault.ItemVaultBlock";
	private static final String TOPOLOGY = "nx.pingwheel.neoforge.integration.create.CreateVaultTopology";
	private static final @Unmodifiable List<String> ISOLATED = List.of(TOPOLOGY,
		"nx.pingwheel.neoforge.integration.create.NeoForgeCreateVaultBlockPresentationResolver",
		"nx.pingwheel.neoforge.platform.PlatformContextServiceImpl",
		"nx.pingwheel.neoforge.NeoClient",
		"nx.pingwheel.common.platform.IPlatformContextService", "nx.pingwheel.common.marker.MinecraftTargetGroupingResolver",
		"nx.pingwheel.common.core.ServerCore", "nx.pingwheel.common.config.ServerConfig");
	int optionalLoads;
	boolean driftedApi;
	VaultOptionalHarness() { super(VaultOptionalHarness.class.getClassLoader()); }
	@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			@Nullable Class<?> found = findLoadedClass(name);
			if (found == null && (name.equals(VAULT) || name.equals(SMART) || name.equals(BLOCK))) {
				optionalLoads++;
				try {
					byte[] bytes = optional(name);
					if (driftedApi && name.equals(VAULT)) {
						var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); node.methods.removeIf(m -> m.name.equals("getHeight"));
						var writer = new ClassWriter(0); node.accept(writer); bytes = writer.toByteArray();
					}
					found = defineClass(name, bytes, 0, bytes.length);
				}
				catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
			}
			if (found == null && ISOLATED.stream().anyMatch(owner -> name.equals(owner) || name.startsWith(owner + "$"))) {
				try (@Nullable InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
					if (input == null) throw new ClassNotFoundException(name);
					byte[] bytes = input.readAllBytes();
					if (name.equals("nx.pingwheel.common.core.ServerCore")) bytes = creationFactory(bytes);
					if (name.equals("nx.pingwheel.common.config.ServerConfig")) {
						var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
						node.methods.removeIf(m -> m.name.equals("<clinit>"));
						var writer = new ClassWriter(0); node.accept(writer); bytes = writer.toByteArray();
					}
					found = defineClass(name, bytes, 0, bytes.length);
				} catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
			}
			if (found == null) return super.loadClass(name, resolve);
			if (resolve) resolveClass(found); return found;
		}
	}
	private static byte[] creationFactory(byte[] bytes) {
		// Execute the production factory unchanged; omit unrelated static config/network initialization.
		var original = new ClassNode(); new ClassReader(bytes).accept(original, 0);
		original.fields.removeIf(f -> !List.of("MARKER_STORE", "SERVER_CONFIG").contains(f.name));
		original.fields.forEach(f -> f.access &= ~Opcodes.ACC_FINAL);
		original.methods.removeIf(m -> !List.of("markerService", "markerStore").contains(m.name));
		var writer = new ClassWriter(0); original.accept(writer); return writer.toByteArray();
	}
	static ClassNode archive(String name) throws Exception {
		try (var zip = new ZipFile(System.getProperty("pingforit.test.createArtifact"))) {
			var entry = zip.getEntry(name.replace('.', '/') + ".class");
			if (entry == null) throw new AssertionError("missing Create class " + name);
			try (var input = zip.getInputStream(entry)) { var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node; }
		}
	}
	private static byte[] optional(String name) throws Exception {
		var original = archive(name); var copy = new ClassNode(); copy.version = original.version;
		copy.access = Opcodes.ACC_PUBLIC; copy.name = original.name;
		if (name.equals(BLOCK)) {
			copy.superName = Type.getInternalName(VaultNativeFixture.VaultBlock.class);
			var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			constructor.visitVarInsn(Opcodes.ALOAD, 0);
			constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, copy.superName, "<init>", "()V", false);
			constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); copy.methods.add(constructor);
		} else if (name.equals(SMART)) {
			copy.superName = Type.getInternalName(BlockEntity.class);
			copy.fields.add(original.fields.stream().filter(f -> f.name.equals("virtualMode")).findFirst().orElseThrow());
			copy.methods.add(original.methods.stream().filter(m -> m.name.equals("isVirtual")).findFirst().orElseThrow());
			var multi = archive("com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer");
			copy.methods.add(multi.methods.stream().filter(m -> m.name.equals("getMainAxisOf")).findFirst().orElseThrow());
			for (String method : List.of("read", "write")) {
				var empty = new MethodNode(Opcodes.ACC_PROTECTED, method, "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", null, null);
				empty.visitInsn(Opcodes.RETURN); empty.visitMaxs(0, 4); copy.methods.add(empty);
			}
		} else {
			copy.superName = SMART.replace('.', '/');
			for (var f : original.fields) if (List.of("controller", "lastKnownPos", "radius", "length", "updateConnectivity", "inventory").contains(f.name)) copy.fields.add(f);
			for (var m : original.methods) if (List.of("getController", "isController", "getLastKnownPos", "getWidth", "getHeight", "getMainConnectionAxis", "read", "write").contains(m.name)) {
				// Only the optional NBT position helper is replaced; clientPacket branches and inventory calls remain unchanged.
				for (var instruction : m.instructions) if (instruction instanceof MethodInsnNode call
					&& call.owner.equals("net/createmod/catnip/nbt/NBTHelper") && call.name.equals("readBlockPos")) call.owner = Type.getInternalName(NbtPosition.class);
				copy.methods.add(m);
			}
		}
		// Instances are allocated without constructors, just like the native Level fixture.
		var writer = new ClassWriter(0); copy.accept(writer); return writer.toByteArray();
	}
	@NotNullByDefault
	public static final class NbtPosition {
		private NbtPosition() {}
		public static BlockPos readBlockPos(CompoundTag tag, String key) { return net.minecraft.nbt.NbtUtils.readBlockPos(tag, key).orElseThrow(); }
	}
	BlockEntity member(BlockPos position, BlockState state, @Nullable BlockPos controller, int width, int length) throws Exception {
		var entity = (BlockEntity) allocate(loadClass(VAULT));
		field(BlockEntity.class, "worldPosition").set(entity, position);
		field(BlockEntity.class, "blockState").set(entity, state);
		field(BlockEntity.class, "type").set(entity, BlockEntityType.BEEHIVE);
		field(entity.getClass(), "controller").set(entity, controller);
		field(entity.getClass(), "radius").setInt(entity, width);
		field(entity.getClass(), "length").setInt(entity, length);
		return entity;
	}
	static Field field(Class<?> owner, String name) throws Exception { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
	static <T> T allocate(Class<T> type) {
		try {
			var unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	@NotNullByDefault
	static final class Mods implements AutoCloseable {
		final Field instance;
		final @Nullable Object previous;
		Mods(@Nullable String version) throws Exception {
			Class<?> modList = Class.forName("net.neoforged.fml.ModList"), modInfo = Class.forName("net.neoforged.neoforgespi.language.IModInfo");
			instance = field(modList, "INSTANCE"); previous = instance.get(null);
			var list = allocate(modList);
			var mod = Proxy.newProxyInstance(modInfo.getClassLoader(), new Class<?>[] {modInfo}, (p, method, args) -> switch (method.getName()) {
				case "getModId" -> "create";
				case "getVersion" -> Class.forName("org.apache.maven.artifact.versioning.DefaultArtifactVersion").getConstructor(String.class).newInstance(version == null ? "0" : version);
				default -> throw new AssertionError(method);
			});
			field(modList, "sortedList").set(list, version == null ? List.of() : List.of(mod));
			field(modList, "indexedMods").set(list, version == null ? Map.of() : java.util.Collections.singletonMap("create", null));
			instance.set(null, list);
		}
		@Override public void close() throws Exception { instance.set(null, previous); }
	}
}
