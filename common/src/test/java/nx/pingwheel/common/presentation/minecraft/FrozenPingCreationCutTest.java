package nx.pingwheel.common.presentation.minecraft;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.PingAuthorization;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationAuthorization;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
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
 * D0019's server creation cut: the frozen grant is resolved from the server
 * registry and the fresh server policy/permission/child result. It is not
 * narrowed by an accepted session manifest, an advertised mask or an earlier
 * RESET child map, and readiness after creation never recomputes it.
 *
 * <p>Source-owner read scope, locks, keys and true source readiness are
 * deliberately outside this file: they remain current runtime gates and are
 * not presentation grant inputs.
 */
@NotNullByDefault
class FrozenPingCreationCutTest {
	private static final UUID OWNER = new UUID(0x700L, 1L);
	private static final UUID A = new UUID(0x701L, 2L);
	private static final UUID B = new UUID(0x702L, 3L);
	private static final String TYPE_ID = "block";
	private static final String BASIC = PresentationBasic.ID;
	private static final String NAME = PresentationBasic.NAME;
	private static final String HEALTH = PresentationBasic.HEALTH;
	private static final String STATE = PresentationBasic.BLOCK_STATE;
	private static final String CUSTOM_NAME = PresentationBasic.CUSTOM_NAME;
	private static final String LATE_ADAPTER = "test:late";
	private static final String LATE_FIELD = "test:late.value";
	private static final Target TARGET = new Target.BlockTarget("minecraft:overworld", 1, 64, 3, "minecraft:chest");
	private static final PresentationPropertyRef CHILD =
		new PresentationPropertyRef(BASIC, STATE, List.of("denied"));
	private static final PresentationPropertyRef OLD_ADVERTISED_CHILD =
		new PresentationPropertyRef(BASIC, STATE, List.of("advertised"));

	// Headless constructors are bypassed: a real DedicatedServer constructor
	// dereferences a WorldStem that this focused fixture does not own.
	private final Server server = DoubleChestFixture.allocate(Server.class);
	private final Players players = DoubleChestFixture.allocate(Players.class);
	private final Player playerA = player(A, 0);
	private final Player playerB = player(B, 0);

	private static Player player(UUID id, int permission) {
		Player player = DoubleChestFixture.allocate(Player.class);
		player.id = id;
		player.permission = permission;
		return player;
	}
	private PresentationSettings policy;
	private PresentationSettings savedPolicy;

	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@BeforeEach
	void setUp() {
		players.add(playerA);
		players.add(playerB);
		server.players = players;
		policy = ServerConfig.HANDLER.getConfig().getPresentation();
		savedPolicy = policy.deepCopy();
		policy.copyFrom(PresentationSettings.serverDefaults());
		PresentationAuthorization.setProvider(null);
		PresentationServer.reset();
		PresentationServer.activate(server);
	}

	@AfterEach
	void tearDown() {
		PresentationAuthorization.setProvider(null);
		policy.copyFrom(savedPolicy);
		PresentationServer.reset();
		BudgetDelayMonitoring.reset();
	}

	@Test
	void createFreezeUsesFreshSelectorNativeCustomAndChildRulesFromTheServer() {
		policy.setRules(TYPE_ID, new PresentationSettings.RuleSet(
			List.of(), List.of(HEALTH), false, List.of(CHILD)));
		policy.setPermissionLevels(Map.of(NAME, 2, CUSTOM_NAME, 2));
		PresentationAuthorization.setProvider((recipient, field, actual, required) ->
			field.id().equals(NAME) || actual >= required);

		var authorization = freeze(List.of(A));
		var grant = authorization.forRecipient(A);

		assertTrue(grant.allowsField(BASIC, NAME),
			"a custom provider may allow the field despite its native required level");
		assertFalse(grant.allowsField(BASIC, HEALTH),
			"the fresh selector deny is part of the create result");
		assertFalse(grant.allowsField(BASIC, CUSTOM_NAME),
			"the native permission result still denies an ordinary field");
		assertTrue(grant.allowsField(BASIC, STATE));
		assertTrue(grant.childBlack().contains(CHILD),
			"the fresh server child deny is frozen with the grant");
		assertEquals(Set.of(A), authorization.recipients().keySet(),
			"the marker owner is not invented as an audience recipient");
	}

