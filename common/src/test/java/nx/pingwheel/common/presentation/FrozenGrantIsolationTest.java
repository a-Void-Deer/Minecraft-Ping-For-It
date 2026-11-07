package nx.pingwheel.common.presentation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.marker.PingAuthorization;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D0019's immutable authorization value: a frozen grant is scoped to one
 * recipient, keeps exact child denial without removing its root record, and
 * cannot be widened by mutating the inputs it was built from.
 */
@NotNullByDefault
class FrozenGrantIsolationTest {
	private static final String ADAPTER = "test:adapter";
	private static final String TEXT = "test:text";
	private static final String RECORD = "test:record";
	private static final String MISSING = "test:missing";
	private static final UUID A = new UUID(0x1111L, 1L);
	private static final UUID B = new UUID(0x2222L, 2L);
	private static final UUID C = new UUID(0x3333L, 3L);

	private static PresentationGrant.AdapterGrant adapter(
		@Unmodifiable Map<String, PresentationField.Kind> fields) {
		return new PresentationGrant.AdapterGrant(7, fields);
	}

	private static PresentationGrant grant(
		@Unmodifiable Map<String, PresentationField.Kind> fields,
		@Unmodifiable Set<PresentationPropertyRef> childBlack) {
		return new PresentationGrant(Map.of(ADAPTER, adapter(fields)), childBlack);
	}

	@Test
	void grantAllowsOnlyFrozenFieldsAndExactChildDenialDoesNotCascadeOrRemoveItsRoot() {
		var child = new PresentationPropertyRef(ADAPTER, RECORD, List.of("denied"));
		var descendant = new PresentationPropertyRef(ADAPTER, RECORD, List.of("denied", "allowed", "leaf"));
		var sibling = new PresentationPropertyRef(ADAPTER, RECORD, List.of("sibling"));
		var grant = grant(Map.of(TEXT, PresentationField.Kind.TEXT, RECORD, PresentationField.Kind.RECORD),
			Set.of(child));

		assertTrue(grant.allowsField(ADAPTER, TEXT));
		assertTrue(grant.allowsField(ADAPTER, RECORD), "a denied child ref does not remove the parent record field");
		assertFalse(grant.allowsField(ADAPTER, MISSING));
		assertFalse(grant.allowsField("test:other", TEXT));

		assertTrue(grant.allowsRef(PresentationPropertyRef.root(ADAPTER, RECORD)));
		assertFalse(grant.allowsRef(child), "the exact child deny is denied");
		assertTrue(grant.allowsRef(descendant), "child denial is exact and does not cascade to descendants");
		assertTrue(grant.allowsRef(sibling));
		assertFalse(grant.allowsRef(new PresentationPropertyRef(ADAPTER, TEXT, List.of("denied"))),
			"a nested ref requires a frozen RECORD field");
		assertFalse(grant.allowsRef(new PresentationPropertyRef(ADAPTER, MISSING, List.of())));
	}

	@Test
	void grantCopiesAndFreezesAdapterFieldsAndChildBlackFromLaterInputMutation() {
		var fields = new LinkedHashMap<String, PresentationField.Kind>();
		fields.put(TEXT, PresentationField.Kind.TEXT);
		fields.put(RECORD, PresentationField.Kind.RECORD);
		var adapters = new LinkedHashMap<String, PresentationGrant.AdapterGrant>();
		adapters.put(ADAPTER, adapter(fields));
		var childBlack = new LinkedHashSet<PresentationPropertyRef>();
		var initialChild = new PresentationPropertyRef(ADAPTER, RECORD, List.of("denied"));
		childBlack.add(initialChild);

		var grant = new PresentationGrant(adapters, childBlack);

		fields.put("test:late", PresentationField.Kind.TEXT);
		adapters.put("test:late", adapter(Map.of(TEXT, PresentationField.Kind.TEXT)));
		childBlack.add(new PresentationPropertyRef(ADAPTER, RECORD, List.of("late")));

		assertFalse(grant.allowsField(ADAPTER, "test:late"));
		assertFalse(grant.adapters().containsKey("test:late"));
		assertTrue(grant.allowsRef(new PresentationPropertyRef(ADAPTER, RECORD, List.of("late"))),
			"the frozen child deny set must not observe later input mutation");
		assertThrows(UnsupportedOperationException.class,
			() -> grant.adapters().put("test:mutated", adapter(Map.of(TEXT, PresentationField.Kind.TEXT))));
		assertThrows(UnsupportedOperationException.class,
			() -> grant.adapters().get(ADAPTER).fields().put("test:mutated", PresentationField.Kind.TEXT));
		assertThrows(UnsupportedOperationException.class,
			() -> grant.childBlack().add(new PresentationPropertyRef(ADAPTER, RECORD, List.of("mutated"))));
	}

