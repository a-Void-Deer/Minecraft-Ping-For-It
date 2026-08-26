package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.name.TargetNameJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerCreationServiceTest {

	private static final String OVERWORLD = "minecraft:overworld";

	// Fixed UUIDs so recipient ordering assertions are deterministic:
	// RECIPIENT < OWNER < STRANGER in natural UUID order.
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID STRANGER = new UUID(0L, 200L);
	private static final UUID RECIPIENT = new UUID(0L, 10L);
	private static final UUID ENTITY_A = new UUID(1L, 1L);
	private static final UUID ENTITY_B = new UUID(2L, 2L);

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final TargetTypeCatalog TARGET_TYPES = TargetTypeCatalog.builtIn();

	private static final MarkerAnchor ANCHOR = new MarkerAnchor(1.0, 2.0, 3.0);
	private static final TargetNameJson DEFAULT_NAME = new TargetNameJson("{\"translate\":\"minecraft.zombie\"}");

	private ServerMarkerStore store;
	private RecordingValidator validator;
	private RecordingResolver resolver;
	private MarkerCreationService service;

	private static TargetType targetType(String id) {
		return TARGET_TYPES.findById(id).orElseThrow();
	}

	private static Target entityTarget(UUID uuid) {
		return new Target.EntityTarget(OVERWORLD, uuid);
	}

	private static Target blockTarget() {
		return new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
	}

	private static Target locationTarget() {
		return new Target.LocationTarget(OVERWORLD, 1.0, 2.0, 3.0);
	}

	private static Target.ExternalBlockTarget externalCandidate() {
		return Target.ExternalBlockTarget.candidate(
			OVERWORLD, "provider:test", "minecraft:chest", "candidate-locator", true);
	}

	private static Target.ExternalBlockTarget committedExternalTarget() {
		return Target.ExternalBlockTarget.committed(
			OVERWORLD, "provider:test", "tracking-id", "minecraft:chest", "committed-locator", true);
	}

	private static ValidatedMarkerTarget validated(Target normalized, TargetMatchContext context) {
		return validated(normalized, context, DEFAULT_NAME);
	}

	private static ValidatedMarkerTarget validated(
		Target normalized, TargetMatchContext context, TargetNameJson name
	) {
		return new ValidatedMarkerTarget(normalized, context, ANCHOR, name);
	}

	private void newService() {
		store = new ServerMarkerStore(new MarkerIdSource());
		validator = new RecordingValidator();
		resolver = new RecordingResolver();
		service = new MarkerCreationService(store, resolver, PING_TYPES, validator);
	}

	private MarkerCreateOutcome create(Target requested, String pingTypeId) {
		return service.create(OWNER, requested, pingTypeId, 10L, 110L, List.of(RECIPIENT));
	}

	private static void assertRejected(MarkerCreateOutcome outcome, MarkerRejectReason reason) {
		assertFalse(outcome.isAccepted());
		assertEquals(Optional.of(reason), outcome.rejectReason());
		assertTrue(outcome.creation().isEmpty());
		assertTrue(outcome.targetName().isEmpty());
	}

	// --- pipeline order -------------------------------------------------------

	@Test
	void validationRunsFirstAndRejectionSkipsResolverAndStore() {
		newService();
		validator.rejected(MarkerRejectReason.TARGET_GONE);

		Target requested = entityTarget(ENTITY_A);
		assertRejected(create(requested, "attention"), MarkerRejectReason.TARGET_GONE);

		assertEquals(1, validator.calls);
		assertEquals(OWNER, validator.requester);
		assertEquals(requested, validator.requested);
		assertEquals(0, resolver.calls);
		assertEquals(0, store.size());
	}

	@Test
	void outOfRangeRejectionIsPropagatedUnchanged() {
		newService();
		validator.rejected(MarkerRejectReason.OUT_OF_RANGE);

		assertRejected(create(entityTarget(ENTITY_A), "attention"), MarkerRejectReason.OUT_OF_RANGE);
		assertEquals(0, resolver.calls);
		assertEquals(0, store.size());
	}

	@Test
	void validatorContractFailureProducesInvalidRequestWithoutMutation() {
		newService();
		validator.fail(new IllegalStateException("world unavailable"));

		assertRejected(create(entityTarget(ENTITY_A), "attention"), MarkerRejectReason.INVALID_REQUEST);
		assertEquals(0, resolver.calls);
		assertEquals(0, store.size());
	}

	@Test
	void validatorNullResultProducesInvalidRequestWithoutMutation() {
		newService();
		validator.returnNull();

		assertRejected(create(entityTarget(ENTITY_A), "attention"), MarkerRejectReason.INVALID_REQUEST);
		assertEquals(0, resolver.calls);
		assertEquals(0, store.size());
	}

	// --- accepted path reclassification ----------------------------------------

	@Test
	void acceptedPathReclassifiesDroppedItemFromClientEntityTarget() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.entityType("minecraft:item")));
		resolver.resolves(requested, targetType("dropped_item"));

		MarkerCreateOutcome outcome = create(requested, "loot");

		assertTrue(outcome.isAccepted());
		ServerMarker marker = outcome.creation().orElseThrow().marker();

		// The client only sent a plain entity target; the server-side
		// re-resolution alone decides the target type.
		assertEquals(targetType("dropped_item"), marker.targetType());
		assertEquals("loot", marker.pingType().id());
		assertEquals(requested, marker.target());

		assertEquals(1, resolver.calls);
		assertEquals(requested, resolver.target);
		assertEquals(TargetMatchContext.entityType("minecraft:item"), resolver.context);
	}

	@Test
	void acceptedPathReclassifiesGenericEntity() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("entity"));

		MarkerCreateOutcome outcome = create(requested, "danger");

		assertTrue(outcome.isAccepted());
		assertEquals(targetType("entity"), outcome.creation().orElseThrow().marker().targetType());
		assertEquals("danger", outcome.creation().orElseThrow().marker().pingType().id());
	}

	@Test
	void acceptedPathReclassifiesBlock() {
		newService();
		Target requested = blockTarget();
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("block"));

		MarkerCreateOutcome outcome = create(requested, "go_to");

		assertTrue(outcome.isAccepted());
		assertEquals(targetType("block"), outcome.creation().orElseThrow().marker().targetType());
		assertEquals("go_to", outcome.creation().orElseThrow().marker().pingType().id());
	}

	@Test
	void acceptedPathReclassifiesLocation() {
		newService();
		Target requested = locationTarget();
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("location"));

		MarkerCreateOutcome outcome = create(requested, "attention");

		assertTrue(outcome.isAccepted());
		assertEquals(targetType("location"), outcome.creation().orElseThrow().marker().targetType());
		assertEquals("attention", outcome.creation().orElseThrow().marker().pingType().id());
	}

	// --- server values, not client values --------------------------------------

	@Test
	void createUsesNormalizedTargetNotRequested() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		Target normalized = entityTarget(ENTITY_B);
		validator.accepted(validated(normalized, TargetMatchContext.none()));
		resolver.resolves(normalized, targetType("entity"));

		MarkerCreateOutcome outcome = create(requested, "attention");

		assertTrue(outcome.isAccepted());
		assertEquals(normalized, outcome.creation().orElseThrow().marker().target());
		assertEquals(normalized, resolver.target);
	}

	@Test
	void createUsesValidatedAnchor() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		MarkerAnchor anchor = new MarkerAnchor(4.0, 5.0, 6.0);
		validator.accepted(new ValidatedMarkerTarget(
			requested, TargetMatchContext.none(), anchor, DEFAULT_NAME));
		resolver.resolves(requested, targetType("entity"));

		MarkerCreateOutcome outcome = create(requested, "attention");

		assertTrue(outcome.isAccepted());
		assertEquals(anchor, outcome.creation().orElseThrow().marker().anchor());
	}

	@Test
	void createPassesValidatorNameThroughUnchanged() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		TargetNameJson name = new TargetNameJson("{\"translate\":\"minecraft.skeleton\"}");
		validator.accepted(validated(requested, TargetMatchContext.none(), name));
		resolver.resolves(requested, targetType("entity"));

		MarkerCreateOutcome outcome = create(requested, "attention");

		assertTrue(outcome.isAccepted());
		assertEquals(Optional.of(name), outcome.targetName());
	}

	@Test
	void rejectedCreateCarriesNoName() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		TargetNameJson name = new TargetNameJson("{\"translate\":\"minecraft.skeleton\"}");
		validator.accepted(validated(requested, TargetMatchContext.none(), name));
		resolver.resolves(requested, targetType("entity"));

		assertRejected(create(requested, "does_not_exist"), MarkerRejectReason.INVALID_PING_TYPE);
	}

	@Test
	void createPreservesOwnerRecipientsAndLifetime() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("entity"));

		MarkerCreateOutcome outcome = service.create(
			OWNER, requested, "attention", 42L, 942L, List.of(OWNER, RECIPIENT));

		assertTrue(outcome.isAccepted());
		ServerMarker marker = outcome.creation().orElseThrow().marker();

		assertEquals(new MarkerId(0L), marker.id());
		assertEquals(OWNER, marker.owner());
		assertEquals(42L, marker.arrivalTick());
		assertEquals(942L, marker.expiresAtTick());
		assertEquals(List.of(RECIPIENT, OWNER), marker.recipients());
	}

	// --- ping type admission ----------------------------------------------------

	@Test
	void createAllowsEveryPingTypeOfResolvedTargetType() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("entity"));

		for (String pingTypeId : List.of("attention", "danger", "go_to")) {
			assertTrue(create(requested, pingTypeId).isAccepted());
		}

		assertEquals(3, store.size());
	}

	@Test
	void createRejectsUnknownPingType() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("entity"));

		assertRejected(create(requested, "does_not_exist"), MarkerRejectReason.INVALID_PING_TYPE);
		assertRejected(create(requested, ""), MarkerRejectReason.INVALID_PING_TYPE);
		assertEquals(0, store.size());
	}

	@Test
	void createRejectsPingTypeDisallowedForResolvedTargetType() {
		newService();

		Target droppedItem = entityTarget(ENTITY_A);
		validator.accepted(validated(droppedItem, TargetMatchContext.entityType("minecraft:item")));
		resolver.resolves(droppedItem, targetType("dropped_item"));
		assertRejected(create(droppedItem, "go_to"), MarkerRejectReason.INVALID_PING_TYPE);

		Target entity = entityTarget(ENTITY_B);
		validator.accepted(validated(entity, TargetMatchContext.none()));
		resolver.resolves(entity, targetType("entity"));
		assertRejected(create(entity, "loot"), MarkerRejectReason.INVALID_PING_TYPE);

		Target block = blockTarget();
		validator.accepted(validated(block, TargetMatchContext.none()));
		resolver.resolves(block, targetType("block"));
		assertRejected(create(block, "loot"), MarkerRejectReason.INVALID_PING_TYPE);

		Target location = locationTarget();
		validator.accepted(validated(location, TargetMatchContext.none()));
		resolver.resolves(location, targetType("location"));
		assertRejected(create(location, "loot"), MarkerRejectReason.INVALID_PING_TYPE);

		assertEquals(0, store.size());
	}

	// --- external materialization transaction ---------------------------------

	@Test
	void externalMaterializationWaitsForResolverAndPingMembership() {
		newService();
		Target.ExternalBlockTarget candidate = externalCandidate();
		RecordingExternalProvider provider = new RecordingExternalProvider(
			new ExternalBlockServerProvider.MaterializationResult.Invalid());
		ExternalBlockTransaction transaction = transaction(provider);
		validator.accepted(validated(candidate, TargetMatchContext.blockEntityBlock(true)));
		resolver.resolves(candidate, targetType("entity_block"));

		assertRejected(
			service.createWithExternalTransaction(
				transaction, OWNER, candidate, "does_not_exist", 10L, 110L, List.of(RECIPIENT)),
			MarkerRejectReason.INVALID_PING_TYPE);
		assertRejected(
			service.createWithExternalTransaction(
				transaction, OWNER, candidate, "go_to", 10L, 110L, List.of(RECIPIENT)),
			MarkerRejectReason.INVALID_PING_TYPE);

		assertEquals(0, provider.materializeCalls);
		assertEquals(0, provider.releaseCalls);
		assertEquals(0, store.size());
	}

	@Test
	void externalMaterializationRejectionReleasesExactlyOnce() {
		newService();
		Target.ExternalBlockTarget candidate = externalCandidate();
		RecordingExternalProvider provider = new RecordingExternalProvider(
			new ExternalBlockServerProvider.MaterializationResult.Materialized(
				new ExternalBlockServerProvider.MaterializedTarget(
					committedExternalTarget(),
					TargetMatchContext.blockEntityBlock(true),
					ANCHOR)));
		validator.accepted(validated(candidate, TargetMatchContext.blockEntityBlock(true)));
		resolver.resolves(candidate, targetType("entity_block"));
		resolver.returnNullAfterFirst();

		assertRejected(
			service.createWithExternalTransaction(
				transaction(provider), OWNER, candidate, "attention", 10L, 110L, List.of(RECIPIENT)),
			MarkerRejectReason.INVALID_REQUEST);

		assertEquals(1, provider.materializeCalls);
		assertEquals(1, provider.releaseCalls);
		assertEquals(0, store.size());
	}

	@Test
	void successfulExternalMaterializationRetainsCommittedTargetInStore() {
		newService();
		Target.ExternalBlockTarget candidate = externalCandidate();
		Target.ExternalBlockTarget committed = committedExternalTarget();
		RecordingExternalProvider provider = new RecordingExternalProvider(
			new ExternalBlockServerProvider.MaterializationResult.Materialized(
				new ExternalBlockServerProvider.MaterializedTarget(
					committed,
					TargetMatchContext.blockEntityBlock(true),
					ANCHOR)));
		validator.accepted(validated(candidate, TargetMatchContext.blockEntityBlock(true)));
		resolver.resolves(candidate, targetType("entity_block"));

		MarkerCreateOutcome outcome = service.createWithExternalTransaction(
			transaction(provider), OWNER, candidate, "attention", 10L, 110L, List.of(RECIPIENT));

		assertTrue(outcome.isAccepted());
		assertEquals(1, provider.materializeCalls);
		assertEquals(0, provider.releaseCalls);
		assertEquals(1, store.size());

		ServerMarker marker = outcome.creation().orElseThrow().marker();
		assertEquals(committed, marker.target());
		assertNotEquals(candidate, marker.target());
		assertTrue(marker.target() instanceof Target.ExternalBlockTarget external && external.isCommitted());
		assertEquals(committed, MarkerSnapshot.from(marker).target());
	}

	// --- resolver contract failures ----------------------------------------------

	@Test
	void resolverExceptionProducesInvalidRequestWithoutMutation() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.fail(new IllegalStateException("no location fallback"));

		assertRejected(create(requested, "attention"), MarkerRejectReason.INVALID_REQUEST);
		assertEquals(1, validator.calls);
		assertEquals(1, resolver.calls);
		assertEquals(0, store.size());
	}

	@Test
	void resolverNullResultProducesInvalidRequestWithoutMutation() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.returnNull();

		assertRejected(create(requested, "attention"), MarkerRejectReason.INVALID_REQUEST);
		assertEquals(0, store.size());
	}

	// --- store contract failures ---------------------------------------------------

	@Test
	void resolvedTypeKindMismatchProducesInvalidRequestWithoutMutation() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("block")); // block type for an entity target

		assertRejected(create(requested, "attention"), MarkerRejectReason.INVALID_REQUEST);
		assertEquals(0, store.size());
	}

	@Test
	void recipientWithNullElementProducesInvalidRequestWithoutMutation() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("entity"));

		List<UUID> recipients = Arrays.asList((UUID) null);
		MarkerCreateOutcome outcome =
			service.create(OWNER, requested, "attention", 10L, 110L, recipients);

		assertRejected(outcome, MarkerRejectReason.INVALID_REQUEST);
		assertEquals(0, store.size());
	}

	@Test
	void emptyRecipientsProduceInvalidRequestWithoutMutation() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("entity"));

		MarkerCreateOutcome outcome =
			service.create(OWNER, requested, "attention", 10L, 110L, List.of());

		assertRejected(outcome, MarkerRejectReason.INVALID_REQUEST);
		assertEquals(0, store.size());
	}

	// --- invalid arguments ----------------------------------------------------------

	@Test
	void invalidArgumentsProduceInvalidRequestWithoutCallingValidator() {
		newService();

		Target requested = entityTarget(ENTITY_A);
		List<UUID> recipients = List.of(RECIPIENT);

		assertRejected(
			service.create(null, requested, "attention", 10L, 110L, recipients),
			MarkerRejectReason.INVALID_REQUEST);
		assertRejected(
			service.create(OWNER, null, "attention", 10L, 110L, recipients),
			MarkerRejectReason.INVALID_REQUEST);
		assertRejected(
			service.create(OWNER, requested, null, 10L, 110L, recipients),
			MarkerRejectReason.INVALID_REQUEST);
		assertRejected(
			service.create(OWNER, requested, "attention", 10L, 110L, null),
			MarkerRejectReason.INVALID_REQUEST);
		assertRejected(
			service.create(OWNER, requested, "attention", -1L, 110L, recipients),
			MarkerRejectReason.INVALID_REQUEST);
		assertRejected(
			service.create(OWNER, requested, "attention", 10L, 10L, recipients),
			MarkerRejectReason.INVALID_REQUEST);

		assertEquals(0, validator.calls);
		assertEquals(0, resolver.calls);
		assertEquals(0, store.size());
	}

	// --- removal ---------------------------------------------------------------

	@Test
	void removeOwnedMapsStoreStatuses() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("entity"));

		MarkerId owned = create(requested, "attention").creation().orElseThrow().marker().id();

		// Successful removal maps to no rejection.
		assertEquals(Optional.empty(), service.removeOwned(OWNER, owned));
		assertEquals(0, store.size());

		// A stranger cannot remove the owner's marker.
		MarkerId seeded = create(requested, "attention").creation().orElseThrow().marker().id();
		assertEquals(Optional.of(MarkerRejectReason.NOT_OWNER), service.removeOwned(STRANGER, seeded));
		assertEquals(1, store.size());

		// A missing marker maps to NOT_FOUND.
		assertEquals(
			Optional.of(MarkerRejectReason.NOT_FOUND), service.removeOwned(OWNER, new MarkerId(999L)));
		assertEquals(1, store.size());
	}

	// --- expiry -----------------------------------------------------------------

	@Test
	void expireDelegatesToStore() {
		newService();
		Target requested = entityTarget(ENTITY_A);
		validator.accepted(validated(requested, TargetMatchContext.none()));
		resolver.resolves(requested, targetType("entity"));

		create(requested, "attention"); // arrival 10, expires 110

		assertTrue(service.expire(50L).removals().isEmpty());
		assertEquals(1, store.size());

		MarkerBatchRemoval batch = service.expire(110L);

		assertEquals(1, batch.removals().size());
		assertEquals(MarkerRemovalReason.EXPIRED, batch.removals().get(0).reason());
		assertEquals(0, store.size());
	}

	// --- fakes -------------------------------------------------------------------

	private static final class RecordingValidator implements AuthoritativeTargetValidator {

		int calls;
		UUID requester;
		Target requested;

		private AuthoritativeTargetValidation result;
		private RuntimeException failure;
		private boolean returnNull;

		@Override
		public AuthoritativeTargetValidation validate(UUID requester, Target requestedTarget) {
			calls++;
			this.requester = requester;
			this.requested = requestedTarget;

			if (failure != null) {
				throw failure;
			}

			if (returnNull) {
				return null;
			}

			return result;
		}

		void accepted(ValidatedMarkerTarget validated) {
			result = AuthoritativeTargetValidation.accepted(validated);
		}

		void rejected(MarkerRejectReason reason) {
			result = AuthoritativeTargetValidation.rejected(reason);
		}

		void fail(RuntimeException failure) {
			this.failure = failure;
		}

		void returnNull() {
			this.returnNull = true;
		}
	}

	private static final class RecordingResolver implements TargetResolver {

		int calls;
		Target target;
		TargetMatchContext context;

		private ResolvedTarget result;
		private RuntimeException failure;
		private boolean returnNull;
		private boolean returnNullAfterFirst;

		@Override
		public ResolvedTarget resolve(Target target, TargetMatchContext context) {
			calls++;
			this.target = target;
			this.context = context;

			if (failure != null) {
				throw failure;
			}

			if (returnNull || (returnNullAfterFirst && calls > 1)) {
				return null;
			}

			return result;
		}

		void resolves(Target target, TargetType targetType) {
			result = new ResolvedTarget(target, targetType);
		}

		void fail(RuntimeException failure) {
			this.failure = failure;
		}

		void returnNull() {
			this.returnNull = true;
		}

		void returnNullAfterFirst() {
			this.returnNullAfterFirst = true;
		}
	}

	private static ExternalBlockTransaction transaction(RecordingExternalProvider provider) {
		return new ExternalBlockTransaction() {
			@Override
			public ExternalBlockServerProvider.MaterializationResult materialize(
				Target.ExternalBlockTarget candidate
			) {
				return provider.materialize(null, candidate);
			}

			@Override
			public void release(Target.ExternalBlockTarget committed) {
				provider.release(null, committed);
			}
		};
	}

	private static final class RecordingExternalProvider implements ExternalBlockServerProvider {

		private final MaterializationResult result;
		private int materializeCalls;
		private int releaseCalls;

		private RecordingExternalProvider(MaterializationResult result) {
			this.result = result;
		}

		@Override
		public String providerId() {
			return "provider:test";
		}

		@Override
		public ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) {
			return new ValidationResult.Invalid();
		}

		@Override
		public MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget candidate) {
			materializeCalls++;
			return result;
		}

		@Override
		public RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
			return new RefreshResult.Invalid();
		}

		@Override
		public Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget target) {
			return Optional.empty();
		}

		@Override
		public void release(MinecraftServer server, Target.ExternalBlockTarget committed) {
			releaseCalls++;
		}
	}
}
