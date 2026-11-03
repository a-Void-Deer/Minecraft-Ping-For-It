package nx.pingwheel.common.platform;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Enumeration;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.CaptureLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Server-safe optional service discovery. Registration refusal never supplies an invented key. */
@NotNullByDefault
public final class FluidRegistries {

	private static final int MAX_DESCRIPTORS = 8;
	private static final int MAX_DESCRIPTOR_BYTES = 1024;
	private static final int MAX_LAYERS = 16;
	private static final int MAX_MODULES = 1024;
	private static final int MAX_DECLARATIONS = 4096;
	// OpenJDK21/25: BufferedReader char[8192] + StreamDecoder byte[8192].
	// Per input byte: <=one line String/backing, comment/trim copies, two set nodes,
	// two hash-table growth sequences and bounded syntax/error text. 128 B/byte
	// dominates their cumulative allocation (including discarded copies), not live heap.
	// The fixed row covers readers/decoder/URL wrappers, one provider reflection result,
	// constructor invocation wrappers and the 16-node hard-failure scan, with headers.
	private static final long DESCRIPTOR_ALLOCATION = 24576 + 32768 + 128L * MAX_DESCRIPTOR_BYTES;
	// UTF8 decode, line/comment/trim/name scans, String hashing and collision/tree
	// comparisons are <=256 events/input byte; fixed calls/errors/reflection <=32768.
	private static final long DESCRIPTOR_WORK = 32768 + 256L * MAX_DESCRIPTOR_BYTES;
	private static final long DISCOVERY_ALLOCATION = 65536;
	private static final long DISCOVERY_WORK = 65536;
	private static @Nullable IPlatformFluidRegistryService cached;
	private static boolean discovered;
	private static boolean discovering;
	private FluidRegistries() {}

	public static @Nullable ResourceLocation registeredKey(Fluid fluid, IPlatformFluidRegistryService.Admission admission) {
		admission.admit(128, 0);
		@Nullable IPlatformFluidRegistryService service = service(admission);
		return registeredKey(fluid, admission, service);
	}

	/** No catch around the admitted lookup: its budget exception must retain its original classification. */
	public static @Nullable ResourceLocation registeredKey(Fluid fluid, IPlatformFluidRegistryService.Admission admission,
		@Nullable IPlatformFluidRegistryService service) {
		return service == null ? null : service.registeredKey(fluid, admission);
	}