	@Test
	void recipientGrantsAreIndependentAndAMissingRecipientIsDenied() {
		var aGrant = grant(Map.of(TEXT, PresentationField.Kind.TEXT), Set.of());
		var bGrant = grant(Map.of(RECORD, PresentationField.Kind.RECORD), Set.of());
		var recipients = new LinkedHashMap<UUID, PresentationGrant>();
		recipients.put(A, aGrant);
		recipients.put(B, bGrant);
		var authorization = new PingAuthorization(recipients);

		recipients.put(C, aGrant);

		assertEquals(Set.of(A, B), authorization.recipients().keySet());
		assertTrue(authorization.forRecipient(A).allowsField(ADAPTER, TEXT));
		assertFalse(authorization.forRecipient(A).allowsField(ADAPTER, RECORD));
		assertTrue(authorization.forRecipient(B).allowsField(ADAPTER, RECORD));
		assertFalse(authorization.forRecipient(B).allowsField(ADAPTER, TEXT));
		assertTrue(authorization.forRecipient(C).adapters().isEmpty(),
			"a recipient absent from the frozen creation audience is denied, not added later");
		assertThrows(UnsupportedOperationException.class,
			() -> authorization.recipients().put(C, aGrant));
	}

	@Test
	void retainingKeepsOnlyTheOriginalAudienceAndDeniedCreatesExplicitDenyEntries() {
		var aGrant = grant(Map.of(TEXT, PresentationField.Kind.TEXT), Set.of());
		var bGrant = grant(Map.of(RECORD, PresentationField.Kind.RECORD), Set.of());
		var authorization = new PingAuthorization(Map.of(A, aGrant, B, bGrant));

		var retained = authorization.retaining(List.of(A, C));
		assertEquals(Set.of(A), retained.recipients().keySet());
		assertTrue(retained.forRecipient(A).allowsField(ADAPTER, TEXT));
		assertTrue(retained.forRecipient(B).adapters().isEmpty(),
			"a recipient removed from the retained audience must not keep an older grant");
		assertTrue(retained.forRecipient(C).adapters().isEmpty(),
			"retaining must not grant an audience member that had no create-time grant");

		var denied = PingAuthorization.denied(List.of(A, B));
		assertEquals(Set.of(A, B), denied.recipients().keySet());
		assertTrue(denied.forRecipient(A).adapters().isEmpty());
		assertTrue(denied.forRecipient(B).adapters().isEmpty());
		assertTrue(denied.forRecipient(C).adapters().isEmpty());
	}

	@Test
	void markerContentGrantKeepsAuthorizationAndDedicatedTrackingKindSeparate() {
		var grant = grant(Map.of(TEXT, PresentationField.Kind.TEXT), Set.of());
		var ordinary = new MarkerContentGrant(grant, null);
		var item = new MarkerContentGrant(grant, InventoryResourceKind.ITEM);

		assertEquals(grant, ordinary.authorization());
		assertNull(ordinary.trackingKind());
		assertEquals(InventoryResourceKind.ITEM, item.trackingKind());
		assertEquals(grant, item.authorization());
	}
}
