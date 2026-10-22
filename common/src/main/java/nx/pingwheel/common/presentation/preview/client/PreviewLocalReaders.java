package nx.pingwheel.common.presentation.preview.client;

import java.util.ArrayList;
import java.util.List;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;

/** Fixed optional endpoint registration, never reflection over world fields. */
public final class PreviewLocalReaders {
	private PreviewLocalReaders() {}
	public static List<PreviewFieldAccess> create() {
		List<PreviewFieldAccess> readers = new ArrayList<>();
		readers.add(new MinecraftPreviewFieldAccess());
		try {
			Class<?> gate = Class.forName("nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAvailability");
			if (Boolean.TRUE.equals(gate.getMethod("available").invoke(null))) {
				Object reader = Class.forName("nx.pingwheel.neoforge.integration.create.presentation.CreateClientPreviewFieldAccess")
					.getConstructor().newInstance();
				if (reader instanceof PreviewFieldAccess fields) readers.add(fields);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			// Optional integration absence/drift must not affect Basic.
		}
		return List.copyOf(readers);
	}
}
