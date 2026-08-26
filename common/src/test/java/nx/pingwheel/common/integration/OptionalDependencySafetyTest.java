package nx.pingwheel.common.integration;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import nx.pingwheel.common.integration.sable.client.SableClientProvider;
import nx.pingwheel.common.domain.Target;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the optional-dependency boundaries: stable hot/core classes must
 * never reference the optional mod packages (only the dedicated
 * integration/wrapper classes may), and with the loader flags off every
 * boundary method must short-circuit to its no-op/empty result without
 * linking the optional jars.
 */
class OptionalDependencySafetyTest {

	private static final List<String> STABLE_CLASS_NAMES = List.of(
		"nx/pingwheel/common/math/Raycast",
		"nx/pingwheel/common/integration/ModContext",
		"nx/pingwheel/common/integration/ExternalBlockServerProviders",
		"nx/pingwheel/common/integration/externalblock/ExternalBlockServerProvider",
		"nx/pingwheel/common/integration/externalblock/ExternalBlockServerProviderRegistry",
		"nx/pingwheel/common/integration/externalblock/ExternalBlockReferenceIndex",
		"nx/pingwheel/common/integration/sable/client/SableClientProvider",
		"nx/pingwheel/common/client/marker/MarkerView",
		"nx/pingwheel/common/name/ClientTargetNameResolver",
		"nx/pingwheel/common/client/outline/BlockOutlineRenderer",
		"nx/pingwheel/common/client/ClientPingRuntime",
		"nx/pingwheel/common/integration/TeamContextHandler",
		"nx/pingwheel/common/resolve/DefaultTargetResolver",
		"nx/pingwheel/common/resolve/TargetMatcherRegistry",
		"nx/pingwheel/common/resolve/RegistryBackedTargetMatcher",
		"nx/pingwheel/common/resolve/BuiltInTargetMatchers",
		"nx/pingwheel/common/resolve/TargetMatcher",
		"nx/pingwheel/common/domain/Target",
		"nx/pingwheel/common/domain/TargetMatchContext",
		"nx/pingwheel/common/interaction/TargetSnapshotFactory",
		"nx/pingwheel/common/marker/TargetKey",
		"nx/pingwheel/common/network/MarkerPacketCodec",
		"nx/pingwheel/common/marker/MinecraftAuthoritativeTargetValidator",
		"nx/pingwheel/common/marker/AuthoritativeTargetValidator");

	private static final List<String> FORBIDDEN_PACKAGE_FRAGMENTS = List.of(
		"com/seibel/distanthorizons",
		"dev/ryanhcode/sable",
		"de/maxhenkel/voicechat",
		"dev/ftb/mods/ftbteams");

	@Test
	void stableClassesContainNoOptionalPackageReferences() throws IOException {
		for (String className : STABLE_CLASS_NAMES) {
			String rawBytes = new String(readClassBytes(className), StandardCharsets.ISO_8859_1);

			for (String fragment : FORBIDDEN_PACKAGE_FRAGMENTS) {
				assertFalse(rawBytes.contains(fragment),
					className + " references optional package fragment " + fragment);
			}
		}
	}

	@Test
	void boundariesAreNoOpsWhenFlagsDisabled() {
		boolean distantHorizons = ModContext.HasDistantHorizons;
		boolean sable = ModContext.HasSable;
		boolean voiceChat = ModContext.HasVoiceChat;
		boolean ftbTeams = ModContext.HasFTBTeams;

		try {
			ModContext.HasDistantHorizons = false;
			ModContext.HasSable = false;
			ModContext.HasVoiceChat = false;
			ModContext.HasFTBTeams = false;

			// Each call must short-circuit before touching the optional jars.
			assertFalse(DistantHorizonsIntegration.traceDistantAsync(Vec3.ZERO, Vec3.ZERO, ignored -> {}));
			assertTrue(SableIntegration.projectOutOfSubLevel(null, Vec3.ZERO).isEmpty());
			assertTrue(SableClientProvider.capture(null, null, null, null).isEmpty());
			Target.ExternalBlockTarget corrupt = Target.ExternalBlockTarget.committed(
				"minecraft:overworld", "sable", UUID.randomUUID().toString(),
				"minecraft:stone", "not-a-sable-locator", false);
			assertTrue(SableClientProvider.resolvePosition(null, corrupt, 0.0F).isEmpty());
			assertTrue(VoiceChatWrapper.getGroupId(null).isEmpty());
			assertTrue(VoiceChatWrapper.getSelfGroupId().isEmpty());
			assertTrue(FTBTeamsWrapper.getTeamId(null).isEmpty());
			assertTrue(FTBTeamsWrapper.getSelfTeamId().isEmpty());
		} finally {
			ModContext.HasDistantHorizons = distantHorizons;
			ModContext.HasSable = sable;
			ModContext.HasVoiceChat = voiceChat;
			ModContext.HasFTBTeams = ftbTeams;
		}
	}

	private static byte[] readClassBytes(String className) throws IOException {
		String resource = "/" + className + ".class";

		try (InputStream stream = OptionalDependencySafetyTest.class.getResourceAsStream(resource)) {
			assertNotNull(stream, "missing compiled class " + resource);
			return stream.readAllBytes();
		}
	}
}
