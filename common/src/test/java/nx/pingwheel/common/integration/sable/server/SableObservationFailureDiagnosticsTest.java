package nx.pingwheel.common.integration.sable.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import nx.pingwheel.common.integration.IntegrationLinkGuard;
import nx.pingwheel.common.integration.externalblock.ExternalBlockReferenceIndex;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.sable.SableDiagnostics;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real observation catch using a throwing reflective tracking-point API. */
@NotNullByDefault
class SableObservationFailureDiagnosticsTest {
	@BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test void refreshDomainTransitionReportsAfterDebugOnlyStateAndDoesNotAddASecondWindow()
		throws ReflectiveOperationException {
		try (var capture = new IntegrationDiagnosticCapture(org.apache.logging.log4j.Level.WARN)) {
			var provider = token(SableExternalBlockServerProvider.class);
			set(provider, "diagnostics", SableDiagnostics.global());
			Class<?> stateType = nested("ServerState");
			var stateConstructor = stateType.getDeclaredConstructor();
			stateConstructor.setAccessible(true);
			Object state = stateConstructor.newInstance();
			var invalid = SableExternalBlockServerProvider.class.getDeclaredMethod("refreshInvalid",
				stateType, String.class, Target.ExternalBlockTarget.class, String.class, Throwable.class);
			invalid.setAccessible(true);
			var target = Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "stable",
				"minecraft:stone", "opaque", false);
			invalid.invoke(provider, state, "stable", target, "same-state", null);
			var failure = new IllegalStateException("original refresh cause");
			invalid.invoke(provider, state, "stable", target, "same-state", failure);
			invalid.invoke(provider, state, "stable", target, "same-state", failure);
			invalid.invoke(provider, state, "other-stable", target, "same-state", failure);
			assertEquals(2, capture.events().size(), "separate domain targets have no shared ten-second suppression");
			assertTrue(capture.events().stream().allMatch(event ->
				event.getMessage().getFormattedMessage().contains("original refresh cause") && event.getThrown() == null));
		}
	}

	@Test void runtimeAndLinkageFailuresKeepTemporaryObservationAndSharedDisableRefreshSemantics()
		throws ReflectiveOperationException {
		for (boolean linkage : new boolean[] {false, true}) {
			try (var capture = new IntegrationDiagnosticCapture(org.apache.logging.log4j.Level.WARN)) {
				var provider = token(SableExternalBlockServerProvider.class);
				var guard = new IntegrationLinkGuard("sable");
				set(provider, "linkGuard", guard);
				set(provider, "diagnostics", SableDiagnostics.global());
				Class<?> apiType = nested("ReflectiveApi");
				var apiConstructor = apiType.getDeclaredConstructors()[0];
				apiConstructor.setAccessible(true);
				Method failure = ThrowingPoint.class.getMethod(linkage ? "linkage" : "runtime", UUID.class);
				Object[] methods = new Object[apiType.getRecordComponents().length];
				Arrays.fill(methods, failure);
				set(provider, "api", apiConstructor.newInstance(methods));

				MinecraftServer server = (MinecraftServer) token(Class.forName("net.minecraft.server.dedicated.DedicatedServer"));
				ServerLevel level = token(ServerLevel.class);
				set(level, "server", server);
				set(level, "dimension", Level.OVERWORLD);
				Class<?> stateType = nested("ServerState");
				var stateConstructor = stateType.getDeclaredConstructor();
				stateConstructor.setAccessible(true);
				Object state = stateConstructor.newInstance();
				@Unmodifiable Map<MinecraftServer, Object> servers = Map.of(server, state);
				set(provider, "servers", servers);
				UUID id = UUID.randomUUID();
				var references = (ExternalBlockReferenceIndex) get(state, "references");
				references.commit(references.prepare(new ExternalBlockReferenceIndex.LocatorKey(
					"sable", "opaque", "minecraft:stone", false), id::toString));
				var entryConstructor = nested("Entry").getDeclaredConstructor(UUID.class, Object.class);
				entryConstructor.setAccessible(true);
				@SuppressWarnings("unchecked") Map<String, Object> entries = (Map<String, Object>) get(state, "entries");
				entries.put(id.toString(), entryConstructor.newInstance(id, new Object()));
				var target = Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", id.toString(),
					"minecraft:stone", "opaque", false);
				assertInstanceOf(ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable.class,
					provider.observeBlock(level, target));
				assertEquals(linkage, guard.disabled());
				assertEquals(1, capture.events().size());
				var event = capture.events().getFirst();
				assertEquals(org.apache.logging.log4j.Level.WARN, event.getLevel());
				assertTrue(event.getMessage().getFormattedMessage().contains("original observation cause"));
				assertNull(event.getThrown());
				assertEquals(1, references.references(id.toString()));
				assertEquals(1, entries.size());
				if (linkage) assertInstanceOf(ExternalBlockServerProvider.RefreshResult.Invalid.class,
					provider.refresh(level, target));
			}
		}
	}

	@NotNullByDefault
	public static final class ThrowingPoint {
		public static Object runtime(UUID ignored) { throw new IllegalStateException("original observation cause"); }
		public static Object linkage(UUID ignored) { throw new LinkageError("original observation cause"); }
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(SableExternalBlockServerProvider.class.getName() + "$" + name);
	}
	private static Object get(Object target, String name) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return java.util.Objects.requireNonNull(field.get(target));
	}
	private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
		Class<?> owner = target.getClass();
		while (owner != Object.class) {
			try {
				Field field = owner.getDeclaredField(name);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (NoSuchFieldException missing) { owner = owner.getSuperclass(); }
		}
		throw new NoSuchFieldException(name);
	}
	private static <T> T token(Class<T> type) throws ReflectiveOperationException {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		var field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
	}
}
