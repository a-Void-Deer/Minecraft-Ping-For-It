package nx.pingwheel.common.client.marker;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@NotNullByDefault
class MarkerOverlayStateForgetTest {

	private static final UUID OWNER = new UUID(0L, 1L);

	@Test
	void batchRemovalKeepsBothSurvivorsWithoutIntermediatePublication() throws ReflectiveOperationException {
		MarkerOverlayState state = MarkerOverlayState.INSTANCE;
		state.clear();
		try {
			Fixture first = fixture(1L);
			Fixture second = fixture(2L);
			Fixture third = fixture(3L);
			Fixture fourth = fixture(4L);
			install(state, List.of(first, second, third, fourth));
			List<MarkerView> original = state.renderViews();

			state.forgetMarkers(guardedIds(state, original, first.id(), third.id()));

			List<MarkerView> current = state.renderViews();
			assertEquals(List.of(second.view(), fourth.view()), current);
			assertSame(second.view(), current.get(0));
			assertSame(fourth.view(), current.get(1));
			assertEquals(Set.of(second.id(), fourth.id()), cachedViews(state).keySet());
			assertNotSame(original, current);
			assertEquals(List.of(first.view(), second.view(), third.view(), fourth.view()), original);
			assertThrows(UnsupportedOperationException.class, () -> original.add(first.view()));
			assertThrows(UnsupportedOperationException.class, () -> current.add(first.view()));
		} finally {
			state.clear();
		}
	}

	@Test
	void singleRemovalKeepsOrderAndPublishesOneSnapshot() throws ReflectiveOperationException {
		MarkerOverlayState state = MarkerOverlayState.INSTANCE;
		state.clear();
		try {
			Fixture first = fixture(1L);
			Fixture second = fixture(2L);
			Fixture third = fixture(3L);
			install(state, List.of(first, second, third));
			List<MarkerView> original = state.renderViews();

			state.forgetMarker(second.id());

			List<MarkerView> current = state.renderViews();
			assertEquals(List.of(first.view(), third.view()), current);
			assertSame(first.view(), current.get(0));
			assertSame(third.view(), current.get(1));
			assertEquals(Set.of(first.id(), third.id()), cachedViews(state).keySet());
			assertNotSame(original, current);
			assertEquals(List.of(first.view(), second.view(), third.view()), original);
		} finally {
			state.clear();
		}
	}

	@Test
	void nullSingleRemovalAndEmptyBatchRemainSilentNoOps() throws ReflectiveOperationException {
		MarkerOverlayState state = MarkerOverlayState.INSTANCE;
		state.clear();
		try {
			Fixture first = fixture(1L);
			Fixture second = fixture(2L);
			install(state, List.of(first, second));
			List<MarkerView> original = state.renderViews();
			Set<MarkerId> cachedIds = Set.copyOf(cachedViews(state).keySet());

			state.forgetMarker(null);
			state.forgetMarkers(List.of());

			assertSame(original, state.renderViews());
			assertEquals(cachedIds, cachedViews(state).keySet());
		} finally {
			state.clear();
		}
	}

	/**
	 * Returns the batch ids while asserting the render snapshot still equals the
	 * one observed on entry. A per-id fallback publishes before asking for the
	 * next id, so the second access fails; the real batch removes every id first
	 * and publishes once afterwards.
	 */
	private static List<MarkerId> guardedIds(
		MarkerOverlayState state,
		List<MarkerView> entrySnapshot,
		MarkerId... ids
	) {
		return new AbstractList<>() {
			@Override
			public MarkerId get(int index) {
				assertSame(entrySnapshot, state.renderViews());
				return ids[index];
			}

			@Override
			public int size() {
				return ids.length;
			}
		};
	}

	private static void install(MarkerOverlayState state, List<Fixture> fixtures) throws ReflectiveOperationException {
		Map<MarkerId, MarkerView> views = cachedViews(state);
		views.clear();
		List<MarkerView> renderViews = new ArrayList<>(fixtures.size());
		for (Fixture fixture : fixtures) {
			views.put(fixture.id(), fixture.view());
			renderViews.add(fixture.view());
		}
		set(state, "renderViews", List.copyOf(renderViews));
	}

	@SuppressWarnings("unchecked")
	private static Map<MarkerId, MarkerView> cachedViews(MarkerOverlayState state) throws ReflectiveOperationException {
		return (Map<MarkerId, MarkerView>) get(state, "views");
	}

	private static Object get(MarkerOverlayState state, String name) throws ReflectiveOperationException {
		Field field = MarkerOverlayState.class.getDeclaredField(name);
		field.setAccessible(true);
		return Objects.requireNonNull(field.get(state), name);
	}

	private static void set(MarkerOverlayState state, String name, Object value) throws ReflectiveOperationException {
		Field field = MarkerOverlayState.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(state, value);
	}

	private static Fixture fixture(long id) {
		var target = new Target.LocationTarget("minecraft:overworld", 0, 0, 0);
		var snapshot = new MarkerSnapshot(new MarkerId(id), OWNER, target, "block", "attention",
			new MarkerAnchor(0, 0, 0), 1L, 100L);
		return new Fixture(new MarkerId(id), new MarkerView(ClientMarker.withLocalDuration(snapshot, 0L, 1L, 100L)));
	}

	@NotNullByDefault
	private record Fixture(MarkerId id, MarkerView view) {}
}
