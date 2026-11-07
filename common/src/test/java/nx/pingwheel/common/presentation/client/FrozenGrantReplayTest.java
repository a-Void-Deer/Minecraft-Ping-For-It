package nx.pingwheel.common.presentation.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationGrant;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D0019's client rule: CREATED carries the server-owned grant, later same-epoch
 * RESET and SECTION traffic cannot replace that grant, and replaying a changed
 * grant on an already-known marker is rejected instead of widening it.
 *
 * <p>Assertions use the production CREATED factory and codec plus the public
 * client API; no test-local packet model replaces the server-owned grant.
 */
@NotNullByDefault
class FrozenGrantReplayTest {
	private static final long EPOCH = 81L;
	private static final long VIEW = 1L;
	private static final String BASIC = PresentationBasic.ID;
	private static final String NAME = PresentationBasic.NAME;
	private static final String HEALTH = PresentationBasic.HEALTH;
	private static final String STATE = PresentationBasic.BLOCK_STATE;
	private static final MarkerId A_ID = new MarkerId(11);
	private static final MarkerId B_ID = new MarkerId(12);
	private static final PresentationPropertyRef CHILD = new PresentationPropertyRef(BASIC, STATE, List.of("denied"));
	private static final PresentationPropertyRef DESCENDANT =
		new PresentationPropertyRef(BASIC, STATE, List.of("denied", "allowed", "leaf"));
	private static final PresentationPropertyRef SIBLING = new PresentationPropertyRef(BASIC, STATE, List.of("sibling"));

	@Test
	void perMarkerGrantsProjectOppositeFieldsAndExactChildDenials() {
		try (var f = new Fixture()) {
			var viewA = f.client.view(A_ID);
			var viewB = f.client.view(B_ID);

			assertEquals(new PresentationValue.Text("A"), viewA.field(BASIC, NAME));
			assertNull(viewA.field(BASIC, HEALTH), "A's frozen grant does not include health");

			assertNull(viewB.field(BASIC, NAME), "B's frozen grant does not include the target name");
			assertEquals(new PresentationValue.NumberValue(1), viewB.field(BASIC, HEALTH));

			assertEquals(f.grantA, f.client.markerGrant(A_ID).orElseThrow().authorization());
			assertEquals(f.grantB, f.client.markerGrant(B_ID).orElseThrow().authorization());
			assertNull(f.client.markerGrant(A_ID).orElseThrow().trackingKind());

			var record = assertInstanceOf(PresentationValue.RecordValue.class, viewA.field(BASIC, STATE));
			assertTrue(record.values().containsKey("denied"),
				"an exact child deny never removes the nested value from the parent record");
			assertNull(viewA.property(CHILD), "the exact child annotation is denied");
			assertEquals(new PresentationValue.NumberValue(2), viewA.property(SIBLING));
			assertEquals(new PresentationValue.Flag(true), viewA.property(DESCENDANT));
			assertFalse(viewA.sections().get(BASIC).annotations().containsKey(CHILD));
			assertEquals(Fixture.deniedValue(), viewB.property(CHILD),
				"the other marker's empty child deny still admits the same exact child");
			assertTrue(viewB.sections().get(BASIC).annotations().containsKey(CHILD));
		}
	}

	@Test
	void sameEpochTighterResetCannotPruneAnExistingGrant() {
		try (var f = new Fixture()) {
			assertTrue(f.client.reset(PresentationS2CPacket.reset(EPOCH, VIEW + 1,
				Map.of("block", Map.of(BASIC, Set.of(HEALTH))), PresentationS2CPacket.emptyChildBlack())));

			assertEquals(new PresentationValue.Text("A"), f.client.view(A_ID).field(BASIC, NAME),
				"the old marker keeps its frozen name after a tighter same-epoch mask");
			assertNull(f.client.view(A_ID).field(BASIC, HEALTH));
			assertEquals(new PresentationValue.NumberValue(1), f.client.view(B_ID).field(BASIC, HEALTH));
		}
	}

	@Test
	void sameEpochLooserResetCannotWidenAnExistingGrant() {
		try (var f = new Fixture()) {
			assertTrue(f.client.reset(PresentationS2CPacket.reset(EPOCH, VIEW + 1,
				Map.of("block", Map.of(BASIC, Set.of(HEALTH))), PresentationS2CPacket.emptyChildBlack())));
			assertTrue(f.client.reset(PresentationS2CPacket.reset(EPOCH, VIEW + 2,
				Map.of("block", Map.of(BASIC, Set.of(NAME, HEALTH, STATE))), PresentationS2CPacket.emptyChildBlack())));

			assertNull(f.client.view(A_ID).field(BASIC, HEALTH),
				"a later loose mask cannot add a field denied by A's create-time grant");
			assertNull(f.client.view(B_ID).field(BASIC, NAME),
				"a later loose mask cannot add a field denied by B's create-time grant");
			assertEquals(new PresentationValue.Text("A"), f.client.view(A_ID).field(BASIC, NAME));
			assertEquals(new PresentationValue.NumberValue(1), f.client.view(B_ID).field(BASIC, HEALTH));
		}
	}

