package nx.pingwheel.common.monitor.client;

import java.util.List;
import java.util.Objects;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.CounterReading;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.LaneSample;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorHudLabels;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Layout identity protects height reservations, not old rendered telemetry. */
@NotNullByDefault
class MonitorLayoutStateTest {

	@Test void newViewStatusAndHudToggleRebuildWithoutResettingLayoutOrRetainingSampleValues() {
		var state = new MonitorLayoutState();
		var font = font();
		var language = new TestLanguage();
		var selected = List.of(metric("local.preview.apply.accepted"));
		var active = view(true, selected, ClientMonitorState.Status.ACTIVE);
		assertTrue(state.update(active, 7, 80, font, language, false, false));
		var reserved = reservation(); state.commit(reserved);
		assertFalse(state.update(active, 7, 80, font, language, false, false));
		assertSame(reserved, state.reservations());
		for (var status : ClientMonitorState.Status.values()) {
			assertTrue(state.update(view(true, selected, status), 7, 80, font, language, false, false));
			assertSame(reserved, state.reservations());
		}
		assertTrue(state.update(view(false, selected, ClientMonitorState.Status.OFF), 7, 80, font, language, false, false));
		assertSame(reserved, state.reservations());
		state.invalidatePrepared(); assertSame(reserved, state.reservations());
		assertTrue(state.update(active, 7, 80, font, language, false, false));
		assertSame(reserved, state.reservations());
		assertFalse(state.update(active, 7, 80, font, language, false, false), "height and GUI scale alone are deliberately absent from identity");
		state.reset(); assertSame(MonitorDisplayModel.Reservations.EMPTY, state.reservations());
		assertTrue(state.update(active, 7, 80, font, language, false, false));
	}

	@Test void onlyIdentityBoundariesClearReservationsIncludingWhileHudIsDisabled() throws ReflectiveOperationException {
		var state = new MonitorLayoutState();
		var font = font(); var language = new TestLanguage();
		var selected = List.of(metric("local.preview.apply.accepted"));
		var active = view(true, selected, ClientMonitorState.Status.ACTIVE);
		state.update(active, 7, 80, font, language, false, false);
		state.commit(reservation());
		assertTrue(state.update(active, 7, 81, font, language, false, false)); assertEmpty(state);
		state.commit(reservation());
		assertTrue(state.update(active, 8, 81, font, language, false, false)); assertEmpty(state);
		state.commit(reservation()); font = font();
		assertTrue(state.update(active, 8, 81, font, language, false, false)); assertEmpty(state);
		state.commit(reservation()); language = new TestLanguage();
		assertTrue(state.update(active, 8, 81, font, language, false, false)); assertEmpty(state);
		state.commit(reservation());
		assertTrue(state.update(active, 8, 81, font, language, true, false)); assertEmpty(state);
		state.commit(reservation());
		assertTrue(state.update(active, 8, 81, font, language, true, true)); assertEmpty(state);
		state.commit(reservation());
		var lineHeight = Font.class.getDeclaredField("lineHeight"); lineHeight.setAccessible(true);
		lineHeight.setInt(font, 17);
		assertTrue(state.update(active, 8, 81, font, language, true, true)); assertEmpty(state);
		assertFalse(state.update(active, 8, 81, font, language, true, true));
		state.commit(reservation());
		var disabled = view(false, selected, ClientMonitorState.Status.OFF);
		assertTrue(state.update(disabled, 8, 82, font, language, true, true)); assertEmpty(state);
		state.commit(reservation());
		var changed = view(false, List.of(metric("local.preview.apply.rejected")), ClientMonitorState.Status.OFF);
		assertTrue(state.update(changed, 8, 82, font, language, true, true)); assertEmpty(state);
	}

	@Test void normalizedOriginalSelectionIgnoresOrderAndDuplicatesButDoesNotExpandSummary() {
		var state = new MonitorLayoutState(); var font = font(); var language = new TestLanguage();
		Metric accepted = metric("local.preview.apply.accepted"), rejected = metric("local.preview.apply.rejected");
		state.update(view(true, List.of(accepted, rejected), ClientMonitorState.Status.ACTIVE), 1, 80, font, language, false, false);
		var reserved = reservation(); state.commit(reserved);
		assertTrue(state.update(view(true, List.of(rejected, accepted, accepted), ClientMonitorState.Status.ACTIVE), 1, 80, font, language, false, false));
		assertSame(reserved, state.reservations());
		var summary = view(true, List.of(Metric.SUMMARY_PROCESSING), ClientMonitorState.Status.ACTIVE);
		assertTrue(state.update(summary, 1, 80, font, language, false, false)); assertEmpty(state);
		state.commit(reserved);
		var raw = view(true, Metric.SUMMARY_PROCESSING.expanded(), ClientMonitorState.Status.ACTIVE);
		assertTrue(state.update(raw, 1, 80, font, language, false, false)); assertEmpty(state);
	}

