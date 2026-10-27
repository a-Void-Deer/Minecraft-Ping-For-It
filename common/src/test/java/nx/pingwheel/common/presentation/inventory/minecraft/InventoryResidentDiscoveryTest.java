package nx.pingwheel.common.presentation.inventory.minecraft;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.ServiceConfigurationError;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Each discovery starts in its own defining loader, independent of suite initialization order. */
@NotNullByDefault
class InventoryResidentDiscoveryTest {
	private static final String PORT = InventoryResidentAccess.class.getName();
	private static final String SCRATCH = InventoryContainerReads.Scratch.class.getName();
	private static final String SERVICE = "META-INF/services/" + PORT;
	@TempDir Path directory;

	@NotNullByDefault
	public static final class CountingProvider implements InventoryResidentAccess {
		public static int constructions;
		public CountingProvider() { constructions++; }
	}
	@NotNullByDefault
	public static final class ThrowingProvider implements InventoryResidentAccess {
		public ThrowingProvider() { throw new IllegalStateException("resident discovery failure"); }
	}

	@NotNullByDefault
	private static final class IsolatedLoader extends URLClassLoader {
		IsolatedLoader(Path directory) throws IOException {
			super(new URL[] {directory.toUri().toURL()}, InventoryResidentDiscoveryTest.class.getClassLoader());
		}
		@Override public Enumeration<URL> getResources(String name) throws IOException {
			return name.equals(SERVICE) ? findResources(name) : super.getResources(name);
		}
		@Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!(name.equals(PORT) || name.startsWith(PORT + "$") || name.equals(SCRATCH)
				|| name.equals(CountingProvider.class.getName()) || name.equals(ThrowingProvider.class.getName())))
				return super.loadClass(name, resolve);
			@Nullable Class<?> type = findLoadedClass(name);
			if (type == null) {
				try (var stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
					if (stream == null) throw new ClassNotFoundException(name);
					byte[] bytes = stream.readAllBytes(); type = defineClass(name, bytes, 0, bytes.length);
				} catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
			}
			if (resolve) resolveClass(type);
			return type;
		}
	}

	private Path service(String name, @Nullable Class<?> provider) throws IOException {
		Path root = directory.resolve(name);
		Files.createDirectories(root.resolve("META-INF/services"));
		if (provider != null) Files.writeString(root.resolve(SERVICE), provider.getName());
		return root;
	}

	@Test void firstContextDiscoveryIsLazyAndSharedByIndependentRequestsAndScratch() throws Exception {
		@Nullable ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try (var loader = new IsolatedLoader(service("selected", CountingProvider.class));
			var laterContext = new IsolatedLoader(service("later", ThrowingProvider.class))) {
			Thread.currentThread().setContextClassLoader(loader);
			var port = Class.forName(PORT, true, loader);
			var provider = Class.forName(CountingProvider.class.getName(), true, loader);
			assertEquals(0, provider.getField("constructions").getInt(null), "interface initialization must not discover a service");
			Object first = port.getMethod("load").invoke(null);
			assertEquals(1, provider.getField("constructions").getInt(null));
			Thread.currentThread().setContextClassLoader(laterContext);
			var scratch = Class.forName(SCRATCH, true, loader);
			var raw = scratch.getDeclaredField("raw"); raw.setAccessible(true);
			for (int request = 0; request < 8; request++) {
				assertSame(first, port.getMethod("load").invoke(null));
				assertSame(first, raw.get(scratch.getConstructor().newInstance()));
			}
			Object injected = provider.getConstructor().newInstance();
			assertSame(injected, raw.get(scratch.getConstructor(port).newInstance(injected)), "explicit raw ports stay independent");
			assertEquals(2, provider.getField("constructions").getInt(null), "only the deliberate explicit construction adds a port");
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}

	@Test void absentServiceUsesMixinAndDoesNotRediscoverAfterContextChanges() throws Exception {
		@Nullable ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try (var loader = new IsolatedLoader(service("absent", null));
			var laterContext = new IsolatedLoader(service("available", CountingProvider.class))) {
			Thread.currentThread().setContextClassLoader(loader);
			var port = Class.forName(PORT, true, loader);
			Object first = port.getMethod("load").invoke(null);
			assertSame(port.getField("MIXIN").get(null), first);
			Thread.currentThread().setContextClassLoader(laterContext);
			assertSame(first, port.getMethod("load").invoke(null));
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}

	@Test void firstDiscoveryUsesTheRequestingContextRatherThanTheDefiningLoadersResources() throws Exception {
		@Nullable ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try (var loader = new IsolatedLoader(service("defining", null));
			var context = new URLClassLoader(new URL[] {service("context", CountingProvider.class).toUri().toURL()}, loader)) {
			var port = Class.forName(PORT, true, loader);
			Thread.currentThread().setContextClassLoader(context);
			Object selected = port.getMethod("load").invoke(null);
			assertEquals(CountingProvider.class.getName(), selected.getClass().getName());
			assertNotSame(port.getField("MIXIN").get(null), selected);
			assertEquals(1, selected.getClass().getField("constructions").getInt(null));
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}

	@Test void discoveryFailureRemainsVisibleAndCannotBecomeAMixinFallback() throws Exception {
		@Nullable ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try (var loader = new IsolatedLoader(service("failing", ThrowingProvider.class))) {
			Thread.currentThread().setContextClassLoader(loader);
			var port = Class.forName(PORT, true, loader);
			var first = assertThrows(InvocationTargetException.class, () -> port.getMethod("load").invoke(null));
			var failure = assertInstanceOf(ServiceConfigurationError.class, first.getCause());
			assertInstanceOf(IllegalStateException.class, failure.getCause());
			assertEquals("resident discovery failure", failure.getCause().getMessage());
			var retry = assertThrows(InvocationTargetException.class, () -> port.getMethod("load").invoke(null));
			assertInstanceOf(NoClassDefFoundError.class, retry.getCause(), "failed holder initialization is not an empty service result");
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
}
