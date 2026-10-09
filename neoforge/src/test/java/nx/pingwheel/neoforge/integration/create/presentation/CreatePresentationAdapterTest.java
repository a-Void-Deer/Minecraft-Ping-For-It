package nx.pingwheel.neoforge.integration.create.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationValue;

class CreatePresentationAdapterTest {
	private static final PresentationAdapter.DetachedTarget VAULT =
		new PresentationAdapter.DetachedTarget("minecraft:overworld", "entity_block",
			"create:item_vault", 8, 70, 12, null);

	@Test
	void serverAndClientManifestsAgreeAndRegistryAcceptsThem() {
		var client = new CreatePresentationAdapter();
		var server = new CreatePresentationAdapter((target, demand, budget) -> null);
		assertEquals("create:presentation", client.adapterId());
		assertEquals("create", client.modId());
		assertEquals(1, client.schema());
		assertEquals(10, client.minUpdateIntervalTicks());
		assertEquals(client.fields(), server.fields());
		assertEquals(8, client.fields().size());
		assertEquals(6L, client.fields().stream().filter(PresentationField::enabledByDefault).count());
		assertEquals(2L, client.fields().stream().filter(field -> !field.enabledByDefault()).count());
		var available = client.fields().stream()
			.filter(field -> field.id().equals(CreatePresentationAdapter.AVAILABLE_CAPACITY))
			.findFirst().orElseThrow();
		assertEquals(PresentationField.Kind.NUMBER, available.kind());
		assertTrue(available.enabledByDefault());
		var registry = new PresentationRegistry();
		registry.register(server);
		assertEquals(server, registry.get(client.adapterId()));
		assertEquals(1, registry.all().size());
	}

	@Test
	void clientManifestNeverAttemptsCaptureAndUnknownDemandNeverInvokesSource() {
		assertNull(new CreatePresentationAdapter().collect(VAULT, Set.of(CreatePresentationAdapter.SPEED),
			new PresentationAdapter.CaptureBudget(8)));
		AtomicInteger called = new AtomicInteger();
		var adapter = new CreatePresentationAdapter((target, demand, budget) -> {
			called.incrementAndGet();
			return null;
		});
		assertTrue(adapter.collect(VAULT, Set.of("other:unknown"),
			new PresentationAdapter.CaptureBudget(8)).fields().isEmpty());
		assertNull(adapter.collect(VAULT, Set.of(CreatePresentationAdapter.SPEED),
			new PresentationAdapter.CaptureBudget(0)));
		assertNull(adapter.collect(new PresentationAdapter.DetachedTarget("minecraft:overworld",
			"entity", "create:item_vault", 8, 70, 12, null),
			Set.of(CreatePresentationAdapter.SPEED), new PresentationAdapter.CaptureBudget(8)));
		assertEquals(0, called.get());
	}

	@Test
	void requestedSpeedNeverEmitsCachedOrSummaryFields() {
		var observation = new CreatePresentationAdapter.Observation(
			new CreatePresentationAdapter.Speed(0, 32, false), true, true,
			3.0, 8.0, new CreatePresentationAdapter.Summary(Map.of("minecraft:iron_ingot", 4L),
				false, 1, true), null);
		var adapter = new CreatePresentationAdapter((target, demand, budget) -> observation);
		var section = adapter.collect(VAULT, Set.of(CreatePresentationAdapter.SPEED),
			new PresentationAdapter.CaptureBudget(8));
		assertEquals(Set.of(CreatePresentationAdapter.SPEED), section.fields().keySet());
		assertEquals(Map.of(
			"effective_rpm", new PresentationValue.NumberValue(0),
			"theoretical_rpm", new PresentationValue.NumberValue(32),
			"moving", new PresentationValue.Flag(false)),
			((PresentationValue.RecordValue) section.fields().get(CreatePresentationAdapter.SPEED)).values());
	}

	@Test
	void unavailableCacheFieldIsOmittedButPublicGettersSurvive() {
		var observation = new CreatePresentationAdapter.Observation(
			new CreatePresentationAdapter.Speed(-16, -16, true), false, false,
			null, null, null, null);
		var section = CreatePresentationAdapter.project(observation,
			Set.of(CreatePresentationAdapter.SPEED, CreatePresentationAdapter.OVERSTRESSED,
				CreatePresentationAdapter.STRESS, CreatePresentationAdapter.CAPACITY));
		assertEquals(Set.of(CreatePresentationAdapter.SPEED, CreatePresentationAdapter.OVERSTRESSED),
			section.fields().keySet());
		assertEquals(new PresentationValue.Flag(false),
			section.fields().get(CreatePresentationAdapter.OVERSTRESSED));
		assertFalse(section.stale());
	}

