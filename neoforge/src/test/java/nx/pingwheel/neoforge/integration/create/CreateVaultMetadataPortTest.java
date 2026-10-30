package nx.pingwheel.neoforge.integration.create;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executed discovery counters around the real compiled port; optional getters keep their archive bodies. */
@NotNullByDefault
class CreateVaultMetadataPortTest {
	private static final String ACCESS = "nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess";
	private static final String HANDLER = ItemStackHandler.class.getName();
	private static final @Unmodifiable List<String> GETTERS = List.of("getController", "isController", "getWidth", "getHeight", "getMainConnectionAxis", "getInventoryOfBlock");
	@TempDir Path directory;
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	/** No production counter or alternate API: insert increments immediately before actual reflection instructions. */
	@NotNullByDefault
	private static final class PortLoader extends ClassLoader {
		final boolean optional;
		final String method, drift, fieldDrift;
		PortLoader(ClassLoader parent) { this(parent, false, "", "", ""); }
		PortLoader(ClassLoader parent, boolean optional, String method, String drift, String fieldDrift) {
			super(parent); this.optional = optional; this.method = method; this.drift = drift; this.fieldDrift = fieldDrift;
		}
		@Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			boolean port = name.equals(ACCESS) || name.startsWith(ACCESS + "$"), nativeShape = name.equals(HANDLER) && !fieldDrift.isEmpty();
			boolean create = optional && List.of(VAULT, SMART, BLOCK).contains(name);
			if (!port && !nativeShape && !create) return super.loadClass(name, resolve);
			@Nullable Class<?> found = findLoadedClass(name);
			if (found == null) {
				try {
					if (create && drift.equals("linkage") && name.equals(VAULT)) throw new NoClassDefFoundError("deliberate optional linkage failure");
					var node = new ClassNode(); new ClassReader(create ? optional(name) : resource(name)).accept(node, 0);
					if (port) instrument(node);
					if (create) driftMethod(name, node);
					if (nativeShape) driftField(node);
					var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); byte[] bytes = writer.toByteArray();
					found = defineClass(name, bytes, 0, bytes.length);
				} catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
			}
			if (resolve) resolveClass(found); return found;
		}
		private byte[] resource(String name) throws IOException, ClassNotFoundException {
			try (@Nullable InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
				if (input == null) throw new ClassNotFoundException(name); return input.readAllBytes();
			}
		}
		private static void instrument(ClassNode node) {
			String owner = ACCESS.replace('.', '/');
			if (node.name.equals(owner)) for (String counter : List.of("methodDiscoveries", "fieldDiscoveries", "accessibilityChecks"))
				node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, counter, "I", null, null));
			for (var body : node.methods) for (var instruction : body.instructions.toArray()) if (instruction instanceof MethodInsnNode call) {
				@Nullable String counter = call.owner.equals("java/lang/Class") ? switch (call.name) {
					case "getMethod", "getDeclaredMethod" -> "methodDiscoveries";
					case "getDeclaredField" -> "fieldDiscoveries";
					default -> null;
				} : call.owner.equals("java/lang/reflect/Field") && call.name.equals("trySetAccessible") ? "accessibilityChecks" : null;
				if (counter != null) {
					var increment = new InsnList(); increment.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, counter, "I"));
					increment.add(new InsnNode(Opcodes.ICONST_1)); increment.add(new InsnNode(Opcodes.IADD));
					increment.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, counter, "I")); body.instructions.insertBefore(call, increment);
				}
			}
		}
		private void driftMethod(String name, ClassNode node) {
			if (drift.equals("inherited") && name.equals(SMART)) node.methods.add(stub(method, descriptor(method), Opcodes.ACC_PUBLIC));
			if (!name.equals(VAULT)) return;
			if (drift.equals("nonpublic-class")) { node.access &= ~Opcodes.ACC_PUBLIC; return; }
			if (drift.isEmpty() || drift.equals("linkage")) return;
			node.methods.removeIf(m -> m.name.equals(method));
			if (drift.equals("inherited") || drift.equals("missing")) return;
			String descriptor = descriptor(method);
			if (drift.equals("returns")) descriptor = "()Ljava/lang/Object;";
			if (drift.equals("parameter")) descriptor = "(I)" + descriptor.substring(2);
			node.methods.add(stub(method, descriptor, drift.equals("private") ? Opcodes.ACC_PRIVATE
				: Opcodes.ACC_PUBLIC | (drift.equals("static") ? Opcodes.ACC_STATIC : 0)));
		}
		private void driftField(ClassNode node) {
			var field = node.fields.stream().filter(f -> f.name.equals("stacks")).findFirst().orElseThrow();
			switch (fieldDrift) {
				case "missing" -> node.fields.remove(field);
				case "returns" -> field.desc = "Ljava/lang/Object;";
				case "private" -> field.access = Opcodes.ACC_PRIVATE;
				case "public" -> field.access = Opcodes.ACC_PUBLIC;
				case "static" -> field.access |= Opcodes.ACC_STATIC;
				case "final" -> field.access |= Opcodes.ACC_FINAL;
				case "native" -> { }
				default -> throw new AssertionError(fieldDrift);
			}
		}
		int count(String name) throws Exception { return loadClass(ACCESS).getField(name).getInt(null); }
	}
	private static String descriptor(String name) {
		return switch (name) {
			case "getController" -> "()Lnet/minecraft/core/BlockPos;";
			case "isController" -> "()Z";
			case "getWidth", "getHeight" -> "()I";
			case "getMainConnectionAxis" -> "()Lnet/minecraft/core/Direction$Axis;";
			case "getInventoryOfBlock" -> "()Lnet/neoforged/neoforge/items/ItemStackHandler;";
			default -> throw new AssertionError(name);
		};
	}
	private static MethodNode stub(String name, String descriptor, int access) {
		var method = new MethodNode(access, name, descriptor, null, null);
		if (descriptor.endsWith("Z") || descriptor.endsWith("I")) { method.visitInsn(Opcodes.ICONST_0); method.visitInsn(Opcodes.IRETURN); }
		else { method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ARETURN); }
		method.visitMaxs(1, 2); return method;
	}
	@SuppressWarnings("unchecked")
	private static Optional<IPlatformInventoryService.Access> find(ClassLoader loader, ServerLevel level, Predicate<BlockPos> gate) throws Exception {
		return (Optional<IPlatformInventoryService.Access>) loader.loadClass(ACCESS)
			.getMethod("find", ServerLevel.class, BlockPos.class, Direction.class, Predicate.class).invoke(null, level, BlockPos.ZERO, Direction.NORTH, gate);
	}
	private static Object api(ClassLoader loader) throws Exception { return field(Class.forName(ACCESS + "$ApiHolder", true, loader), "API").get(null); }
	private static void initializeInventories(VaultNativeFixture fixture) throws Exception {
		for (var entity : fixture.entities.values()) field(entity.getClass(), "inventory").set(entity, new ItemStackHandler(20));
	}

	@Test void absentAndUntestedVersionsNeverLinkOptionalMetadataOrPerformDiscovery() throws Exception {
		for (@Nullable String version : new String[] {null, "6.0.11", "0"}) try (var mods = new Mods(version)) {
			var optional = new VaultOptionalHarness(); var loader = new PortLoader(optional);
			assertTrue(find(loader, allocate(VaultNativeFixture.ServerWorld.class), ignored -> { throw new AssertionError("version gate precedes world scope"); }).isEmpty());
			assertEquals(0, optional.optionalLoads); assertEquals(0, loader.count("methodDiscoveries")); assertEquals(0, loader.count("fieldDiscoveries"));
		}
	}
	@Test void allSixExactArchiveDeclarationsAndControllerDependencyFailClosedOnDrift() throws Exception {
		try (var mods = new Mods("6.0.10")) {
			var good = new PortLoader(getClass().getClassLoader(), true, "", "", "");
			Object fixed = api(good); assertEquals(6, good.count("methodDiscoveries"));
			assertSame(good.loadClass(VAULT), field(fixed.getClass(), "vault").get(fixed));
			for (String method : GETTERS) for (String drift : List.of("missing", "inherited", "returns", "private", "static", "parameter")) {
				var loader = new PortLoader(getClass().getClassLoader(), true, method, drift, "");
				var first = assertThrows(ExceptionInInitializerError.class, () -> api(loader), method + "/" + drift);
				assertInstanceOf(NoSuchMethodException.class, first.getCause());
				int count = loader.count("methodDiscoveries"); assertThrows(NoClassDefFoundError.class, () -> api(loader));
				assertEquals(count, loader.count("methodDiscoveries"), "failed holder cannot retry or fall back reflectively");
			}
			assertThrows(ExceptionInInitializerError.class, () -> api(new PortLoader(getClass().getClassLoader(), true, "", "nonpublic-class", "")));
			assertThrows(NoClassDefFoundError.class, () -> api(new PortLoader(getClass().getClassLoader(), true, "", "linkage", "")));
		}
	}
	@Test void productionWorldDeclinesDriftedOrForeignDefiningClassWithoutReflectiveFallback() throws Exception {
		try (var mods = new Mods("6.0.10"); var fixture = new VaultNativeFixture(Direction.Axis.X, 1, 1, BlockPos.ZERO)) {
			initializeInventories(fixture);
			for (String drift : List.of("missing", "inherited", "returns", "linkage", "")) {
				var loader = new PortLoader(getClass().getClassLoader(), true, "isController", drift, "");
				assertTrue(find(loader, fixture.serverWorld, ignored -> true).isEmpty(), "foreign same-named class or metadata failure must decline: " + drift);
			}
		}
	}
	@Test void warmArchiveInvocationsDoNoDiscoveryAndObserveEveryCurrentValueAndGate() throws Exception {
		try (var mods = new Mods("6.0.10-281"); var fixture = new VaultNativeFixture(Direction.Axis.X, 1, 2, BlockPos.ZERO)) {
			initializeInventories(fixture); var loader = new PortLoader(fixture.loader);
			var allowed = new java.util.HashSet<>(fixture.entities.keySet());
			var access = assertInstanceOf(IPlatformInventoryService.CertifiedSharedAccess.class, find(loader, fixture.serverWorld, allowed::contains).orElseThrow());
			var layout = access.snapshotLayout().orElseThrow(); Object metadata = api(loader);
			assertEquals(9, loader.count("methodDiscoveries"), "six fixed API declarations plus three exact native ownership declarations");
			assertEquals(0, loader.count("fieldDiscoveries"), "layout/certification do not read backing storage");
			var current = new ItemStackHandler(20); current.setStackInSlot(0, new ItemStack(Items.DIAMOND, 37));
			field(fixture.entities.get(BlockPos.ZERO).getClass(), "inventory").set(fixture.entities.get(BlockPos.ZERO), current);
			assertTrue(access.consumeSharedResident(0, entry -> { assertEquals(37, entry.amount()); return true; }, () -> {}));
			assertEquals(1, loader.count("fieldDiscoveries")); assertEquals(1, loader.count("accessibilityChecks"));
			int discoveries = loader.count("methodDiscoveries");
			for (int iteration = 0; iteration < 16; iteration++) {
				assertEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout));
				current.setStackInSlot(0, new ItemStack(Items.STONE, 40 + iteration)); final int amount = 40 + iteration;
				assertTrue(access.consumeSharedResident(0, entry -> { assertEquals(amount, entry.amount()); return true; }, () -> {}));
			}
			assertSame(metadata, api(loader)); assertEquals(discoveries, loader.count("methodDiscoveries"));
			assertEquals(1, loader.count("fieldDiscoveries")); assertEquals(1, loader.count("accessibilityChecks"));
			var root = fixture.entities.get(BlockPos.ZERO); var width = field(root.getClass(), "radius"); var length = field(root.getClass(), "length");
			width.setInt(root, 2); assertNotEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout)); width.setInt(root, 1);
			length.setInt(root, 1); assertNotEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout)); length.setInt(root, 2);
			var controller = field(root.getClass(), "controller"); controller.set(root, new BlockPos(1, 0, 0));
			assertNotEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout)); controller.set(root, null);
			var state = root.getBlockState(); field(BlockEntity.class, "blockState").set(root, state.setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z));
			assertNotEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout)); field(BlockEntity.class, "blockState").set(root, state);
			field(root.getClass(), "inventory").set(root, new ItemStackHandler(19)); assertNotEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout));
			field(root.getClass(), "inventory").set(root, current); assertEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout));
			allowed.remove(new BlockPos(1, 0, 0)); assertNotEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout));
			assertThrows(IllegalStateException.class, () -> access.consumeSharedResident(0, entry -> { fail("revoked sibling"); return true; }, () -> {}));
			allowed.add(new BlockPos(1, 0, 0)); fixture.unloaded.add(0L); fixture.inventoryEntities = 0;
			assertNotEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout)); assertEquals(0, fixture.inventoryEntities, "unloaded trap precedes BE lookup");
			fixture.unloaded.clear(); try (var absent = new Mods(null)) { assertNotEquals(InventorySharedRead.Check.VALID, access.validateSharedRead(layout)); }
			assertEquals(discoveries, loader.count("methodDiscoveries")); assertEquals(1, loader.count("fieldDiscoveries"));
		}
	}
	@Test void independentDefiningLoadersIgnoreContextProvidersAndRetainOnlyImmutableMetadata() throws Exception {
		var first = new PortLoader(getClass().getClassLoader(), true, "", "", "");
		var second = new PortLoader(getClass().getClassLoader(), true, "", "", "");
		@Nullable ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(new ClassLoader(null) { @Override public Class<?> loadClass(String name) { throw new AssertionError("metadata must not select the TCCL"); } });
		try {
			Object a = api(first), b = api(second); assertNotSame(a, b);
			assertNotSame(field(a.getClass(), "vault").get(a), field(b.getClass(), "vault").get(b));
			for (Object port : List.of(a, b)) for (var member : port.getClass().getDeclaredFields()) {
				assertTrue(Modifier.isFinal(member.getModifiers()));
				assertTrue(member.getType() == Class.class || member.getType() == java.lang.reflect.Method.class, "fixed port retains no world/entity/handler");
			}
			assertEquals(6, first.count("methodDiscoveries")); assertEquals(6, second.count("methodDiscoveries"));
			var handler = new ItemStackHandler(1);
			assertNotNull(backing(first, handler)); assertNotNull(backing(second, handler));
			assertEquals(1, first.count("fieldDiscoveries")); assertEquals(1, second.count("fieldDiscoveries"));
			// The hostile context also proves cold fixed-field discovery never recursively discovers a resident service.
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	private static @Nullable Object backing(PortLoader loader, Object handler) throws Exception {
		var type = loader.loadClass(ACCESS + "$LocalStacks"); var read = type.getDeclaredMethod("read", loader.loadClass(HANDLER)); read.setAccessible(true);
		return read.invoke(null, handler);
	}
	@Test void fixedFieldChecksEveryModifierAndTypeOnceAndReadsCurrentBackingWithoutCachingAudit() throws Exception {
		for (String drift : List.of("missing", "returns", "private", "public", "static", "final")) {
			var loader = new PortLoader(getClass().getClassLoader(), false, "", "", drift);
			Object handler = allocate(loader.loadClass(HANDLER)); assertNull(backing(loader, handler), drift); assertNull(backing(loader, handler));
			assertEquals(1, loader.count("fieldDiscoveries")); assertEquals(0, loader.count("accessibilityChecks"), "wrong shape never attempts deep access");
		}
		var first = new PortLoader(getClass().getClassLoader()); var second = new PortLoader(getClass().getClassLoader());
		var handler = new ItemStackHandler(1); var stacks = field(ItemStackHandler.class, "stacks");
		assertSame(stacks.get(handler), backing(first, handler)); assertSame(stacks.get(handler), backing(second, handler));
		var replacement = NonNullList.withSize(2, ItemStack.EMPTY); stacks.set(handler, replacement); assertSame(replacement, backing(first, handler));
		stacks.set(handler, null); assertNull(backing(first, handler));
		for (var loader : List.of(first, second)) { assertEquals(1, loader.count("fieldDiscoveries")); assertEquals(1, loader.count("accessibilityChecks")); }
		var fieldA = field(first.loadClass(ACCESS + "$LocalStacks"), "FIELD").get(null);
		assertNotSame(fieldA, field(second.loadClass(ACCESS + "$LocalStacks"), "FIELD").get(null));
	}
	@Test void warmFieldNeverCachesNativeBackingApprovalAndNewSubclassStillRequiresOwnershipDiscovery() throws Exception {
		var loader = new PortLoader(getClass().getClassLoader()); var type = loader.loadClass(ACCESS);
		var nativeLocal = type.getDeclaredMethod("nativeLocal", IItemHandler.class, InventoryResidentAccess.class); nativeLocal.setAccessible(true);
		int[] audits = {0}; var raw = new InventoryResidentAccess() {
			@Override public @Nullable List<?> nonNullList(NonNullList<?> value) { audits[0]++; return audits[0] == 1 ? new java.util.ArrayList<>() : new java.util.LinkedList<>(); }
		};
		var handler = new ItemStackHandler(1);
		assertEquals(true, nativeLocal.invoke(null, handler, raw)); assertEquals(false, nativeLocal.invoke(null, handler, raw));
		assertEquals(2, audits[0]); assertEquals(1, loader.count("fieldDiscoveries"));
		int before = loader.count("methodDiscoveries");
		@NotNullByDefault class Replacement extends ItemStackHandler { Replacement() { super(1); } }
		nativeLocal.invoke(null, new Replacement(), raw); assertTrue(loader.count("methodDiscoveries") > before, "arbitrary replacement classes are not assumed warm");
		@NotNullByDefault class Unsafe extends ItemStackHandler {
			@Override public ItemStack getStackInSlot(int slot) { throw new AssertionError("override must not be invoked by classification"); }
		}
		int calls = audits[0]; assertEquals(false, nativeLocal.invoke(null, new Unsafe(), raw)); assertEquals(calls, audits[0], "override refused before backing audit");
		var failing = new InventoryResidentAccess() { @Override public List<?> nonNullList(NonNullList<?> value) { throw new NoClassDefFoundError("raw linkage"); } };
		assertEquals(false, nativeLocal.invoke(null, handler, failing), "a fixed field never replaces the current raw backing check");
		var serviceFailure = new java.util.ServiceConfigurationError("resident provider failure");
		var brokenService = new InventoryResidentAccess() { @Override public List<?> nonNullList(NonNullList<?> value) { throw serviceFailure; } };
		assertSame(serviceFailure, assertThrows(java.lang.reflect.InvocationTargetException.class,
			() -> nativeLocal.invoke(null, handler, brokenService)).getCause(), "service failure cannot be swallowed into an unsafe backing grant");
		var field = field(loader.loadClass(ACCESS + "$LocalStacks"), "FIELD"); assertTrue(Modifier.isFinal(field.getModifiers()));
		assertEquals(java.lang.reflect.Field.class, field.getType());
	}
	@Test void exactButInaccessibleNativeFieldDeclinesOnceWithoutPermissiveFallback() throws Exception {
		String pkg = HANDLER.substring(0, HANDLER.lastIndexOf('.'));
		var descriptor = new ClassWriter(0); descriptor.visit(Opcodes.V21, Opcodes.ACC_MODULE, "module-info", null, null, null);
		var module = descriptor.visitModule("vault.closed.fixture", 0, null); module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
		module.visitExport(pkg.replace('.', '/'), 0); module.visitEnd(); descriptor.visitEnd();
		var handler = new ClassWriter(0); handler.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HANDLER.replace('.', '/'), null, "java/lang/Object", null);
		handler.visitField(Opcodes.ACC_PROTECTED, "stacks", "Lnet/minecraft/core/NonNullList;", null, null).visitEnd(); handler.visitEnd();
		// An exploded module has no VM-held JarFile to prevent Windows TempDir cleanup.
		Path root = directory.resolve("closed"); Path classFile = root.resolve(HANDLER.replace('.', '/') + ".class");
		Files.createDirectories(classFile.getParent()); Files.write(root.resolve("module-info.class"), descriptor.toByteArray());
		Files.write(classFile, handler.toByteArray());
		var configuration = ModuleLayer.boot().configuration().resolve(ModuleFinder.of(root), ModuleFinder.of(), Set.of("vault.closed.fixture"));
		var layer = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()), getClass().getClassLoader());
		var closed = layer.layer().findModule("vault.closed.fixture").orElseThrow(); layer.addReads(closed, getClass().getModule());
		var loader = new PortLoader(layer.layer().findLoader("vault.closed.fixture")); var type = loader.loadClass(HANDLER);
		assertSame(closed, type.getModule()); assertFalse(closed.isOpen(pkg, loader.getUnnamedModule()));
		Object value = allocate(type); assertNull(backing(loader, value)); assertNull(backing(loader, value));
		assertEquals(1, loader.count("fieldDiscoveries")); assertEquals(1, loader.count("accessibilityChecks"));
		assertNull(field(loader.loadClass(ACCESS + "$LocalStacks"), "FIELD").get(null));
	}
}
