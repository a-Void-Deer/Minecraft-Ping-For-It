package net.minecraftforge.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.network.CustomPayloadEvent;
import org.jetbrains.annotations.NotNullByDefault;

/** Captures the real channel listener SPI without requiring ModLauncher's event-class transformation. */
@NotNullByDefault
public final class HeadlessEventChannel extends EventNetworkChannel {

	private final List<Consumer<CustomPayloadEvent>> listeners = new ArrayList<>();

	private HeadlessEventChannel(NetworkInstance instance) { super(instance); }

	public static HeadlessEventChannel create(ResourceLocation id) {
		return ChannelBuilder.named(id).optional().channel(HeadlessEventChannel::new);
	}

	@Override
	public <T extends CustomPayloadEvent> EventNetworkChannel addListener(Consumer<T> listener) {
		@SuppressWarnings("unchecked")
		Consumer<CustomPayloadEvent> captured = (Consumer<CustomPayloadEvent>) listener;
		listeners.add(captured);
		return this;
	}

	public void receive(CustomPayloadEvent event) { listeners.forEach(listener -> listener.accept(event)); }
}