	@Test
	void availableCapacityIsDerivedOnlyFromTheRawPairAndNeverLeaksIt() {
		var observation = new CreatePresentationAdapter.Observation(
			new CreatePresentationAdapter.Speed(0, 32, false), true, false, 3.0, 8.0, null, null);
		var section = CreatePresentationAdapter.project(observation,
			Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY));
		assertEquals(Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY), section.fields().keySet());
		assertEquals(new PresentationValue.NumberValue(5),
			section.fields().get(CreatePresentationAdapter.AVAILABLE_CAPACITY));

		AtomicInteger captures = new AtomicInteger();
		var adapter = new CreatePresentationAdapter((target, demand, budget) -> {
			captures.incrementAndGet();
			return observation;
		});
		var demanded = adapter.collect(VAULT, Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY),
			new PresentationAdapter.CaptureBudget(8));
		assertEquals(Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY), demanded.fields().keySet(),
			"the raw pair never leaks into a projection that did not demand it");
		assertEquals(1, captures.get(), "the derived field shares the one source capture");
		assertNull(adapter.collect(VAULT, Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY),
			new PresentationAdapter.CaptureBudget(0)));
		assertEquals(1, captures.get(), "a zero budget never reaches the source");
	}

	@Test
	void availableCapacityNeedsTheCompleteRawPairAndKeepsNegativeExactValues() {
		assertTrue(CreatePresentationAdapter.project(new CreatePresentationAdapter.Observation(
			null, null, null, 3.0, null, null, null),
			Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY)).fields().isEmpty(),
			"a missing raw capacity yields no derived value");
		assertTrue(CreatePresentationAdapter.project(new CreatePresentationAdapter.Observation(
			null, null, null, null, 8.0, null, null),
			Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY)).fields().isEmpty(),
			"a missing raw stress yields no derived value");
		assertEquals(new PresentationValue.NumberValue(-2), CreatePresentationAdapter.project(
			new CreatePresentationAdapter.Observation(null, null, null, 12.0, 10.0, null, null),
			Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY)).fields()
			.get(CreatePresentationAdapter.AVAILABLE_CAPACITY), "negative available capacity stays valid");
		assertEquals(new PresentationValue.NumberValue(999_999_999d), CreatePresentationAdapter.project(
			new CreatePresentationAdapter.Observation(null, null, null, 1.0, 1.0e9, null, null),
			Set.of(CreatePresentationAdapter.AVAILABLE_CAPACITY)).fields()
			.get(CreatePresentationAdapter.AVAILABLE_CAPACITY), "subtraction keeps double precision");
	}

	@Test
	void partialAndEmptySummariesAreDistinctFromUnavailable() {
		var unavailable = new CreatePresentationAdapter.Summary(Map.of(), false, 0, false);
		var partial = new CreatePresentationAdapter.Summary(Map.of("minecraft:water", 250L), true, 2, true);
		var observed = new CreatePresentationAdapter.Observation(null, null, null,
			null, null, unavailable, partial);
		var section = CreatePresentationAdapter.project(observed,
			Set.of(CreatePresentationAdapter.INVENTORY, CreatePresentationAdapter.FLUID));
		assertEquals(Set.of(CreatePresentationAdapter.FLUID), section.fields().keySet());
		var summary = (PresentationValue.RecordValue) section.fields().get(CreatePresentationAdapter.FLUID);
		assertEquals(new PresentationValue.Flag(true), summary.values().get("partial"));
		assertEquals(new PresentationValue.NumberValue(2), summary.values().get("scanned"));
		assertEquals(new PresentationValue.NumberValue(250),
			((PresentationValue.RecordValue) summary.values().get("counts")).values().get("minecraft:water"));
		var empty = CreatePresentationAdapter.project(new CreatePresentationAdapter.Observation(
			null, null, null, null, null,
			new CreatePresentationAdapter.Summary(Map.of(), false, 8, true), null),
			Set.of(CreatePresentationAdapter.INVENTORY));
		assertTrue(empty.fields().containsKey(CreatePresentationAdapter.INVENTORY));
	}

	@Test
	void detachedSummaryCopiesCountsAndRejectsInexactOverflow() {
		var mutable = new HashMap<String, Long>();
		mutable.put("minecraft:stone", 12L);
		var detached = new CreatePresentationAdapter.Summary(mutable, false, 1, true);
		mutable.clear();
		assertEquals(Map.of("minecraft:stone", 12L), detached.counts());
		assertThrows(IllegalArgumentException.class,
			() -> new CreatePresentationAdapter.Summary(Map.of("minecraft:stone", (1L << 53)),
				true, 1, true));
	}

	@Test
	void serverCadenceOverrideChangesOnlySchedulingNotClientSchema() {
		var client = new CreatePresentationAdapter();
		var server = new CreatePresentationAdapter((target, demand, budget) -> null, 40);
		assertEquals(40, server.minUpdateIntervalTicks());
		assertEquals(client.fields(), server.fields());
		assertEquals(client.schema(), server.schema());
		assertThrows(IllegalArgumentException.class,
			() -> new CreatePresentationAdapter((target, demand, budget) -> null, 0));
	}
}
