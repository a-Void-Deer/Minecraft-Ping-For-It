package nx.pingwheel.common.presentation.minecraft;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
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
import nx.pingwheel.common.network.PresentationGrantCodec;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationGrant;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.TestContentGrants;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 boundaries for zero-adapter denial, projected initial sizing and
 * side-effect-free inventory admission before the first CREATED effect.
 */
@NotNullByDefault
class FrozenInitialAdmissionTest {
	private static final long EPOCH = 81L;
	private static final long VIEW = 1L;
	private static final String BASIC = PresentationBasic.ID;
	private static final String NAME = PresentationBasic.NAME;
	private static final String TYPE = "block";
	private static final UUID OWNER = new UUID(1, 1);

	@Test
	void denyAllZeroAdapterGrantAcceptsMarkerIdentityButMismatchedSchemaStillFails() {
		ClientPresentation client = readyClient();
		MarkerId id = new MarkerId(11);
		PresentationSection basic = new PresentationSection(BASIC, 1,
			Map.of(NAME, new PresentationValue.Text("{\"text\":\"Chest\"}")), false);
		MarkerContentGrant denyAll = new MarkerContentGrant(PresentationGrant.denied(), null);

		PresentationS2CPacket accepted = wire(PresentationS2CPacket.created(EPOCH, VIEW, 1,
			snapshot(id), "Owner", PresentationPropertyRef.root(BASIC, NAME), denyAll,
			PresentationReceiptContent.whole(), basic));
		assertFalse(accepted.isCorrupt());
		assertTrue(client.initial(accepted), "marker identity is valid even when the grant authorizes no field");
		assertTrue(client.markerGrant(id).isPresent());
		assertTrue(client.view(id).sections().isEmpty(), "the denied Basic field is not retained");

		PresentationGrant wrongSchema = new PresentationGrant(Map.of(BASIC,
			new PresentationGrant.AdapterGrant(2, Map.of(NAME, PresentationField.Kind.TEXT))), Set.of());
		PresentationS2CPacket schemaMismatch = wire(PresentationS2CPacket.created(EPOCH, VIEW, 2,
			snapshot(new MarkerId(12)), "Owner", PresentationPropertyRef.root(BASIC, NAME),
			new MarkerContentGrant(wrongSchema, null), PresentationReceiptContent.whole(), basic));
		assertFalse(client.initial(schemaMismatch), "a present adapter grant must match the section schema");

		PresentationGrant wrongKind = new PresentationGrant(Map.of(BASIC,
			new PresentationGrant.AdapterGrant(1, Map.of(NAME, PresentationField.Kind.NUMBER))), Set.of());
		PresentationS2CPacket kindMismatch = wire(PresentationS2CPacket.created(EPOCH, VIEW, 3,
			snapshot(new MarkerId(13)), "Owner", PresentationPropertyRef.root(BASIC, NAME),
			new MarkerContentGrant(wrongKind, null), PresentationReceiptContent.whole(), basic));
		assertTrue(client.initial(kindMismatch));
		assertTrue(client.view(new MarkerId(13)).sections().isEmpty(), "a mismatched kind never widens the grant");
	}

	@Test
	void projectedGrantSizeMatchesWireFilteringAndIgnoresInertCanonicalChildRefs() throws Exception {
		PresentationRegistry registry = new PresentationRegistry();
		registry.register(new BasicAdapter());
		PresentationRegistry previous = replaceRegistry(registry);
		try {
			PresentationServer.Session session = new PresentationServer.Session(EPOCH,
				Map.of(BASIC, 1), Map.of(BASIC, List.of(new PresentationField(NAME,
					PresentationField.Kind.TEXT, true, 0, "Name"))));
			Set<PresentationPropertyRef> children = new LinkedHashSet<>();
			for (int index = 0; index < 128; index++)
				children.add(new PresentationPropertyRef("test:absent", "test:absent.field",
					List.of("k".repeat(124) + "%04d".formatted(index))));
			PresentationGrant canonical = new PresentationGrant(Map.of(
				BASIC, new PresentationGrant.AdapterGrant(1, Map.of(NAME, PresentationField.Kind.TEXT)),
				"test:absent", new PresentationGrant.AdapterGrant(1, Map.of("test:absent.field", PresentationField.Kind.TEXT))),
				children);

			int canonicalBytes = PresentationGrantCodec.encodedSize(new MarkerContentGrant(canonical, null));
			MarkerContentGrant projected = PresentationServer.wireContentGrant(session,
				(InventoryResourceKind) null, canonical);
			int projectedBytes = PresentationServer.projectedGrantSize(session, null, canonical);
			assertEquals(PresentationGrantCodec.encodedSize(projected), projectedBytes,
				"staging size must equal the actual session projection");
			assertTrue(canonicalBytes > 16384, "the canonical bound would exceed the tracking rolling queue");
			assertTrue(projectedBytes < 16384, "inert absent-adapter child refs cannot consume the wire queue");
			assertTrue(projected.authorization().childBlack().isEmpty());
			assertTrue(projected.authorization().allowsField(BASIC, NAME));
		} finally {
			replaceRegistry(previous);
		}
	}

