package nx.pingwheel.neoforge.platform;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService.ResidentReads;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentItem;
import nx.pingwheel.neoforge.platform.lookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegment.authorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegm.ContentTrap;
import nx.pingwheel.neoforge.platform.lookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegment.authorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegm.MetadataTrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformInventoryResidentTest {
	@Test void synchronousConsumerFreezesBeforeScopeMetadataReusesTheStackAndRevocationStillFails() {
		for (boolean revoke : new boolean[] {false, true}) {
			var stack = new ItemStack(Items.STONE, 7);
			var name = net.minecraft.network.chat.Component.literal("getter-time");
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
			boolean[] returned = {false}; AtomicInteger mutations = new AtomicInteger();
			var handler = new ItemStackHandler(1) {
				@Override public ItemStack getStackInSlot(int slot) { returned[0] = true; return stack; }
			};
			var scope = new nx.pingwheel.common.platform.IPlatformInventoryService.ResidentScope() {
				@Override public net.minecraft.core.Direction face() { return net.minecraft.core.Direction.NORTH; }
				@Override public boolean valid() {
					if (returned[0]) { mutations.incrementAndGet(); stack.setCount(0); name.append("-mutated"); }
					return !returned[0] || !revoke;
				}
				@Override public boolean allows(net.minecraft.world.Container value) { return false; }
				@Override public boolean audited(net.minecraft.world.Container value) { return false; }
				@Override public @org.jetbrains.annotations.Nullable java.util.List<Integer> slots(net.minecraft.world.Container value) { return null; }
			};
			var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true).scopedResidents(scope);
			@org.jetbrains.annotations.Nullable String[] frozen = new String[1];
			nx.pingwheel.common.platform.IPlatformInventoryService.ResidentVisitor visitor = entry -> {
				assertEquals(0, mutations.get()); assertEquals(7L, entry.amount()); assertEquals(7, stack.getCount());
				// Detached explicit-patch oracle; the full freezer is exercised in common.
				frozen[0] = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,
					((InventoryResidentItem.Stack) entry.item()).resident().getComponentsPatch()).getOrThrow().toString();
				return false;
			};
			if (revoke) assertThrows(IllegalStateException.class, () -> access.consumeResident(0, visitor));
			else assertFalse(access.consumeResident(0, visitor));
			assertEquals(1, mutations.get()); assertEquals(0, stack.getCount()); assertEquals("getter-time-mutated", name.getString());
			assertNotNull(frozen[0]); assertTrue(frozen[0].contains("getter-time"));
			assertFalse(frozen[0].contains("mutated"));
		}
	}
	private static final nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess RAW = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess() {
		@Override public java.util.List<?> nonNullList(net.minecraft.core.NonNullList<?> value) {
			try { var field = net.minecraft.core.NonNullList.class.getDeclaredField("list"); field.setAccessible(true); return (java.util.List<?>) field.get(value); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
	};
	@Test void scopedNativeWrappersComposeButForeignDelegatesCannotBeRead() {
		var first = new net.minecraft.world.SimpleContainer(1); var second = new net.minecraft.world.SimpleContainer(1);
		first.setItem(0, new ItemStack(Items.STONE, 2)); second.setItem(0, new ItemStack(Items.DIRT, 3));
		var combined = new net.minecraft.world.CompoundContainer(first, second);
		var scope = new nx.pingwheel.common.platform.IPlatformInventoryService.ResidentScope() {
			@Override public net.minecraft.core.Direction face() { return net.minecraft.core.Direction.NORTH; }
			@Override public boolean valid() { return true; }
			@Override public boolean allows(net.minecraft.world.Container value) { return value == first || value == second || value == combined; }
			@Override public boolean audited(net.minecraft.world.Container value) { return nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.audited(value, this::allows, RAW); }
			@Override public java.util.List<Integer> slots(net.minecraft.world.Container value) { return value == combined ? java.util.List.of(0, 1) : java.util.List.of(0); }
		};
		var access = new PlatformInventoryServiceImpl.HandlerAccess(new net.neoforged.neoforge.items.wrapper.InvWrapper(combined), net.minecraft.core.BlockPos.ZERO, ignored -> true).scopedResidents(scope);
		assertTrue(access.supportsDetachedSlotCapture()); assertEquals(3, access.readResident(1).amount());
		var foreign = new net.minecraft.world.SimpleContainer(1) { @Override public ItemStack getItem(int slot) { throw new AssertionError("foreign content"); } };
		var denied = new PlatformInventoryServiceImpl.HandlerAccess(new net.neoforged.neoforge.items.wrapper.InvWrapper(foreign), net.minecraft.core.BlockPos.ZERO, ignored -> true).scopedResidents(scope);
		assertThrows(IllegalStateException.class, () -> denied.readResident(0));
	}
	@Test void virtualGetInvRedirectCannotReachForeignContentEvenWhenGetSlotsIsOverridden() {
		var admitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger foreignReads = new AtomicInteger(), redirects = new AtomicInteger();
		var foreign = new net.minecraft.world.SimpleContainer(1) {
			@Override public ItemStack getItem(int slot) { foreignReads.incrementAndGet(); throw new AssertionError("foreign content/loot"); }
		};
		var handler = new net.neoforged.neoforge.items.wrapper.InvWrapper(admitted) {
			@Override public net.minecraft.world.Container getInv() { redirects.incrementAndGet(); return foreign; }
			@Override public int getSlots() { return 1; } // keeps the inherited content route reachable
		};
		assertThrows(AssertionError.class, () -> handler.getStackInSlot(0));
		assertEquals(1, foreignReads.get()); foreignReads.set(0); redirects.set(0);
		var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(admitted));
		assertThrows(IllegalStateException.class, () -> access.readResident(0));
		assertTrue(redirects.get() > 0, "the actual virtual delegate must be checked");
		assertEquals(0, foreignReads.get());
	}
	@Test void inheritedContentRechecksTheResolvedDelegateFaceAfterTheLastGetInvCallback() {
		class Inventory extends net.minecraft.world.SimpleContainer implements net.minecraft.world.WorldlyContainer {
			int[] mapping = {0, 1, 2};
			int reads;
			Inventory() { super(3); }
			@Override public int[] getSlotsForFace(net.minecraft.core.Direction face) { assertEquals(net.minecraft.core.Direction.NORTH, face); return mapping.clone(); }
			@Override public ItemStack getItem(int slot) {
				reads++;
				if (slot == 1 && java.util.Arrays.stream(mapping).noneMatch(value -> value == 1)) throw new AssertionError("the revoked face really hides local content");
				return super.getItem(slot);
			}
			@Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
			@Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
		}
		for (boolean resident : new boolean[] {false, true}) {
		var inventory = new Inventory(); inventory.setItem(1, new ItemStack(Items.STONE, 7));
		AtomicInteger contentDispatches = new AtomicInteger();
		var handler = new net.neoforged.neoforge.items.wrapper.InvWrapper(inventory) {
			boolean contentPhase;
			@Override public int getSlots() { contentPhase = true; return 3; }
			@Override public net.minecraft.world.Container getInv() {
				if (contentPhase) { contentDispatches.incrementAndGet(); inventory.mapping = new int[] {2, 0}; }
				return inventory;
			}
		};
		var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(inventory));
			int before = inventory.reads;
			assertThrows(IllegalStateException.class, () -> { if (resident) access.readResident(1); else access.read(1); });
			assertEquals(1, contentDispatches.get(), "each route independently reaches the final getInv revocation");
			assertEquals(before, inventory.reads, "no content may be read after the resolved delegate face is revoked");
		inventory.mapping = new int[] {2, 0};
		assertThrows(AssertionError.class, () -> inventory.getItem(1)); // positive control: hidden local slot
		}
	}
	@Test void inheritedContentReadUsesTheOnceCheckedVirtualDelegate() {
		for (boolean resident : new boolean[] {false, true}) {
			var admitted = new net.minecraft.world.SimpleContainer(1); admitted.setItem(0, new ItemStack(Items.STONE, 7));
			AtomicInteger foreignReads = new AtomicInteger(), contentDispatches = new AtomicInteger();
			var foreign = new net.minecraft.world.SimpleContainer(1) {
				@Override public ItemStack getItem(int slot) { foreignReads.incrementAndGet(); throw new AssertionError("second virtual dispatch escaped the checked delegate"); }
			};
			var handler = new net.neoforged.neoforge.items.wrapper.InvWrapper(admitted) {
				boolean contentPhase;
				@Override public int getSlots() { contentPhase = true; return 1; }
				@Override public net.minecraft.world.Container getInv() {
					return !contentPhase || contentDispatches.incrementAndGet() == 1 ? admitted : foreign;
				}
			};
			var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(admitted));
			assertEquals(7L, resident ? access.readResident(0).amount() : access.read(0).amount());
			assertEquals(1, contentDispatches.get()); assertEquals(0, foreignReads.get());
			assertThrows(AssertionError.class, () -> handler.getStackInSlot(0), "a second native dispatch really reaches the foreign trap");
			assertEquals(1, foreignReads.get());
		}
	}
	private static nx.pingwheel.common.platform.IPlatformInventoryService.ResidentScope layoutlessScope(net.minecraft.world.Container inventory) {
		return new nx.pingwheel.common.platform.IPlatformInventoryService.ResidentScope() {
			@Override public net.minecraft.core.Direction face() { return net.minecraft.core.Direction.NORTH; }
			@Override public boolean valid() { return true; }
			@Override public boolean allows(net.minecraft.world.Container value) { return value == inventory; }
			@Override public boolean audited(net.minecraft.world.Container value) { return false; }
			@Override public java.util.List<Integer> slots(net.minecraft.world.Container value) { return null; }
		};
	}
	@Test void layoutlessUnsidedWrapperCannotWidenTheFaceButSidedWrapperKeepsLocalOrder() {
		class Inventory extends net.minecraft.world.SimpleContainer implements net.minecraft.world.WorldlyContainer {
			int reads;
			Inventory() { super(3); }
			@Override public int[] getSlotsForFace(net.minecraft.core.Direction face) { assertEquals(net.minecraft.core.Direction.NORTH, face); return new int[] {2, 0}; }
			@Override public ItemStack getItem(int slot) { reads++; if (slot == 1) throw new AssertionError("hidden content/loot"); return super.getItem(slot); }
			@Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
			@Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
		}
		var inventory = new Inventory(); inventory.setItem(2, new ItemStack(Items.DIAMOND, 7)); inventory.setItem(0, new ItemStack(Items.DIRT, 11));
		assertThrows(AssertionError.class, () -> inventory.getItem(1)); inventory.reads = 0;
		var scope = layoutlessScope(inventory);
		var denied = new PlatformInventoryServiceImpl.HandlerAccess(new net.neoforged.neoforge.items.wrapper.InvWrapper(inventory), BlockPos.ZERO, ignored -> true).scopedResidents(scope);
		assertThrows(IllegalStateException.class, () -> denied.readResident(1)); assertEquals(0, inventory.reads);
		var allowed = new PlatformInventoryServiceImpl.HandlerAccess(new net.neoforged.neoforge.items.wrapper.SidedInvWrapper(inventory, net.minecraft.core.Direction.NORTH), BlockPos.ZERO, ignored -> true).scopedResidents(scope);
		assertFalse(allowed.supportsDetachedSlotCapture());
		var first = allowed.readResident(0); var second = allowed.readResident(1);
		assertSame(Items.DIAMOND, ((InventoryResidentItem.Stack) first.item()).resident().getItem());
		assertSame(Items.DIRT, ((InventoryResidentItem.Stack) second.item()).resident().getItem());
		assertEquals(7L, first.amount()); assertEquals(11L, second.amount()); assertEquals(2, inventory.reads);
	}
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void changedProtectedHelperIsClassifiedWithoutCallingItAndLiveReadKeepsExternalTrust() {
		AtomicInteger calls = new AtomicInteger();
		var handler = new ItemStackHandler(1) {
			@Override protected void validateSlotIndex(int slot) { calls.incrementAndGet(); throw new IllegalStateException("helper trap"); }
		};
		var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true);
		assertFalse(access.supportsDetachedSlotCapture()); assertEquals(0, calls.get());
		assertThrows(IllegalStateException.class, () -> access.readResident(0)); assertEquals(1, calls.get());
	}
	@Test void writeSideOverrideKeepsTheNativeReadFamilyAndResidentObject() {
		var stack = new ItemStack(Items.STONE, 11);
		var handler = new ItemStackHandler(1) { @Override protected void onContentsChanged(int slot) {} };
		assertTrue(ResidentReads.inherits(handler, ItemStackHandler.class, "getSlots"));
		assertTrue(ResidentReads.inherits(handler, ItemStackHandler.class, "getStackInSlot", int.class));
		assertTrue(ResidentReads.inherits(handler, ItemStackHandler.class, "validateSlotIndex", int.class));
		handler.setStackInSlot(0, stack);
		var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true, RAW);
		assertTrue(access.supportsDetachedSlotCapture(), "field-equivalent raw injection enables native backing audit headlessly");
		var entry = access.readResident(0);
		assertEquals(11, entry.amount()); assertSame(stack, ((InventoryResidentItem.Stack) entry.item()).resident());
	}
	@Test void overlongContentTrapRefusesBeforeForeignItemRuns() {
		var directAdmitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger directItems = new AtomicInteger();
		var direct = new ContentTrap(directAdmitted, foreignItemTrap(directItems));
		assertTrue(direct.getClass().getName().length() > ResidentReads.MAX_TYPE_NAME_CHARS);
		assertEquals(ResidentReads.MethodOwnership.UNCLASSIFIED,
			ResidentReads.methodOwnership(direct, net.neoforged.neoforge.items.wrapper.InvWrapper.class, "getStackInSlot", int.class));
		assertEquals(ResidentReads.MethodOwnership.UNCLASSIFIED,
			ResidentReads.methodOwnership(direct, net.neoforged.neoforge.items.wrapper.InvWrapper.class, "getSlots"));
		assertThrows(AssertionError.class, () -> direct.getStackInSlot(0)); // the inherited native content getter really redirects
		assertEquals(1, directItems.get()); assertEquals(1, direct.redirects.get());
		for (boolean resident : new boolean[] {false, true}) {
			var admitted = new net.minecraft.world.SimpleContainer(1);
			AtomicInteger items = new AtomicInteger();
			var handler = new ContentTrap(admitted, foreignItemTrap(items));
			var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(admitted));
			assertEquals("inventory wrapper ownership unclassified",
				assertThrows(IllegalStateException.class, () -> access.slots()).getMessage());
			assertEquals("inventory wrapper ownership unclassified",
				assertThrows(IllegalStateException.class, () -> access.supportsDetachedSlotCapture()).getMessage());
			assertEquals("inventory wrapper ownership unclassified",
				assertThrows(IllegalStateException.class, () -> { if (resident) access.readResident(0); else access.read(0); }).getMessage());
			assertEquals(0, items.get());
			assertEquals(0, handler.redirects.get(), "no virtual delegate dispatch may precede the refusal");
		}
	}
	@Test void overlongMetadataTrapRefusesBeforeForeignContainerSizeRuns() {
		var directAdmitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger directSizes = new AtomicInteger(), directItems = new AtomicInteger();
		var direct = new MetadataTrap(directAdmitted, foreignSizeTrap(directSizes, directItems));
		assertTrue(direct.getClass().getName().length() > ResidentReads.MAX_TYPE_NAME_CHARS);
		assertEquals(ResidentReads.MethodOwnership.UNCLASSIFIED,
			ResidentReads.methodOwnership(direct, net.neoforged.neoforge.items.wrapper.InvWrapper.class, "getStackInSlot", int.class));
		assertEquals(ResidentReads.MethodOwnership.UNCLASSIFIED,
			ResidentReads.methodOwnership(direct, net.neoforged.neoforge.items.wrapper.InvWrapper.class, "getSlots"));
		assertThrows(AssertionError.class, () -> direct.getSlots()); // the inherited native slot-count really reaches the trap
		assertEquals(1, directSizes.get()); assertEquals(1, direct.redirects.get());
		for (boolean resident : new boolean[] {false, true}) {
			var admitted = new net.minecraft.world.SimpleContainer(1);
			AtomicInteger sizes = new AtomicInteger(), items = new AtomicInteger();
			var handler = new MetadataTrap(admitted, foreignSizeTrap(sizes, items));
			var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(admitted));
			assertEquals("inventory wrapper ownership unclassified",
				assertThrows(IllegalStateException.class, () -> access.slots()).getMessage());
			assertEquals("inventory wrapper ownership unclassified",
				assertThrows(IllegalStateException.class, () -> { if (resident) access.readResident(0); else access.read(0); }).getMessage());
			assertEquals(0, sizes.get(), "foreign container size/loot must not run before the scoped refusal");
			assertEquals(0, items.get());
			assertEquals(0, handler.redirects.get(), "no virtual delegate dispatch may precede the refusal");
		}
	}
	@Test void shortStackOverrideDoesNotCertifyInheritedSlots() {
		var admitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger sizes = new AtomicInteger(), items = new AtomicInteger(), redirects = new AtomicInteger();
		var foreign = foreignSizeTrap(sizes, items);
		var handler = new net.neoforged.neoforge.items.wrapper.InvWrapper(admitted) {
			@Override public ItemStack getStackInSlot(int slot) { return new ItemStack(Items.GOLD_INGOT, 4); }
			@Override public net.minecraft.world.Container getInv() { redirects.incrementAndGet(); return foreign; }
		};
		assertEquals(ResidentReads.MethodOwnership.PROVEN_OVERRIDE,
			ResidentReads.methodOwnership(handler, net.neoforged.neoforge.items.wrapper.InvWrapper.class, "getStackInSlot", int.class));
		assertEquals(ResidentReads.MethodOwnership.PROVEN_INHERITED,
			ResidentReads.methodOwnership(handler, net.neoforged.neoforge.items.wrapper.InvWrapper.class, "getSlots"));
		assertThrows(AssertionError.class, () -> handler.getSlots()); // direct control: the inherited size path reaches the trap
		assertEquals(1, sizes.get()); assertEquals(1, redirects.get());
		sizes.set(0); redirects.set(0);
		var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(admitted));
		assertEquals("known inventory wrapper delegate changed",
			assertThrows(IllegalStateException.class, () -> access.slots()).getMessage());
		assertEquals(0, sizes.get(), "a redirected delegate is refused before any size metadata");
		assertEquals(1, redirects.get(), "the identity check resolves the virtual delegate once");
	}
	@Test void shortProvenOverrideKeepsDirectExternalTrustContent() {
		for (boolean resident : new boolean[] {false, true}) {
			var admitted = new net.minecraft.world.SimpleContainer(1);
			AtomicInteger overrideReads = new AtomicInteger();
			var handler = new net.neoforged.neoforge.items.wrapper.InvWrapper(admitted) {
				@Override public ItemStack getStackInSlot(int slot) { overrideReads.incrementAndGet(); return new ItemStack(Items.GOLD_INGOT, 4); }
				@Override public int getSlots() { return 1; }
			};
			assertEquals(ResidentReads.MethodOwnership.PROVEN_OVERRIDE,
				ResidentReads.methodOwnership(handler, net.neoforged.neoforge.items.wrapper.InvWrapper.class, "getStackInSlot", int.class));
			var access = new PlatformInventoryServiceImpl.HandlerAccess(handler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(admitted));
			assertEquals(4L, resident ? access.readResident(0).amount() : access.read(0).amount());
			assertEquals(1, overrideReads.get(), "a proven override is read directly, not through the raw delegate");
		}
	}
	private static net.minecraft.world.SimpleContainer foreignItemTrap(AtomicInteger items) {
		return new net.minecraft.world.SimpleContainer(1) {
			@Override public ItemStack getItem(int slot) { items.incrementAndGet(); throw new AssertionError("foreign content/loot"); }
		};
	}
	@Test void statefulGetInvSizeRedirectCannotPrecedeSavedDelegateAuthority() {
		// Direct native control: the admitted first getInv return is followed by
		// the native size dispatch, which really reaches the foreign size trap.
		var controlAdmitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger controlSizes = new AtomicInteger(), controlItems = new AtomicInteger(), controlDispatches = new AtomicInteger();
		var control = statefulMixedWrapper(controlAdmitted, foreignSizeTrap(controlSizes, controlItems), controlDispatches, 1);
		assertSame(controlAdmitted, control.getInv());
		assertThrows(AssertionError.class, () -> control.getSlots());
		assertEquals(1, controlSizes.get()); assertEquals(2, controlDispatches.get()); assertEquals(0, controlItems.get());

		// Direct slots: one resolution dispatch, size from the admitted saved delegate.
		var directAdmitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger directSizes = new AtomicInteger(), directItems = new AtomicInteger(), directDispatches = new AtomicInteger();
		var directHandler = statefulMixedWrapper(directAdmitted, foreignSizeTrap(directSizes, directItems), directDispatches, 1);
		var directAccess = new PlatformInventoryServiceImpl.HandlerAccess(directHandler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(directAdmitted));
		assertEquals(1, directAccess.slots());
		assertEquals(1, directDispatches.get(), "the scoped size resolves the delegate exactly once");
		assertEquals(0, directSizes.get()); assertEquals(0, directItems.get());

		// Legacy read: the metadata phase must not redirect to foreign size.
		var legacyAdmitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger legacySizes = new AtomicInteger(), legacyItems = new AtomicInteger(), legacyDispatches = new AtomicInteger();
		var legacyHandler = statefulMixedWrapper(legacyAdmitted, foreignSizeTrap(legacySizes, legacyItems), legacyDispatches, 1);
		var legacyAccess = new PlatformInventoryServiceImpl.HandlerAccess(legacyHandler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(legacyAdmitted));
		assertEquals(4L, legacyAccess.read(0).amount());
		assertEquals(1, legacyDispatches.get()); assertEquals(0, legacySizes.get()); assertEquals(0, legacyItems.get());

		// Resident read: supports check plus one metadata resolution, no third dispatch.
		var residentAdmitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger residentSizes = new AtomicInteger(), residentItems = new AtomicInteger(), residentDispatches = new AtomicInteger();
		var residentHandler = statefulMixedWrapper(residentAdmitted, foreignSizeTrap(residentSizes, residentItems), residentDispatches, 2);
		var residentAccess = new PlatformInventoryServiceImpl.HandlerAccess(residentHandler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(residentAdmitted));
		assertEquals(4L, residentAccess.readResident(0).amount());
		assertEquals(2, residentDispatches.get()); assertEquals(0, residentSizes.get()); assertEquals(0, residentItems.get());

		// supportsDetachedSlotCapture: its classification check does not authorize;
		// a following metadata resolution that redirects refuses before foreign size.
		var supportsAdmitted = new net.minecraft.world.SimpleContainer(1);
		AtomicInteger supportsSizes = new AtomicInteger(), supportsItems = new AtomicInteger(), supportsDispatches = new AtomicInteger();
		var supportsHandler = statefulMixedWrapper(supportsAdmitted, foreignSizeTrap(supportsSizes, supportsItems), supportsDispatches, 1);
		var supportsAccess = new PlatformInventoryServiceImpl.HandlerAccess(supportsHandler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(supportsAdmitted));
		assertFalse(supportsAccess.supportsDetachedSlotCapture());
		assertEquals(1, supportsDispatches.get()); assertEquals(0, supportsSizes.get());
		assertEquals("known inventory wrapper delegate changed",
			assertThrows(IllegalStateException.class, () -> supportsAccess.slots()).getMessage());
		assertEquals(2, supportsDispatches.get()); assertEquals(0, supportsSizes.get()); assertEquals(0, supportsItems.get());
	}
	@Test void mixedOverrideSavedSizeMatchesNativeOrdinaryAndSidedPositives() {
		var ordinary = new net.minecraft.world.SimpleContainer(1);
		var ordinaryHandler = stableMixedWrapper(ordinary);
		var ordinaryAccess = new PlatformInventoryServiceImpl.HandlerAccess(ordinaryHandler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(ordinary));
		assertEquals(ordinaryHandler.getSlots(), ordinaryAccess.slots());
		assertEquals(1, ordinaryAccess.slots());

		class Inventory extends net.minecraft.world.SimpleContainer implements net.minecraft.world.WorldlyContainer {
			Inventory() { super(3); }
			@Override public int[] getSlotsForFace(net.minecraft.core.Direction face) { return new int[] {2, 0}; }
			@Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
			@Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
		}
		var sided = new Inventory();
		var sidedHandler = stableMixedWrapper(sided);
		var sidedAccess = new PlatformInventoryServiceImpl.HandlerAccess(sidedHandler, BlockPos.ZERO, ignored -> true).scopedResidents(layoutlessScope(sided));
		assertEquals(sidedHandler.getSlots(), sidedAccess.slots());
		assertEquals(3, sidedAccess.slots(), "the saved-delegate size keeps the native logical size for a sided face");
	}
	private static net.neoforged.neoforge.items.wrapper.InvWrapper statefulMixedWrapper(
		net.minecraft.world.Container admitted, net.minecraft.world.Container foreign, AtomicInteger dispatches, int allowedChecks) {
		return new net.neoforged.neoforge.items.wrapper.InvWrapper(admitted) {
			@Override public ItemStack getStackInSlot(int slot) { return new ItemStack(Items.GOLD_INGOT, 4); }
			@Override public net.minecraft.world.Container getInv() {
				return dispatches.incrementAndGet() <= allowedChecks ? admitted : foreign;
			}
		};
	}
	private static net.neoforged.neoforge.items.wrapper.InvWrapper stableMixedWrapper(net.minecraft.world.Container admitted) {
		return new net.neoforged.neoforge.items.wrapper.InvWrapper(admitted) {
			@Override public ItemStack getStackInSlot(int slot) { return new ItemStack(Items.GOLD_INGOT, 4); }
		};
	}
	private static net.minecraft.world.SimpleContainer foreignSizeTrap(AtomicInteger sizes, AtomicInteger items) {
		return new net.minecraft.world.SimpleContainer(1) {
			@Override public int getContainerSize() { sizes.incrementAndGet(); throw new AssertionError("foreign container size/loot unpack"); }
			@Override public ItemStack getItem(int slot) { items.incrementAndGet(); throw new AssertionError("foreign content/loot"); }
		};
	}
}
