package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import java.util.Locale;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.*;
import net.minecraft.server.network.Filterable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native oracles and pre-call traps; these tests do not measure heap/time. */
class InventoryEncodingAdmissionTest {
	private static RegistryAccess registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	private static InventoryDetachedItem freeze(ItemStack stack, InventoryResidentAccess raw) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, 4096,
			InventoryItemBudget.root(4096), raw, new InventoryTestNativeAccess());
	}
	private static void oracle(ItemStack stack) {
		var result = freeze(stack, new InventoryResidentAccessFields());
		assertEquals(InventoryIdentityMode.EXACT, result.identityMode());
		var original = DataComponentMap.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack.getComponents()).result().orElseThrow();
		assertArrayEquals(InventoryItemCodec.canonicalTag(original), result.canonicalForCatalog());
	}
	@Test void unnamedRgbLeafMatchesOriginalFormatterIncludingMaskAndUppercase() {
		for (int rgb : new int[] {0, 1, 15, 16, 255, 256, 0x123abc, 0xffffff, -1, Integer.MIN_VALUE}) {
			assertEquals(String.format(Locale.ROOT, "#%06X", rgb & 0xffffff), InventoryRgbColor.format(rgb));
			assertEquals(TextColor.fromRgb(rgb).serialize(), InventoryRgbColor.format(rgb));
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.literal("RGB").withStyle(style -> style.withColor(rgb)));
			oracle(stack);
		}
		assertEquals("gold", TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GOLD).serialize());
	}
	@Test void exactNativeStringAndFlightRangesPreserveSuccessfulNativeEdges() {
		for (int flight : new int[] {-1, 0, 255}) {
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.FIREWORKS, new Fireworks(flight, List.of())); oracle(stack);
		}
		var invalid = new ItemStack(Items.STONE); invalid.set(DataComponents.FIREWORKS, new Fireworks(256, List.of()));
		assertTrue(Fireworks.CODEC.encodeStart(NbtOps.INSTANCE, invalid.get(DataComponents.FIREWORKS)).result().isEmpty());
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(invalid, new InventoryResidentAccessFields()).identityMode());
		for (int length : new int[] {0, 1024}) {
			var stack = new ItemStack(Items.STONE);
			stack.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(new Filterable<>("x".repeat(length), Optional.of("f".repeat(length)))))); oracle(stack);
		}
		invalid.remove(DataComponents.FIREWORKS);
		invalid.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("x".repeat(1025)))));
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(invalid, new InventoryResidentAccessFields()).identityMode());
	}
	@Test void ordinaryFirstFiveStyleGettersAndPrimitiveIntStreamFamiliesKeepNativeIdentity() {
		for (boolean value : new boolean[] {false, true}) {
			var stack = new ItemStack(Items.STONE);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("bold").withStyle(style -> style.withBold(value)));
			oracle(stack);
		}
		var profile = new ItemStack(Items.STONE);
		profile.set(DataComponents.PROFILE, new ResolvableProfile(Optional.of("Player"),
			Optional.of(new java.util.UUID(Long.MIN_VALUE, Long.MAX_VALUE)), new com.mojang.authlib.properties.PropertyMap()));
		oracle(profile);
		var lodestone = new ItemStack(Items.STONE);
		lodestone.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(
			GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos(-123, 45, 678))), true));
		oracle(lodestone);
	}
	@Test void nonfiniteTranslationRefusesBeforeTextStyleNativeAccess() {
		var raw = new InventoryResidentAccessFields() {
			@Override public int styleFlags(net.minecraft.network.chat.Style style) { throw new AssertionError("native text cost/encode reached"); }
		};
		for (Object number : new Object[] {Float.NaN, Float.POSITIVE_INFINITY, Double.NaN, Double.NEGATIVE_INFINITY}) {
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.translatable("test", number));
			assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(stack, raw).identityMode());
		}
	}
	@Test void lodestoneUnknownPositionSubclassRefusesBeforeCoordinateCallbacks() {
		var position = new BlockPos(1, 2, 3) {
			@Override public int getX() { throw new AssertionError("resident X"); }
			@Override public int getY() { throw new AssertionError("resident Y"); }
			@Override public int getZ() { throw new AssertionError("resident Z"); }
		};
		var value = new LodestoneTracker(Optional.of(GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, position)), true);
		assertThrows(AssertionError.class, () -> LodestoneTracker.CODEC.encodeStart(JsonOps.INSTANCE, value));
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.LODESTONE_TRACKER, value);
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(stack, new InventoryResidentAccessFields()).identityMode());
	}
	@Test void registryProbeAdmissionUsesRawIdentityCardinalityWithoutTraversal() {
		var map = new java.util.IdentityHashMap<Object, Object>(65536);
		Object key = new Object(); map.put(key, key); map.clear();
		assertEquals(20, InventoryRegistryContext.lookupWork(map), "sparse empty get stops at the first empty cell");
		for (int index = 0; index < 257; index++) map.put(new Object(), key);
		assertEquals(8L * 258 + 12, InventoryRegistryContext.lookupWork(map));
		var trap = new java.util.IdentityHashMap<>() {
			@Override public int size() { throw new AssertionError("unknown map callback"); }
		};
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> InventoryRegistryContext.lookupWork(trap));
	}
	@Test void displayExhaustionCannotChangeAlreadyFinalIdentityAndCaptureExhaustionEscapes() {
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(20000)));
		var result = freeze(stack, new InventoryResidentAccessFields());
		assertEquals(InventoryIdentityMode.EXACT, result.identityMode()); assertFalse(result.display().aggregateRequired());
		assertNotNull(result.canonicalForCatalog());
		var exhausted = InventoryItemBudget.capture(4096, 1, 1_000_000, 1);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack),
			registries, 4096, exhausted, new InventoryResidentAccessFields(), new InventoryTestNativeAccess()));
	}
}
