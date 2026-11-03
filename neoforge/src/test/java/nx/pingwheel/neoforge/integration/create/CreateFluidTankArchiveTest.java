package nx.pingwheel.neoforge.integration.create;

import java.util.List;
import java.util.Map;
import nx.pingwheel.neoforge.integration.create.presentation.CreateFluidTankAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Audits security-relevant call boundaries in the resolved tested archive; not installed-mod evidence. */
@NotNullByDefault
class CreateFluidTankArchiveTest {
	private static final String TANK = "com.simibubi.create.content.fluids.tank.FluidTankBlockEntity";
	private static final String BOILER = "com.simibubi.create.content.fluids.tank.BoilerData";

	@Test void actualModMetadataGateDeclinesMissingAndUntestedCreateAndAcceptsTheTestedVersion() throws Exception {
		var containers = new Containers();
		for (@Nullable String version : new String[] {null, "6.0.11", "6.0.10-282", "6.0.10", "6.0.10-281"}) {
			try (var mods = new Mods(version)) {
				if (version != null) {
					var list = modList();
					field(list.getClass(), "indexedMods").set(list, Map.of("create", containers.create(firstMod(list))));
				}
				assertEquals("6.0.10".equals(version) || "6.0.10-281".equals(version), CreateFluidTankAccess.available());
			}
		}
	}

	@Test void indexedAvailabilityDoesNotEnumerate512UnrelatedModsAndStillObservesVersionChanges() throws Exception {
		var containers = new Containers();
		try (var mods = new Mods("6.0.10")) {
			var list = modList(); var original = firstMod(list);
			var unrelated = new java.util.ArrayList<Object>();
			var indexed = new java.util.HashMap<String, Object>();
			var callbacks = new java.util.concurrent.atomic.AtomicInteger();
			for (int index = 0; index < 512; index++) {
				String id = "unrelated_" + index;
				var info = java.lang.reflect.Proxy.newProxyInstance(containers.infoType.getClassLoader(),
					new Class<?>[] {containers.infoType}, (proxy, method, arguments) -> {
						if (method.getName().equals("getModId")) { callbacks.incrementAndGet(); return id; }
						throw new AssertionError("unrelated mod metadata inspected: " + method.getName());
					});
				unrelated.add(info); indexed.put(id, containers.create(info));
			}
			unrelated.add(original); indexed.put("create", containers.create(original));
			field(list.getClass(), "sortedList").set(list, List.copyOf(unrelated));
			field(list.getClass(), "indexedMods").set(list, indexed); callbacks.set(0);
			for (int check = 0; check < 16; check++) assertTrue(CreateFluidTankAccess.available());
			assertEquals(0, callbacks.get(), "bounded ID lookup cannot spend one callback per installed mod");
			try (var wrong = new Mods("6.0.11")) {
				indexed.put("create", containers.create(firstMod(modList())));
			}
			assertFalse(CreateFluidTankAccess.available(), "no successful availability/authority cache");
			indexed.remove("create"); assertFalse(CreateFluidTankAccess.available()); assertEquals(0, callbacks.get());
		}
	}

	private static Object modList() throws ReflectiveOperationException {
		return java.util.Objects.requireNonNull(Class.forName("net.neoforged.fml.ModList").getMethod("get").invoke(null));
	}
	private static Object firstMod(Object list) throws ReflectiveOperationException {
		return ((List<?>) list.getClass().getMethod("getMods").invoke(list)).getFirst();
	}

