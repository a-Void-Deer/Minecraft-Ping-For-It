package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native failure controls and admission ordering, not allocation measurements. */
class InventoryNativeErrorCostTest {
	private static RegistryAccess registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	private static InventoryDetachedItem freeze(ItemStack stack, InventoryResidentAccess raw) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, 4096,
			InventoryItemBudget.root(4096), raw, new InventoryTestNativeAccess());
	}
	private static WrittenBookContent book(Component page) {
		return new WrittenBookContent(Filterable.passThrough("title"), "author", 0,
			List.of(Filterable.passThrough(page)), false);
	}
	@Test void writtenPageUsesActualStableStringLimitNotResidentCodepointCount() {
		Component legal = Component.literal("x".repeat(4096));
		Component escaped = Component.literal("\0".repeat(6000));
		String legalJson = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, legal).result().orElseThrow().toString();
		String escapedJson = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, escaped).result().orElseThrow().toString();
		assertTrue(legalJson.length() > 2048);
		assertTrue(WrittenBookContent.CONTENT_CODEC.encodeStart(NbtOps.INSTANCE, legal).result().isPresent());
		assertTrue(escapedJson.length() > legalJson.length());
		assertTrue(WrittenBookContent.CONTENT_CODEC.encodeStart(NbtOps.INSTANCE, escaped).result().isEmpty());
		assertTrue(WrittenBookContent.CODEC.encodeStart(NbtOps.INSTANCE, book(escaped)).result().isEmpty(),
			"error propagates through Filterable, ListCodec and the outer record");
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.WRITTEN_BOOK_CONTENT, book(legal));
		var frozen = freeze(stack, new InventoryResidentAccessFields());
		assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode());
		var original = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), ((net.minecraft.core.component.PatchedDataComponentMap) stack.getComponents()).asPatch()).result().orElseThrow();
		assertArrayEquals(InventoryItemCodec.canonicalTag(original), frozen.canonicalForCatalog());
		stack.set(DataComponents.WRITTEN_BOOK_CONTENT, book(escaped));
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(stack, new InventoryResidentAccessFields()).identityMode());
	}
	@Test void nativeDisallowedClickFailureIsDistinctFromGuardedStringRefusal() {
		var name = Component.literal("x").withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, "file")));
		assertTrue(ComponentSerialization.FLAT_CODEC.encodeStart(NbtOps.INSTANCE, name).result().isEmpty(),
			"headless/dedicated OPEN_FILE validator is a reachable empty-partial failure");
		var paid = new AtomicInteger();
		var raw = new InventoryResidentAccessFields() {
			@Override public int styleFlags(net.minecraft.network.chat.Style style) { paid.incrementAndGet(); return super.styleFlags(style); }
		};
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, name);
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(stack, raw).identityMode());
		assertTrue(paid.get() > 0, "the native error path was admitted rather than preguard-refused");
		paid.set(0); stack.remove(DataComponents.CUSTOM_NAME);
		var oversized = new WritableBookContent(List.of(Filterable.passThrough("x".repeat(1025))));
		assertTrue(WritableBookContent.CODEC.encodeStart(NbtOps.INSTANCE, oversized).result().isEmpty());
		stack.set(DataComponents.WRITABLE_BOOK_CONTENT, oversized);
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(stack, raw).identityMode());
		assertEquals(0, paid.get());
	}
	@Test void emptyPartialErrorsStayEmptyAndNeverEvaluateMessagesAcrossEveryApCombination() {
		var messages = new AtomicInteger();
		for (boolean leftError : new boolean[] {false, true}) for (boolean rightError : new boolean[] {false, true}) {
			DataResult<Integer> left = leftError ? DataResult.error(() -> { messages.incrementAndGet(); return "left"; }) : DataResult.success(1);
			DataResult<Integer> right = rightError ? DataResult.error(() -> { messages.incrementAndGet(); return "right"; }) : DataResult.success(2);
			var result = left.apply2stable(Integer::sum, right).map(value -> value + 1).flatMap(DataResult::success);
			assertEquals(!leftError && !rightError, result.result().isPresent());
			assertEquals(!leftError && !rightError, result.resultOrPartial().isPresent());
			var builder = net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, registries).listBuilder();
			builder.add(left.map(JsonOps.INSTANCE::createInt)); builder.add(right.map(JsonOps.INSTANCE::createInt));
			assertEquals(!leftError && !rightError, builder.build(JsonOps.INSTANCE.empty()).result().isPresent());
		}
		assertEquals(0, messages.get(), "concatenation suppliers and Error.toString must remain unevaluated");
	}
	@Test void smallCaptureWorkStopsBeforeNativeTextAccountingWithoutIdentityDisplayCoupling() {
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(20000)));
		var nativeCalls = new AtomicInteger();
		var raw = new InventoryResidentAccessFields() {
			@Override public int styleFlags(net.minecraft.network.chat.Style style) { nativeCalls.incrementAndGet(); return super.styleFlags(style); }
		};
		var exhausted = InventoryItemBudget.capture(4096, 1, 1_000_000, 1);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack),
			registries, 4096, exhausted, raw, new InventoryTestNativeAccess()));
		assertEquals(0, nativeCalls.get());
		var enough = freeze(stack, new InventoryResidentAccessFields());
		assertEquals(InventoryIdentityMode.EXACT, enough.identityMode());
		assertNotNull(enough.canonicalForCatalog()); assertFalse(enough.display().aggregateRequired());
	}
}
