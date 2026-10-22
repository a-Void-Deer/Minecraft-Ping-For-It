package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryItemFailureDiagnosticsTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void canonicalByteRefusalProducesAggregateWithoutChangingSafeIdentityOrLongAmounts() {
		var huge = new ItemStack(Items.STONE);
		var payload = new CompoundTag(); payload.putString("data", "\u0800".repeat(23000));
		huge.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		var safe = catalog.encode(new ItemStack(Items.STONE));
		try (var logs = new InventoryDiagnosticsTestCapture()) {
			var aggregate = catalog.encode(huge);
			assertEquals("", aggregate.key().componentsKey()); assertTrue(aggregate.display().aggregateRequired());
			assertEquals(safe.key(), catalog.encode(new ItemStack(Items.STONE)).key());
			assertEquals(1, logs.events().size());
			assertTrue(logs.messages().contains("operation=inventory/item-identity"));
			assertTrue(logs.messages().contains("canonical byte bound=65536"), logs.messages());
			assertTrue(logs.messages().contains("minecraft:stone")); assertNull(logs.events().getFirst().getThrown());
			long amount = 9_007_199_254_740_993L;
			var source = new InventoryScanner.Source() {
				@Override public int slots() { return 2; }
				@Override public boolean stableCursor() { return true; }
				@Override public boolean stableSnapshot() { return true; }
				@Override public InventoryScanner.Stack read(int slot) { return new InventoryScanner.Stack(slot == 0 ? safe.key() : aggregate.key(), slot == 0 ? amount : 7); }
			};
			var result = new InventoryScanner(source, Set.of(), Set.of("minecraft:stone"), true, 4).step(2);
			assertTrue(result.complete()); assertEquals(amount + 7, result.itemTotals().get("minecraft:stone"));
		}
	}

	@Test void intentionalDisplayStrippingAndCeilingProjectionHaveNoFailureDiagnostic() {
		var stack = new ItemStack(Items.STONE);
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("hidden lore"))));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("n".repeat(5000)));
		try (var logs = new InventoryDiagnosticsTestCapture()) {
			var encoded = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(stack);
			assertFalse(encoded.display().aggregateRequired()); assertFalse(encoded.key().componentsKey().isEmpty());
			assertFalse(encoded.display().displayJson().contains("hidden lore"));
			assertFalse(encoded.display().displayJson().contains("nnnnnnnnnn"));
			assertTrue(logs.events().isEmpty());
		}
	}

	@Test void displayGetterFailuresPropagateToTheSourceOwnerWithoutAnExactResultOrDuplicateReport() {
		for (Throwable failure : List.of(new IllegalStateException("display getter failed", new RuntimeException("display getter root cause")),
			new LinkageError("display getter linkage failure"))) {
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.literal("display").withStyle(net.minecraft.ChatFormatting.BOLD));
			var ordinary = InventoryResidentAccess.load(); var styles = new java.util.concurrent.atomic.AtomicInteger();
			var raw = new InventoryResidentAccessFields() {
				@Override public int styleFlags(net.minecraft.network.chat.Style style) {
					if (styles.incrementAndGet() > 1) {
						if (failure instanceof RuntimeException runtime) throw runtime;
						throw (LinkageError) failure;
					}
					return ordinary.styleFlags(style);
				}
			};
			var input = new InventoryResidentItem.Stack(stack);
			try (var logs = new InventoryDiagnosticsTestCapture()) {
				assertSame(failure, assertThrows(failure.getClass(), () -> InventoryItemCodec.freeze(input, RegistryAccess.EMPTY,
					4096, InventoryItemBudget.root(4096), raw, InventoryItemNativeAccess.load())));
				assertTrue(styles.get() > 1, "the failure follows the identity pass, during display extraction");
				assertTrue(logs.events().isEmpty(), "the source boundary owns the propagated failure report");
			}
		}
	}

	@Test void displayRootRefusalStillKeepsExactIdentityWithUnavailableProjection() {
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.literal("display").withStyle(net.minecraft.ChatFormatting.BOLD));
		var ordinary = InventoryResidentAccess.load(); var styles = new java.util.concurrent.atomic.AtomicInteger();
		var raw = new InventoryResidentAccessFields() {
			@Override public int styleFlags(net.minecraft.network.chat.Style style) {
				if (styles.incrementAndGet() > 1) throw new InventoryItemBudget.RootLimitException("display root bound");
				return ordinary.styleFlags(style);
			}
		};
		try (var logs = new InventoryDiagnosticsTestCapture()) {
			var frozen = InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), RegistryAccess.EMPTY,
				4096, InventoryItemBudget.root(4096), raw, InventoryItemNativeAccess.load());
			assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode()); assertNotNull(frozen.canonicalForCatalog());
			assertNull(frozen.display().displayJson()); assertFalse(frozen.display().aggregateRequired());
			assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("display root bound"));
		}
	}

	@Test void nativeItemDecodeRejectsWithoutRetainingNativeErrorPayloadDetail() {
		var item = new CompoundTag(); item.putInt("Slot", 0); item.putString("id", "minecraft:stone"); item.putInt("count", 1);
		var components = new CompoundTag(); components.putString("minecraft:damage", "not-an-integer"); item.put("components", components);
		var items = new ListTag(); items.add(item);
		var index = InventorySnapshotSchemas.index(items, 1, List.of(0));
		var failure = assertThrows(InventorySnapshotSchemas.ReadFailureException.class, () -> index.read(0, RegistryAccess.EMPTY));
		assertTrue(failure.getMessage().contains("Minecraft items codec rejected"));
		assertFalse(failure.getMessage().contains("not-an-integer"));
	}

	@Test void clientNativeDecodeRefusalReportsBoundedReasonAndKeepsRegistryFallback() {
		String json = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:damage\":\"not-an-integer\"}}";
		try (var logs = new InventoryDiagnosticsTestCapture()) {
			var display = nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.resolve(RegistryAccess.EMPTY,
				"minecraft:stone", "block.minecraft.stone", json);
			assertTrue(display.stack().isEmpty());
			assertEquals(1, logs.events().size()); assertFalse(logs.messages().contains("not-an-integer"), logs.messages());
			assertTrue(logs.messages().contains("stage=persistent display patch"));
			assertTrue(logs.messages().contains("native error detail not evaluated"));
			assertTrue(logs.messages().contains("reason=native-codec-rejected")); assertNull(logs.events().getFirst().getThrown());
		}
	}

	@Test void catalogueGuardAfterSuccessfulFreezeDoesNotClaimAnIdentityAggregate() {
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, 1, InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES);
		catalog.encode(new ItemStack(Items.STONE));
		try (var logs = new InventoryDiagnosticsTestCapture()) {
			assertThrows(InventoryItemCodec.IdentityUnavailableException.class, () -> catalog.encode(new ItemStack(Items.DIRT)));
			assertEquals(1, catalog.displays().size()); assertTrue(logs.events().isEmpty(), "a catalogue failure belongs to its source-read owner, not item identity fallback");
		}
	}
}
