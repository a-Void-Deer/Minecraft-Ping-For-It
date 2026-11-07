package nx.pingwheel.neoforge.integration.create;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import nx.pingwheel.common.client.outline.EntityOutlineSourceRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Directly verifies optional adapter cleanup without depending on another test class's order. */
@NotNullByDefault
class CreateBoundaryIsolationTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void closeCreateAdaptersRestoresGlobalEntityOutlineAndRaycastState() throws Exception {
		try (var mods = new Mods("6.0.10")) {
			var loader = new VaultOptionalHarness();
			var client = loader.loadClass("nx.pingwheel.neoforge.NeoClient");
			closeCreateAdapters(client);
			List<String> outlineBefore = outlineIds();
			String raycastBefore = CreateContraptionRaycastAdapter.registrationState();
			assertFalse(outlineBefore.contains(CreateEntityOutlineAdapter.SOURCE_ID), "outline registration must start clean");
			assertEquals("not-registered", raycastBefore, "raycast registration must start clean");

			var entityRegister = client.getDeclaredMethod("registerOptionalAdapter", String.class, String.class, boolean.class);
			entityRegister.setAccessible(true);
			entityRegister.invoke(null, CreateEntityOutlineAdapter.class.getName(), "create-entity", true);
			var raycastRegister = client.getDeclaredMethod("registerOptionalContraptionRaycastAdapter");
			raycastRegister.setAccessible(true);
			raycastRegister.invoke(null);

			assertTrue(outlineIds().contains(CreateEntityOutlineAdapter.SOURCE_ID));
			assertEquals("registered", CreateContraptionRaycastAdapter.registrationState());

			closeCreateAdapters(client);

			assertEquals(outlineBefore, outlineIds());
			assertEquals(raycastBefore, CreateContraptionRaycastAdapter.registrationState());
		}
	}

	private static List<String> outlineIds() {
		return EntityOutlineSourceRegistry.INSTANCE.snapshot().stream().map(source -> source.id()).toList();
	}
}
