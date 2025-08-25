package nx.pingwheel.forge.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.platform.IPlatformClientEventService;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.Objects;
import java.util.function.BiConsumer;

public class PlatformClientEventServiceImpl implements IPlatformClientEventService {

	@Override
	public void registerTickStartEvent(Runnable callback) {
		MinecraftForge.EVENT_BUS.register(new ClientTickEventEventHandler(callback));
	}
	private record ClientTickEventEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientTick(TickEvent.ClientTickEvent event) {
			if (event.phase.equals(TickEvent.Phase.START)) {
				callback.run();
				CommonClient.INSTANCE.onTickStart();
			}
		}
	}

	@Override
	public void registerJoinServerEvent(Runnable callback) {
		MinecraftForge.EVENT_BUS.register(new JoinServerEventHandler(callback));
	}
	private record JoinServerEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientConnectedToServer(ClientPlayerNetworkEvent.LoggedInEvent event) {
			callback.run();
		}
	}

	@Override
	public void registerLeaveServerEvent(Runnable callback) {
		MinecraftForge.EVENT_BUS.register(new LeaveServerEventHandler(callback));
	}
	private record LeaveServerEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientDisconnectedFromServer(ClientPlayerNetworkEvent.LoggedOutEvent event) {
			callback.run();
		}
	}

	@Override
	public void registerRenderWorldEvent(TriConsumer<Matrix4f, Matrix4f, Float> callback) {
		MinecraftForge.EVENT_BUS.register(new RenderWorldEventEventHandler(callback));
	}
	private record RenderWorldEventEventHandler(TriConsumer<Matrix4f, Matrix4f, Float> callback) {
		@SubscribeEvent
		public void onRenderWorld(RenderLevelStageEvent event) {
			if (event.getStage().equals(RenderLevelStageEvent.Stage.AFTER_WEATHER)) {
				callback.accept(event.getPoseStack().last().pose(), event.getProjectionMatrix(), event.getPartialTick());
			}
		}
	}

	@Override
	public void registerRenderGUIEvent(BiConsumer<PoseStack, Float> callback) {
		MinecraftForge.EVENT_BUS.register(new RenderGUIEventEventHandler(callback));
	}
	private record RenderGUIEventEventHandler(BiConsumer<PoseStack, Float> callback) {
		@SubscribeEvent
		public void onPreGuiRender(RenderGameOverlayEvent.Pre event) {
			if (Objects.equals(event.getType(), RenderGameOverlayEvent.ElementType.ALL)) {
				callback.accept(event.getMatrixStack(), event.getPartialTicks());
			}
		}
	}
}
