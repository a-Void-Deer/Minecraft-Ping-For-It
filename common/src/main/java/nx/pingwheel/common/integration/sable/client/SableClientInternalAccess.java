package nx.pingwheel.common.integration.sable.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The deliberately tiny reflective part of the Sable client adapter.
 * Companion 1.6.0 exposes poses but not UUID-to-container lookup, raw loaded-list
 * iteration or local plot bounds/containment. Those read-only accessors are
 * reflected here; supplemental methods are resolved separately from ordinary capture.
 */
final class SableClientInternalAccess {

	private static final String CLIENT_CONTAINER_CLASS =
		"dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer";
	private static final String SUB_LEVEL_CLASS = "dev.ryanhcode.sable.sublevel.SubLevel";

	private final Method getContainer;
	private final Method getSubLevel;
	private final Method getLevel;
	private final Method getBlockState;
	private volatile SupplementalMethods supplementalMethods;
	private volatile Method contentIsRemoved;

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

	/** Resolve additive APIs lazily so drift here cannot disable established ordinary capture. */
	private SupplementalMethods supplementalMethods() throws ReflectiveOperationException {
		SupplementalMethods methods = supplementalMethods;
		if (methods != null) return methods;
		ClassLoader loader = SableClientInternalAccess.class.getClassLoader();
		Class<?> containerClass = Class.forName(CLIENT_CONTAINER_CLASS, false, loader);
		Class<?> subLevelClass = Class.forName(SUB_LEVEL_CLASS, false, loader);
		Method getPlot = subLevelClass.getMethod("getPlot");
		Class<?> plotClass = getPlot.getReturnType();
		methods = new SupplementalMethods(containerClass.getMethod("getAllSubLevels"), subLevelClass.getMethod("isRemoved"),
			getPlot, plotClass.getMethod("contains", double.class, double.class), plotClass.getMethod("getBoundingBox"));
		supplementalMethods = methods;
		return methods;
	}

	/** O(1) raw loaded list access, not Companion's filtered iterator with hidden scans in hasNext. */
	List<?> loadedSubLevels(ClientLevel parent) throws ReflectiveOperationException {
		return loadedSubLevels(parent, null);
	}

	List<?> loadedSubLevels(ClientLevel parent, nx.pingwheel.common.interaction.candidate.CandidateWorkBudget budget)
		throws ReflectiveOperationException {
		charge(budget);
		Object container = invoke(getContainer, null, parent);
		if (container == null) throw new IllegalStateException("Sable client container unavailable");
		charge(budget);
		Object values = invoke(supplementalMethods().getAllSubLevels(), container);
		if (!(values instanceof List<?> list) || !(list instanceof java.util.RandomAccess)) {
			throw new IllegalStateException("unsupported Sable loaded-sublevel list structure");
		}
		return list;
	}

	Object existingPlot(ClientLevel parent, Object subLevel,
		nx.pingwheel.common.interaction.candidate.CandidateWorkBudget budget) throws ReflectiveOperationException {
		var methods = supplementalMethods();
		charge(budget);
		if (Boolean.TRUE.equals(invoke(methods.isRemoved(), subLevel))) return null;
		charge(budget);
		if (invoke(getLevel, subLevel) != parent) throw new IllegalStateException("Sable sublevel level mismatch");
		charge(budget);
		Object plot = invoke(methods.getPlot(), subLevel);
		if (plot == null) throw new IllegalStateException("Sable local plot unavailable");
		return plot;
	}

	boolean contains(Object plot, BlockPos pos,
		nx.pingwheel.common.interaction.candidate.CandidateWorkBudget budget) throws ReflectiveOperationException {
		charge(budget);
		return Boolean.TRUE.equals(invoke(supplementalMethods().plotContains(), plot, (double) pos.getX(), (double) pos.getZ()));
	}

	dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds(Object plot,
		nx.pingwheel.common.interaction.candidate.CandidateWorkBudget budget) throws ReflectiveOperationException {
		charge(budget);
		Object bounds = invoke(supplementalMethods().plotBounds(), plot);
		if (bounds == null) return null;
		if (!(bounds instanceof dev.ryanhcode.sable.companion.math.BoundingBox3ic value)) {
			throw new IllegalStateException("unsupported Sable plot bounds");
		}
		return value;
	}

	private static void charge(nx.pingwheel.common.interaction.candidate.CandidateWorkBudget budget) {
		if (budget != null && !budget.callProvider()) throw new nx.pingwheel.common.interaction.candidate.ExactNativeShapeClip.Incomplete();
	}

	private record SupplementalMethods(Method getAllSubLevels, Method isRemoved, Method getPlot,
		Method plotContains, Method plotBounds) {}

	/** Content-only live identity gate. Additive discovery cannot change ordinary capture's API gate. */
	Object contentSubLevel(ClientLevel parent, UUID id) throws ReflectiveOperationException {
		Method removed = contentIsRemoved;
		if (removed == null) {
			Class<?> owner = Class.forName(SUB_LEVEL_CLASS, false, SableClientInternalAccess.class.getClassLoader());
			removed = owner.getMethod("isRemoved");
			if (removed.getReturnType() != boolean.class || Modifier.isStatic(removed.getModifiers())) {
				throw new NoSuchMethodException("isRemoved");
			}
			contentIsRemoved = removed;
		}
		Object container = invoke(getContainer, null, parent);
		Object subLevel = container == null ? null : invoke(getSubLevel, container, id);
		if (!(subLevel instanceof dev.ryanhcode.sable.companion.ClientSubLevelAccess access)
			|| !id.equals(access.getUniqueId()) || !Boolean.FALSE.equals(invoke(removed, subLevel))
			|| invoke(getLevel, subLevel) != parent) return null;
		return subLevel;
	}

	Resolution resolve(ClientLevel parent, UUID subLevelId, BlockPos localPos)
		throws ReflectiveOperationException {
		Object container = invoke(getContainer, null, parent);

		if (container == null) {
			return Resolution.unavailable("sublevel_container_unavailable");
		}

		Object subLevel = invoke(getSubLevel, container, subLevelId);

		if (subLevel == null) {
			return Resolution.unavailable("sublevel_unresolved_or_removed");
		}

		Object levelObject = invoke(getLevel, subLevel);

		if (!(levelObject instanceof ClientLevel clientLevel)) {
			return Resolution.unavailable("sublevel_unresolved_or_removed");
		}

		if (clientLevel != parent) {
			return Resolution.unavailable("sublevel_level_mismatch");
		}

		if (!clientLevel.isLoaded(localPos)) {
			return Resolution.unavailable("unloaded_or_missing_state");
		}

		Object stateObject = invoke(getBlockState, clientLevel, localPos);

		if (!(stateObject instanceof BlockState state)) {
			return Resolution.unavailable("unloaded_or_missing_state");
		}

		return Resolution.available(new ResolvedSubLevel(subLevel, clientLevel, state));
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

	record Resolution(Optional<ResolvedSubLevel> value, String failureReason) {
		Resolution {
			if (value.isPresent() && failureReason != null) {
				throw new IllegalArgumentException("available resolution cannot have a failure reason");
			}

			if (value.isEmpty() && failureReason == null) {
				throw new IllegalArgumentException("unavailable resolution needs a failure reason");
			}
		}

		static Resolution available(ResolvedSubLevel resolved) {
			return new Resolution(Optional.of(resolved), null);
		}

		static Resolution unavailable(String reason) {
			return new Resolution(Optional.empty(), reason);
		}
	}
}
