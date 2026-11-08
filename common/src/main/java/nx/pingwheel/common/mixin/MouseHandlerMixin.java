package nx.pingwheel.common.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import nx.pingwheel.common.client.MouseGrabAccess;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Re-grabs the cursor without the {@code Minecraft.setScreen(null)} side
 * effect of {@code MouseHandler.grabMouse()}.
 *
 * <p>See {@link MouseGrabAccess} for why the wheel must avoid that screen
 * transition. This implementation mirrors the state changes of the vanilla
 * grab (cursor mode, remembered position, first-move suppression) so the
 * camera does not jump, but deliberately leaves the screen alone and does not
 * call {@code KeyMapping.setAll()}: the wheel never opened a screen, so the
 * mapping states already reflect the physical keys.</p>
 *
 * <p>The private fields are reached through {@link Accessor} methods rather
 * than {@code @Shadow} fields so the annotation processor emits the
 * production-name entries into the Forge refmap.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin implements MouseGrabAccess {

	@Accessor("mouseGrabbed")
	protected abstract boolean pingforit$getMouseGrabbed();

	@Accessor("mouseGrabbed")
	protected abstract void pingforit$setMouseGrabbed(boolean grabbed);

	@Accessor("xpos")
	protected abstract void pingforit$setX(double xpos);

	@Accessor("ypos")
	protected abstract void pingforit$setY(double ypos);

	@Accessor("ignoreFirstMove")
	protected abstract void pingforit$setIgnoreFirstMove(boolean ignoreFirstMove);

	@Override
	public void pingforit$grabWithoutScreenChange() {
		Minecraft minecraft = Minecraft.getInstance();

		if (!minecraft.isWindowActive() || this.pingforit$getMouseGrabbed()) {
			return;
		}

		double centerX = minecraft.getWindow().getScreenWidth() / 2.0D;
		double centerY = minecraft.getWindow().getScreenHeight() / 2.0D;

		this.pingforit$setMouseGrabbed(true);
		this.pingforit$setX(centerX);
		this.pingforit$setY(centerY);
		InputConstants.grabOrReleaseMouse(
			minecraft.getWindow().getWindow(),
			GLFW.GLFW_CURSOR_DISABLED,
			centerX,
			centerY);
		this.pingforit$setIgnoreFirstMove(true);
	}
}
