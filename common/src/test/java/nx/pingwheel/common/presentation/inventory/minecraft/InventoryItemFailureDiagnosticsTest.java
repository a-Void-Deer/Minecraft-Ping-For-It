package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
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
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryItemFailureDiagnosticsTest {
	@Test void sharedDiagnosticScopeIsolatesLazyChannelsAndRestoresOuterSuppression() {
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			var refusal = new IllegalStateException("owned codec failure");
			InventoryItemDiagnostics.refusal("inventory/item-identity", "minecraft:stone", refusal);
			InventoryItemDiagnostics.refusal("inventory/item-identity", "minecraft:stone", refusal);
			assertEquals(1, logs.events().size());
			try (var nested = nx.pingwheel.common.presentation.PresentationDiagnosticScope.install()) {
				InventoryItemDiagnostics.refusal("inventory/item-identity", "minecraft:stone", refusal);
				InventoryItemDiagnostics.refusal("inventory/item-identity", "minecraft:stone", refusal);
				assertEquals(2, logs.events().size(), "the nested scope admits its own first report");
			}
			InventoryItemDiagnostics.refusal("inventory/item-identity", "minecraft:stone", refusal);
			assertEquals(2, logs.events().size(), "closing the nested scope restores the outer channel history");
		}
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			InventoryItemDiagnostics.refusal("inventory/item-identity", "minecraft:stone", new IllegalStateException("next scope"));
			assertEquals(1, logs.events().size(), "a later capture has independent first-report admission");
		}
	}

	@Test void nativeErrorSupplierIsNeverEvaluatedAtAnyProfile() {
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			for (var profile : nx.pingwheel.common.diagnostics.LoggingProfile.values()) {
				int before = logs.events().size();
				nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER, profile);
				var calls = new java.util.concurrent.atomic.AtomicInteger();
				var result = com.mojang.serialization.DataResult.<String>error(() -> {
					calls.incrementAndGet(); throw new AssertionError("native codec error supplier must not traverse payloads");
				});
				var refusal = assertThrows(RuntimeException.class, () -> InventoryItemDiagnostics.complete(result, "caller-owned-stage"));
				InventoryItemDiagnostics.refusal("inventory/item-identity", "private:item", refusal);
				assertEquals(0, calls.get());
				assertEquals(profile == nx.pingwheel.common.diagnostics.LoggingProfile.DEEP ? 1 : 0, logs.events().size() - before);
				if (profile == nx.pingwheel.common.diagnostics.LoggingProfile.DEEP) {
					assertTrue(logs.messages().contains("handling=degraded"));
					assertTrue(logs.messages().contains("stage=native-codec-rejected-all-variants"));
				}
				assertFalse(logs.messages().contains("private:item"));
			}
		}
	}

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void expectedCanonicalByteRefusalIsDetailedAndDoesNotChangeAggregateOrSafeIdentity() {
		var huge = new ItemStack(Items.STONE);
		var payload = new CompoundTag(); payload.putString("data", "\u0800".repeat(23000));
		huge.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		var safe = catalog.encode(new ItemStack(Items.STONE));
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER, nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			var aggregate = catalog.encode(huge);
			assertEquals("", aggregate.key().componentsKey()); assertTrue(aggregate.display().aggregateRequired());
			assertEquals(safe.key(), catalog.encode(new ItemStack(Items.STONE)).key());
			assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("reason=entry-limit"));
		}
	}

	@Test void sizeDegradationHasOneDetailedDiagnosticWithoutLoggingFixedLoreSuppression() {
		var stack = new ItemStack(Items.STONE);
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("hidden lore"))));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("n".repeat(5000)));
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER, nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			var encoded = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(stack);
			assertFalse(encoded.display().aggregateRequired()); assertFalse(encoded.key().componentsKey().isEmpty());
			assertFalse(encoded.display().displayJson().contains("hidden lore"));
			assertFalse(encoded.display().displayJson().contains("nnnnnnnnnn"));
			assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("reason=display-limit"));
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
				if (styles.incrementAndGet() > 1) throw new InventoryItemBudget.RootLimitException(
					nx.pingwheel.common.presentation.inventory.InventoryFailureReason.NATIVE_WORK_LIMIT, "display root bound");
				return ordinary.styleFlags(style);
			}
		};
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER, nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			var frozen = InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), RegistryAccess.EMPTY,
				4096, InventoryItemBudget.root(4096), raw, InventoryItemNativeAccess.load());
			assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode()); assertNotNull(frozen.canonicalForCatalog());
			assertNull(frozen.display().displayJson()); assertFalse(frozen.display().aggregateRequired());
			assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("impact=degraded"));
			assertTrue(logs.messages().contains("reason=native-work-limit"));
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
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.CLIENT, nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			var display = nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.resolve(RegistryAccess.EMPTY,
				"minecraft:stone", "block.minecraft.stone", json);
			assertTrue(display.stack().isEmpty());
			assertEquals(1, logs.events().size()); assertFalse(logs.messages().contains("not-an-integer"), logs.messages());
			assertTrue(logs.messages().contains("stage=persistent-display-codec"));
			assertTrue(logs.messages().contains("handling=degraded")); assertNull(logs.events().getFirst().getThrown());
		}
	}
	@Test void clientNativeCodecRefusalKeepsFallbackAndContentFreeNormalSummaryAtEveryProfile() {
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			for (var profile : nx.pingwheel.common.diagnostics.LoggingProfile.values()) {
				int before = logs.events().size();
				nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.CLIENT, profile);
				nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.reset();
				var display = nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.resolve(RegistryAccess.EMPTY,
					"minecraft:stone", "block.minecraft.stone", "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:damage\":\"private-codec-input\"}}");
				assertTrue(display.stack().isEmpty());
				assertEquals(profile == nx.pingwheel.common.diagnostics.LoggingProfile.DEEP ? 1 : 0, logs.events().size() - before);
				assertFalse(logs.messages().contains("private-codec-input"));
				if (profile == nx.pingwheel.common.diagnostics.LoggingProfile.DEEP) {
					assertTrue(logs.messages().contains("handling=degraded"));
					assertTrue(logs.messages().contains("stage=persistent-display-codec"));
				}
			}
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
	@Test void nativeCapFallbacksUseTypedReasonsAndReportEveryDebugOccurrenceWithoutPayloads() {
		try (var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER, nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			for (int attempt = 0; attempt < 2; attempt++) {
				var data = new CompoundTag(); data.putByteArray("private-array-payload", new byte[60 * 1024 + 1]);
				var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
				assertTrue(new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(stack).display().aggregateRequired());
			}
			var data = new CompoundTag(); var list = new ListTag();
			for (int i = 0; i < 513; i++) list.add(net.minecraft.nbt.IntTag.valueOf(i)); data.put("private-list-payload", list);
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
			assertTrue(new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(stack).display().aggregateRequired());
			assertEquals(3, logs.events().size());
			assertEquals(2, logs.events().stream().filter(event -> event.getMessage().getFormattedMessage().contains("reason=native-array-limit")).count());
			assertTrue(logs.messages().contains("reason=native-list-limit")); assertFalse(logs.messages().contains("private-"));
		}
	}
}
