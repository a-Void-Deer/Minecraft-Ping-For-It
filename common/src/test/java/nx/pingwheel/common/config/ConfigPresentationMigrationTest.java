package nx.pingwheel.common.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPresentationMigrationTest {
	private static final String CURRENT_VERSION = "0.4.0-pfi-beta1";

	private static JsonObject readRoot(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	@Test
	void legacyFlatServerPolicyIsCopiedToEveryTargetTypeAndFlatKeysAreDropped(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{"
				+ "\"white\":[\"minecraft:basic\"],\"black\":[\"create:*\"],\"whitelistOnly\":true,\"scanBudget\":4},"
				+ "\"unknown\":{\"source\":\"legacy\"}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		PresentationSettings settings = handler.getConfig().getPresentation();
		for (String type : PresentationSettings.TARGET_TYPE_IDS) {
			assertEquals(List.of("minecraft:basic"), settings.policyFor(type).white());
			assertEquals(List.of("create:*"), settings.policyFor(type).black());
			assertTrue(settings.policyFor(type).whitelistOnly());
		}

		JsonObject presentation = readRoot(configPath).getAsJsonObject("presentation");
		assertTrue(presentation.has("targetTypes"));
		assertFalse(presentation.has("white"));
		assertFalse(presentation.has("black"));
		assertFalse(presentation.has("whitelistOnly"));
		assertEquals("legacy", readRoot(configPath).getAsJsonObject("unknown").get("source").getAsString());
	}

	@Test
	void legacyFlatMigrationDoesNotAliasRulesBetweenTargetTypes(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{"
				+ "\"white\":[\"minecraft:basic\"],\"black\":[\"create:*\"],\"whitelistOnly\":true}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();
		PresentationSettings settings = handler.getConfig().getPresentation();

		settings.setRules("entity", new PresentationSettings.RuleSet(List.of("create:*"), List.of(), false));

		assertTrue(settings.policyFor("block").whitelistOnly());
		assertEquals(List.of("create:*"), settings.policyFor("block").black());
		assertEquals(List.of("create:*"), settings.policyFor("entity").white());

		var detached = settings.rulesFor("entity");
		detached.setWhite(List.of());
		assertEquals(List.of("create:*"), settings.policyFor("entity").white());
	}

	@Test
	void newTargetTypesMapWinsOverLegacyFlatKeysAndMissingTypesFailClosed(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{"
				+ "\"targetTypes\":{\"dropped_item\":{\"white\":[\"minecraft:item.id\"],\"black\":[],\"whitelistOnly\":true}},"
				+ "\"white\":[\"should:not\"],\"black\":[\"also:not\"],\"whitelistOnly\":false}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		PresentationSettings settings = handler.getConfig().getPresentation();
		assertEquals(List.of("minecraft:item.id"), settings.policyFor("dropped_item").white());
		assertTrue(settings.policyFor("dropped_item").allows("minecraft:item.id", false));
		assertFalse(settings.policyFor("dropped_item").allows("minecraft:target.name", true));
		assertTrue(settings.policyFor("block").whitelistOnly());

		JsonObject presentation = readRoot(configPath).getAsJsonObject("presentation");
		assertTrue(presentation.has("targetTypes"));
		assertFalse(presentation.has("white"));
		assertFalse(presentation.has("black"));
		assertFalse(presentation.has("whitelistOnly"));
	}

	@Test
	void partiallyConfiguredTargetTypesMaterializeFailClosedOthers(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{\"targetTypes\":{"
				+ "\"entity\":{\"white\":[],\"black\":[],\"whitelistOnly\":false}}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertTrue(handler.getConfig().getPresentation().policyFor("entity").allows("minecraft:basic", true));
		assertTrue(handler.getConfig().getPresentation().policyFor("block").whitelistOnly());
		assertEquals(5, readRoot(configPath).getAsJsonObject("presentation")
			.getAsJsonObject("targetTypes").size());
	}

	@Test
	void fullyConfiguredCurrentShapeIsNotRewrittenOnLoad(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		StringBuilder rules = new StringBuilder();
		for (String id : List.of("dropped_item", "entity", "entity_block", "block", "location")) {
			if (rules.length() > 0) rules.append(',');
			rules.append('"').append(id).append("\":{\"white\":[],\"black\":[],\"whitelistOnly\":false}");
		}
		String serialized = "{\"pingforit-version\":\"" + CURRENT_VERSION
			+ "\",\"presentation\":{\"targetTypes\":{" + rules + "}}}\n";
		Files.writeString(configPath, serialized, StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertArrayEquals(serialized.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(configPath));
		assertTrue(handler.getConfig().getPresentation().policyFor("block").allows("minecraft:basic", true));
	}

	@Test
	void mistypedNestedRulesDenyOnlyTheirOwnType(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3,\"presentation\":{\"targetTypes\":{"
				+ "\"entity\":[],"
				+ "\"location\":{\"white\":{\"not\":\"an array\"},\"black\":5,\"whitelistOnly\":\"yes\"},"
				+ "\"block\":{\"white\":[],\"black\":[],\"whitelistOnly\":false},"
				+ "\"dropped_item\":{\"white\":[7],\"black\":[],\"whitelistOnly\":false}"
				+ "}},\"unknown\":{\"keep\":true}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		var settings = handler.getConfig().getPresentation();
		assertFalse(settings.policyFor("entity").allows("minecraft:basic", true));
		assertFalse(settings.policyFor("location").allows("minecraft:basic", true));
		assertFalse(settings.policyFor("dropped_item").allows("minecraft:basic", true)); // non-string array element
		assertTrue(settings.policyFor("block").allows("minecraft:basic", true));
		assertTrue(settings.policyFor("entity_block").whitelistOnly()); // missing type entry fails closed
		assertEquals(3, handler.getConfig().getRateLimit());
		assertEquals(5, readRoot(configPath).getAsJsonObject("presentation")
			.getAsJsonObject("targetTypes").size());
		assertTrue(readRoot(configPath).getAsJsonObject("unknown").get("keep").getAsBoolean());
	}

	@Test
	void malformedLegacyFlatShapeFailsClosedForEveryTargetType(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{"
				+ "\"white\":{\"not\":\"an array\"},\"black\":[\"*:*\"],\"whitelistOnly\":true}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		var settings = handler.getConfig().getPresentation();
		for (String type : List.of("dropped_item", "entity", "entity_block", "block", "location")) {
			assertFalse(settings.policyFor(type).allows("minecraft:basic", true));
			assertEquals(List.of("*:*"), settings.policyFor(type).black());
		}
		JsonObject presentation = readRoot(configPath).getAsJsonObject("presentation");
		assertEquals(5, presentation.getAsJsonObject("targetTypes").size());
		assertFalse(presentation.has("white"));
	}

	@Test
	void malformedPermissionArrayDeniesEveryTargetTypeDurably(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3,\"presentation\":{"
				+ "\"permissionLevels\":[],"
				+ "\"targetTypes\":{\"block\":{\"white\":[\"minecraft:basic\"],\"black\":[],\"whitelistOnly\":false}}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertFalse(handler.getConfig().getPresentation().policyFor("block").allows("minecraft:basic", true));
		assertEquals(3, handler.getConfig().getRateLimit());
		JsonObject permission = readRoot(configPath).getAsJsonObject("presentation")
			.getAsJsonObject("permissionLevels");
		assertNotNull(permission);
		assertEquals(0, permission.size()); // the malformed structure is sanitized to a valid empty map

		ConfigHandler<ServerConfig> reloaded = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		for (String type : List.of("dropped_item", "entity", "entity_block", "block", "location")) {
			assertFalse(reloaded.getConfig().getPresentation().policyFor(type).allows("minecraft:basic", true));
		}
	}

	@Test
	void nonIntegerPermissionValueSanitizesToDurableGlobalDenial(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{"
				+ "\"permissionLevels\":{\"minecraft:basic\":\"2\",\"minecraft:target.name\":1},"
				+ "\"targetTypes\":{\"block\":{\"white\":[\"minecraft:basic\"],\"black\":[],\"whitelistOnly\":false}}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertFalse(handler.getConfig().getPresentation().policyFor("block").allows("minecraft:basic", true));
		JsonObject permission = readRoot(configPath).getAsJsonObject("presentation").getAsJsonObject("permissionLevels");
		assertEquals(1, permission.size());
		assertEquals(1, permission.get("minecraft:target.name").getAsInt());

		ConfigHandler<ServerConfig> reloaded = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		assertFalse(reloaded.getConfig().getPresentation().policyFor("block").allows("minecraft:basic", true));
	}

	@Test
	void malformedIntervalShapesDoNotResetTheConfig(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3,\"presentation\":{"
				+ "\"updateIntervals\":{\"create:kinetic\":\"fast\",\"create:other\":5},"
				+ "\"minUpdateIntervalTicks\":\"soon\",\"scanBudget\":true,"
				+ "\"targetTypes\":{\"block\":{\"white\":[\"minecraft:basic\"],\"black\":[],\"whitelistOnly\":false}}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(3, handler.getConfig().getRateLimit());
		assertTrue(handler.getConfig().getPresentation().policyFor("block").allows("minecraft:basic", true));
		assertEquals(5, handler.getConfig().getPresentation().getUpdateIntervals().get("create:other"));
		JsonObject presentation = readRoot(configPath).getAsJsonObject("presentation");
		JsonElement minInterval = presentation.get("minUpdateIntervalTicks");
		JsonElement scanBudget = presentation.get("scanBudget");
		assertNotNull(minInterval);
		assertNotNull(scanBudget);
		assertTrue(minInterval.isJsonPrimitive() && minInterval.getAsJsonPrimitive().isNumber());
		assertTrue(scanBudget.isJsonPrimitive() && scanBudget.getAsJsonPrimitive().isNumber());
		assertEquals(1, presentation.getAsJsonObject("updateIntervals").size());
	}

	@Test
	void olderVersionServerMigrationComposesWithThePresentationShapeCopy(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"0.2.0-pfi-beta1\",\"pingDuration\":23,\"presentation\":{"
				+ "\"white\":[\"minecraft:basic\"],\"whitelistOnly\":true}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		JsonObject persisted = readRoot(configPath);
		assertEquals(CURRENT_VERSION, persisted.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertEquals(23, persisted.get("syncDuration").getAsInt());
		assertFalse(persisted.has("pingDuration"));
		assertTrue(persisted.getAsJsonObject("presentation").has("targetTypes"));
		assertEquals(List.of("minecraft:basic"), handler.getConfig().getPresentation()
			.policyFor("entity").white());
	}

	@Test
	void obsoleteClientPresentationKeysAreRemovedAndUnknownDataIsPreserved(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"pingVolume\":37,"
				+ "\"presentationReceive\":{\"white\":[\"create:*\"]},"
				+ "\"presentationDisplay\":{\"whitelistOnly\":true},"
				+ "\"unknownFutureField\":{\"value\":true}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(37, handler.getConfig().getPingVolume());
		JsonObject persisted = readRoot(configPath);
		assertFalse(persisted.has("presentationReceive"));
		assertFalse(persisted.has("presentationDisplay"));
		assertEquals(37, persisted.get("pingVolume").getAsInt());
		assertTrue(persisted.getAsJsonObject("unknownFutureField").get("value").getAsBoolean());
	}

	@Test
	void futureVersionFileIsNeverRewritten(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"9.9.9-pfi-beta1\",\"presentation\":{"
			+ "\"white\":[\"should:not\"],\"black\":[],\"whitelistOnly\":false},\"future\":true}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(configPath, original);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertArrayEquals(original, Files.readAllBytes(configPath));
		assertTrue(handler.getConfig().getPresentation().policyFor("entity").allows("minecraft:basic", true));
		assertFalse(handler.getConfig().getPresentation().policyFor("entity").white().contains("should:not"));
	}

	@Test
	void malformedTargetTypesArrayBecomesExplicitDenyAllForEveryTargetType(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{"
				+ "\"targetTypes\":[],\"white\":[\"minecraft:basic\"],\"black\":[],\"whitelistOnly\":false},"
				+ "\"unknown\":{\"keep\":true}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		PresentationSettings settings = handler.getConfig().getPresentation();
		for (String type : List.of("dropped_item", "entity", "entity_block", "block", "location")) {
			assertTrue(settings.policyFor(type).whitelistOnly());
			assertEquals(List.of("*:*"), settings.policyFor(type).black());
			assertFalse(settings.policyFor(type).allows("minecraft:basic", true));
		}

		JsonObject presentation = readRoot(configPath).getAsJsonObject("presentation");
		assertEquals(5, presentation.getAsJsonObject("targetTypes").size());
		assertFalse(presentation.has("white"));
		assertFalse(presentation.has("black"));
		assertFalse(presentation.has("whitelistOnly"));
		assertTrue(readRoot(configPath).getAsJsonObject("unknown").get("keep").getAsBoolean());
	}

	@Test
	void olderVersionMalformedTargetTypesStillFailsClosed(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"0.2.0-pfi-beta1\",\"pingDuration\":23,\"presentation\":{"
				+ "\"targetTypes\":true,\"black\":[\"create:*\"],\"whitelistOnly\":true}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(23, handler.getConfig().getSyncDuration());
		assertFalse(handler.getConfig().getPresentation().policyFor("block").allows("create:gear", true));
		JsonObject persisted = readRoot(configPath);
		assertEquals(CURRENT_VERSION, persisted.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertTrue(persisted.getAsJsonObject("presentation").getAsJsonObject("targetTypes").size() == 5);
	}

	@Test
	void malformedPresentationObjectFailsClosedWithoutResettingTheConfig(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":[],\"rateLimit\":3}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(3, handler.getConfig().getRateLimit());
		for (String type : List.of("dropped_item", "entity", "entity_block", "block", "location")) {
			assertFalse(handler.getConfig().getPresentation().policyFor(type).allows("minecraft:basic", true));
		}
		assertTrue(readRoot(configPath).getAsJsonObject("presentation").has("targetTypes"));
		try (var files = Files.list(tempDir)) {
			assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".broken-")));
		}
	}

	private static PresentationPropertyRef childRef(String field, String path) {
		return new PresentationPropertyRef("create:presentation", field, List.of(path));
	}

	private static final PresentationPropertyRef EFFECTIVE_RPM = childRef("create:kinetic.speed", "effective_rpm");
	private static final PresentationPropertyRef THEORETICAL_RPM =
		childRef("create:kinetic.speed", "theoretical_rpm");

	static Stream<Arguments> malformedChildBlackMembers() {
		String reference = "{\"adapterId\":\"create:presentation\",\"fieldId\":\"create:kinetic.speed\","
			+ "\"recordPath\":[\"effective_rpm\"]}";
		StringBuilder overCapacity = new StringBuilder("[");
		for (int i = 0; i <= PresentationSettings.MAX_CHILD_BLACK_REFS; i++) {
			if (i > 0) overCapacity.append(',');
			overCapacity.append("{\"adapterId\":\"create:presentation\",\"fieldId\":\"create:kinetic.speed\",")
				.append("\"recordPath\":[\"k").append(i).append("\"]}");
		}
		overCapacity.append(']');
		return Stream.of(
			Arguments.of("primitive member", "5"),
			Arguments.of("null member", "null"),
			Arguments.of("string element", "[\"effective_rpm\"]"),
			Arguments.of("null element", "[null]"),
			Arguments.of("missing field id",
				"[{\"adapterId\":\"create:presentation\",\"recordPath\":[\"effective_rpm\"]}]"),
			Arguments.of("recordPath not an array",
				"[{\"adapterId\":\"create:presentation\",\"fieldId\":\"create:kinetic.speed\","
					+ "\"recordPath\":\"effective_rpm\"}]"),
			Arguments.of("empty record path",
				"[{\"adapterId\":\"create:presentation\",\"fieldId\":\"create:kinetic.speed\",\"recordPath\":[]}]"),
			Arguments.of("invalid adapter id",
				"[{\"adapterId\":\"Bad Id\",\"fieldId\":\"create:kinetic.speed\","
					+ "\"recordPath\":[\"effective_rpm\"]}]"),
			Arguments.of("deep record path",
				"[{\"adapterId\":\"create:presentation\",\"fieldId\":\"create:kinetic.speed\","
					+ "\"recordPath\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}]"),
			Arguments.of("duplicate reference", "[" + reference + "," + reference + "]"),
			Arguments.of("over capacity", overCapacity.toString()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("malformedChildBlackMembers")
	void malformedChildBlackMemberDeniesItsOwnTargetType(String description, String childBlack, @TempDir Path tempDir)
		throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3,\"presentation\":{\"targetTypes\":{"
				+ "\"block\":{\"white\":[\"minecraft:basic\"],\"black\":[],\"whitelistOnly\":false,"
				+ "\"childBlack\":" + childBlack + "},"
				+ "\"entity\":{\"white\":[\"minecraft:basic\"],\"black\":[],\"whitelistOnly\":false}}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		PresentationSettings settings = handler.getConfig().getPresentation();
		assertTrue(settings.policyFor("block").whitelistOnly(), description);
		assertFalse(settings.policyFor("block").allows("minecraft:basic", true), description);
		assertTrue(settings.policyFor("entity").allows("minecraft:basic", true), description);
		assertEquals(3, handler.getConfig().getRateLimit(), description);
	}

	@Test
	void malformedChildBlackDeniesOnlyItsOwnTargetTypeDurably(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3,\"presentation\":{\"targetTypes\":{"
				+ "\"block\":{\"white\":[\"minecraft:basic\"],\"black\":[],\"whitelistOnly\":false,"
				+ "\"childBlack\":[{\"adapterId\":\"create:presentation\",\"fieldId\":\"create:kinetic.speed\"}]},"
				+ "\"entity\":{\"white\":[\"minecraft:basic\"],\"black\":[],\"whitelistOnly\":false}}},"
				+ "\"unknown\":{\"keep\":true}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		PresentationSettings settings = handler.getConfig().getPresentation();
		assertTrue(settings.policyFor("block").whitelistOnly());
		assertFalse(settings.policyFor("block").allows("minecraft:basic", true));
		assertTrue(settings.policyFor("entity").allows("minecraft:basic", true));
		assertEquals(3, handler.getConfig().getRateLimit());
		assertTrue(readRoot(configPath).getAsJsonObject("unknown").get("keep").getAsBoolean());

		// The normalized deny-all state is durable across a reload.
		ConfigHandler<ServerConfig> reloaded = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		assertFalse(reloaded.getConfig().getPresentation().policyFor("block").allows("minecraft:basic", true));
		assertTrue(reloaded.getConfig().getPresentation().policyFor("entity").allows("minecraft:basic", true));
	}

	@Test
	void missingChildBlackMemberGetsTheSemanticDefaultsWithoutRewritingTheFile(@TempDir Path tempDir)
		throws IOException {
		Path configPath = tempDir.resolve("server.json");
		StringBuilder rules = new StringBuilder();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			if (rules.length() > 0) rules.append(',');
			rules.append('"').append(id).append("\":{\"white\":[],\"black\":[],\"whitelistOnly\":false}");
		}
		String serialized = "{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3,"
			+ "\"presentation\":{\"targetTypes\":{" + rules + "}}}\n";
		Files.writeString(configPath, serialized, StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		PresentationSettings settings = handler.getConfig().getPresentation();
		for (String type : PresentationSettings.TARGET_TYPE_IDS) {
			assertEquals(2, settings.rulesFor(type).getChildBlack().size(), type);
			assertTrue(settings.rulesFor(type).childDenied(EFFECTIVE_RPM), type);
			assertTrue(settings.rulesFor(type).childDenied(THEORETICAL_RPM), type);
			assertFalse(settings.policyFor(type).propertyAllowed(EFFECTIVE_RPM, true), type);
			assertTrue(settings.policyFor(type).propertyAllowed(
				PresentationPropertyRef.root("create:presentation", "create:kinetic.speed"), true), type);
		}
		// An absent additive member needs no migration, normalization write, or reset.
		assertArrayEquals(serialized.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(configPath));
		assertEquals(3, handler.getConfig().getRateLimit());
	}

	@Test
	void explicitEmptyChildBlackOptsOutAndSurvivesReload(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{\"targetTypes\":{"
				+ "\"entity\":{\"white\":[],\"black\":[],\"whitelistOnly\":false,\"childBlack\":[]},"
				+ "\"block\":{\"white\":[],\"black\":[],\"whitelistOnly\":false}}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();
		assertTrue(handler.getConfig().getPresentation().rulesFor("entity").getChildBlack().isEmpty());
		assertTrue(handler.getConfig().getPresentation().policyFor("entity").propertyAllowed(EFFECTIVE_RPM, true));
		assertFalse(handler.getConfig().getPresentation().policyFor("block").propertyAllowed(EFFECTIVE_RPM, true));

		ConfigHandler<ServerConfig> reloaded = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		assertTrue(reloaded.getConfig().getPresentation().rulesFor("entity").getChildBlack().isEmpty());
		assertTrue(reloaded.getConfig().getPresentation().policyFor("entity").propertyAllowed(EFFECTIVE_RPM, true));
		assertFalse(reloaded.getConfig().getPresentation().policyFor("block").propertyAllowed(EFFECTIVE_RPM, true));
	}

	@Test
	void customChildBlackReferenceSurvivesReloadAndNormalization(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		String custom = "{\"adapterId\":\"create:presentation\",\"fieldId\":\"create:kinetic.stress\","
			+ "\"recordPath\":[\"effective\"]}";
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{\"targetTypes\":{"
				+ "\"block\":{\"white\":[],\"black\":[],\"whitelistOnly\":false,\"childBlack\":[" + custom + "]}}}}\n",
			StandardCharsets.UTF_8);

		var customRef = new PresentationPropertyRef("create:presentation", "create:kinetic.stress",
			List.of("effective"));
		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();
		assertTrue(handler.getConfig().getPresentation().policyFor("block").childDenied(customRef));

		ConfigHandler<ServerConfig> reloaded = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		assertTrue(reloaded.getConfig().getPresentation().policyFor("block").childDenied(customRef));
		// The explicit list replaces the semantic defaults instead of stacking on them.
		assertFalse(reloaded.getConfig().getPresentation().policyFor("block").childDenied(EFFECTIVE_RPM));
	}

	@Test
	void resetToDefaultsRestoresTheSemanticChildDefaults(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"presentation\":{\"targetTypes\":{"
				+ "\"entity\":{\"white\":[],\"black\":[],\"whitelistOnly\":false,\"childBlack\":[]}}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();
		assertTrue(handler.getConfig().getPresentation().policyFor("entity").propertyAllowed(EFFECTIVE_RPM, true));

		handler.resetToDefaults();

		for (String type : PresentationSettings.TARGET_TYPE_IDS) {
			assertFalse(handler.getConfig().getPresentation().policyFor(type).propertyAllowed(EFFECTIVE_RPM, true), type);
			assertTrue(handler.getConfig().getPresentation().policyFor(type).propertyAllowed(
				PresentationPropertyRef.root("create:presentation", "create:kinetic.speed"), true), type);
		}
	}
}