	/** Like Mods, link loader types only at test runtime. Keep the real ModList, constructor and
	 * getModInfo implementation; generate only the concrete event-bus-free fixture subclass. */
	@NotNullByDefault
	private static final class Containers extends ClassLoader {
		private final Class<?> infoType, containerType;
		private Containers() throws ReflectiveOperationException {
			super(CreateFluidTankArchiveTest.class.getClassLoader());
			infoType = Class.forName("net.neoforged.neoforgespi.language.IModInfo", false, getParent());
			Class<?> base = Class.forName("net.neoforged.fml.ModContainer", false, getParent());
			String name = CreateFluidTankArchiveTest.class.getName() + "$GeneratedContainer";
			var writer = new ClassWriter(0);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name.replace('.', '/'), null, Type.getInternalName(base), null);
			writer.visitAnnotation(Type.getDescriptor(NotNullByDefault.class), false).visitEnd();
			String constructor = "(" + Type.getDescriptor(infoType) + ")V";
			var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructor, null, null);
			init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ALOAD, 1);
			init.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(base), "<init>", constructor, false);
			init.visitInsn(Opcodes.RETURN); init.visitMaxs(2, 2); init.visitEnd();
			var bus = writer.visitMethod(Opcodes.ACC_PUBLIC, "getEventBus", Type.getMethodDescriptor(base.getMethod("getEventBus")), null, null);
			bus.visitAnnotation(Type.getDescriptor(Nullable.class), false).visitEnd();
			bus.visitCode(); bus.visitInsn(Opcodes.ACONST_NULL); bus.visitInsn(Opcodes.ARETURN); bus.visitMaxs(1, 1); bus.visitEnd();
			writer.visitEnd(); byte[] bytes = writer.toByteArray(); containerType = defineClass(name, bytes, 0, bytes.length);
		}
		private Object create(Object info) throws ReflectiveOperationException {
			return containerType.getConstructor(infoType).newInstance(info);
		}
	}

	@Test void archiveMetadataHasExactOwnersAndNoHiddenControllerLookupOrContentAccess() throws Exception {
		var tank = archive(TANK); var smart = archive(SMART); var boiler = archive(BOILER);
		for (var signature : Map.of("getController", "()Lnet/minecraft/core/BlockPos;", "isController", "()Z",
			"getLastKnownPos", "()Lnet/minecraft/core/BlockPos;", "getWidth", "()I", "getHeight", "()I",
			"getMainConnectionAxis", "()Lnet/minecraft/core/Direction$Axis;").entrySet()) {
			MethodNode method = tank.methods.stream().filter(body -> body.name.equals(signature.getKey()) && body.desc.equals(signature.getValue())).findFirst().orElseThrow();
			assertEquals(Opcodes.ACC_PUBLIC, method.access, signature.getKey());
			for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call)
				assertTrue(call.owner.equals("net/minecraft/core/BlockPos") && List.of("getX", "getY", "getZ").contains(call.name)
					|| call.owner.equals(tank.name) && call.name.equals("isController"), "metadata reached unadmitted call: " + call.owner + "." + call.name);
		}
		var virtual = smart.methods.stream().filter(body -> body.name.equals("isVirtual") && body.desc.equals("()Z")).findFirst().orElseThrow();
		var active = boiler.methods.stream().filter(body -> body.name.equals("isActive") && body.desc.equals("()Z")).findFirst().orElseThrow();
		for (var method : List.of(virtual, active)) {
			assertEquals(Opcodes.ACC_PUBLIC, method.access);
			for (AbstractInsnNode instruction : method.instructions) {
				assertFalse(instruction instanceof MethodInsnNode, "mode metadata cannot perform hidden callbacks");
				if (instruction instanceof FieldInsnNode field) assertEquals(Opcodes.GETFIELD, field.getOpcode(), "mode checks must not mutate Create");
			}
		}
		var field = tank.fields.stream().filter(value -> value.name.equals("boiler")).findFirst().orElseThrow();
		assertEquals(Opcodes.ACC_PUBLIC, field.access); assertEquals("L" + boiler.name + ";", field.desc);
		var identity = tank.methods.stream().filter(method -> method.name.equals("getTankInventory")
			&& method.desc.equals("()Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;")).findFirst().orElseThrow();
		assertEquals(Opcodes.ACC_PUBLIC, identity.access);
		assertTrue(calls(identity).isEmpty(), "identity-only tank witness cannot read contents or refresh a route");
		var fields = new java.util.ArrayList<FieldInsnNode>();
		for (AbstractInsnNode instruction : identity.instructions) if (instruction instanceof FieldInsnNode read) fields.add(read);
		assertEquals(1, fields.size()); assertEquals(Opcodes.GETFIELD, fields.getFirst().getOpcode());
		assertEquals("tankInventory", fields.getFirst().name);
	}

	@Test void controllerCapabilityBranchStaysLocalWhileMemberBranchHasTheKnownUnguardedLookup() throws Exception {
		var tank = archive(TANK);
		var choose = tank.methods.stream().filter(method -> method.name.equals("handlerForCapability")).findFirst().orElseThrow();
		var lookup = tank.methods.stream().filter(method -> method.name.equals("getControllerBE")
			&& method.desc.equals("()L" + tank.name + ";")).findFirst().orElseThrow();
		assertTrue(calls(lookup).stream().anyMatch(call -> call.name.equals("getBlockEntity")), "positive control: member lookup really can enter the level");
		assertFalse(calls(lookup).stream().anyMatch(call -> List.of("isLoaded", "hasChunkAt", "getChunkNow").contains(call.name)),
			"the member API must not be mistaken for the platform's loaded/member gate");
		var calls = calls(choose);
		assertEquals("isController", calls.getFirst().name);
		int firstControllerLookup = -1;
		for (int index = 0; index < calls.size(); index++) if (calls.get(index).name.equals("getControllerBE")) { firstControllerLookup = index; break; }
		assertTrue(firstControllerLookup >= 0);
		for (var call : calls.subList(1, firstControllerLookup))
			assertTrue(call.owner.equals(BOILER.replace('.', '/')) && List.of("isActive", "createHandler").contains(call.name),
				"verified controller prefix unexpectedly reaches world/content: " + call.owner + "." + call.name);
		// Registration's cached/refreshing lambda receives a Direction, but the tested body never
		// reads local 1; keeping the real frozen face at controller therefore preserves the same view.
		var providers = tank.methods.stream().filter(method -> method.name.startsWith("lambda$registerCapabilities$")).toList();
		assertEquals(1, providers.size());
		for (AbstractInsnNode instruction : providers.getFirst().instructions)
			if (instruction instanceof org.objectweb.asm.tree.VarInsnNode variable) assertNotEquals(1, variable.var, "Create capability became face-dependent");
	}

	@Test void singletonMergeAndMemberUpdatesCanLeaveFormerControllerBoilerStateStale() throws Exception {
		var tank = archive(TANK); var boiler = archive(BOILER);
		var connectivity = archive("com.simibubi.create.api.connectivity.ConnectivityHandler");
		var split = connectivity.methods.stream().filter(method -> method.name.equals("splitMultiAndInvalidate")).findFirst().orElseThrow();
		// A 1x1 singleton returns before removeController/boiler.clear. This exact short circuit
		// is why promoting a new lower controller need not reset the previous singleton's boiler.
		var width = calls(split).stream().filter(call -> call.name.equals("getWidth")).findFirst().orElseThrow();
		var widthStore = nextCode(width); assertEquals(Opcodes.ISTORE, widthStore.getOpcode());
		var widthLoad = nextCode(widthStore); assertEquals(Opcodes.ILOAD, widthLoad.getOpcode());
		var widthOne = nextCode(widthLoad); assertEquals(Opcodes.ICONST_1, widthOne.getOpcode());
		var widthJump = nextCode(widthOne); assertEquals(Opcodes.IF_ICMPNE, widthJump.getOpcode());
		var heightLoad = nextCode(widthJump); assertEquals(Opcodes.ILOAD, heightLoad.getOpcode());
		var heightOne = nextCode(heightLoad); assertEquals(Opcodes.ICONST_1, heightOne.getOpcode());
		var heightJump = nextCode(heightOne); assertEquals(Opcodes.IF_ICMPNE, heightJump.getOpcode());
		assertEquals(Opcodes.RETURN, nextCode(heightJump).getOpcode());
		assertTrue(calls(split).stream().anyMatch(call -> call.name.equals("removeController")), "nonsingleton splitting has a different reset path");
		var remove = tank.methods.stream().filter(method -> method.name.equals("removeController")).findFirst().orElseThrow();
		assertTrue(calls(remove).stream().anyMatch(call -> call.owner.equals(boiler.name) && call.name.equals("clear")));
		var merge = connectivity.methods.stream().filter(method -> method.name.equals("tryToFormNewMultiOfWidth")).findFirst().orElseThrow();
		@Unmodifiable List<String> changes = calls(merge).stream().map(call -> call.name)
			.filter(name -> List.of("splitMultiAndInvalidate", "setController", "notifyMultiUpdated").contains(name)).toList();
		assertEquals(List.of("splitMultiAndInvalidate", "setController", "notifyMultiUpdated"), changes);
		var setController = tank.methods.stream().filter(method -> method.name.equals("setController")).findFirst().orElseThrow();
		for (AbstractInsnNode instruction : setController.instructions) {
			if (instruction instanceof FieldInsnNode field) assertFalse(field.name.equals("boiler"));
			if (instruction instanceof MethodInsnNode call) assertFalse(call.owner.equals(boiler.name), "member assignment does not clear/evaluate the old boiler");
		}
		var notify = tank.methods.stream().filter(method -> method.name.equals("notifyMultiUpdated")).findFirst().orElseThrow();
		assertTrue(calls(notify).stream().anyMatch(call -> call.name.equals("updateBoilerState")));
		var update = tank.methods.stream().filter(method -> method.name.equals("updateBoilerState")).findFirst().orElseThrow();
		assertEquals("isController", calls(update).getFirst().name);
		assertEquals(Opcodes.IFNE, nextCode(calls(update).getFirst()).getOpcode());
		assertEquals(Opcodes.RETURN, nextCode(nextCode(calls(update).getFirst())).getOpcode());
		assertTrue(calls(update).stream().anyMatch(call -> call.owner.equals(boiler.name) && call.name.equals("evaluate")));
		var engine = archive("com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock");
		for (String name : List.of("onPlace", "onRemove")) {
			var callback = engine.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
			assertTrue(calls(callback).stream().anyMatch(call -> call.owner.equals("com/simibubi/create/content/fluids/tank/FluidTankBlock")
				&& call.name.equals("updateBoilerState")), name);
		}
		var block = archive(TANK_BLOCK);
		var forward = block.methods.stream().filter(method -> method.name.equals("updateBoilerState")).findFirst().orElseThrow();
		@Unmodifiable List<String> route = calls(forward).stream().map(call -> call.name)
			.filter(name -> List.of("getControllerBE", "updateBoilerState").contains(name)).toList();
		assertEquals(List.of("getControllerBE", "updateBoilerState"), route, "engine removal updates the current controller, not the former controller's local boiler");
	}

	private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
		@Nullable AbstractInsnNode next = instruction.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return java.util.Objects.requireNonNull(next, "missing archive instruction");
	}

	private static @Unmodifiable List<MethodInsnNode> calls(MethodNode method) {
		var result = new java.util.ArrayList<MethodInsnNode>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
		return List.copyOf(result);
	}
}
