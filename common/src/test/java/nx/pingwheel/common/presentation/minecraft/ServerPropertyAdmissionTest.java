package nx.pingwheel.common.presentation.minecraft;

import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import nx.pingwheel.common.name.TargetNameComposer;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import nx.pingwheel.common.presentation.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerPropertyAdmissionTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final String ADAPTER = "test:source";
	private static final String COUNT = "test:count";
	private static final String GROUP = "test:group";
	private static final PresentationPropertyRef COUNT_REF = PresentationPropertyRef.root(ADAPTER, COUNT);
	private static final PresentationPropertyRef NESTED = new PresentationPropertyRef(ADAPTER, GROUP, List.of("units"));
	private static final PresentationPropertyRef NAME_REF =
		PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME);
	private static final PresentationAdapter ADAPTER_IMPL = new PresentationAdapter() {
		public String adapterId() { return ADAPTER; }
		public String modId() { return "test"; }
		public int schema() { return 1; }
		public int minUpdateIntervalTicks() { return 5; }
		public List<PresentationField> fields() {
			return List.of(new PresentationField(COUNT, PresentationField.Kind.NUMBER, true, 0, "count"),
				new PresentationField(GROUP, PresentationField.Kind.RECORD, true, 0, "group"));
		}
		public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			throw new AssertionError("admission must use the injected authoritative capture");
		}
	};

	private static PresentationRegistry registry() {
		PresentationRegistry registry = new PresentationRegistry();
		registry.register(ADAPTER_IMPL);
		return registry;
	}

	private static PresentationSection live(Set<String> demand) {
		Map<String, PresentationValue> fields = new java.util.LinkedHashMap<>();
		if (demand.contains(COUNT)) fields.put(COUNT, new PresentationValue.NumberValue(9));
		if (demand.contains(GROUP)) fields.put(GROUP,
			new PresentationValue.RecordValue(Map.of("units", new PresentationValue.NumberValue(4))));
		return new PresentationSection(ADAPTER, 1, fields, false,
			Map.of());
	}

	@Test
	void serverValueWinsStaleNumericClaimAndOneAdapterCaptureSeedsBothRoots() {
		int[] calls = {0};
		var admitted = ServerPropertyAdmission.admit(List.of(
			PresentationPropertyIntent.of(COUNT_REF, new PresentationValue.NumberValue(999), "attention"),
			PresentationPropertyIntent.of(NESTED, new PresentationValue.NumberValue(-1), "danger")),
			registry(), Map.of(ADAPTER, Set.of(COUNT, GROUP)), 3,
			new ServerPropertyAdmission.Context("minecraft:stone", Set.of()),
			(adapter, roots, budget) -> { calls[0]++; assertEquals(Set.of(COUNT, GROUP), roots); return live(roots); });
		assertNull(admitted.rejection());
		assertEquals(1, calls[0]);
		assertEquals(new PresentationValue.NumberValue(9), COUNT_REF.resolve(admitted.sourceSeeds().get(ADAPTER)));
		assertEquals(new PresentationValue.NumberValue(4), NESTED.resolve(admitted.sourceSeeds().get(ADAPTER)));
		assertEquals(List.of(PresentationPropertySelection.of(COUNT_REF, "attention"),
			PresentationPropertySelection.of(NESTED, "danger")), admitted.selections());
		assertTrue(admitted.sourceSeeds().get(ADAPTER).annotations().isEmpty(),
			"source cache must not acquire another marker's annotations");
	}

	@Test
	void rejectsUnavailableWrongKindAndUnauthorizedWithoutLeakingClaimedData() {
		var wrong = PresentationPropertyIntent.observed(COUNT_REF, new PresentationValue.Text("9"));
		int[] calls = {0};
		var rejection = ServerPropertyAdmission.admit(List.of(wrong), registry(), Map.of(ADAPTER, Set.of(COUNT)), 2,
			new ServerPropertyAdmission.Context("minecraft:stone", Set.of()),
			(adapter, roots, budget) -> { calls[0]++; return live(roots); });
		assertEquals(MarkerRejectReason.INVALID_REQUEST, rejection.rejection());
		assertEquals(0, calls[0]);
		rejection = ServerPropertyAdmission.admit(List.of(PresentationPropertyIntent.observed(COUNT_REF,
			new PresentationValue.NumberValue(1))), registry(), Map.of(), 2,
			new ServerPropertyAdmission.Context("minecraft:stone", Set.of()),
			(adapter, roots, budget) -> { calls[0]++; return live(roots); });
		assertEquals(MarkerRejectReason.INVALID_REQUEST, rejection.rejection());
		assertEquals(0, calls[0]);
		rejection = ServerPropertyAdmission.admit(List.of(PresentationPropertyIntent.observed(COUNT_REF,
			new PresentationValue.NumberValue(1))), registry(), Map.of(ADAPTER, Set.of(COUNT)), 2,
			new ServerPropertyAdmission.Context("minecraft:stone", Set.of()),
			(adapter, roots, budget) -> null);
		assertEquals(MarkerRejectReason.INVALID_REQUEST, rejection.rejection());
		assertTrue(rejection.sourceSeeds().isEmpty());
	}

	@Test
	void actualTagIdMembershipChangesAllowedTypeIndependentlyOfWholeMarkerType() {
		var request = PresentationPropertyIntent.of(COUNT_REF, new PresentationValue.NumberValue(1), "request");
		var danger = PresentationPropertyIntent.of(COUNT_REF, new PresentationValue.NumberValue(1), "danger");
		var chest = new ServerPropertyAdmission.Context("minecraft:chest", Set.of("c:chests"));
		var untagged = new ServerPropertyAdmission.Context("minecraft:chest", Set.of());
		var allowed = Map.of(ADAPTER, Set.of(COUNT));
		assertNull(ServerPropertyAdmission.admit(List.of(request), registry(), allowed, 2, chest,
			(adapter, roots, budget) -> live(roots)).rejection());
		assertEquals(MarkerRejectReason.INVALID_PING_TYPE, ServerPropertyAdmission.admit(List.of(danger),
			registry(), allowed, 2, chest, (adapter, roots, budget) -> { throw new AssertionError("denied before capture"); }).rejection());
		assertEquals(MarkerRejectReason.INVALID_PING_TYPE, ServerPropertyAdmission.admit(List.of(request),
			registry(), allowed, 2, untagged, (adapter, roots, budget) -> { throw new AssertionError("denied before capture"); }).rejection());
		assertNull(ServerPropertyAdmission.admit(List.of(danger), registry(), allowed, 2, untagged,
			(adapter, roots, budget) -> live(roots)).rejection());
	}

	@Test
	void zeroBudgetRejectsBeforeReadingAnySource() {
		assertEquals(MarkerRejectReason.INVALID_REQUEST, ServerPropertyAdmission.admit(
			List.of(PresentationPropertyIntent.observed(COUNT_REF, new PresentationValue.NumberValue(1))),
			registry(), Map.of(ADAPTER, Set.of(COUNT)), 0,
			new ServerPropertyAdmission.Context("minecraft:stone", Set.of()),
			(adapter, roots, budget) -> { throw new AssertionError("budget exhausted"); }).rejection());
	}

	@Test
	void freshCaptureExceptionRejectsEntireRequestWithoutPublishingPartialSeeds() {
		var admitted = ServerPropertyAdmission.admit(List.of(PresentationPropertyIntent.observed(COUNT_REF,
			new PresentationValue.NumberValue(1))), registry(), Map.of(ADAPTER, Set.of(COUNT)), 2,
			new ServerPropertyAdmission.Context("minecraft:stone", Set.of()),
			(adapter, roots, budget) -> { throw new IllegalStateException("source unavailable"); });
		assertEquals(MarkerRejectReason.INVALID_REQUEST, admitted.rejection());
		assertTrue(admitted.sourceSeeds().isEmpty());
	}

	@Test
	void missingNestedKeyRejectsWithoutSynthesizingAZeroOrAnAnnotation() {
		var admitted = ServerPropertyAdmission.admit(List.of(PresentationPropertyIntent.observed(NESTED,
			new PresentationValue.NumberValue(0))), registry(), Map.of(ADAPTER, Set.of(GROUP)), 2,
			new ServerPropertyAdmission.Context("minecraft:stone", Set.of()),
			(adapter, roots, budget) -> new PresentationSection(ADAPTER, 1, Map.of(GROUP,
				new PresentationValue.RecordValue(Map.of("other", new PresentationValue.NumberValue(5)))), false));
		assertEquals(MarkerRejectReason.INVALID_REQUEST, admitted.rejection());
		assertTrue(admitted.sourceSeeds().isEmpty());
	}

	@Test
	void prepaidValidatedContextStillBoundsOptionalCollectorAndRejectsWhenExhausted() {
		var request = PresentationPropertyIntent.observed(COUNT_REF, new PresentationValue.NumberValue(1));
		var admitted = ServerPropertyAdmission.admit(List.of(request), registry(), Map.of(ADAPTER, Set.of(COUNT)),
			Map.of(), 1, new ServerPropertyAdmission.Context("minecraft:stone", Set.of()),
			"minecraft:basic", 1, (adapter, roots, budget) -> {
				throw new AssertionError("context consumed the only scan");
			});
		assertEquals(MarkerRejectReason.INVALID_REQUEST, admitted.rejection());
	}

	@Test
	void recipientPoliciesAndMarkerSelectionsRemainIndependentAcrossLiveSamples() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setRules("entity", new PresentationSettings.RuleSet(List.of(), List.of(COUNT), false));
		UUID viewer = UUID.randomUUID();
		Set<String> entity = PresentationServer.allowedFields(settings, viewer, 0, ADAPTER_IMPL, "entity");
		Set<String> block = PresentationServer.allowedFields(settings, viewer, 0, ADAPTER_IMPL, "block");
		assertFalse(entity.contains(COUNT));
		assertTrue(block.contains(COUNT));
		List<PresentationPropertySelection> chosen = List.of(PresentationPropertySelection.of(NESTED, "attention"));
		var one = PresentationServer.project(ADAPTER_IMPL, live(Set.of(GROUP)), block, chosen);
		assertEquals("attention", one.annotations().get(NESTED));
		assertTrue(PresentationServer.project(ADAPTER_IMPL, live(Set.of(GROUP)), block, List.of()).annotations().isEmpty());
		var changed = new PresentationSection(ADAPTER, 1, Map.of(GROUP,
			new PresentationValue.RecordValue(Map.of("units", new PresentationValue.NumberValue(12)))), false);
		assertEquals(new PresentationValue.NumberValue(12), NESTED.resolve(PresentationServer.project(ADAPTER_IMPL,
			changed, block, chosen)));
		assertEquals("attention", PresentationServer.project(ADAPTER_IMPL, changed, block, chosen)
			.annotations().get(NESTED), "an updated live value keeps its marker's type, not the old value");
		var rootSelection = List.of(PresentationPropertySelection.of(COUNT_REF, "danger"));
		var updatedRoot = new PresentationSection(ADAPTER, 1,
			Map.of(COUNT, new PresentationValue.NumberValue(13)), false);
		var rootProjection = PresentationServer.project(ADAPTER_IMPL, updatedRoot, block, rootSelection);
		assertEquals(new PresentationValue.NumberValue(13), COUNT_REF.resolve(rootProjection));
		assertEquals("danger", rootProjection.annotations().get(COUNT_REF));
		var deniedRoot = PresentationServer.project(ADAPTER_IMPL, updatedRoot, entity, rootSelection);
		assertNull(COUNT_REF.resolve(deniedRoot));
		assertTrue(deniedRoot.annotations().isEmpty(), "a denied root cannot keep an old annotation");
		var disappeared = new PresentationSection(ADAPTER, 1, Map.of(GROUP,
			new PresentationValue.RecordValue(Map.of("other", new PresentationValue.NumberValue(3)))), false);
		assertTrue(PresentationServer.project(ADAPTER_IMPL, disappeared, block, chosen).annotations().isEmpty());
		assertTrue(PresentationServer.project(ADAPTER_IMPL, live(Set.of(GROUP)), Set.of(), chosen).annotations().isEmpty());
	}

	@Test
	void externalNameOnlyUsesAuthoritativeNameWithoutReadingUnavailableBlockState() {
		PresentationRegistry registry = new PresentationRegistry();
		registry.register(new PresentationAdapter() {
			public String adapterId() { return PresentationBasic.ID; }
			public String modId() { return "minecraft"; }
			public int schema() { return 1; }
			public int minUpdateIntervalTicks() { return 5; }
			public List<PresentationField> fields() { return PresentationBasic.fields(); }
			public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
				throw new AssertionError("Basic uses the production name assembler, not this collector");
			}
		});
		var intent = PresentationPropertyIntent.observed(NAME_REF,
			new PresentationValue.Text("untrusted client name"));
		int[] observations = {0};
		var source = PresentationServer.observeExternalAdmission(List.of(intent), () -> {
			observations[0]++;
			return new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
		});
		assertNotNull(source);
		assertTrue(source.nameOnly());
		assertNull(source.state());
		assertEquals(0, observations[0], "name-only admission must not observe external block state");
		assertEquals(Set.of(PresentationBasic.NAME), PresentationServer.admissionBasicDemand(
			Set.of(PresentationBasic.NAME, PresentationBasic.BLOCK_STATE), source.nameOnly()),
			"another recipient's Basic block-state demand cannot force a state read at name-only admission");
		Component base = Component.translatable("block.minecraft.chest");
		Component custom = Component.literal("Server-owned");
		var supplied = new ExternalBlockServerProvider.ExternalBlockName(base, Optional.of(custom));
		TargetNameJson serverName = PresentationServer.availableExternalName(Optional.of(supplied), RegistryAccess.EMPTY);
		assertEquals(TargetNameComposer.compose(custom, base), TargetNameJsonCodec.decode(serverName, RegistryAccess.EMPTY));
		var accepted = ServerPropertyAdmission.admit(List.of(intent), registry,
			Map.of(PresentationBasic.ID, Set.of(PresentationBasic.NAME)),
			Map.of(PresentationBasic.ID, PresentationServer.admissionBasicDemand(
				Set.of(PresentationBasic.NAME, PresentationBasic.BLOCK_STATE), source.nameOnly())), 1, null, null, 0,
			(adapter, demand, budget) -> PresentationServer.assembleExternalBasic(demand, source.state(), serverName));
		assertNull(accepted.rejection());
		assertEquals(new PresentationValue.Text(serverName.value()),
			NAME_REF.resolve(accepted.sourceSeeds().get(PresentationBasic.ID)));
		assertEquals(List.of(PresentationPropertySelection.of(NAME_REF)), accepted.selections());
		assertTrue(accepted.sourceSeeds().get(PresentationBasic.ID).annotations().isEmpty());
		TargetNameJson unavailable = PresentationServer.availableExternalName(Optional.empty(), RegistryAccess.EMPTY);
		assertNull(unavailable, "an absent provider name must not be converted to the normal marker UNKNOWN fallback");
		assertEquals(MarkerRejectReason.INVALID_REQUEST, ServerPropertyAdmission.admit(List.of(intent), registry,
			Map.of(PresentationBasic.ID, Set.of(PresentationBasic.NAME)), 1, null,
			(adapter, demand, budget) -> PresentationServer.assembleExternalBasic(demand, null, unavailable)).rejection(),
			"a missing authoritative name cannot be replaced by the uploaded claim");
		var typedName = PresentationPropertyIntent.of(NAME_REF,
			new PresentationValue.Text("untrusted client name"), "attention");
		assertEquals(MarkerRejectReason.INVALID_REQUEST, ServerPropertyAdmission.admit(List.of(typedName), registry,
			Map.of(PresentationBasic.ID, Set.of(PresentationBasic.NAME)), 2,
			new ServerPropertyAdmission.Context("minecraft:chest", Set.of("c:chests")),
			(adapter, demand, budget) -> PresentationServer.assembleExternalBasic(demand, null, unavailable)).rejection(),
			"available block tags cannot turn an unavailable provider name into a selected property");
		assertEquals(MarkerRejectReason.INVALID_REQUEST, ServerPropertyAdmission.admit(List.of(intent), registry,
			Map.of(), 1, null, (adapter, demand, budget) -> {
				throw new AssertionError("unauthorized name must not be captured");
			}).rejection());
	}

	@Test
	void externalNameTypeAndStateSelectionStillRequireAvailableObservation() {
		var typedName = PresentationPropertyIntent.of(NAME_REF,
			new PresentationValue.Text("untrusted"), "attention");
		var state = PresentationPropertyIntent.observed(
			PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.BLOCK_STATE),
			new PresentationValue.RecordValue(Map.of()));
		int[] observations = {0};
		for (var intent : List.of(typedName, state)) {
			var result = PresentationServer.observeExternalAdmission(List.of(intent), () -> {
				observations[0]++;
				return new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
			});
			assertNull(result, "type rules and block-state properties require actual loaded state");
		}
		var name = PresentationPropertyIntent.observed(NAME_REF, new PresentationValue.Text("untrusted"));
		assertNull(PresentationServer.observeExternalAdmission(List.of(name, state), () -> {
			observations[0]++;
			return new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
		}), "a mixed request must not downgrade its state selection to name-only");
		assertEquals(3, observations[0]);
	}

	@Test
	void allTypeMasksRevokeOnPermissionAndProviderChangesWithoutClientSelections() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setRules("entity", new PresentationSettings.RuleSet(List.of(), List.of(COUNT), false));
		settings.setPermissionLevels(Map.of(GROUP, 2));
		UUID viewer = UUID.randomUUID();
		var schemas = Map.of(ADAPTER, 1);
		var low = PresentationServer.maskFor(registry(), settings, viewer, 0, schemas);
		assertTrue(low.get("block").get(ADAPTER).contains(COUNT));
		assertFalse(low.get("entity").getOrDefault(ADAPTER, Set.of()).contains(COUNT));
		assertFalse(low.get("block").get(ADAPTER).contains(GROUP));
		var high = PresentationServer.maskFor(registry(), settings, viewer, 3, schemas);
		assertTrue(high.get("block").get(ADAPTER).contains(GROUP));
		UUID secondViewer = UUID.randomUUID();
		var otherMask = PresentationServer.maskFor(registry(), settings, secondViewer, 0, schemas);
		assertFalse(otherMask.get("block").get(ADAPTER).contains(GROUP));
		assertEquals(new PresentationValue.NumberValue(4), NESTED.resolve(PresentationServer.project(
			ADAPTER_IMPL, live(Set.of(GROUP)), high.get("block").get(ADAPTER), List.of())));
		assertTrue(PresentationServer.project(ADAPTER_IMPL, live(Set.of(GROUP)),
			otherMask.get("block").get(ADAPTER), List.of()).fields().isEmpty(),
			"a second recipient never receives the first recipient's permitted value");
		PresentationAuthorization.setProvider((recipient, field, actual, required) -> !field.id().equals(GROUP));
		try {
			assertFalse(PresentationServer.maskFor(registry(), settings, viewer, 3, schemas)
				.get("block").get(ADAPTER).contains(GROUP));
		} finally {
			PresentationAuthorization.setProvider(null);
		}
		assertTrue(PresentationServer.maskFor(registry(), settings, viewer, 3, Map.of())
			.get("block").isEmpty(), "a schema mismatch grants no adapter fields");
	}
}
