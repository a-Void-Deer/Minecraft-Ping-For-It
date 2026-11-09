package nx.pingwheel.common.presentation.minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.PingAuthorization;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationAuthorization;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationPropertySelection;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryServer;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D0019 delivery cost: an existing marker's initial, baseline replay, cached
 * refresh and receipt projection all read the creation-frozen grant. They do
 * not call the replaced permission provider, so a later revocation cannot
 * rewrite delivered CREATED/SECTION content or its receipt. A new create does
 * resolve the replaced provider and freezes its denial separately.
 *
 * <p>This complements {@link FrozenPingCreationCutTest}, which proves the
 * create-time grant value and the wire projection but not the complete send
 * entry points. The assertions read delivered packets rather than reconstructing
 * the expected grant from the current policy implementation.
 */
@NotNullByDefault
class FrozenGrantDeliveryTest {
	private static final UUID OWNER = new UUID(0x900L, 1L);
	private static final UUID VIEWER = new UUID(0x901L, 2L);
	private static final String TYPE = "block";
	private static final String BASIC = PresentationBasic.ID;
	private static final String NAME = PresentationBasic.NAME;
	private static final String HEALTH = PresentationBasic.HEALTH;
	private static final String OBSERVATION = "test:frozen-delivery";
	private static final String VALUE = "test:frozen-delivery.value";
	private static final String RECEIPT_PING_TYPE = "attention";
	private static final String NAME_JSON = "{\"text\":\"Chest\"}";
	private static final String VALUE_TEXT = "observation";
	private static final Target TARGET = new Target.BlockTarget("minecraft:overworld", 1, 64, 3, "minecraft:chest");
	private static final PresentationPropertyRef NAME_REF = PresentationPropertyRef.root(BASIC, NAME);

	private final Server server = DoubleChestFixture.allocate(Server.class);
	private final Players players = DoubleChestFixture.allocate(Players.class);
	private final Player viewer = player(VIEWER);
	private final List<PresentationS2CPacket> deliveries = new ArrayList<>();

	private PresentationSettings policy;
	private PresentationSettings savedPolicy;
	private ServerMarkerStore store;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void setUp() {
		players.add(viewer);
		server.players = players;
		store = new ServerMarkerStore(new MarkerIdSource());
		policy = ServerConfig.HANDLER.getConfig().getPresentation();
		savedPolicy = policy.deepCopy();
		policy.copyFrom(PresentationSettings.serverDefaults());
		InventoryServer.reset();
		PresentationAuthorization.setProvider(null);
		PresentationServer.reset();
		InventoryProductionPolicyTest.Network.receiver = (packet, recipient) -> {
			if (packet instanceof PresentationS2CPacket presentation) deliveries.add(presentation);
		};
		PresentationServer.activate(server);
		registry().register(new ObservationAdapter());
		deliveries.clear();
	}

	@AfterEach
	void tearDown() {
		InventoryProductionPolicyTest.Network.receiver = null;
		PresentationAuthorization.setProvider(null);
		policy.copyFrom(savedPolicy);
		InventoryServer.reset();
		PresentationServer.reset();
		BudgetDelayMonitoring.reset();
		deliveries.clear();
	}