	private static synchronized @Nullable IPlatformFluidRegistryService service(IPlatformFluidRegistryService.Admission admission) {
		if (!discovered && !discovering) {
			// Before ClassLoader/ServiceLoader, descriptor enumeration, reader construction or
			// module inspection. Admission failures are outside optional failure handling.
			admission.admit(DISCOVERY_WORK, DISCOVERY_ALLOCATION);
			discovering = true;
			try {
				var discoveryAdmission = new DiscoveryAdmission(admission);
				try { cached = discover(discoveryAdmission); discovered = true; }
				catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) {
					if (discoveryAdmission.failure != null) throw discoveryAdmission.failure;
					rethrowHardFailure(unavailable); discovered = true; return null;
				}
			} finally { discovering = false; }
		}
		return cached;
	}
	@NotNullByDefault
	private static final class DiscoveryAdmission implements IPlatformFluidRegistryService.Admission {
		private final IPlatformFluidRegistryService.Admission delegate;
		private @Nullable RuntimeException failure;
		private DiscoveryAdmission(IPlatformFluidRegistryService.Admission delegate) { this.delegate = delegate; }
		@Override public void admit(long workUnits, long allocationBytes) {
			try { delegate.admit(workUnits, allocationBytes); }
			catch (RuntimeException refused) { failure = refused; throw refused; }
		}
	}

	private static @Nullable IPlatformFluidRegistryService discover(IPlatformFluidRegistryService.Admission admission) {
		Module module = IPlatformFluidRegistryService.class.getModule();
		if (module.isNamed()) {
			@Nullable ModuleLayer layer = module.getLayer();
			if (layer == null || !admitLayers(layer, admission)) return null;
			// Preserve JDK named-module provider/factory/access semantics (including automatic
			// Forge modules). The audited layer graph bounds JDK's DFS/catalogue allocations.
			return ServiceLoader.load(layer, IPlatformFluidRegistryService.class).findFirst().orElse(null);
		}
		return ServiceLoader.load(IPlatformFluidRegistryService.class,
			new DescriptorLoader(Thread.currentThread().getContextClassLoader(), admission)).findFirst().orElse(null);
	}

	private static boolean admitLayers(ModuleLayer root, IPlatformFluidRegistryService.Admission admission) {
		// Fixed worklist; no copied module graph. Every shape is admitted before iteration.
		@Nullable ModuleLayer[] layers = new ModuleLayer[MAX_LAYERS]; layers[0] = root;
		int size = 1, modules = 0, declarations = 0, providers = 0;
		for (int index = 0; index < size; index++) {
			var layer = java.util.Objects.requireNonNull(layers[index]);
			// modules() lazily copies nameToModule.values into a Set. The immutable
			// resolved configuration supplies its cardinality before that owned copy.
			int parents = layer.parents().size(), count = layer.configuration().modules().size();
			if (parents > MAX_LAYERS || count > MAX_MODULES - modules) return false;
			// Set.copyOf's immutable open table can probe all earlier Module identities;
			// pay its worst collision prefix before the first lazy modules() call.
			admission.admit(1024 + 128L * (parents + count) + 16L * count * (count + 1),
				1024 + 128L * (parents + count));
			modules += count;
			for (var parent : layer.parents()) {
				boolean seen = false;
				for (int previous = 0; previous < size; previous++) if (layers[previous] == parent) { seen = true; break; }
				if (!seen) { if (size == MAX_LAYERS) return false; layers[size++] = parent; }
			}
			for (var member : layer.modules()) {
				var provides = member.getDescriptor().provides();
				if (provides.size() > MAX_DECLARATIONS - declarations) return false;
				admission.admit(128L * (provides.size() + 1), 128L * (provides.size() + 1));
				declarations += provides.size();
				for (var declaration : provides) {
					String service = declaration.service();
					if (service.length() > 256) return false;
					int countProviders = declaration.providers().size();
					if (countProviders > MAX_DECLARATIONS - providers) return false;
					// A cold ModuleLayer ServicesCatalog registers ALL service declarations,
					// not only ours. CopyOnWriteArrayList appends copy the prior provider vector;
					// even if every declaration shares one service, P bounds each copied prefix.
					// String hashing/collision comparisons pay the bounded full service name.
					long prefix = providers + (long) countProviders + 1;
					// Exact String keys are Comparable: CHM collision bins use the audited
					// red/black tree after their bounded list prefix, <=2*ceil(log2 D)+2
					// comparisons. No arbitrary key equality or hash callback is involved.
					int levels = 32 - Integer.numberOfLeadingZeros(declarations + 1);
					admission.admit(128L * (service.length() + 1) * (levels + 2)
						+ 128L * (countProviders + 1) + 32L * countProviders * prefix,
						256L * (countProviders + 1) + 16L * countProviders * prefix);
					providers += countProviders;
					for (String provider : declaration.providers()) if (provider.length() > 256) return false;
					if (service.equals(IPlatformFluidRegistryService.class.getName())) {
						if (countProviders > MAX_DESCRIPTORS) return false;
						admission.admit(32768, 32768); // selected provider/reflection/error descriptors
					}
				}
			}
		}
		return true;
	}

	/** Class/resource lookup and provider constructor internals are external-trusted calls.
	 * The JDK-created parser, its readers and our wrappers remain owned and prepaid.
	 * A parentless facade prevents JDK module-catalogue traversal on this classpath path;
	 * classes still come from the actual context loader. Named modules use the layer path. */
	@NotNullByDefault
	private static final class DescriptorLoader extends ClassLoader {
		private final @Nullable ClassLoader delegate;
		private final IPlatformFluidRegistryService.Admission admission;
		private DescriptorLoader(@Nullable ClassLoader delegate, IPlatformFluidRegistryService.Admission admission) {
			super(null); this.delegate = delegate; this.admission = admission;
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			return delegate == null ? ClassLoader.getSystemClassLoader().loadClass(name) : delegate.loadClass(name);
		}
		@Override public Enumeration<URL> getResources(String name) throws IOException {
			var resources = delegate == null ? ClassLoader.getSystemResources(name) : delegate.getResources(name);
			return new DescriptorResources(resources, admission);
		}
	}
	@NotNullByDefault
	private static final class DescriptorResources implements Enumeration<URL> {
		private final Enumeration<URL> delegate;
		private final IPlatformFluidRegistryService.Admission admission;
		private int count;
		private DescriptorResources(Enumeration<URL> delegate, IPlatformFluidRegistryService.Admission admission) {
			this.delegate = delegate; this.admission = admission;
		}
		@Override public boolean hasMoreElements() {
			boolean more = delegate.hasMoreElements();
			if (more && count == MAX_DESCRIPTORS) throw new ServiceConfigurationError("fluid registry descriptor count bound");
			return more;
		}
		@Override public URL nextElement() {
			if (count == MAX_DESCRIPTORS) throw new ServiceConfigurationError("fluid registry descriptor count bound");
			// Before delegate URL/open/reader, including failed and empty descriptors. A budget
			// refusal is not a missing service and must remain retryable, not cache null.
			admission.admit(DESCRIPTOR_WORK, DESCRIPTOR_ALLOCATION);
			count++;
			try { return URL.of(java.net.URI.create("pfi-fluid-registry:descriptor"), new DescriptorHandler(delegate.nextElement())); }
			catch (IOException impossible) { throw new ServiceConfigurationError("fluid registry descriptor URL", impossible); }
		}
	}
	@NotNullByDefault
	private static final class DescriptorHandler extends URLStreamHandler {
		private final URL delegate;
		private DescriptorHandler(URL delegate) { this.delegate = delegate; }
		@Override protected URLConnection openConnection(URL url) throws IOException { return new DescriptorConnection(url, delegate); }
	}
	@NotNullByDefault
	private static final class DescriptorConnection extends URLConnection {
		private final URL delegate;
		private DescriptorConnection(URL url, URL delegate) { super(url); this.delegate = delegate; }
		@Override public void connect() {}
		@Override public InputStream getInputStream() throws IOException {
			var connection = delegate.openConnection(); connection.setUseCaches(false);
			return new DescriptorStream(connection.getInputStream());
		}
	}
	@NotNullByDefault
	private static final class DescriptorStream extends FilterInputStream {
		private int bytes;
		private DescriptorStream(InputStream input) { super(input); }
		@Override public int read() throws IOException {
			int value = in.read(); if (value >= 0) accepted(1); return value;
		}
		@Override public int read(byte[] buffer, int offset, int length) throws IOException {
			int read = in.read(buffer, offset, Math.min(length, MAX_DESCRIPTOR_BYTES + 1 - bytes));
			if (read > 0) accepted(read); return read;
		}
		private void accepted(int count) {
			bytes += count;
			if (bytes > MAX_DESCRIPTOR_BYTES) throw new ServiceConfigurationError("fluid registry descriptor byte bound");
		}
	}

	// Same closed standard-wrapper rule as base-name discovery, without reading opaque cause overrides.
	@SuppressWarnings("removal")
	private static void rethrowHardFailure(Throwable failure) {
		@Nullable Throwable[] seen = new Throwable[16];
		@Nullable Throwable current = failure;
		int count = 0;
		while (current != null) {
			if (current instanceof CaptureLimitException exhausted) throw exhausted;
			if (current instanceof VirtualMachineError fatal) throw fatal;
			if (current instanceof ThreadDeath death) throw death;
			Class<?> type = current.getClass();
			if (type != ServiceConfigurationError.class && type != ExceptionInInitializerError.class
				&& type != java.lang.reflect.InvocationTargetException.class && type != java.lang.reflect.UndeclaredThrowableException.class
				&& type != java.util.concurrent.ExecutionException.class && type != java.util.concurrent.CompletionException.class
				&& type != RuntimeException.class && type != IllegalStateException.class && type != IllegalArgumentException.class
				&& type != LinkageError.class && type != NoClassDefFoundError.class) return;
			for (int index = 0; index < count; index++) if (seen[index] == current) throwUnresolved(failure);
			if (count == seen.length) throwUnresolved(failure);
			seen[count++] = current; current = current.getCause();
		}
	}
	private static void throwUnresolved(Throwable failure) {
		if (failure instanceof RuntimeException runtime) throw runtime;
		throw (Error) failure;
	}
}
