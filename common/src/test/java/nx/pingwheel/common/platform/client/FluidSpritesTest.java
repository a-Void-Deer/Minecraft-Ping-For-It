package nx.pingwheel.common.platform.client;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Original failures cross the native lookup once; only the consumer decides fallback and reporting. */
@NotNullByDefault
class FluidSpritesTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@TempDir Path directory;

	@Test void nativeFailuresKeepIdentityAndTheCompatibilityEntryStillReturnsNullWithoutAnExtraLookup() {
		for (Throwable failure : List.of(new IllegalStateException("native original"),
			new NoClassDefFoundError("native linkage"), new ServiceConfigurationError("native service"))) {
			AtomicInteger calls = new AtomicInteger();
			IPlatformFluidRenderService service = fluid -> {
				calls.incrementAndGet();
				if (failure instanceof RuntimeException runtime) throw runtime;
				throw (Error) failure;
			};
			assertSame(failure, assertThrows(failure.getClass(), () -> FluidSprites.lookupStillSprite(Fluids.WATER, service)));
			assertEquals(1, calls.get());
			assertNull(FluidSprites.stillSprite(Fluids.WATER, service));
			assertEquals(2, calls.get(), "the compatibility entry performs exactly its own one lookup");
		}
	}

	@Test void absenceRemainsNullAndFatalFailuresStillPropagateFromBothEntries() {
		assertNull(FluidSprites.lookupStillSprite(Fluids.WATER, null));
		AtomicInteger calls = new AtomicInteger();
		assertNull(FluidSprites.lookupStillSprite(Fluids.WATER, fluid -> { calls.incrementAndGet(); return null; }));
		assertEquals(1, calls.get());
		var fatal = new OutOfMemoryError("sentinel only");
		IPlatformFluidRenderService service = fluid -> { throw fatal; };
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> FluidSprites.lookupStillSprite(Fluids.WATER, service)));
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> FluidSprites.stillSprite(Fluids.WATER, service)));
	}

	@Test void discoveryFailureCarriesItsOriginalCauseAndOnlyASuccessfulServiceIsCached() throws Exception {
		Path descriptor = directory.resolve("provider"); Files.writeString(descriptor, DiscoveryProvider.class.getName() + "\n");
		var loader = new SpriteLookupLoader(descriptor.toUri().toURL());
		@Nullable var previous = Thread.currentThread().getContextClassLoader();
		DiscoveryProvider.constructors = DiscoveryProvider.lookups = 0;
		var original = new IllegalStateException("discovery constructor original");
		DiscoveryProvider.failure = original;
		Thread.currentThread().setContextClassLoader(loader);
		try {
			var sprites = loader.loadClass(FluidSprites.class.getName());
			var lookup = sprites.getMethod("lookupStillSprite", Fluid.class);
			var compatible = sprites.getMethod("stillSprite", Fluid.class);
			var thrown = assertThrows(InvocationTargetException.class, () -> lookup.invoke(null, Fluids.WATER));
			var discoveryFailure = assertInstanceOf(ServiceConfigurationError.class, thrown.getCause());
			assertSame(original, discoveryFailure.getCause(), "keep ServiceLoader's real graph without formatting or replay storage");
			assertEquals(1, loader.scans); assertEquals(1, DiscoveryProvider.constructors); assertEquals(0, DiscoveryProvider.lookups);
			assertNull(compatible.invoke(null, Fluids.WATER));
			assertEquals(2, loader.scans); assertEquals(2, DiscoveryProvider.constructors);
			DiscoveryProvider.failure = null;
			assertNull(lookup.invoke(null, Fluids.WATER));
			assertNull(lookup.invoke(null, Fluids.WATER));
			assertNull(compatible.invoke(null, Fluids.WATER));
			assertEquals(3, loader.scans, "successful installation alone ends the existing discovery retries");
			assertEquals(3, DiscoveryProvider.constructors); assertEquals(3, DiscoveryProvider.lookups);
		} finally {
			Thread.currentThread().setContextClassLoader(previous);
			DiscoveryProvider.failure = null;
		}
	}

	@Test void missingServiceStaysQuietAndRetainsTheExistingOneDiscoveryPerUncachedCall() throws Exception {
		var loader = new SpriteLookupLoader(null);
		@Nullable var previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(loader);
		try {
			var sprites = loader.loadClass(FluidSprites.class.getName());
			assertNull(sprites.getMethod("lookupStillSprite", Fluid.class).invoke(null, Fluids.WATER));
			assertNull(sprites.getMethod("stillSprite", Fluid.class).invoke(null, Fluids.WATER));
			assertEquals(2, loader.scans, "do not introduce a second discovery or new negative cache");
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}

	@NotNullByDefault
	public static final class DiscoveryProvider implements IPlatformFluidRenderService {
		static @Nullable RuntimeException failure;
		static int constructors;
		static int lookups;
		public DiscoveryProvider() {
			constructors++;
			if (failure != null) throw failure;
		}
		@Override public @Nullable TextureAtlasSprite stillSprite(Fluid fluid) { lookups++; return null; }
	}

	/** Fresh holder state and a controlled descriptor, using the real ServiceLoader path. */
	@NotNullByDefault
	private static final class SpriteLookupLoader extends ClassLoader {
		private final @Nullable URL descriptor;
		private int scans;
		private SpriteLookupLoader(@Nullable URL descriptor) { super(FluidSpritesTest.class.getClassLoader()); this.descriptor = descriptor; }
		@Override public Enumeration<URL> getResources(String name) throws IOException {
			if (name.equals("META-INF/services/" + IPlatformFluidRenderService.class.getName())) {
				scans++;
				return descriptor == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(descriptor));
			}
			return super.getResources(name);
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.equals(FluidSprites.class.getName()) && !name.startsWith(FluidSprites.class.getName() + '$'))
				return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				@Nullable Class<?> found = findLoadedClass(name);
				if (found == null) try (@Nullable InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
					if (input == null) throw new ClassNotFoundException(name);
					byte @Unmodifiable [] bytes = input.readAllBytes(); found = defineClass(name, bytes, 0, bytes.length);
				} catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
				if (resolve) resolveClass(found); return found;
			}
		}
	}
}
