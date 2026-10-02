package nx.pingwheel.common.presentation.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientPresentationPreviewTest {
	private static final String ADAPTER = "test:basic", LOCAL = "test:local", REMOTE = "test:remote";
	static PresentationPreviewAccess access(long view, Set<String> fields) {
		Map<String, PresentationField> descriptors = new java.util.HashMap<>();
		fields.forEach(id -> descriptors.put(id, new PresentationField(id, PresentationField.Kind.NUMBER, true, 0, id)));
		return new PresentationPreviewAccess(71, view, "block", Map.of(ADAPTER, new PresentationPreviewAccess.Adapter(1, descriptors)));
	}
	private static final class Context implements PreviewFieldAccess.ReadContext {
		Object level = new Object(); long tick;
		@Override public Object levelIdentity() { return level; }
		@Override public String dimensionId() { return "minecraft:overworld"; }
		@Override public long tick() { return tick; }
	}
	@Test void localFirstOnlyMissingAuthorizedRootsRequestedAndZeroIsEvidence() {
		Context world = new Context(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		AtomicReference<PresentationPreviewAccess> authorization = new AtomicReference<>(access(1, Set.of(LOCAL, REMOTE)));
		List<Set<String>> demands = new ArrayList<>();
		PreviewFieldAccess reader = new PreviewFieldAccess() {
			public String adapterId() { return ADAPTER; }
			public Map<String, Outcome> observe(Target target, Set<String> fields, ReadContext context) {
				demands.add(fields);
				return Map.of(LOCAL, new Observed(new PreviewObservation(new PresentationValue.NumberValue(0), PreviewObservation.Origin.CLIENT_SYNCED, 0, false)));
			}
		};
		var preview = new ClientPresentationPreview(type -> Optional.ofNullable(authorization.get()), () -> world,
			List.of(reader), (target, type) -> Optional.empty(), sent::add);
		Object token = new Object();
		preview.begin(new ClientPresentationPreview.Binding(token, new Target.BlockTarget(world.dimensionId(), 1, 2, 3, "minecraft:stone"), "block", world.level));
		assertEquals(List.of(Set.of(LOCAL, REMOTE)), demands);
		assertEquals(Set.of(REMOTE), sent.getFirst().fields());
		var ref = PresentationPropertyRef.root(ADAPTER, LOCAL);
		assertEquals(new PresentationValue.NumberValue(0), preview.intent(token, ref, null).orElseThrow().observedValue());
		assertTrue(preview.accept(PresentationPreviewS2CPacket.result(sent.getFirst(),
			new PresentationSection(ADAPTER, 1, Map.of(REMOTE, new PresentationValue.NumberValue(8)), false))));
		assertEquals(new PresentationValue.NumberValue(8), preview.projection().orElseThrow().property(PresentationPropertyRef.root(ADAPTER, REMOTE)).orElseThrow().value());
		preview.tick(); assertEquals(1, sent.size(), "one-shot does not poll again");
		authorization.set(access(2, Set.of(REMOTE)));
		assertTrue(preview.intent(token, ref, null).isEmpty(), "RESET revokes local evidence before dispatch");
		assertFalse(preview.projection().orElseThrow().fields().containsKey(ref));
		assertEquals(Set.of(REMOTE), demands.getLast(), "new view demand never reads revoked local field");
	}
	@Test void unreadyMakesNoReadThenAbortAndWrongLevelFenceResponses() {
		Context world = new Context(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		AtomicReference<PresentationPreviewAccess> auth = new AtomicReference<>(); int[] reads = {0};
		PreviewFieldAccess reader = new PreviewFieldAccess() {
			public String adapterId() { return ADAPTER; }
			public Map<String, Outcome> observe(Target target, Set<String> fields, ReadContext context) { reads[0]++; return Map.of(); }
		};
		var preview = new ClientPresentationPreview(type -> Optional.ofNullable(auth.get()), () -> world, List.of(reader), (t, type) -> Optional.empty(), sent::add);
		preview.begin(new ClientPresentationPreview.Binding(new Object(), new Target.LocationTarget(world.dimensionId(), 0, 0, 0), "block", world.level));
		assertEquals(0, reads[0]); assertTrue(sent.isEmpty());
		auth.set(access(1, Set.of(REMOTE))); preview.tick();
		var request = sent.getFirst(); world.level = new Object();
		assertFalse(preview.accept(PresentationPreviewS2CPacket.result(request, new PresentationSection(ADAPTER, 1, Map.of(), false))));
		assertTrue(preview.projection().isEmpty()); assertEquals(PresentationPreviewC2SPacket.Kind.CANCEL, sent.getLast().kind());
	}
	@Test void timeoutDoesNotInventZeroAndOldViewCannotApply() {
		Context world = new Context(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		AtomicReference<PresentationPreviewAccess> auth = new AtomicReference<>(access(1, Set.of(REMOTE)));
		var preview = new ClientPresentationPreview(type -> Optional.of(auth.get()), () -> world, List.of(), (t, type) -> Optional.empty(), sent::add);
		var binding = new ClientPresentationPreview.Binding(new Object(), new Target.LocationTarget(world.dimensionId(), 0, 0, 0), "block", world.level);
		preview.begin(binding); var first = sent.getFirst();
		world.tick += PresentationPreviewLimits.REQUEST_TICKS; preview.tick();
		assertTrue(preview.projection().orElseThrow().property(PresentationPropertyRef.root(ADAPTER, REMOTE)).isEmpty());
		assertFalse(preview.accept(PresentationPreviewS2CPacket.result(first, new PresentationSection(ADAPTER, 1, Map.of(REMOTE, new PresentationValue.NumberValue(1)), false))));
		auth.set(access(2, Set.of(REMOTE))); preview.tick();
		assertFalse(preview.accept(PresentationPreviewS2CPacket.result(first, new PresentationSection(ADAPTER, 1, Map.of(), false))));
		assertTrue(sent.getLast().requestId() > first.requestId());
	}
	@Test void entirelyAvailableLocalProjectionSendsNothingThenMissingSourceFallsBack() {
		Context world = new Context(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>(); boolean[] present = {true};
		PreviewFieldAccess reader = new PreviewFieldAccess() {
			public String adapterId() { return ADAPTER; }
			public Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext context) {
				return present[0] ? Map.of(LOCAL, new Observed(new PreviewObservation(new PresentationValue.NumberValue(2), PreviewObservation.Origin.CLIENT_SYNCED, context.tick(), false))) : Map.of();
			}
		};
		var preview = new ClientPresentationPreview(type -> Optional.of(access(1, Set.of(LOCAL))), () -> world, List.of(reader), (t, type) -> Optional.empty(), sent::add);
		preview.begin(new ClientPresentationPreview.Binding(new Object(), new Target.LocationTarget(world.dimensionId(), 0, 0, 0), "block", world.level));
		assertTrue(sent.isEmpty()); present[0] = false; preview.tick();
		assertEquals(Set.of(LOCAL), sent.getFirst().fields());
		assertTrue(preview.projection().orElseThrow().property(PresentationPropertyRef.root(ADAPTER, LOCAL)).isEmpty());
	}
}