	@Test
	void inventoryAdmissionRejectsAtCapacityWithoutMutationAndReopensAfterRemoval() throws Exception {
		ClientInventory inventory = new ClientInventory(packet -> {});
		inventory.acceptedSchemas(Set.of(InventoryResourceKind.ITEM));
		MarkerContentGrant itemGrant = TestContentGrants.content(
			TestContentGrants.fromAllowed(Map.of(InventoryPresentation.ADAPTER_ID,
				Set.of(InventoryPresentation.ITEMS))), InventoryResourceKind.ITEM);
		for (int index = 1; index <= ClientInventory.MAX_TRACKED_CHANNELS; index++)
			inventory.markerCreated(snapshot(new MarkerId(index)), itemGrant);
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS, knownMarkers(inventory));

		MarkerSnapshot fresh = snapshot(new MarkerId(999));
		InventoryCreationSnapshot creation = creation(InventoryResourceKind.ITEM);
		assertFalse(inventory.admitsCreation(fresh, itemGrant, creation),
			"the first delivery is rejected before any presentation or sound effect");
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS, knownMarkers(inventory), "admission is side-effect-free");
		assertTrue(inventory.admitsCreation(fresh, new MarkerContentGrant(PresentationGrant.denied(), null), null),
			"ordinary markers keep their existing admission");

		MarkerId duplicate = new MarkerId(2);
		MarkerContentGrant different = TestContentGrants.content(
			TestContentGrants.fromAllowed(Map.of(InventoryPresentation.ADAPTER_ID,
				Set.of(InventoryPresentation.ITEMS, InventoryPresentation.FLUIDS))), InventoryResourceKind.ITEM);
		assertFalse(inventory.admitsCreation(snapshot(duplicate), different, creation),
			"a duplicate id cannot bypass admission with a different grant");

		inventory.markerRemoved(new MarkerId(1));
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS - 1, knownMarkers(inventory));
		assertTrue(inventory.admitsCreation(fresh, itemGrant, creation),
			"a freed slot can still admit a later first delivery");
	}

	private static ClientPresentation readyClient() {
		ClientPresentation client = new ClientPresentation(packet -> {});
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(EPOCH,
			Map.of(BASIC, PresentationBasic.fields()), Map.of(BASIC, 1))));
		assertTrue(client.reset(PresentationS2CPacket.reset(EPOCH, VIEW,
			Map.of(TYPE, Map.of(BASIC, Set.of(NAME))), PresentationS2CPacket.emptyChildBlack())));
		return client;
	}

	private static PresentationS2CPacket wire(PresentationS2CPacket packet) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buffer);
			PresentationS2CPacket decoded = new PresentationS2CPacket(buffer);
			assertFalse(decoded.isCorrupt());
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private static MarkerSnapshot snapshot(MarkerId id) {
		return new MarkerSnapshot(id, OWNER,
			new Target.BlockTarget("minecraft:overworld", 1, 2, (int) id.value(), "minecraft:chest"),
			TYPE, "attention", new MarkerAnchor(1, 2, 3), 0, 10_000);
	}

	private static InventoryCreationSnapshot creation(InventoryResourceKind kind) {
		return new InventoryCreationSnapshot(kind, "key", "minecraft:stone", "stone", null,
			1, false, "attention", true, false);
	}

	@SuppressWarnings("unchecked")
	private static int knownMarkers(ClientInventory inventory) throws ReflectiveOperationException {
		Map<?, ?> known = (Map<?, ?>) field(ClientInventory.class, "knownMarkers").get(inventory);
		return known.size();
	}

	private static PresentationRegistry replaceRegistry(PresentationRegistry registry) throws ReflectiveOperationException {
		Field field = field(PresentationServer.class, "registry");
		PresentationRegistry previous = (PresentationRegistry) field.get(null);
		field.set(null, registry);
		return previous;
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	@NotNullByDefault
	private static final class BasicAdapter implements PresentationAdapter {
		@Override public String adapterId() { return BASIC; }
		@Override public String modId() { return "test"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 1; }
		@Override public @Unmodifiable List<PresentationField> fields() {
			return List.of(new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name"));
		}
		@Override public @Nullable PresentationSection collect(DetachedTarget target, Set<String> demand,
			CaptureBudget budget) { return PresentationSection.empty(BASIC, 1); }
	}
}