	@Test
	void acceptedSessionMaskAndAdvertisedChildMapCannotAlterTheCreateGrant() {
		policy.setRules(TYPE_ID, new PresentationSettings.RuleSet(
			List.of(), List.of(), false, List.of(CHILD)));
		var authorization = freeze(List.of(A));
		var marker = marker(authorization, List.of(A));

		var session = session(A, Map.of(BASIC, 1), Map.of(BASIC, PresentationBasic.fields()),
			Map.of(TYPE_ID, List.of(OLD_ADVERTISED_CHILD)));
		session.mask = Map.of(TYPE_ID, Map.of(BASIC, Set.of(HEALTH)));
		var wire = PresentationServer.wireContentGrant(session, marker);

		assertTrue(wire.authorization().allowsField(BASIC, NAME),
			"a later advertised mask that omits NAME cannot narrow the frozen grant");
		assertTrue(wire.authorization().allowsField(BASIC, HEALTH),
			"the advertised mask is not a create-time denial either");
		assertTrue(wire.authorization().childBlack().contains(CHILD));
		assertFalse(wire.authorization().childBlack().contains(OLD_ADVERTISED_CHILD),
			"an earlier advertised child map is not unioned into the create grant");
	}

	@Test
	void notHelloAudienceKeepsItsCreationGrantWhenTheRecipientBecomesReadyLater() {
		var original = freeze(List.of(A));
		assertTrue(original.forRecipient(A).allowsField(BASIC, NAME));

		policy.setRules(TYPE_ID, new PresentationSettings.RuleSet(
			List.of(), List.of(NAME), false, List.of()));
		policy.setPermissionLevels(Map.of(NAME, 4));
		var laterCreate = freeze(List.of(A));
		assertFalse(laterCreate.forRecipient(A).allowsField(BASIC, NAME),
			"a new create sees the later policy");

		var marker = marker(original, List.of(A));
		var ready = session(A, Map.of(BASIC, 1), Map.of(BASIC, PresentationBasic.fields()),
			Map.of(TYPE_ID, List.of()));
		ready.mask = Map.of(TYPE_ID, Map.of(BASIC, Set.of(HEALTH)));
		var wire = PresentationServer.wireContentGrant(ready, marker);

		assertTrue(wire.authorization().allowsField(BASIC, NAME),
			"an original audience member that was not HELLO-ready at create keeps the original grant");
	}

	@Test
	void twoCreationsOfTheSameTargetTypeKeepOppositeFrozenGrants() {
		var visible = new AtomicReference<>(Set.of(NAME, STATE));
		PresentationAuthorization.setProvider((recipient, field, actual, required) ->
			visible.get().contains(field.id()));

		var first = freeze(List.of(A));
		visible.set(Set.of(HEALTH, STATE));
		var second = freeze(List.of(A));

		assertTrue(first.forRecipient(A).allowsField(BASIC, NAME));
		assertFalse(first.forRecipient(A).allowsField(BASIC, HEALTH));
		assertFalse(second.forRecipient(A).allowsField(BASIC, NAME));
		assertTrue(second.forRecipient(A).allowsField(BASIC, HEALTH));

		var session = session(A, Map.of(BASIC, 1), Map.of(BASIC, PresentationBasic.fields()),
			Map.of(TYPE_ID, List.of()));
		assertTrue(PresentationServer.wireContentGrant(session, marker(first, List.of(A)))
			.authorization().allowsField(BASIC, NAME));
		assertTrue(PresentationServer.wireContentGrant(session, marker(second, List.of(A)))
			.authorization().allowsField(BASIC, HEALTH));
		assertFalse(PresentationServer.wireContentGrant(session, marker(second, List.of(A)))
			.authorization().allowsField(BASIC, NAME));
	}

