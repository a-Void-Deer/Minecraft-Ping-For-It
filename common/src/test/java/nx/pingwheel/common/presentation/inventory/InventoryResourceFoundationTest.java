package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryResourceFoundationTest {
	@Test void quantitiesUseTheExactCommonUnitWithoutFloatingPointOrSaturation() {
		assertEquals(81000, InventoryQuantities.fromFabric(81000));
		assertEquals(81000, InventoryQuantities.fromMillibuckets(1000));
		assertEquals(81, InventoryQuantities.fromMillibuckets(1));
		assertEquals(1, InventoryQuantities.fromFabric(1));
		long lastMillibuckets = Long.MAX_VALUE / 81;
		assertEquals(Long.MAX_VALUE - Long.MAX_VALUE % 81, InventoryQuantities.fromMillibuckets(lastMillibuckets));
		assertThrows(ArithmeticException.class, () -> InventoryQuantities.fromMillibuckets(lastMillibuckets + 1));
		assertThrows(ArithmeticException.class, () -> InventoryQuantities.add(Long.MAX_VALUE, 1));
		assertThrows(IllegalArgumentException.class, () -> InventoryQuantities.fromFabric(-1));
		assertThrows(IllegalArgumentException.class, () -> InventoryQuantities.fromMillibuckets(-1));
		assertThrows(IllegalArgumentException.class, () -> InventoryQuantities.add(0, -1));
	}

	@Test void sameRegistryAndVariantCannotCrossResourceOrOwnerFaceBinding() {
		var item = new InventoryScanner.Key("test:resource", "variant");
		var fluid = new InventoryScanner.Key(InventoryResourceKind.FLUID, "test:resource", "variant");
		assertEquals(InventoryResourceKind.ITEM, item.kind());
		assertEquals(2, Map.of(item, 1L, fluid, 2L).size());
		assertNotEquals(item, fluid);
		var target = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "test:tank");
		var input = new InventorySourceInput(target, new UUID(0, 1), BlockFace.NORTH);
		var fluidInput = new InventorySourceInput(target, input.readOwner(), input.face(), InventoryResourceKind.FLUID);
		assertFalse(input.sameBinding(fluidInput)); assertNotEquals(input.viewKey(), fluidInput.viewKey());
		assertFalse(fluidInput.sameBinding(new InventorySourceInput(target, new UUID(0, 2), input.face(), InventoryResourceKind.FLUID)));
		assertFalse(fluidInput.sameBinding(new InventorySourceInput(target, input.readOwner(), BlockFace.SOUTH, InventoryResourceKind.FLUID)));
		assertThrows(IllegalArgumentException.class, () -> new InventoryDomainCodec.Item(fluid, 1, "label", null, false));
		assertThrows(IllegalArgumentException.class, () -> new InventorySelection(fluid, "test:resource", "label", null, false, "attention"));
	}

	@Test void exactVariantRefusalIsIndependentOfDisplayAndAmountsNeverEnterIdentity() {
		var catalog = new InventoryFluidCodec.Catalog();
		byte[] mutableNativeEncoding = {10, 0, 0, 0, 0};
		var identity = InventoryFluidCodec.Identity.exact(mutableNativeEncoding);
		mutableNativeEncoding[0] = 7;
		var bare = new InventoryFluidValue.Display("minecraft:water", "block.minecraft.water", null);
		var decorated = new InventoryFluidValue.Display("minecraft:water", "variant name", "{\"name\":\"variant\"}");
		var first = catalog.intern("minecraft:water", 1, identity, bare);
		var second = catalog.intern("minecraft:water", Long.MAX_VALUE, identity, decorated);
		assertEquals(first.key(), second.key()); assertFalse(first.aggregate());
		assertNotEquals(first.display(), second.display());
		assertEquals(first.key(), catalog.intern("minecraft:water", 81,
			InventoryFluidCodec.Identity.exact(new byte[] {10, 0, 0, 0, 0}), bare).key());
		assertNotEquals(first.key(), catalog.intern("minecraft:water", 1,
			InventoryFluidCodec.Identity.exact(new byte[] {10, 0, 0, 0, 1}), bare).key());
		var refused = catalog.intern("minecraft:water", 81, InventoryFluidCodec.Identity.refused(), bare);
		assertTrue(refused.aggregate()); assertEquals("", refused.key().componentsKey()); assertEquals(81, refused.amount());
		assertThrows(IllegalArgumentException.class, () -> catalog.intern("", 81, InventoryFluidCodec.Identity.refused(), bare));
		assertThrows(IllegalArgumentException.class, () -> catalog.intern("minecraft:water", -1, InventoryFluidCodec.Identity.refused(), bare));
	}

	@Test void domainDigestsFenceEvenEmptyFluidCutsWhileKeepingTheItemBridge() {
		assertEquals(InventoryChecksums.checksum(List.of()), InventoryChecksums.checksum(InventoryResourceKind.ITEM, List.of()));
		assertNotEquals(InventoryChecksums.checksum(List.of()), InventoryChecksums.checksum(InventoryResourceKind.FLUID, List.of()));
		var sample = new InventoryChecksums.Sample("opaque", "test:resource", 81, null, false);
		assertNotEquals(InventoryChecksums.checksum(List.of(sample)), InventoryChecksums.checksum(InventoryResourceKind.FLUID, List.of(sample)));
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(CaptureResult.OpaqueValue.forCodec(InventoryDomainCodec.CODEC, new byte[0])));
		assertThrows(IllegalArgumentException.class, () -> InventoryDomainCodec.decode(InventoryFluidCodec.encode(null)));
	}
}
