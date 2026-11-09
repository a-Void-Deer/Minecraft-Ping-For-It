package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.ShaderInstance;
import nx.pingwheel.common.monitor.MonitorHudLabels;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Scalar state restoration only; this does not create a GL context or validate pixels. */
@NotNullByDefault
class InventoryMonitorHudStateTest {

	@Test void earlyTextPassRestoresDepthWriteBlendColorAndNativeShaderBindingsAfterFlush() {
		for (boolean initiallyEnabled : java.util.List.of(false, true)) {
			var host = new Host();
			host.depthTest = initiallyEnabled; host.depthMask = !initiallyEnabled; host.blend = initiallyEnabled;
			host.scissor = initiallyEnabled;
			var state = new InventoryMonitorHud.TextState();
			state.capture(host);
			// Native text clears blend and write-mask shards, while GuiGraphics
			// flush unconditionally enables depth. Those are not saved states.
			host.depthTest = true; host.depthMask = true; host.blend = false;
			host.sourceRgb = 770; host.destinationRgb = 771; host.sourceAlpha = 1; host.destinationAlpha = 0;
			host.red = 1; host.green = 1; host.blue = 1; host.alpha = 1;
			host.texture = 100; host.lightmap = 200;
			host.scissor = false;
			state.restore(host);
			assertEquals(initiallyEnabled, host.depthTest);
			assertEquals(!initiallyEnabled, host.depthMask);
			assertEquals(initiallyEnabled, host.blend);
			assertEquals(2, host.sourceRgb); assertEquals(3, host.destinationRgb);
			assertEquals(4, host.sourceAlpha); assertEquals(5, host.destinationAlpha);
			assertEquals(0.2F, host.red); assertEquals(0.3F, host.green);
			assertEquals(0.4F, host.blue); assertEquals(0.5F, host.alpha);
			assertEquals(42, host.texture); assertEquals(43, host.lightmap);
			assertEquals(initiallyEnabled, host.scissor);
			if (initiallyEnabled) {
				assertEquals(11, host.scissorLeft); assertEquals(12, host.scissorBottom);
				assertEquals(13, host.scissorWidth); assertEquals(14, host.scissorHeight);
			}
		}
	}

