package nx.pingwheel.common.resolve;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.registry.OptionalRegistryRef;
import nx.pingwheel.common.registry.RegistryLookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTargetResolverTest {

	private static final String OVERWORLD = "minecraft:overworld";

	@Test
	void lowerPriorityWins() {
		TargetType high = type("high", 200, TargetKind.ENTITY);
		TargetType low = type("low", 100, TargetKind.ENTITY);
		TargetType location = type("location", Integer.MAX_VALUE, TargetKind.LOCATION);

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(high, low, location));
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("high", (t, c) -> t instanceof Target.EntityTarget)
			.bind("low", (t, c) -> t instanceof Target.EntityTarget)
			.bind("location", (t, c) -> t instanceof Target.LocationTarget)
			.build();

		DefaultTargetResolver resolver = new DefaultTargetResolver(catalog, registry, TargetResolutionLogger.noop());

		assertEquals("low",
			resolver.resolve(new Target.EntityTarget(OVERWORLD, UUID.randomUUID()), TargetMatchContext.none()).targetType().id());
	}

	@Test
	void equalPriorityResolvesByDeclarationOrder() {
		TargetType first = type("first", 100, TargetKind.ENTITY);
		TargetType second = type("second", 100, TargetKind.ENTITY);
		TargetType location = type("location", Integer.MAX_VALUE, TargetKind.LOCATION);

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(first, second, location));
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("first", (t, c) -> t instanceof Target.EntityTarget)
			.bind("second", (t, c) -> t instanceof Target.EntityTarget)
			.bind("location", (t, c) -> t instanceof Target.LocationTarget)
			.build();

		DefaultTargetResolver resolver = new DefaultTargetResolver(catalog, registry, TargetResolutionLogger.noop());

		assertEquals("first",
			resolver.resolve(new Target.EntityTarget(OVERWORLD, UUID.randomUUID()), TargetMatchContext.none()).targetType().id());
	}

	@Test
	void repeatedResolutionsAreDeterministic() {
		DefaultTargetResolver resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());

		Target.EntityTarget item = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());
		TargetMatchContext context = TargetMatchContext.entityType("minecraft:item");

		String first = resolver.resolve(item, context).targetType().id();

		for (int i = 0; i < 100; i++) {
			assertEquals(first, resolver.resolve(item, context).targetType().id());
		}
	}

	@Test
	void missingMatcherIsSkipped() {
		TargetType missing = type("missing_type", 100, TargetKind.ENTITY);
		TargetType entity = type("entity", 200, TargetKind.ENTITY);
		TargetType location = type("location", Integer.MAX_VALUE, TargetKind.LOCATION);

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(missing, entity, location));
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("entity", (t, c) -> t instanceof Target.EntityTarget)
			.bind("location", (t, c) -> t instanceof Target.LocationTarget)
			.build();

		DefaultTargetResolver resolver = new DefaultTargetResolver(catalog, registry, TargetResolutionLogger.noop());

		assertEquals("entity",
			resolver.resolve(new Target.EntityTarget(OVERWORLD, UUID.randomUUID()), TargetMatchContext.none()).targetType().id());
	}

	@Test
	void inactiveMatcherIsSkipped() {
		TargetType inactiveType = type("inactive_type", 100, TargetKind.ENTITY);
		TargetType entity = type("entity", 200, TargetKind.ENTITY);
		TargetType location = type("location", Integer.MAX_VALUE, TargetKind.LOCATION);

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(inactiveType, entity, location));
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("inactive_type", inactive())
			.bind("entity", (t, c) -> t instanceof Target.EntityTarget)
			.bind("location", (t, c) -> t instanceof Target.LocationTarget)
			.build();

		DefaultTargetResolver resolver = new DefaultTargetResolver(catalog, registry, TargetResolutionLogger.noop());

		assertEquals("entity",
			resolver.resolve(new Target.EntityTarget(OVERWORLD, UUID.randomUUID()), TargetMatchContext.none()).targetType().id());
	}

	@Test
	void throwsWhenNoFallbackMatches() {
		TargetType entity = type("entity", 200, TargetKind.ENTITY);

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(entity));
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("entity", (t, c) -> false)
			.build();

		DefaultTargetResolver resolver = new DefaultTargetResolver(catalog, registry, TargetResolutionLogger.noop());

		assertThrows(IllegalStateException.class,
			() -> resolver.resolve(new Target.EntityTarget(OVERWORLD, UUID.randomUUID()), TargetMatchContext.none()));
	}

	@Test
	void returnedResultIsFrozenAgainstLookupChanges() {
		Set<String> present = new HashSet<>(List.of("minecraft:block:minecraft:stone"));
		RegistryLookup lookup = (registryId, entryId) -> present.contains(registryId + ":" + entryId);

		TargetMatcher specialBlock = new RegistryBackedTargetMatcher(
			TargetKind.BLOCK,
			List.of(new OptionalRegistryRef("minecraft:block", "minecraft:stone")),
			lookup);
		TargetMatcher anyBlock = (t, c) -> t instanceof Target.BlockTarget;

		TargetType special = type("special_block", 100, TargetKind.BLOCK);
		TargetType block = type("block", 200, TargetKind.BLOCK);

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(special, block));
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("special_block", specialBlock)
			.bind("block", anyBlock)
			.build();

		DefaultTargetResolver resolver = new DefaultTargetResolver(catalog, registry, TargetResolutionLogger.noop());

		Target.BlockTarget stone = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:stone");
		ResolvedTarget resolved = resolver.resolve(stone, TargetMatchContext.none());

		assertEquals("special_block", resolved.targetType().id());

		// mutate the lookup state so the special matcher becomes inactive
		present.clear();

		// the previously returned result is immutable and unchanged
		assertEquals("special_block", resolved.targetType().id());
		assertSame(stone, resolved.target());

		// a fresh resolution now observes the changed lookup and resolves differently
		ResolvedTarget reResolved = resolver.resolve(stone, TargetMatchContext.none());
		assertEquals("block", reResolved.targetType().id());
	}

	@Test
	void resolverEvaluatesRegistryMatcherOnceWithConsistentObservation() {
		AtomicInteger lookups = new AtomicInteger();
		Set<String> seen = new HashSet<>();
		RegistryLookup oneShot = (registryId, entryId) -> {
			lookups.incrementAndGet();
			return seen.add(registryId + ":" + entryId);
		};

		TargetMatcher specialBlock = new RegistryBackedTargetMatcher(
			TargetKind.BLOCK,
			List.of(new OptionalRegistryRef("minecraft:block", "minecraft:stone")),
			oneShot);
		TargetMatcher anyBlock = (t, c) -> t instanceof Target.BlockTarget;

		TargetType special = type("special_block", 100, TargetKind.BLOCK);
		TargetType block = type("block", 200, TargetKind.BLOCK);

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(special, block));
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("special_block", specialBlock)
			.bind("block", anyBlock)
			.build();

		DefaultTargetResolver resolver = new DefaultTargetResolver(catalog, registry, TargetResolutionLogger.noop());

		ResolvedTarget resolved = resolver.resolve(
			new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:stone"), TargetMatchContext.none());

		// The one-shot lookup answers "present" only on the first query for an
		// entry. If the resolver had called isActive() then matches() separately,
		// the second query for the same entry would answer "absent" and resolution
		// would fall through to the generic block type. evaluate() keeps a single,
		// consistent observation and queries the single ref exactly once.
		assertEquals("special_block", resolved.targetType().id());
		assertEquals(1, lookups.get());
	}

	@Test
	void debugLoggerRecordsSelectionTraceWithoutSensitiveValues() {
		RecordingLogger logger = new RecordingLogger();
		DefaultTargetResolver resolver = DefaultTargetResolver.builtIn(logger);

		Target.EntityTarget item = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());
		resolver.resolve(item, TargetMatchContext.entityType("minecraft:item"));

		List<String> messages = logger.messages();

		assertTrue(messages.stream().anyMatch(m -> m.contains("resolved target type='dropped_item' priority=100")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("kind=ENTITY")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("dimension=minecraft:overworld")));

		// the entity type id (registry/optional content) is never logged
		assertFalse(messages.stream().anyMatch(m -> m.contains("minecraft:item")));
	}

	@Test
	void debugLoggerRecordsSkipTraces() {
		RecordingLogger logger = new RecordingLogger();

		TargetType missing = type("missing_type", 100, TargetKind.ENTITY);
		TargetType inactiveType = type("inactive_type", 200, TargetKind.ENTITY);
		TargetType entity = type("entity", 300, TargetKind.ENTITY);
		TargetType location = type("location", Integer.MAX_VALUE, TargetKind.LOCATION);

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(missing, inactiveType, entity, location));
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("inactive_type", inactive())
			.bind("entity", (t, c) -> t instanceof Target.EntityTarget)
			.bind("location", (t, c) -> t instanceof Target.LocationTarget)
			.build();

		DefaultTargetResolver resolver = new DefaultTargetResolver(catalog, registry, logger);
		resolver.resolve(new Target.EntityTarget(OVERWORLD, UUID.randomUUID()), TargetMatchContext.none());

		List<String> messages = logger.messages();

		assertTrue(messages.stream().anyMatch(m -> m.contains("skip 'missing_type': no matcher bound")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("skip 'inactive_type': matcher inactive")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("resolved target type='entity' priority=300")));
	}

	@Test
	void rejectsNullTargetOrContext() {
		DefaultTargetResolver resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());

		assertThrows(NullPointerException.class,
			() -> resolver.resolve(null, TargetMatchContext.none()));
		assertThrows(NullPointerException.class,
			() -> resolver.resolve(new Target.LocationTarget(OVERWORLD, 0, 0, 0), null));
	}

	private static TargetType type(String id, int priority, TargetKind kind) {
		PingType ping = PingTypeCatalog.builtIn().findById("attention").orElseThrow();

		return new TargetType(id, priority, kind, List.of(ping), ping);
	}

	private static TargetMatcher inactive() {
		return new TargetMatcher() {
			@Override
			public boolean isActive() {
				return false;
			}

			@Override
			public boolean matches(Target target, TargetMatchContext context) {
				return true;
			}
		};
	}

	private static final class RecordingLogger implements TargetResolutionLogger {

		private final List<String> messages = new ArrayList<>();

		@Override
		public void debug(String message, Object... args) {
			messages.add(format(message, args));
		}

		List<String> messages() {
			return List.copyOf(messages);
		}

		private static String format(String message, Object... args) {
			StringBuilder sb = new StringBuilder();
			int from = 0;
			int argIndex = 0;
			int open;

			while ((open = message.indexOf("{}", from)) >= 0) {
				sb.append(message, from, open);
				sb.append(argIndex < args.length ? String.valueOf(args[argIndex++]) : "{}");
				from = open + 2;
			}

			sb.append(message.substring(from));
			return sb.toString();
		}
	}
}