	@Test
	void sectionUpdatesProjectAgainstEachMarkersGrant() {
		try (var f = new Fixture()) {
			assertTrue(f.client.reset(PresentationS2CPacket.reset(EPOCH, VIEW + 1,
				Map.of("block", Map.of(BASIC, Set.of(HEALTH))), PresentationS2CPacket.emptyChildBlack())));

			var record = new PresentationValue.RecordValue(Map.of(
				"denied", new PresentationValue.Flag(true),
				"allowed", new PresentationValue.RecordValue(Map.of("leaf", new PresentationValue.Flag(true))),
				"sibling", new PresentationValue.NumberValue(2)));
			var sectionA = new PresentationSection(BASIC, 1, Map.of(
				NAME, new PresentationValue.Text("A2"),
				HEALTH, new PresentationValue.NumberValue(9),
				STATE, record), false, Map.of(CHILD, "danger", SIBLING, "attention"));
			assertTrue(f.client.section(PresentationS2CPacket.section(EPOCH, VIEW + 1, 3, A_ID, sectionA)));
			assertEquals(new PresentationValue.Text("A2"), f.client.view(A_ID).field(BASIC, NAME));
			assertNull(f.client.view(A_ID).field(BASIC, HEALTH), "A's SECTION cannot install a denied field");
			assertEquals(record, f.client.view(A_ID).field(BASIC, STATE));
			assertFalse(f.client.view(A_ID).sections().get(BASIC).annotations().containsKey(CHILD),
				"A's frozen child deny still applies to a later SECTION");
			assertTrue(f.client.view(A_ID).sections().get(BASIC).annotations().containsKey(SIBLING));

			var sectionB = new PresentationSection(BASIC, 1, Map.of(
				NAME, new PresentationValue.Text("B2"),
				HEALTH, new PresentationValue.NumberValue(8),
				STATE, record), false, Map.of(CHILD, "danger"));
			assertTrue(f.client.section(PresentationS2CPacket.section(EPOCH, VIEW + 1, 4, B_ID, sectionB)));
			assertNull(f.client.view(B_ID).field(BASIC, NAME), "B's SECTION cannot install a denied field");
			assertEquals(new PresentationValue.NumberValue(8), f.client.view(B_ID).field(BASIC, HEALTH));
			assertTrue(f.client.view(B_ID).sections().get(BASIC).annotations().containsKey(CHILD),
				"B's empty child deny still admits the exact child on a later SECTION");
		}
	}

	@Test
	void currentViewReplayWithADifferentGrantIsRejectedWithoutMutatingTheMarker() {
		try (var f = new Fixture()) {
			var replacement = created(VIEW, 2, snapshot(A_ID),
				new MarkerContentGrant(f.grantB, null), f.sectionB());

			assertFalse(f.client.initial(replacement),
				"a current-view replay must be rejected for the changed grant, not by the view fence");
			assertEquals(f.grantA, f.client.markerGrant(A_ID).orElseThrow().authorization());
			assertEquals(new PresentationValue.Text("A"), f.client.view(A_ID).field(BASIC, NAME));
			assertNull(f.client.view(A_ID).field(BASIC, HEALTH));
		}
	}

	@Test
	void currentViewReplayWithTheSameGrantIsAccepted() {
		try (var f = new Fixture()) {
			var updated = new PresentationSection(BASIC, 1,
				Map.of(NAME, new PresentationValue.Text("A2")), false);
			var replay = created(VIEW, 2, snapshot(A_ID),
				new MarkerContentGrant(f.grantA, null), updated);

			assertTrue(f.client.initial(replay),
				"the same frozen grant may replay a newer atomic Basic at the current view");
			assertEquals(new PresentationValue.Text("A2"), f.client.view(A_ID).field(BASIC, NAME));
			assertEquals(f.grantA, f.client.markerGrant(A_ID).orElseThrow().authorization());
		}
	}

	@Test
	void oldViewReplayIsRejectedByTheViewFenceWithoutMutatingTheMarker() {
		try (var f = new Fixture()) {
			var stale = created(VIEW + 1, 2, snapshot(A_ID),
				new MarkerContentGrant(f.grantA, null), f.sectionA());

			assertFalse(f.client.initial(stale),
				"a future-view replay is rejected by the view fence even with the same grant");
			assertEquals(f.grantA, f.client.markerGrant(A_ID).orElseThrow().authorization());
			assertEquals(new PresentationValue.Text("A"), f.client.view(A_ID).field(BASIC, NAME));
		}
	}

