package nx.pingwheel.common.presentation.preview.client;

import java.util.ArrayList;
import java.util.List;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.jetbrains.annotations.Unmodifiable;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;

/** Fixed optional endpoint registration, never reflection over world fields. */
public final class PreviewLocalReaders {
	private PreviewLocalReaders() {}
	public static @Unmodifiable List<PreviewFieldAccess> create() {
		List<PreviewFieldAccess> readers = new ArrayList<>();
		readers.add(new MinecraftPreviewFieldAccess());
		try {
			Class<?> gate = Class.forName("nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAvailability");
			if (Boolean.TRUE.equals(gate.getMethod("available").invoke(null))) {
				Object reader = Class.forName("nx.pingwheel.neoforge.integration.create.presentation.CreateClientPreviewFieldAccess")
					.getConstructor().newInstance();
				if (reader instanceof PreviewFieldAccess fields) readers.add(fields);
			}
		} catch (ClassNotFoundException absent) {
			// Optional loader/reader classes may be absent.
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			Diagnostics.warn("presentation/optional-preview-registration", "create", () -> "client reader registration", unavailable);
			// Optional integration absence/drift must not affect Basic.
		}
		return List.copyOf(readers);
	}
}
