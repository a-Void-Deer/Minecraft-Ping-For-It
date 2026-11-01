package nx.pingwheel.neoforge.integration.create;

import java.util.List;
import java.util.Map;
import nx.pingwheel.neoforge.integration.create.presentation.CreateFluidTankAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
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
		for (@Nullable String version : new String[] {null, "6.0.11", "6.0.10-282", "6.0.10", "6.0.10-281"}) {
			try (var mods = new Mods(version)) {
				if (version != null) {
					var info = net.neoforged.fml.ModList.get().getMods().getFirst();
					field(net.neoforged.fml.ModList.class, "indexedMods").set(net.neoforged.fml.ModList.get(), Map.of("create", new Container(info)));
				}
				assertEquals("6.0.10".equals(version) || "6.0.10-281".equals(version), CreateFluidTankAccess.available());
			}
		}
	}

	@Test void indexedAvailabilityDoesNotEnumerate512UnrelatedModsAndStillObservesVersionChanges() throws Exception {
		try (var mods = new Mods("6.0.10")) {
			var list = net.neoforged.fml.ModList.get(); var original = list.getMods().getFirst();
			var unrelated = new java.util.ArrayList<net.neoforged.neoforgespi.language.IModInfo>();
			var indexed = new java.util.HashMap<String, net.neoforged.fml.ModContainer>();
			var callbacks = new java.util.concurrent.atomic.AtomicInteger();
			for (int index = 0; index < 512; index++) {
				String id = "unrelated_" + index;
				var info = (net.neoforged.neoforgespi.language.IModInfo) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] {net.neoforged.neoforgespi.language.IModInfo.class}, (proxy, method, arguments) -> {
						if (method.getName().equals("getModId")) { callbacks.incrementAndGet(); return id; }
						throw new AssertionError("unrelated mod metadata inspected: " + method.getName());
					});
				unrelated.add(info); indexed.put(id, new Container(info));
			}
			unrelated.add(original); indexed.put("create", new Container(original));
			field(net.neoforged.fml.ModList.class, "sortedList").set(list, List.copyOf(unrelated));
			field(net.neoforged.fml.ModList.class, "indexedMods").set(list, indexed); callbacks.set(0);
			for (int check = 0; check < 16; check++) assertTrue(CreateFluidTankAccess.available());
			assertEquals(0, callbacks.get(), "bounded ID lookup cannot spend one callback per installed mod");
			try (var wrong = new Mods("6.0.11")) {
				var info = net.neoforged.fml.ModList.get().getMods().getFirst(); indexed.put("create", new Container(info));
			}
			assertFalse(CreateFluidTankAccess.available(), "no successful availability/authority cache");
			indexed.remove("create"); assertFalse(CreateFluidTankAccess.available()); assertEquals(0, callbacks.get());
		}
	}

	@NotNullByDefault
	private static final class Container extends net.neoforged.fml.ModContainer {
		private Container(net.neoforged.neoforgespi.language.IModInfo info) { super(info); }
		@Override public @Nullable net.neoforged.bus.api.IEventBus getEventBus() { return null; }
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

	private static @Unmodifiable List<MethodInsnNode> calls(MethodNode method) {
		var result = new java.util.ArrayList<MethodInsnNode>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
		return List.copyOf(result);
	}
}
