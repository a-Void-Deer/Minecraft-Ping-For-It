package nx.pingwheel.common.presentation.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DedicatedDeliveryBoundaryTest {
	private static final UUID OWNER = new UUID(0, 1);
	private static final UUID VIEWER = new UUID(0, 2);

	private static ServerMarker marker(long id, List<UUID> viewers) {
		var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		return new ServerMarker(new MarkerId(id), OWNER,
			Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "tracking-id",
				"minecraft:chest", "locator", true),
			type, type.defaultPingType(), new MarkerAnchor(1, 2, 3), 2, 500, viewers);
	}

	@Test
	void dedicatedFieldsAreNegotiatedButExcludedFromSectionMasks() {
		var registry = new PresentationRegistry();
		registry.register(new BasicAdapter());
		registry.register(InventoryPresentation.INSTANCE);

		assertEquals(2, registry.all().size(), "dedicated adapters are negotiated with the others");
		assertEquals(List.of(PresentationBasic.ID),
			registry.sectionAdapters().stream().map(PresentationAdapter::adapterId).toList());

		var settings = PresentationSettings.serverDefaults();
		var schemas = Map.of(PresentationBasic.ID, 1,
			InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA);
		var mask = PresentationServer.maskFor(registry, settings, VIEWER, 0, schemas);
		for (String type : PresentationSettings.TARGET_TYPE_IDS) {
			assertFalse(mask.get(type).containsKey(InventoryPresentation.ADAPTER_ID),
				"a dedicated adapter never enters a legacy SECTION mask");
			assertTrue(mask.get(type).get(PresentationBasic.ID).contains(PresentationBasic.NAME));
		}

		assertEquals(Set.of(InventoryPresentation.ITEMS),
			InventoryPresentation.allowed(settings, VIEWER, 0, "entity_block"),
			"the dedicated route still authorizes the same subject through the shared helper");
	}

	@Test
	void dedicatedCollectorsAreNeverSampledChargedOrCached() {
		var registry = new PresentationRegistry();
		var dedicated = new CountingDedicatedAdapter();
		registry.register(dedicated);
		var lease = new PresentationServer.Lease(marker(1, List.of(VIEWER)), "Owner");
		var work = new PresentationAdapter.CaptureBudget(100);
		var demandRequests = new AtomicInteger();
		int captures = PresentationServer.captureSources(lease, registry, PresentationSettings.serverDefaults(),
			100, work, 10, false,
			adapter -> { demandRequests.incrementAndGet(); return Set.of(CountingDedicatedAdapter.FIELD); },
			fields -> fail("Basic is not registered"));
		assertEquals(0, captures);
		assertEquals(0, dedicated.reads, "a dedicated collector is never reached by SECTION capture");
		assertEquals(0, demandRequests.get(), "dedicated adapters are filtered before demand is gathered");
		assertTrue(lease.sources.isEmpty(), "no sampling lease is allocated for a dedicated adapter");
		assertEquals(100, work.remaining(), "a dedicated adapter is never charged source scans");
	}

	@Test
	void sectionAdaptersKeepSamplingAlongsideADedicatedAdapter() {
		var registry = new PresentationRegistry();
		registry.register(new BasicAdapter());
		var dedicated = new CountingDedicatedAdapter();
		registry.register(dedicated);
		var lease = new PresentationServer.Lease(marker(1, List.of(VIEWER)), "Owner");
		var work = new PresentationAdapter.CaptureBudget(100);
		int captures = PresentationServer.captureSources(lease, registry, PresentationSettings.serverDefaults(),
			100, work, 10, false, adapter -> Set.of(PresentationBasic.NAME),
			fields -> new PresentationSection(PresentationBasic.ID, 1,
				Map.of(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Target\"}")), false));
		assertEquals(1, captures);
		assertEquals(0, dedicated.reads);
		assertEquals(99, work.remaining());
		assertTrue(lease.sources.containsKey(PresentationBasic.ID));
		assertFalse(lease.sources.containsKey(CountingDedicatedAdapter.ID));
	}

	private static final class BasicAdapter implements PresentationAdapter {
		@Override public String adapterId() { return PresentationBasic.ID; }
		@Override public String modId() { return "minecraft"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 5; }
		@Override public List<PresentationField> fields() { return PresentationBasic.fields(); }
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			throw new AssertionError("Basic uses its world reader, not the adapter collector");
		}
	}

	private static final class CountingDedicatedAdapter implements PresentationAdapter {
		static final String ID = "test:dedicated";
		static final String FIELD = "test:dedicated.items";
		int reads;

		@Override public String adapterId() { return ID; }
		@Override public String modId() { return "test"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 1; }
		@Override public List<PresentationField> fields() {
			return List.of(new PresentationField(FIELD, PresentationField.Kind.RECORD, true, 0, "items"));
		}
		@Override public PresentationAdapter.DeliveryMode deliveryMode() {
			return PresentationAdapter.DeliveryMode.DEDICATED;
		}
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			reads++;
			return null;
		}
	}
}
