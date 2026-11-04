package nx.pingwheel.common.testing;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Explicit registry of every test class the offline harness runs.
 *
 * <p>Registration is deliberately a hand-written list instead of a classpath scan:
 * the harness has to behave identically when {@code javac} writes its classes to a
 * throw-away directory, and a scan would silently pick up a different set.
 *
 * <p>The list holds <em>names</em>, not {@code Class} literals: the ported upstream
 * tests are package-private (exactly as upstream wrote them), so referencing them
 * from this package would not compile. They are resolved with
 * {@link Class#forName(String)} instead, and reflection opens their members.
 *
 * <p>Sources of the cases:
 * <ul>
 *   <li>{@code config/*}, {@code core/*}, {@code domain/*}, {@code integration/*},
 *       {@code interaction/*}, {@code marker/*}, {@code math/*}, {@code name/*},
 *       {@code registry/*}, {@code resolve/*}, {@code util/*} — ported 1:1 from the
 *       upstream 1.21.1 test tree (only the JUnit imports were rewritten);</li>
 *   <li>{@code util/RateLimiterTest} — written for this port (review 65 G2: upstream
 *       ships no RateLimiter test).</li>
 * </ul>
 */
public final class AllTests {

	private static final List<String> TEST_CLASS_NAMES = List.of(
		"nx.pingwheel.common.config.ClientConfigBoundsTest",
		"nx.pingwheel.common.config.MavenComparableVersionTest",
		"nx.pingwheel.common.config.PingForItVersionTest",
		"nx.pingwheel.common.config.ServerConfigBoundsTest",
		"nx.pingwheel.common.core.RenderEntityLookupCacheTest",
		"nx.pingwheel.common.core.SyncDurationPolicyTrackerTest",
		"nx.pingwheel.common.domain.EntityCaptureMetadataTest",
		"nx.pingwheel.common.domain.EntityLocatorTest",
		"nx.pingwheel.common.domain.MarkerIdTest",
		"nx.pingwheel.common.domain.PingTypeCatalogTest",
		"nx.pingwheel.common.domain.ResolvedTargetTest",
		"nx.pingwheel.common.domain.TargetMatchContextTest",
		"nx.pingwheel.common.domain.TargetTypeCatalogTest",
		"nx.pingwheel.common.integration.externalblock.ExternalBlockReferenceIndexTest",
		"nx.pingwheel.common.integration.sable.SableDiagnosticsTest",
		"nx.pingwheel.common.integration.sable.server.SableRefreshLogGateTest",
		"nx.pingwheel.common.interaction.ActiveInteractionTest",
		"nx.pingwheel.common.interaction.CapturedRayTest",
		"nx.pingwheel.common.interaction.InteractionTokenTest",
		"nx.pingwheel.common.interaction.PingCaptureCoordinatorTest",
		"nx.pingwheel.common.interaction.TargetSnapshotTest",
		"nx.pingwheel.common.interaction.cancel.CancelCandidatePickerTest",
		"nx.pingwheel.common.interaction.cancel.CancelMarkerCandidateTest",
		"nx.pingwheel.common.interaction.cancel.CancellationContextTest",
		"nx.pingwheel.common.interaction.cancel.MarkerCandidatePositionTest",
		"nx.pingwheel.common.interaction.cancel.WorldVectorTest",
		"nx.pingwheel.common.interaction.state.InteractionTimeSourceTest",
		"nx.pingwheel.common.interaction.state.PingInteractionActionTest",
		"nx.pingwheel.common.interaction.state.PingInteractionLoggerTest",
		"nx.pingwheel.common.interaction.state.PingInteractionStateMachineTest",
		"nx.pingwheel.common.interaction.state.TargetValidationTest",
		"nx.pingwheel.common.interaction.wheel.WheelSelectionTest",
		"nx.pingwheel.common.marker.AuthoritativeTargetValidationTest",
		"nx.pingwheel.common.marker.MarkerAnchorTest",
		"nx.pingwheel.common.marker.MarkerCreateOutcomeTest",
		"nx.pingwheel.common.marker.MarkerIdSourceTest",
		"nx.pingwheel.common.marker.MarkerRemovalReasonTest",
		"nx.pingwheel.common.marker.MarkerSnapshotTest",
		"nx.pingwheel.common.marker.MarkerWinnerTest",
		"nx.pingwheel.common.marker.ServerEntityLocatorPolicyTest",
		"nx.pingwheel.common.marker.ServerMarkerStoreTest",
		"nx.pingwheel.common.marker.ServerMarkerTest",
		"nx.pingwheel.common.marker.TargetKeyTest",
		"nx.pingwheel.common.marker.ValidatedMarkerTargetTest",
		"nx.pingwheel.common.math.RaycastPolicyTest",
		"nx.pingwheel.common.name.ClientTargetNameStoreTest",
		"nx.pingwheel.common.name.TargetNameJsonTest",
		"nx.pingwheel.common.registry.OptionalRegistryRefTest",
		"nx.pingwheel.common.resolve.BuiltInTargetMatchersTest",
		"nx.pingwheel.common.resolve.DefaultTargetResolverTest",
		"nx.pingwheel.common.resolve.RegistryBackedTargetMatcherTest",
		"nx.pingwheel.common.resolve.TargetMatcherRegistryTest",
		"nx.pingwheel.common.util.ClaimedInputStateTest",
		"nx.pingwheel.common.util.PingClickArbitrationTest",
		"nx.pingwheel.common.util.RateLimiterTest",
		"nx.pingwheel.common.util.SafeExceptionReportTest",
		"nx.pingwheel.common.util.ThrowableCausesTest",
		"nx.pingwheel.common.util.ToggleInputStateTest",
		"nx.pingwheel.common.util.WeakIdentityCacheTest"
	);

	private AllTests() {
	}

	/** Resolved test classes, in registration order. */
	public static List<Class<?>> testClasses() {
		List<Class<?>> classes = new ArrayList<>(TEST_CLASS_NAMES.size());
		for (String name : TEST_CLASS_NAMES) {
			try {
				classes.add(Class.forName(name));
			} catch (ClassNotFoundException missing) {
				throw new IllegalStateException("registered test class is missing: " + name, missing);
			}
		}
		return classes;
	}

	/** Builds one {@link TestCase} per {@code @Test} method, sorted by display name. */
	public static List<TestCase> collect() {
		List<TestCase> cases = new ArrayList<>();

		for (Class<?> type : testClasses()) {
			for (Method method : type.getDeclaredMethods()) {
				if (!method.isAnnotationPresent(Test.class)) {
					continue;
				}
				cases.add(newCase(type, method));
			}
		}

		cases.sort(Comparator.comparing(TestCase::name));
		return cases;
	}

	private static TestCase newCase(Class<?> type, Method method) {
		String displayName = displayPrefix(type) + "." + method.getName();

		return new TestCase() {
			@Override
			public String name() {
				return displayName;
			}

			@Override
			public void run() throws Exception {
				Object instance = newInstance(type);
				invokeBeforeEach(instance);
				invoke(instance, method);
			}
		};
	}

	private static String displayPrefix(Class<?> type) {
		String packageName = type.getPackageName();
		String relative = packageName.startsWith("nx.pingwheel.common.")
			? packageName.substring("nx.pingwheel.common.".length()).replace('.', '/')
			: packageName.replace('.', '/');
		return relative.isEmpty() ? type.getSimpleName() : relative + "/" + type.getSimpleName();
	}

	private static Object newInstance(Class<?> type) throws Exception {
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static void invokeBeforeEach(Object instance) throws Exception {
		List<Method> hooks = new ArrayList<>();
		for (Method candidate : instance.getClass().getDeclaredMethods()) {
			if (candidate.isAnnotationPresent(BeforeEach.class)) {
				hooks.add(candidate);
			}
		}
		for (Method hook : hooks) {
			invoke(instance, hook);
		}
	}

	private static void invoke(Object instance, Method method) throws Exception {
		method.setAccessible(true);
		try {
			method.invoke(instance);
		} catch (InvocationTargetException failure) {
			Throwable cause = failure.getCause();
			if (cause instanceof Exception checked) {
				throw checked;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw failure;
		}
	}
}
