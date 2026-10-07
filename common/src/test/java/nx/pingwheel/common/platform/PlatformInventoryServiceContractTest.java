package nx.pingwheel.common.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the loader seam of the platform inventory bridge. Each loader ships
 * exactly one service registration and reads through its native read-only
 * capability behind the loaded-chunk and pending-loot gates, with no mutating
 * capability call.
 */
class PlatformInventoryServiceContractTest {

	private static final String SERVICE =
		"META-INF/services/nx.pingwheel.common.platform.IPlatformInventoryService";
	private static final String INTERFACE =
		"common/src/main/java/nx/pingwheel/common/platform/IPlatformInventoryService.java";
	private static final String FABRIC =
		"fabric/src/main/java/nx/pingwheel/fabric/platform/PlatformInventoryServiceImpl.java";
	private static final String FORGE =
		"forge/src/main/java/nx/pingwheel/forge/platform/PlatformInventoryServiceImpl.java";
	private static final String NEOFORGE =
		"neoforge/src/main/java/nx/pingwheel/neoforge/platform/PlatformInventoryServiceImpl.java";

	@Test
	void commonInterfaceDeclaresTheServiceLoaderSeam() throws IOException {
		String source = read(INTERFACE);

		assertTrue(source.contains("ServiceLoader.load(IPlatformInventoryService.class)"));
		assertTrue(source.contains(
			"Optional<Access> find(ServerLevel level, BlockPos pos, Direction side)"));
		assertTrue(source.contains("default Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.empty(); }"));
	}

	@Test
	void eachLoaderRegistersItsImplementation() throws IOException {
		assertTrue(read("fabric/src/main/resources/" + SERVICE)
			.contains("nx.pingwheel.fabric.platform.PlatformInventoryServiceImpl"));
		assertTrue(read("forge/src/main/resources/" + SERVICE)
			.contains("nx.pingwheel.forge.platform.PlatformInventoryServiceImpl"));
		assertTrue(read("neoforge/src/main/resources/" + SERVICE)
			.contains("nx.pingwheel.neoforge.platform.PlatformInventoryServiceImpl"));
	}

	@Test
	void eachLoaderReadsItsNativeCapability() throws IOException {
		String fabric = read(FABRIC);
		String forge = read(FORGE);
		String neoforge = read(NEOFORGE);

		assertTrue(fabric.contains("ItemStorage.SIDED.find("));
		assertTrue(fabric.contains("SlottedStorage<ItemVariant>"));
		assertTrue(forge.contains("ForgeCapabilities.ITEM_HANDLER"));
		assertTrue(forge.contains(".resolve("));
		assertTrue(neoforge.contains("Capabilities.ItemHandler.BLOCK"));
	}

	@Test
	void bridgeReadsAreGatedAndNeverMutate() throws IOException {
		for (String loader : new String[] {FABRIC, FORGE, NEOFORGE}) {
			String source = read(loader);
			assertTrue(source.contains("isLoaded("), loader + " must gate on a loaded chunk");
			assertTrue(source.contains("getLootTable()"), loader + " must gate on pending loot");
			assertFalse(source.contains("insertItem("), loader + " must not mutate the handler");
			assertFalse(source.contains("extractItem("), loader + " must not mutate the handler");
			assertFalse(source.contains("insert("), loader + " must not mutate the storage");
			assertFalse(source.contains("extract("), loader + " must not mutate the storage");
		}
	}

	private static String read(String source) throws IOException {
		Path fromRoot = Path.of(source);
		Path fromCommonProject = Path.of("..", source);
		Path path = Files.exists(fromRoot) ? fromRoot : fromCommonProject;
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