	@Test
	void anAdapterFieldRegisteredAfterCreationDoesNotExtendTheOldGrant() {
		var before = freeze(List.of(A));
		assertFalse(before.forRecipient(A).allowsField(LATE_ADAPTER, LATE_FIELD));

		registerLateAdapter();
		var after = freeze(List.of(A));
		assertTrue(after.forRecipient(A).allowsField(LATE_ADAPTER, LATE_FIELD),
			"a new create sees the newly registered adapter field");

		var schemas = Map.of(BASIC, 1, LATE_ADAPTER, 1);
		var manifest = Map.of(BASIC, PresentationBasic.fields(), LATE_ADAPTER,
			List.of(new PresentationField(LATE_FIELD, PresentationField.Kind.TEXT, true, 0, "late")));
		var ready = session(A, schemas, manifest, Map.of(TYPE_ID, List.of()));

		assertFalse(PresentationServer.wireContentGrant(ready, marker(before, List.of(A)))
			.authorization().allowsField(LATE_ADAPTER, LATE_FIELD),
			"an existing marker's frozen grant never acquires a later registered field");
		assertTrue(PresentationServer.wireContentGrant(ready, marker(after, List.of(A)))
			.authorization().allowsField(LATE_ADAPTER, LATE_FIELD));
	}

	@Test
	void existingMarkerWireProjectionDoesNotReevaluatePermission() {
		var calls = new AtomicInteger();
		PresentationAuthorization.setProvider((recipient, field, actual, required) -> {
			calls.incrementAndGet();
			return true;
		});
		var authorization = freeze(List.of(A));
		int afterCreate = calls.get();
		var marker = marker(authorization, List.of(A));
		var ready = session(A, Map.of(BASIC, 1), Map.of(BASIC, PresentationBasic.fields()),
			Map.of(TYPE_ID, List.of()));

		for (int index = 0; index < 5; index++) {
			assertNotNull(PresentationServer.wireContentGrant(ready, marker));
		}

		assertEquals(afterCreate, calls.get(),
			"the production wire projection of an existing marker must not call the permission provider again");
	}

	private PingAuthorization freeze(List<UUID> audience) {
		return PresentationServer.freezeForCreate(server, TARGET, TYPE_ID, OWNER, audience);
	}

	private static ServerMarker marker(PingAuthorization authorization, List<UUID> audience) {
		var type = TargetTypeCatalog.builtIn().findById(TYPE_ID).orElseThrow();
		return new ServerMarker(new MarkerId(1), OWNER, TARGET, TargetKey.from(TARGET), type, type.defaultPingType(),
			new MarkerAnchor(1, 64, 3), 0, 1000, audience, List.of(), authorization);
	}

	private static PresentationServer.Session session(UUID recipient, Map<String, Integer> schemas,
		@Unmodifiable Map<String, List<PresentationField>> manifest,
		@Unmodifiable Map<String, List<PresentationPropertyRef>> childBlack) {
		var session = new PresentationServer.Session(91, schemas, manifest);
		session.recipient = recipient;
		session.ready = true;
		session.view = 1;
		session.childBlack = childBlack;
		return session;
	}

	private static void registerLateAdapter() {
		try {
			Field field = PresentationServer.class.getDeclaredField("registry");
			field.setAccessible(true);
			((PresentationRegistry) field.get(null)).register(new LateAdapter());
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	@NotNullByDefault
	private static final class LateAdapter implements PresentationAdapter {
		@Override public String adapterId() { return LATE_ADAPTER; }
		@Override public String modId() { return "test"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 5; }
		@Override public List<PresentationField> fields() {
			return List.of(new PresentationField(LATE_FIELD, PresentationField.Kind.TEXT, true, 0, "late"));
		}
		@Override public PresentationSection collect(PresentationAdapter.DetachedTarget target, Set<String> demand,
			PresentationAdapter.CaptureBudget budget) {
			return PresentationSection.empty(LATE_ADAPTER, 1);
		}
	}

	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public DedicatedPlayerList getPlayerList() { return java.util.Objects.requireNonNull(players); }
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
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
		@Override public UUID getUUID() { return java.util.Objects.requireNonNull(id); }
		@Override public GameProfile getGameProfile() { return new GameProfile(getUUID(), "test"); }
		@Override public boolean hasPermissions(int required) { return permission >= required; }
	}
}
