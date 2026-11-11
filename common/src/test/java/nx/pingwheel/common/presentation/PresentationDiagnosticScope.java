package nx.pingwheel.common.presentation;

import nx.pingwheel.common.diagnostics.DiagnosticBackend;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Scoped backend/profile seam for domain tests, with owned explicit channel state restored on close. */
@NotNullByDefault
public final class PresentationDiagnosticScope implements AutoCloseable {
	private final DiagnosticBackend previous;
	private final EnumMap<DiagnosticSide, LoggingProfile> profiles = new EnumMap<>(DiagnosticSide.class);
	private final List<SavedField> fields = new ArrayList<>();
	private final List<SavedChannels> channelArrays = new ArrayList<>();

	private PresentationDiagnosticScope() throws ReflectiveOperationException {
		previous = swap(new DiagnosticBackend() {
			@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return LogManager.getLogger("pingforit").isEnabled(severity); }
			@Override public void log(DiagnosticSide side, Level severity, String message) { LogManager.getLogger("pingforit").log(severity, message); }
		});
		try {
			for (DiagnosticSide side : DiagnosticSide.values()) {
				profiles.put(side, Diagnostics.profile(side));
				Diagnostics.setProfile(side, LoggingProfile.DEBUG);
			}
			for (String owner : OWNERS) resetChannels(Class.forName("nx.pingwheel.common." + owner, false, PresentationDiagnosticScope.class.getClassLoader()));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
			close();
			throw failure;
		}
	}

	public static PresentationDiagnosticScope install() {
		try { return new PresentationDiagnosticScope(); }
		catch (ReflectiveOperationException failure) { throw new AssertionError("Diagnostic test seam unavailable", failure); }
	}

	private static DiagnosticBackend swap(DiagnosticBackend backend) throws ReflectiveOperationException {
		Method method = Diagnostics.class.getDeclaredMethod("swapBackendForTesting", DiagnosticBackend.class);
		method.setAccessible(true);
		return (DiagnosticBackend) method.invoke(null, backend);
	}

	private void resetChannels(Class<?> owner) throws ReflectiveOperationException {
		for (Field field : owner.getDeclaredFields()) {
			if (field.getType() == AtomicReferenceArray.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				field.setAccessible(true);
				resetChannelArray((AtomicReferenceArray<?>) field.get(null));
				continue;
			}
			if (field.getType() != DiagnosticChannel.class) continue;
			field.setAccessible(true);
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) resetChannel((DiagnosticChannel) field.get(null));
			else if (owner.isEnum()) for (Object constant : owner.getEnumConstants()) resetChannel((DiagnosticChannel) field.get(constant));
		}
		for (Class<?> nested : owner.getDeclaredClasses()) resetChannels(nested);
	}

	@SuppressWarnings("unchecked")
	private void resetChannelArray(AtomicReferenceArray<?> array) {
		var values = new ArrayList<@Nullable DiagnosticChannel>(array.length());
		for (int index = 0; index < array.length(); index++) {
			@Nullable Object value = array.get(index);
			if (value != null && !(value instanceof DiagnosticChannel)) return;
			values.add((@Nullable DiagnosticChannel) value);
		}
		var channels = (AtomicReferenceArray<@Nullable DiagnosticChannel>) array;
		channelArrays.add(new SavedChannels(channels, Collections.unmodifiableList(values)));
		for (int index = 0; index < channels.length(); index++) channels.set(index, null);
	}

	private void resetChannel(DiagnosticChannel channel) throws ReflectiveOperationException {
		for (String side : List.of("client", "server", "bootstrap")) {
			Field stateField = DiagnosticChannel.class.getDeclaredField(side); stateField.setAccessible(true);
			Object state = stateField.get(channel);
			for (Field field : state.getClass().getDeclaredFields()) {
				field.setAccessible(true); fields.add(new SavedField(state, field, field.get(state)));
				if (field.getType() == boolean.class) field.setBoolean(state, false);
				else field.setLong(state, 0L);
			}
		}
	}

	@Override public void close() {
		try {
			for (SavedChannels saved : channelArrays) {
				for (int index = 0; index < saved.values.size(); index++) saved.channels.set(index, saved.values.get(index));
			}
			for (SavedField saved : fields) saved.field.set(saved.owner, saved.value);
			swap(previous);
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Diagnostic test seam restore failed", failure); }
		finally { profiles.forEach(Diagnostics::setProfile); }
	}

	@NotNullByDefault
	private record SavedField(Object owner, Field field, @Nullable Object value) {}
	@NotNullByDefault
	private record SavedChannels(AtomicReferenceArray<@Nullable DiagnosticChannel> channels,
		@Unmodifiable List<@Nullable DiagnosticChannel> values) {}
	private static final @Unmodifiable List<String> OWNERS = List.of(
		"network.PacketHandler", "network.PresentationPreviewS2CPacket",
		"presentation.PresentationAuthorization", "presentation.PresentationCodec", "presentation.PresentationSettings",
		"presentation.client.ClientPresentation", "presentation.minecraft.PresentationServer",
		"presentation.minecraft.ServerPropertyAdmission", "presentation.minecraft.BudgetDelayMonitoring",
		"presentation.minecraft.MinecraftBlockReadSources", "presentation.minecraft.MinecraftPresentationPreview",
		"presentation.preview.ClientPresentationPreview", "presentation.preview.PresentationPreviewServer",
		"presentation.preview.client.MinecraftPreviewFieldAccess", "presentation.preview.client.MinecraftPreviewReadContext",
		"presentation.preview.client.PreviewLocalReaders", "presentation.inventory.InventoryBackend",
		"presentation.inventory.InventoryCaptureDiagnostics", "presentation.inventory.InventoryDiagnostics", "presentation.inventory.InventoryRuntime",
		"presentation.inventory.InventorySyncPublisher", "presentation.inventory.minecraft.InventoryItemDiagnostics",
		"presentation.inventory.minecraft.InventoryFluidSources",
		"presentation.inventory.minecraft.InventorySnapshotDiagnostics", "presentation.inventory.minecraft.InventoryMinecraftSources",
		"platform.IPlatformInventoryService", "marker.MarkerCreationService", "marker.MinecraftAuthoritativeTargetValidator",
		"marker.MinecraftTargetGroupingResolver", "name.ClientTargetNameResolver", "name.MinecraftTargetNameResolver",
		"name.ClientTargetNameDecoder", "chat.PingChatBuilder", "chat.ContentChatTemplate",
		"chat.ContentChatComposer", "chat.PendingContentChatController", "client.ClientPingActionDispatcher",
		"client.ClientPingRuntime", "client.spatial.PreciseCaptureRefresh", "math.Raycast",
		"render.SpatialFluidIconRenderer");
}