	@Test void nativeLifecycleRestoresStateAndScissorAfterBackgroundTextBoundaryOrFlushFailure() throws ReflectiveOperationException {
		var font = new Font(ignored -> { throw new AssertionError("width override must not request glyphs"); }, false) {
			@Override public int width(net.minecraft.util.FormattedCharSequence text) { return 20; }
		};
		var model = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(), ClientMonitorState.Status.OFF,
			ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, null, null), MonitorHudLabels.DEFAULT);
		var prepared = InventoryMonitorHud.prepare(model, font);
		for (String failure : List.of("none", "background", "boundary", "text", "flush")) {
			for (boolean initiallyEnabled : List.of(false, true)) {
				var host = new Host();
				var previousShader = allocateShader(); host.shader = previousShader;
				host.depthTest = initiallyEnabled; host.depthMask = !initiallyEnabled; host.blend = initiallyEnabled; host.scissor = initiallyEnabled;
				var painter = new FailingPainter(host, failure);
				var state = new InventoryMonitorHud.TextState();
				if (failure.equals("none")) InventoryMonitorHud.draw(prepared, new InventoryMonitorHud.Viewport(1, 1), 9, state, host, painter);
				else assertThrows(PaintFailure.class, () -> InventoryMonitorHud.draw(prepared, new InventoryMonitorHud.Viewport(1, 1), 9, state, host, painter));
				assertEquals(1, host.captures, "one state capture, not one capture per row");
				assertEquals(1, painter.events.stream().filter("flush"::equals).count());
				assertEquals("disableScissor", painter.events.getLast());
				assertFalse(painter.scissorActive);
				assertEquals(initiallyEnabled, host.depthTest); assertEquals(!initiallyEnabled, host.depthMask);
				assertEquals(initiallyEnabled, host.blend); assertEquals(initiallyEnabled, host.scissor);
				assertEquals(2, host.sourceRgb); assertEquals(3, host.destinationRgb);
				assertEquals(4, host.sourceAlpha); assertEquals(5, host.destinationAlpha);
				assertEquals(0.2F, host.red); assertEquals(0.3F, host.green);
				assertEquals(0.4F, host.blue); assertEquals(0.5F, host.alpha);
				assertEquals(42, host.texture); assertEquals(43, host.lightmap);
				assertSame(previousShader, host.shader, "the original native shader identity is restored even after a failed submission");
				if (initiallyEnabled) {
					assertEquals(11, host.scissorLeft); assertEquals(12, host.scissorBottom);
					assertEquals(13, host.scissorWidth); assertEquals(14, host.scissorHeight);
				}
			}
		}
	}

	private static ShaderInstance allocateShader() throws ReflectiveOperationException {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return ShaderInstance.class.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), ShaderInstance.class));
	}

	@Test void emptyPreparedNeverCapturesOrFlushesNativeStateAndPaddingScissorRetainsShadowBudget() {
		var host = new Host();
		var painter = new FailingPainter(host, "none");
		InventoryMonitorHud.draw(InventoryMonitorHud.Prepared.EMPTY, new InventoryMonitorHud.Viewport(0, 0), 9,
			new InventoryMonitorHud.TextState(), host, painter);
		assertEquals(0, host.captures); assertTrue(painter.events.isEmpty());
		var model = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(), ClientMonitorState.Status.OFF,
			ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, null, null), MonitorHudLabels.DEFAULT);
		var prepared = InventoryMonitorHud.prepare(model, new Font(ignored -> { throw new AssertionError("no glyphs"); }, false) {
			@Override public int width(net.minecraft.util.FormattedCharSequence text) { return 20; }
		});
		for (int width : List.of(0, 1, 8, 9, 10, 427, Integer.MAX_VALUE)) {
			var recorder = new FailingPainter(new Host(), "none");
			InventoryMonitorHud.draw(prepared, new InventoryMonitorHud.Viewport(width, 1), 9,
				new InventoryMonitorHud.TextState(), recorder.host, recorder);
			assertEquals(Math.min(3, width), recorder.left);
			assertEquals(Math.min(width, 6 + InventoryMonitorHud.availableTextWidth(width)), recorder.right);
			assertEquals(1, recorder.height);
		}
	}

	@NotNullByDefault
	private static final class PaintFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	@NotNullByDefault
	private static final class FailingPainter implements InventoryMonitorHud.DrawAccess {
		private final Host host;
		private final String failure;
		private final List<String> events = new ArrayList<>();
		private boolean scissorActive;
		private int left, right, height;
		private FailingPainter(Host host, String failure) { this.host = host; this.failure = failure; }
		@Override public void enableScissor(int left, int right, int height) {
			this.left = left; this.right = right; this.height = height;
			scissorActive = true; host.scissor = true; events.add("enableScissor");
		}
		@Override public void background(int left, int top, int right, int bottom, int color) { event("background"); }
		@Override public void backgroundsComplete() { event("boundary"); }
		@Override public void row(MonitorDisplayModel.Row row, int x, int y, int maximumWidth) { event("text"); }
		@Override public void flush() { assertTrue(scissorActive, "flush must remain inside the native horizontal guard"); event("flush"); }
		@Override public void disableScissor() { scissorActive = false; host.scissor = false; events.add("disableScissor"); }
		private void event(String name) {
			events.add(name);
			// Native shards and GUI flush may clear or replace all these bindings.
			host.depthTest = true; host.depthMask = true; host.blend = false;
			host.sourceRgb = 770; host.destinationRgb = 771; host.sourceAlpha = 1; host.destinationAlpha = 0;
			host.red = 1; host.green = 1; host.blue = 1; host.alpha = 1;
			host.texture = 100; host.lightmap = 200;
			host.shader = null;
			if (name.equals(failure)) throw new PaintFailure();
		}
	}

	@NotNullByDefault
	private static final class Host implements InventoryMonitorHud.StateAccess {
		int captures;
		boolean depthTest, depthMask, blend, scissor;
		int scissorLeft = 11, scissorBottom = 12, scissorWidth = 13, scissorHeight = 14;
		int sourceRgb = 2, destinationRgb = 3, sourceAlpha = 4, destinationAlpha = 5, texture = 42, lightmap = 43;
		float red = 0.2F, green = 0.3F, blue = 0.4F, alpha = 0.5F;
		@Nullable ShaderInstance shader;
		public boolean depthTest() { captures++; return depthTest; }
		public boolean depthMask() { return depthMask; }
		public boolean blend() { return blend; }
		public int sourceRgb() { return sourceRgb; }
		public int destinationRgb() { return destinationRgb; }
		public int sourceAlpha() { return sourceAlpha; }
		public int destinationAlpha() { return destinationAlpha; }
		public boolean scissor() { return scissor; }
		public void captureScissor() {}
		public int scissorBox(int index) { return switch (index) { case 0 -> scissorLeft; case 1 -> scissorBottom; case 2 -> scissorWidth; default -> scissorHeight; }; }
		public float color(int channel) { return switch (channel) { case 0 -> red; case 1 -> green; case 2 -> blue; default -> alpha; }; }
		public @Nullable ShaderInstance shader() { return shader; }
		public int shaderTexture(int slot) { return slot == 0 ? texture : lightmap; }
		public void depthTest(boolean value) { depthTest = value; }
		public void depthMask(boolean value) { depthMask = value; }
		public void blend(boolean value) { blend = value; }
		public void blendFunction(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
			this.sourceRgb = sourceRgb; this.destinationRgb = destinationRgb;
			this.sourceAlpha = sourceAlpha; this.destinationAlpha = destinationAlpha;
		}
		public void color(float red, float green, float blue, float alpha) {
			this.red = red; this.green = green; this.blue = blue; this.alpha = alpha;
		}
		public void shader(@Nullable ShaderInstance value) { shader = value; }
		public void shaderTexture(int slot, int value) { if (slot == 0) texture = value; else lightmap = value; }
		public void scissor(boolean enabled, int left, int bottom, int width, int height) {
			scissor = enabled; scissorLeft = left; scissorBottom = bottom; scissorWidth = width; scissorHeight = height;
		}
	}
}
