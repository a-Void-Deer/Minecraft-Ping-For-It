package nx.pingwheel.common.integration.sable.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The deliberately tiny reflective part of the Sable client adapter.
 * Companion 1.6.0 exposes poses but not UUID-to-container lookup, the owning
 * client level, or the actual sub-level BlockState, so only those accessors are
 * reflected here. No invocation detail is logged.
 */
final class SableClientInternalAccess {

	private static final String CLIENT_CONTAINER_CLASS =
		"dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer";
	private static final String SUB_LEVEL_CLASS = "dev.ryanhcode.sable.sublevel.SubLevel";

	private final Method getContainer;
	private final Method getSubLevel;
	private final Method getLevel;
	private final Method getBlockState;

	private SableClientInternalAccess(
		Method getContainer, Method getSubLevel, Method getLevel, Method getBlockState
	) {
		this.getContainer = getContainer;
		this.getSubLevel = getSubLevel;
		this.getLevel = getLevel;
		this.getBlockState = getBlockState;
	}

	static SableClientInternalAccess create() throws ReflectiveOperationException {
		ClassLoader loader = SableClientInternalAccess.class.getClassLoader();
		Class<?> containerClass = Class.forName(CLIENT_CONTAINER_CLASS, false, loader);
		Class<?> subLevelClass = Class.forName(SUB_LEVEL_CLASS, false, loader);

		Method getContainer = containerClass.getMethod("getContainer", ClientLevel.class);
		Method getSubLevel = containerClass.getMethod("getSubLevel", UUID.class);
		Method getLevel = subLevelClass.getMethod("getLevel");
		Method getBlockState = net.minecraft.world.level.Level.class.getMethod("getBlockState", BlockPos.class);

		if (!Modifier.isStatic(getContainer.getModifiers())) {
			throw new NoSuchMethodException("getContainer");
		}

		return new SableClientInternalAccess(getContainer, getSubLevel, getLevel, getBlockState);
	}

	Optional<ResolvedSubLevel> resolve(ClientLevel parent, UUID subLevelId, BlockPos localPos)
		throws ReflectiveOperationException {
		Object container = invoke(getContainer, null, parent);

		if (container == null) {
			return Optional.empty();
		}

		Object subLevel = invoke(getSubLevel, container, subLevelId);

		if (subLevel == null) {
			return Optional.empty();
		}

		Object levelObject = invoke(getLevel, subLevel);

		if (!(levelObject instanceof ClientLevel clientLevel) || clientLevel != parent
			|| !clientLevel.isLoaded(localPos)) {
			return Optional.empty();
		}

		Object stateObject = invoke(getBlockState, clientLevel, localPos);

		if (!(stateObject instanceof BlockState state)) {
			return Optional.empty();
		}

		return Optional.of(new ResolvedSubLevel(subLevel, clientLevel, state));
	}

	private static Object invoke(Method method, Object receiver, Object... arguments)
		throws ReflectiveOperationException {
		try {
			return method.invoke(receiver, arguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();

			if (cause instanceof LinkageError linkageError) {
				throw linkageError;
			}

			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}

			if (cause instanceof Error error) {
				throw error;
			}

			throw new ReflectiveOperationException(cause);
		}
	}

	record ResolvedSubLevel(Object subLevel, ClientLevel level, BlockState state) {
	}
}
