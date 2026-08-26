package nx.pingwheel.common.integration.sable.client;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the client integration to Companion's level-aware containment API
 * without loading Sable classes in the test JVM.
 */
class SableClientCompanionAccessContractTest {

	private static final String CLASS_RESOURCE =
		"nx/pingwheel/common/integration/sable/client/SableClientCompanionAccess.class";

	@Test
	void usesLevelAwareContainmentInsteadOfDeprecatedClientLookup() throws IOException {
		List<MethodReference> references = methodReferences(readClassBytes());

		assertFalse(references.stream().anyMatch(reference -> reference.name().equals("getClientLevel")));
		assertFalse(references.stream().anyMatch(reference -> reference.name().equals("getContainingClient")));
		assertTrue(references.stream().anyMatch(reference ->
			reference.owner().equals("dev/ryanhcode/sable/companion/SableCompanion")
				&& reference.name().equals("getContaining")
				&& reference.descriptor().equals(
					"(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;)"
						+ "Ldev/ryanhcode/sable/companion/SubLevelAccess;")));
	}

	private static byte[] readClassBytes() throws IOException {
		ClassLoader loader = SableClientCompanionAccessContractTest.class.getClassLoader();
		try (InputStream stream = loader.getResourceAsStream(CLASS_RESOURCE)) {
			if (stream == null) {
				throw new IOException("missing compiled Sable client access class");
			}

			return stream.readAllBytes();
		}
	}

	private static List<MethodReference> methodReferences(byte[] classBytes) throws IOException {
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes))) {
			if (input.readInt() != 0xCAFEBABE) {
				throw new IOException("invalid class file");
			}

			input.skipBytes(4);
			int constantPoolCount = input.readUnsignedShort();
			ConstantPoolEntry[] constantPool = new ConstantPoolEntry[constantPoolCount];

			for (int index = 1; index < constantPoolCount; index++) {
				int tag = input.readUnsignedByte();

				switch (tag) {
					case 1 -> constantPool[index] = new Utf8Entry(input.readUTF());
					case 3, 4 -> input.skipBytes(4);
					case 5, 6 -> {
						input.skipBytes(8);
						index++;
					}
					case 7, 8, 16, 19, 20 ->
						constantPool[index] = new SingleIndexEntry(tag, input.readUnsignedShort());
					case 9, 10, 11, 12, 17, 18 ->
						constantPool[index] = new DoubleIndexEntry(
							tag, input.readUnsignedShort(), input.readUnsignedShort());
					case 15 -> {
						input.skipBytes(1);
						input.skipBytes(2);
					}
					default -> throw new IOException("unknown constant-pool tag: " + tag);
				}
			}

			List<MethodReference> references = new ArrayList<>();

			for (int index = 1; index < constantPool.length; index++) {
				ConstantPoolEntry entry = constantPool[index];

				if (!(entry instanceof DoubleIndexEntry methodEntry)
					|| (methodEntry.tag() != 10 && methodEntry.tag() != 11)) {
					continue;
				}

				SingleIndexEntry ownerEntry = require(constantPool[methodEntry.firstIndex()], SingleIndexEntry.class);
				DoubleIndexEntry nameAndType = require(
					constantPool[methodEntry.secondIndex()], DoubleIndexEntry.class);
				String owner = utf8(constantPool[ownerEntry.index()]);
				String name = utf8(constantPool[nameAndType.firstIndex()]);
				String descriptor = utf8(constantPool[nameAndType.secondIndex()]);

				references.add(new MethodReference(owner, name, descriptor));
			}

			return references;
		}
	}

	private static String utf8(ConstantPoolEntry entry) throws IOException {
		if (entry instanceof Utf8Entry utf8) {
			return utf8.value();
		}

		throw new IOException("expected UTF-8 constant-pool entry");
	}

	private static <T extends ConstantPoolEntry> T require(
		ConstantPoolEntry entry, Class<T> expectedType
	) throws IOException {
		if (expectedType.isInstance(entry)) {
			return expectedType.cast(entry);
		}

		throw new IOException("invalid constant-pool reference");
	}

	private interface ConstantPoolEntry {
		int tag();
	}

	private record Utf8Entry(String value) implements ConstantPoolEntry {
		@Override
		public int tag() {
			return 1;
		}
	}

	private record SingleIndexEntry(int tag, int index) implements ConstantPoolEntry {
	}

	private record DoubleIndexEntry(int tag, int firstIndex, int secondIndex) implements ConstantPoolEntry {
	}

	private record MethodReference(String owner, String name, String descriptor) {
	}
}