	@Test void disabledSelectionRoundTripDropsOldReservationsInsteadOfKeepingPerSelectionHistory() {
		var state = new MonitorLayoutState(); var font = font(); var language = new TestLanguage();
		var a = List.of(metric("local.preview.apply.accepted"));
		var b = List.of(metric("local.preview.apply.rejected"));
		var active = view(true, a, ClientMonitorState.Status.ACTIVE);
		state.update(active, 1, 8, font, language, false, false);
		state.commit(reservation());
		state.update(view(false, a, ClientMonitorState.Status.OFF), 1, 8, font, language, false, false);
		assertNotSame(MonitorDisplayModel.Reservations.EMPTY, state.reservations());
		state.update(view(false, b, ClientMonitorState.Status.OFF), 1, 8, font, language, false, false); assertEmpty(state);
		state.update(view(false, a, ClientMonitorState.Status.OFF), 1, 8, font, language, false, false); assertEmpty(state);
		state.update(active, 1, 8, font, language, false, false); assertEmpty(state);
	}

	@Test void newServerCollectionSequenceAndValuesDoNotResetButConnectionDoesEvenIfServerGenerationIsReused() {
		var state = new MonitorLayoutState(); var font = font(); var language = new TestLanguage();
		var selected = List.of(metric("server.preview.requests.received"));
		var first = sampledView(selected, 1, 1, 1, Long.MAX_VALUE);
		state.update(first, 7, 80, font, language, false, false);
		var reserved = MonitorDisplayModel.prepare(first, MonitorHudLabels.DEFAULT)
			.fit(8, new StringSplitter((code, style) -> 1f), state.reservations()).nextReservations();
		state.commit(reserved);
		for (var next : List.of(sampledView(selected, 2, 1, 1, 1), sampledView(selected, 2, 2, 1, 0), sampledView(selected, 2, 2, 2, 5))) {
			assertTrue(state.update(next, 7, 80, font, language, false, false)); assertSame(reserved, state.reservations());
		}
		assertTrue(state.update(sampledView(selected, 2, 2, 1, 1), 8, 80, font, language, false, false)); assertEmpty(state);
	}
	private static ClientMonitorState.View sampledView(List<Metric> selected, long server, long collection, long sequence, long value) {
		var lane = new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(), List.of(new CounterReading(Counter.REQUESTS_RECEIVED, value, false)), List.of(), List.of());
		var sample = new MonitorSample(server, collection, sequence, 1, 1, 1_000_000_000L, lane, null, null);
		return new ClientMonitorState.View(true, selected, ClientMonitorState.Status.ACTIVE, ClientMonitorState.Status.ACTIVE,
			ClientMonitorState.Status.ACTIVE, null, sample);
	}

	private static void assertEmpty(MonitorLayoutState state) { assertSame(MonitorDisplayModel.Reservations.EMPTY, state.reservations()); }
	private static Metric metric(String id) { return Objects.requireNonNull(Metric.byId(id)); }
	private static ClientMonitorState.View view(boolean enabled, List<Metric> selected, ClientMonitorState.Status status) {
		return new ClientMonitorState.View(enabled, selected, status, status, status, null, null);
	}
	private static MonitorDisplayModel.Reservations reservation() {
		var model = MonitorDisplayModel.prepare(view(true, List.of(metric("local.preview.apply.accepted")), ClientMonitorState.Status.ACTIVE),
			metric -> Component.literal("full"), metric -> Component.literal("Field"), scope -> Component.literal("Scope"));
		var reserved = model.fit(4, new StringSplitter((code, style) -> 1f), MonitorDisplayModel.Reservations.EMPTY).nextReservations();
		assertNotSame(MonitorDisplayModel.Reservations.EMPTY, reserved); return reserved;
	}
	private static Font font() { return new Font(ignored -> { throw new AssertionError("identity must not request glyphs"); }, false); }

	@NotNullByDefault
	private static final class TestLanguage extends Language {
		@Override public String getOrDefault(String key, String fallback) { return fallback; }
		@Override public boolean has(String key) { return false; }
		@Override public boolean isDefaultRightToLeft() { return false; }
		@Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
	}
}