	@Test
	void closeAndANewEpochOfferDoNotRestoreOldPerMarkerGrants() {
		try (var f = new Fixture()) {
			f.client.close();
			assertTrue(f.client.markerGrant(A_ID).isEmpty());
			assertTrue(f.client.markerGrant(B_ID).isEmpty());

			f.client.tick(true);
			assertTrue(f.client.offer(PresentationS2CPacket.offer(EPOCH + 1,
				Map.of(BASIC, PresentationBasic.fields()), Map.of(BASIC, 1))));
			assertTrue(f.client.markerGrant(A_ID).isEmpty(),
				"a true epoch boundary must not replay an old marker grant");
			assertTrue(f.client.markerGrant(B_ID).isEmpty());
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		private final List<IPacket> sent = new ArrayList<>();
		private final PresentationGrant grantA = grant(Set.of(NAME, STATE), Set.of(CHILD));
		private final PresentationGrant grantB = grant(Set.of(HEALTH, STATE), Set.of());
		private final ClientPresentation client = readyClient(sent);

		private Fixture() {
			assertTrue(client.initial(created(VIEW, 1, snapshot(A_ID),
				new MarkerContentGrant(grantA, null), sectionA())));
			assertTrue(client.initial(created(VIEW, 1, snapshot(B_ID),
				new MarkerContentGrant(grantB, null), sectionB())));
		}

		@Override public void close() { client.close(); }

		private PresentationSection sectionA() {
			return new PresentationSection(BASIC, 1, Map.of(
				NAME, new PresentationValue.Text("A"),
				HEALTH, new PresentationValue.NumberValue(1),
				STATE, record()), false, Map.of(CHILD, "danger", DESCENDANT, "attention", SIBLING, "attention"));
		}

		private PresentationSection sectionB() {
			return new PresentationSection(BASIC, 1, Map.of(
				NAME, new PresentationValue.Text("B"),
				HEALTH, new PresentationValue.NumberValue(1),
				STATE, record()), false, Map.of(CHILD, "danger", SIBLING, "attention"));
		}

		private static PresentationValue.RecordValue record() {
			return new PresentationValue.RecordValue(Map.of(
				"denied", deniedValue(),
				"sibling", new PresentationValue.NumberValue(2)));
		}

		private static PresentationValue.RecordValue deniedValue() {
			return new PresentationValue.RecordValue(Map.of(
				"allowed", new PresentationValue.RecordValue(Map.of("leaf", new PresentationValue.Flag(true)))));
		}
	}

	private static ClientPresentation readyClient(List<IPacket> sent) {
		ClientPresentation client = new ClientPresentation(sent::add);
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(EPOCH,
			Map.of(BASIC, PresentationBasic.fields()), Map.of(BASIC, 1))));
		assertTrue(client.reset(PresentationS2CPacket.reset(EPOCH, VIEW,
			Map.of("block", Map.of(BASIC, Set.of(NAME, HEALTH, STATE))),
			PresentationS2CPacket.emptyChildBlack())));
		return client;
	}

	private static PresentationGrant grant(@Unmodifiable Set<String> fields,
		@Unmodifiable Set<PresentationPropertyRef> childBlack) {
		var descriptors = new LinkedHashMap<String, PresentationField.Kind>();
		for (String field : fields) descriptors.put(field, kind(field));
		return new PresentationGrant(Map.of(BASIC,
			new PresentationGrant.AdapterGrant(1, descriptors)), childBlack);
	}

	private static PresentationField.Kind kind(String field) {
		return switch (field) {
			case NAME -> PresentationField.Kind.TEXT;
			case HEALTH -> PresentationField.Kind.NUMBER;
			case STATE -> PresentationField.Kind.RECORD;
			default -> throw new IllegalArgumentException("unexpected test field " + field);
		};
	}

	private static MarkerSnapshot snapshot(MarkerId id) {
		return new MarkerSnapshot(id, new UUID(0L, id.value()),
			new Target.BlockTarget("minecraft:overworld", 1, 2, (int) id.value(), "minecraft:chest"),
			"block", "attention", new MarkerAnchor(1, 2, 3), 1, 100);
	}

	private static PresentationS2CPacket created(long view, long revision, MarkerSnapshot snapshot,
		MarkerContentGrant grant, PresentationSection basic) {
		PresentationS2CPacket packet = PresentationS2CPacket.created(EPOCH, view, revision, snapshot, "Owner",
			PresentationPropertyRef.root(BASIC, NAME), grant, PresentationReceiptContent.whole(), basic);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buffer);
			var decoded = new PresentationS2CPacket(buffer);
			assertFalse(decoded.isCorrupt(), "the CREATED grant must survive the production wire codec");
			return decoded;
		} finally {
			buffer.release();
		}
	}
}