	@Test
	void existingMarkerDeliveryUsesItsCreationGrantAcrossEverySendEntryPoint() {
		PresentationAuthorization.setProvider((recipient, field, actual, required) ->
			field.id().equals(NAME) || field.id().equals(VALUE));
		PingAuthorization oldAuthorization = PresentationServer.freezeForCreate(
			server, TARGET, TYPE, OWNER, List.of(VIEWER));
		assertTrue(oldAuthorization.forRecipient(VIEWER).allowsField(BASIC, NAME));
		assertTrue(oldAuthorization.forRecipient(VIEWER).allowsField(OBSERVATION, VALUE));
		assertFalse(oldAuthorization.forRecipient(VIEWER).allowsField(BASIC, HEALTH));
		ServerMarker oldMarker = marker(1, oldAuthorization);

		PresentationServer.Session session = negotiateSession();
		assertEquals(VIEWER, session.recipient, "the session carries the real ready recipient");
		assertTrue(session.ready);
		deliveries.clear();

		// D0019: later revocation affects only new authorization and new Pings.
		AtomicInteger providerCalls = new AtomicInteger();
		PresentationAuthorization.setProvider((recipient, field, actual, required) -> {
			providerCalls.incrementAndGet();
			return false;
		});

		// Production created() -> sendBaseline(): the optional SECTION is the
		// second concrete value path besides the Basic CREATED payload.
		PresentationServer.created(server, oldMarker, TargetNameJsonCodec.UNKNOWN, "Owner",
			Map.of(BASIC, cachedBasic(), OBSERVATION, cachedObservation()));
		assertEquals(2, deliveries.size(), "the baseline sends the CREATED record and its optional SECTION");
		PresentationS2CPacket baseline = deliveries.get(0);
		assertEquals(PresentationS2CPacket.Kind.CREATED, baseline.kind());
		assertEquals(new PresentationValue.Text(NAME_JSON), decoded(baseline).fields().get(NAME),
			"the baseline CREATED carries the creation-frozen Basic value");
		assertEquals(PresentationReceiptContent.properties(List.of(NAME_REF)), baseline.content(),
			"the receipt still follows the creation grant that authorized the selected name");
		assertTrue(baseline.markerGrant().authorization().allowsField(BASIC, NAME));
		assertTrue(baseline.markerGrant().authorization().allowsField(OBSERVATION, VALUE));
		PresentationS2CPacket optional = deliveries.get(1);
		assertEquals(PresentationS2CPacket.Kind.SECTION, optional.kind());
		assertEquals(OBSERVATION, decoded(optional).adapterId());
		assertEquals(new PresentationValue.Text(VALUE_TEXT), decoded(optional).fields().get(VALUE),
			"the SECTION carries the creation-frozen optional value");
		assertEquals(0, providerCalls.get(), "baseline delivery must not resolve a fresh permission");

		// Production updated() -> sendInitial(): a metadata refresh replays the
		// same frozen Basic projection and receipt.
		deliveries.clear();
		PresentationServer.updated(server, oldMarker);
		assertEquals(1, deliveries.size());
		PresentationS2CPacket refreshed = deliveries.get(0);
		assertEquals(PresentationS2CPacket.Kind.CREATED, refreshed.kind());
		assertEquals(new PresentationValue.Text(NAME_JSON), decoded(refreshed).fields().get(NAME));
		assertEquals(PresentationReceiptContent.properties(List.of(NAME_REF)), refreshed.content());
		assertEquals(0, providerCalls.get(), "refreshed initial delivery must not resolve a fresh permission");

		// The exact cached-refresh seam is given throwing fresh suppliers: the
		// frozen marker grant is the only authority it may evaluate.
		PresentationAdapter basic = Objects.requireNonNull(registry().get(BASIC), "basic adapter");
		deliveries.clear();
		PresentationServer.refreshMarker(oldMarker, Map.of(), basic, 0, Map.of(VIEWER, session),
			recipient -> true, recipient -> Set.of(),
			recipient -> adapter -> {
				throw new AssertionError("fresh authorization must not be evaluated");
			},
			recipient -> {
				throw new AssertionError("fresh inventory gate must not be evaluated");
			}, (recipient, packet) -> deliveries.add(packet));
		assertEquals(1, deliveries.size());
		PresentationS2CPacket cached = deliveries.get(0);
		assertEquals(PresentationS2CPacket.Kind.CREATED, cached.kind());
		assertEquals(new PresentationValue.Text(NAME_JSON), decoded(cached).fields().get(NAME));
		assertEquals(PresentationReceiptContent.properties(List.of(NAME_REF)), cached.content());
		assertEquals(0, providerCalls.get(), "cached refresh must not resolve a fresh permission");

		int beforeNewCreate = providerCalls.get();
		PingAuthorization newAuthorization = PresentationServer.freezeForCreate(
			server, TARGET, TYPE, OWNER, List.of(VIEWER));
		assertTrue(providerCalls.get() > beforeNewCreate,
			"a new create resolves the replaced provider instead of reusing the old grant");
		assertFalse(newAuthorization.forRecipient(VIEWER).allowsField(BASIC, NAME),
			"a new create freezes the later denial");
		assertFalse(newAuthorization.forRecipient(VIEWER).allowsField(OBSERVATION, VALUE));
		assertFalse(PresentationServer.wireContentGrant(session, marker(2, newAuthorization))
			.authorization().allowsField(BASIC, NAME),
			"the new marker's wire projection carries its own denied grant");
		assertTrue(oldAuthorization.forRecipient(VIEWER).allowsField(BASIC, NAME),
			"the old marker's create result is never rewritten by a later create");
	}

