package nx.pingwheel.common.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.client.IPlatformFluidRenderService;
import nx.pingwheel.common.platform.client.FluidSprites;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Production sprite dispatch with only final quad submission replaced; no GPU or Minecraft singleton. */
@NotNullByDefault
class SpatialFluidIconRendererTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@TempDir java.nio.file.Path directory;

	@Test void productionDiscoveryFailureIsReportedOnlyAfterTheRendererSelectsTextOnly() throws Exception {
		var descriptor = directory.resolve("native-sprite-provider");
		java.nio.file.Files.writeString(descriptor, NativeSprites.class.getName() + "\n");
		for (LoggingProfile profile : LoggingProfile.values()) {
			try (var discovery = new FreshDiscovery(descriptor.toUri().toURL());
				var channels = new FailureChannels();
				var capture = new IntegrationDiagnosticCapture(Level.ALL, profile);
				var contents = contents("test:discovery-recovery")) {
				var graphics = allocate(RecordingGraphics.class);
				var original = new CountingFailure("discovery original cause");
				NativeSprites.discoveryFailure = original; NativeSprites.constructors = NativeSprites.calls = 0;
				try {
					SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record);
					assertNull(graphics.painted); assertEquals(1, discovery.loader.scans);
					assertEquals(1, NativeSprites.constructors); assertEquals(0, NativeSprites.calls);
					assertEquals(1, capture.events().size());
					String message = capture.events().getFirst().getMessage().getFormattedMessage();
					assertTrue(message.contains("handling=text-only"));
					assertEquals(profile != LoggingProfile.NORMAL, message.contains("discovery original cause"));
					if (profile == LoggingProfile.NORMAL) assertEquals(0, original.reads);
					original.reads = 0;
					SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record);
					assertEquals(2, discovery.loader.scans); assertEquals(2, NativeSprites.constructors);
					assertEquals(0, NativeSprites.calls); assertEquals(1, capture.events().size()); assertEquals(0, original.reads);
					NativeSprites.discoveryFailure = null; NativeSprites.sprite = new Sprite(contents);
					SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record);
					assertSame(NativeSprites.sprite, graphics.painted);
					SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record);
					assertEquals(3, discovery.loader.scans, "only the recovered service is cached, never a discovery failure");
					assertEquals(3, NativeSprites.constructors); assertEquals(2, NativeSprites.calls);
					assertEquals(1, capture.events().size()); assertEquals(0, original.reads);
				} finally { NativeSprites.discoveryFailure = null; NativeSprites.sprite = null; }
			}
		}
	}

	@Test void productionNativePortFailureReachesOneConsumerReportWithoutExtraLookupAndThenRecovers() throws Exception {
		for (LoggingProfile profile : LoggingProfile.values()) {
			for (String failureKind : List.of("runtime", "linkage", "service")) {
				try (var channels = new FailureChannels();
					var capture = new IntegrationDiagnosticCapture(Level.ALL, profile);
					var contents = contents("test:port-recovery")) {
					var graphics = allocate(RecordingGraphics.class);
					var original = new CountingFailure("native port original");
					NativeSprites.calls = 0;
					NativeSprites.failure = switch (failureKind) {
						case "linkage" -> new LinkageError("native dependency", original);
						case "service" -> new java.util.ServiceConfigurationError("native provider", original);
						default -> original;
					};
					try {
						SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record);
						assertNull(graphics.painted); assertEquals(1, NativeSprites.calls);
						assertEquals(1, capture.events().size(), "only the renderer consumes and reports the native failure");
						assertEquals(Level.WARN, capture.events().getFirst().getLevel());
						String message = capture.events().getFirst().getMessage().getFormattedMessage();
						assertTrue(message.contains("handling=text-only"));
						assertEquals(profile != LoggingProfile.NORMAL, message.contains("native port original"));
						if (profile == LoggingProfile.NORMAL) assertEquals(0, original.reads);
						original.reads = 0;
						SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record);
						assertEquals(2, NativeSprites.calls); assertEquals(1, capture.events().size()); assertEquals(0, original.reads);
						NativeSprites.failure = null; NativeSprites.sprite = new Sprite(contents);
						SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record);
						assertSame(NativeSprites.sprite, graphics.painted); assertEquals(3, NativeSprites.calls);
						assertEquals(1, capture.events().size());
					} finally { NativeSprites.failure = null; NativeSprites.sprite = null; }
				}
			}
		}
	}

	@Test void unusableNativePortSpritesRemainQuietAndTryLegacyOnlyOnce() throws Exception {
		try (var channels = new FailureChannels();
			var capture = new IntegrationDiagnosticCapture(Level.ALL, LoggingProfile.DEBUG);
			var missing = contents("minecraft:missingno"); var valid = contents("test:valid")) {
			var legacy = new Sprite(valid);
			for (@Nullable TextureAtlasSprite sprite : new @Nullable TextureAtlasSprite @Unmodifiable [] {
				null, new Sprite(missing), new Sprite(ResourceLocation.parse("test:wrong_atlas"), valid)
			}) {
				AtomicInteger nativeReads = new AtomicInteger();
				AtomicInteger legacyReads = new AtomicInteger();
				assertSame(legacy, SpatialFluidIconRenderer.resolve(Fluids.WATER,
					fluid -> FluidSprites.lookupStillSprite(fluid, requested -> { nativeReads.incrementAndGet(); return sprite; }),
					state -> { legacyReads.incrementAndGet(); return legacy; }));
				assertEquals(1, nativeReads.get()); assertEquals(1, legacyReads.get());
			}
			assertTrue(capture.events().isEmpty(), "ordinary unsupported or missing sprites are not native exceptions");
		}
	}

	@Test void nativePortKeepsLegacyReplacementHandlingAndOriginalCauseWithoutReportingUpstream() throws Exception {
		try (var channels = new FailureChannels();
			var capture = new IntegrationDiagnosticCapture(Level.ALL, LoggingProfile.DEEP);
			var contents = contents("test:port-legacy")) {
			AtomicInteger nativeReads = new AtomicInteger();
			AtomicInteger legacyReads = new AtomicInteger();
			var original = new IllegalStateException("native port cause");
			var legacy = new Sprite(contents);
			assertSame(legacy, SpatialFluidIconRenderer.resolve(Fluids.WATER,
				fluid -> FluidSprites.lookupStillSprite(fluid, requested -> { nativeReads.incrementAndGet(); throw original; }),
				state -> { legacyReads.incrementAndGet(); return legacy; }));
			assertEquals(1, nativeReads.get()); assertEquals(1, legacyReads.get()); assertEquals(1, capture.events().size());
			String message = capture.events().getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("handling=legacy-sprite")); assertTrue(message.contains("native port cause"));
		}
	}

	@Test void ordinaryAbsenceIsQuietButExceptionalTextOnlyAndDrawFailureHaveIndependentNormalSummaries() throws Exception {
		try (var channels = new FailureChannels();
			var capture = new IntegrationDiagnosticCapture(Level.ALL, LoggingProfile.NORMAL);
			var contents = contents("test:diagnostic")) {
			var sprite = new Sprite(contents);
			var graphics = allocate(RecordingGraphics.class);
			AtomicInteger lookups = new AtomicInteger();
			var nativeFailure = new CountingFailure("private native");
			var legacyFailure = new CountingFailure("private legacy");
			assertNull(SpatialFluidIconRenderer.resolve(Fluids.WATER, fluid -> null, state -> null));
			assertTrue(capture.events().isEmpty());
			SpatialFluidIconRenderer.NativeSprite broken = fluid -> { lookups.incrementAndGet(); throw nativeFailure; };
			assertSame(sprite, SpatialFluidIconRenderer.resolve(Fluids.WATER, broken, state -> sprite));
			assertNull(SpatialFluidIconRenderer.resolve(Fluids.WATER, broken, state -> { throw legacyFailure; }));
			assertEquals(2, capture.events().size(), "a legacy replacement must not hide a later terminal text-only failure");
			assertTrue(capture.events().getFirst().getMessage().getFormattedMessage().contains("handling=legacy-sprite"));
			assertTrue(capture.events().getLast().getMessage().getFormattedMessage().contains("handling=text-only"));
			var drawFailure = new CountingFailure("private draw");
			SpatialFluidIconRenderer.draw(graphics, Fluids.WATER, 1234, 5678, .6f, .2f,
				(gui, value, x, y, r, g, b, alpha) -> { throw drawFailure; }, broken, state -> sprite);
			assertEquals(3, capture.events().size(), "resolution degradation cannot consume draw-failure admission");
			assertEquals(List.of(Level.WARN, Level.WARN, Level.ERROR), capture.events().stream().map(event -> event.getLevel()).toList());
			assertTrue(capture.events().getLast().getMessage().getFormattedMessage().contains("draw-failed"));
			assertEquals(0, nativeFailure.reads + legacyFailure.reads + drawFailure.reads);
			assertTrue(capture.events().stream().noneMatch(event -> {
				String message = event.getMessage().getFormattedMessage();
				return message.contains("private") || message.contains("1234") || message.contains("5678");
			}));
			SpatialFluidIconRenderer.draw(graphics, Fluids.WATER, 0, 0, 1, 1, graphics::record, fluid -> sprite,
				state -> { throw new AssertionError("native recovery needs no legacy lookup"); });
			assertSame(sprite, graphics.painted);
			assertEquals(3, lookups.get(), "diagnostics cannot repeat a lookup");
		}
	}

	@Test void deepResolutionRetainsBothOriginalCausesAndSuppressedOrDisabledReportsReadNeitherAgain() throws Exception {
		try (var channels = new FailureChannels();
			var capture = new IntegrationDiagnosticCapture(Level.ALL, LoggingProfile.DEEP)) {
			var nativeFailure = new CountingFailure("native original");
			var legacyFailure = new CountingFailure("legacy original");
			for (int attempt = 0; attempt < 2; attempt++) {
				assertNull(SpatialFluidIconRenderer.resolve(Fluids.WATER, fluid -> { throw nativeFailure; }, state -> { throw legacyFailure; }));
				if (attempt == 0) {
					String message = capture.events().getFirst().getMessage().getFormattedMessage();
					assertTrue(message.contains("native original")); assertTrue(message.contains("legacy original"));
					assertNull(capture.events().getFirst().getThrown());
					nativeFailure.reads = legacyFailure.reads = 0;
				}
			}
			assertEquals(1, capture.events().size());
			assertEquals(0, nativeFailure.reads + legacyFailure.reads);
			capture.level(Level.OFF);
			SpatialFluidIconRenderer.resolve(Fluids.WATER, fluid -> { throw nativeFailure; }, state -> { throw legacyFailure; });
			assertEquals(0, nativeFailure.reads + legacyFailure.reads);
			assertEquals(0, nativeFailure.getSuppressed().length, "the original exceptions are never mutated");
			assertEquals(0, legacyFailure.getSuppressed().length);
		}
	}

	@Test void nonPlaceableFluidStillAttemptsNativeSpriteAndPaintsWithExistingAlpha() throws Exception {
		assertTrue(Fluids.EMPTY.defaultFluidState().createLegacyBlock().isAir());
		try (var contents = contents("test:tea_still")) {
			var sprite = new Sprite(contents);
			var graphics = allocate(RecordingGraphics.class);
			NativeSprites.sprite = sprite; NativeSprites.calls = 0;
			try {
				SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 4, 7, .65f, .37f, graphics::record);
				assertEquals(1, NativeSprites.calls, "legacy AIR cannot bypass the native base-fluid renderer");
				assertSame(sprite, graphics.painted); assertEquals(.37f, graphics.alpha); assertEquals(.65f, graphics.red);
			} finally { NativeSprites.sprite = null; }
		}
	}
	@Test void nullMissingWrongAtlasAndThrowingNativeRoutesTryOnlyNonAirLegacyThenTextOnly() throws Exception {
		try (var valid = contents("test:legacy"); var missing = contents("minecraft:missingno")) {
			var legacy = new Sprite(valid);
			for (SpatialFluidIconRenderer.NativeSprite nativeRoute : new SpatialFluidIconRenderer.NativeSprite[] {
				fluid -> null, fluid -> new Sprite(missing), fluid -> new Sprite(ResourceLocation.parse("test:wrong_atlas"), valid),
				fluid -> { throw new IllegalStateException("native"); }, fluid -> { throw new NoClassDefFoundError("native"); },
				fluid -> { throw new java.util.ServiceConfigurationError("native"); }
			}) {
				int[] attempts = {0};
				assertSame(legacy, SpatialFluidIconRenderer.resolve(Fluids.WATER, nativeRoute, state -> { attempts[0]++; assertFalse(state.isAir()); return legacy; }));
				assertEquals(1, attempts[0]);
				assertNull(SpatialFluidIconRenderer.resolve(Fluids.EMPTY, nativeRoute, state -> { throw new AssertionError("AIR fallback"); }));
				assertNull(SpatialFluidIconRenderer.resolve(Fluids.WATER, nativeRoute, state -> new Sprite(missing)));
				assertNull(SpatialFluidIconRenderer.resolve(Fluids.WATER, nativeRoute, state -> { throw new IllegalStateException("legacy"); }));
			}
			assertFalse(FluidSprites.usable(new Sprite(missing)), "a non-null atlas lookup can still be Minecraft's missing sprite");
		}
	}
	@Test void everyDrawRequeriesSpriteAndFailedDrawRecoversAfterResourceReload() throws Exception {
		try (var oldContents = contents("test:old"); var newContents = contents("test:reloaded")) {
			var first = new Sprite(oldContents); var replacement = new Sprite(newContents);
			var graphics = allocate(RecordingGraphics.class); NativeSprites.calls = 0;
			try {
				NativeSprites.sprite = null; SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record); assertNull(graphics.painted);
				NativeSprites.sprite = first; SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record); assertSame(first, graphics.painted);
				NativeSprites.sprite = replacement; SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record); assertSame(replacement, graphics.painted);
				assertEquals(3, NativeSprites.calls);
				SpatialFluidIconRenderer.draw(graphics, null, 0, 0, 1, 1); SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 0);
				assertEquals(3, NativeSprites.calls, "hidden icon has no native callback");
			} finally { NativeSprites.sprite = null; }
		}
	}
	@Test void waterKeepsExistingTintAndFailedSubmissionLeavesTextOnlyThenRecovers() throws Exception {
		var holder = Fluids.WATER.builtInRegistryHolder();
		var tags = holder.getClass().getDeclaredField("tags"); tags.setAccessible(true);
		@Nullable Object previousTags = tags.get(holder);
		try (var contents = contents("test:water")) {
			tags.set(holder, java.util.Set.of(net.minecraft.tags.FluidTags.WATER));
			var graphics = allocate(RecordingGraphics.class);
			NativeSprites.sprite = new Sprite(contents);
			try {
				SpatialFluidIconRenderer.draw(graphics, Fluids.WATER, 2, 3, .65f, .05f, (gui, sprite, x, y, r, g, b, a) -> {
					throw new IllegalStateException("submission unavailable");
				});
				assertNull(graphics.painted);
				SpatialFluidIconRenderer.draw(graphics, Fluids.WATER, 2, 3, .65f, .05f, graphics::record);
				assertSame(NativeSprites.sprite, graphics.painted);
				assertEquals(63 / 255f * .65f, graphics.red);
				assertEquals(.05f, graphics.alpha);
			} finally { NativeSprites.sprite = null; }
		} finally { tags.set(holder, previousTags); }
	}

	private static SpriteContents contents(String id) {
		return new SpriteContents(ResourceLocation.parse(id), new FrameSize(16, 16), new NativeImage(16, 16, false), ResourceMetadata.EMPTY);
	}

	@NotNullByDefault
	private static final class CountingFailure extends RuntimeException {
		private int reads;
		private CountingFailure(String message) { super(message); }
		@Override public @Nullable String getMessage() { reads++; return super.getMessage(); }
		@Override public synchronized @Nullable Throwable getCause() { reads++; return super.getCause(); }
		@Override public StackTraceElement @Unmodifiable [] getStackTrace() { reads++; return super.getStackTrace(); }
	}

	@NotNullByDefault
	private static final class FreshDiscovery implements AutoCloseable {
		private final java.lang.reflect.Field cached;
		private final @Nullable Object previousService;
		private final @Nullable ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
		private final DescriptorLoader loader;
		private FreshDiscovery(java.net.URL descriptor) throws Exception {
			cached = Class.forName(FluidSprites.class.getName() + "$ServiceHolder").getDeclaredField("cached");
			cached.setAccessible(true); previousService = cached.get(null); cached.set(null, null);
			loader = new DescriptorLoader(descriptor); Thread.currentThread().setContextClassLoader(loader);
		}
		@Override public void close() throws Exception {
			try { cached.set(null, previousService); }
			finally { Thread.currentThread().setContextClassLoader(previousLoader); }
		}
	}
	@NotNullByDefault
	private static final class DescriptorLoader extends ClassLoader {
		private final java.net.URL descriptor;
		private int scans;
		private DescriptorLoader(java.net.URL descriptor) { super(SpatialFluidIconRendererTest.class.getClassLoader()); this.descriptor = descriptor; }
		@Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
			if (name.equals("META-INF/services/" + IPlatformFluidRenderService.class.getName())) {
				scans++;
				return java.util.Collections.enumeration(List.of(descriptor));
			}
			return super.getResources(name);
		}
	}

	/** Scope just this renderer's interval histories without changing production cadence. */
	@NotNullByDefault
	private static final class FailureChannels implements AutoCloseable {
		private final List<SavedField> saved = new java.util.ArrayList<>();
		private FailureChannels() throws Exception {
			for (var ownerField : SpatialFluidIconRenderer.class.getDeclaredFields()) {
				if (ownerField.getType() != DiagnosticChannel.class) continue;
				ownerField.setAccessible(true);
				Object channel = java.util.Objects.requireNonNull(ownerField.get(null));
				var sideField = DiagnosticChannel.class.getDeclaredField("client"); sideField.setAccessible(true);
				Object state = java.util.Objects.requireNonNull(sideField.get(channel));
				for (var field : state.getClass().getDeclaredFields()) {
					field.setAccessible(true); saved.add(new SavedField(state, field, field.get(state)));
					if (field.getType() == boolean.class) field.setBoolean(state, false); else field.setLong(state, 0L);
				}
			}
		}
		@Override public void close() throws Exception { for (var field : saved) field.field().set(field.owner(), field.value()); }
	}
	@NotNullByDefault
	private record SavedField(Object owner, java.lang.reflect.Field field, @Nullable Object value) {}
	@NotNullByDefault
	private static final class Sprite extends TextureAtlasSprite {
		private Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 16, 16, 0, 0); }
		private Sprite(ResourceLocation atlas, SpriteContents contents) { super(atlas, contents, 16, 16, 0, 0); }
	}
	@NotNullByDefault
	public static final class NativeSprites implements IPlatformFluidRenderService {
		static @Nullable TextureAtlasSprite sprite;
		static @Nullable Throwable failure;
		static @Nullable RuntimeException discoveryFailure;
		static int constructors;
		static int calls;
		public NativeSprites() {
			constructors++;
			if (discoveryFailure != null) throw discoveryFailure;
		}
		@Override public @Nullable TextureAtlasSprite stillSprite(Fluid fluid) {
			calls++;
			if (failure instanceof RuntimeException runtime) throw runtime;
			if (failure instanceof Error error) throw error;
			return sprite;
		}
	}
	@NotNullByDefault
	private static final class RecordingGraphics extends GuiGraphics {
		private @Nullable TextureAtlasSprite painted;
		private float red, alpha;
		private RecordingGraphics() { super(null, null); }
		private void record(GuiGraphics graphics, TextureAtlasSprite sprite, int x, int y, float red, float green, float blue, float alpha) {
			this.painted = sprite; this.red = red; this.alpha = alpha;
		}
	}
	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws Exception {
		var unsafeType = Class.forName("sun.misc.Unsafe"); var field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return (T) unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
	}
}