	private PresentationServer.Session negotiateSession() {
		PresentationServer.negotiate(server, viewer,
			PresentationC2SPacket.hello(Map.of(BASIC, 1, OBSERVATION, 1)), store);
		PresentationServer.Session session = sessions().get(VIEWER);
		assertNotNull(session, "the production HELLO path installs a ready session");
		return Objects.requireNonNull(session);
	}

	private static ServerMarker marker(long id, PingAuthorization authorization) {
		var type = TargetTypeCatalog.builtIn().findById(TYPE).orElseThrow();
		return new ServerMarker(new MarkerId(id), OWNER, TARGET, TargetKey.from(TARGET), type,
			type.defaultPingType(), new MarkerAnchor(1, 64, 3), 0, 1000, List.of(VIEWER),
			List.of(PresentationPropertySelection.of(NAME_REF, RECEIPT_PING_TYPE)), authorization);
	}

	private static PresentationSection cachedBasic() {
		return new PresentationSection(BASIC, 1,
			Map.of(NAME, new PresentationValue.Text(NAME_JSON)), false);
	}

	private static PresentationSection cachedObservation() {
		return new PresentationSection(OBSERVATION, 1,
			Map.of(VALUE, new PresentationValue.Text(VALUE_TEXT)), false);
	}

	private static PresentationSection decoded(PresentationS2CPacket packet) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(packet.sectionBytes()));
		try {
			return PresentationCodec.read(buffer, field -> true);
		} finally {
			buffer.release();
		}
	}

	private static Player player(UUID id) {
		Player player = DoubleChestFixture.allocate(Player.class);
		player.id = id;
		player.permission = 4;
		return player;
	}

	private static PresentationRegistry registry() {
		try {
			Field field = PresentationServer.class.getDeclaredField("registry");
			field.setAccessible(true);
			return (PresentationRegistry) Objects.requireNonNull(field.get(null));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<UUID, PresentationServer.Session> sessions() {
		try {
			Field field = PresentationServer.class.getDeclaredField("SESSIONS");
			field.setAccessible(true);
			return (Map<UUID, PresentationServer.Session>) Objects.requireNonNull(field.get(null));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	@NotNullByDefault
	private static final class ObservationAdapter implements PresentationAdapter {
		@Override public String adapterId() { return OBSERVATION; }
		@Override public String modId() { return "test"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 5; }
		@Override public @Unmodifiable List<PresentationField> fields() {
			return List.of(new PresentationField(VALUE, PresentationField.Kind.TEXT, true, 0, "Value"));
		}
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand,
			CaptureBudget budget) {
			return PresentationSection.empty(OBSERVATION, 1);
		}
	}

	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public DedicatedPlayerList getPlayerList() { return Objects.requireNonNull(players); }
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
		@Override public int getTickCount() { return 0; }
	}

	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private @Nullable Map<UUID, Player> all;
		private Players() { super(null, null, null); }
		private void add(Player player) {
			if (all == null) all = new LinkedHashMap<>();
			all.put(player.getUUID(), player);
		}
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return all == null ? null : all.get(id); }
		@Override public @Unmodifiable List<ServerPlayer> getPlayers() { return all == null ? List.of() : List.copyOf(all.values()); }
	}

	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private @Nullable UUID id;
		private int permission;
		private Player() { super(null, null, null, null); }
		@Override public UUID getUUID() { return Objects.requireNonNull(id); }
		@Override public GameProfile getGameProfile() { return new GameProfile(getUUID(), "viewer"); }
		@Override public boolean hasPermissions(int required) { return permission >= required; }
	}
}
