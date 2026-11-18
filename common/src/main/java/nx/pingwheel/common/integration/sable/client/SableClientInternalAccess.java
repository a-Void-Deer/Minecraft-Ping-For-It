package nx.pingwheel.common.integration.sable.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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
	private volatile @Nullable SupplementalMethods supplementalMethods;
	private volatile @Nullable Method contentIsRemoved;
	private volatile @Nullable PlotMemberMethods plotMemberMethods;

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
		Object container = invoke(getContainer, null, parent);
		if (container == null) throw new IllegalStateException("Sable client container unavailable");
		Object values = invoke(supplementalMethods().getAllSubLevels(), container);
		if (!(values instanceof List<?> list) || !(list instanceof java.util.RandomAccess)) {
			throw new IllegalStateException("unsupported Sable loaded-sublevel list structure");
		}
		return list;
	}

	@Nullable Object existingPlot(ClientLevel parent, Object subLevel) throws ReflectiveOperationException {
		var methods = supplementalMethods();
		if (Boolean.TRUE.equals(invoke(methods.isRemoved(), subLevel))) return null;
		if (invoke(getLevel, subLevel) != parent) throw new IllegalStateException("Sable sublevel level mismatch");
		Object plot = invoke(methods.getPlot(), subLevel);
		if (plot == null) throw new IllegalStateException("Sable local plot unavailable");
		return plot;
	}

	boolean contains(Object plot, BlockPos pos) throws ReflectiveOperationException {
		return Boolean.TRUE.equals(invoke(supplementalMethods().plotContains(), plot, (double) pos.getX(), (double) pos.getZ()));
	}

	@Nullable dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds(Object plot)
		throws ReflectiveOperationException {
		Object bounds = invoke(supplementalMethods().plotBounds(), plot);
		if (bounds == null) return null;
		if (!(bounds instanceof dev.ryanhcode.sable.companion.math.BoundingBox3ic value)) {
			throw new IllegalStateException("unsupported Sable plot bounds");
		}
		return value;
	}

	private record SupplementalMethods(Method getAllSubLevels, Method isRemoved, Method getPlot,
		Method plotContains, Method plotBounds) {}

	private Method removedMethod() throws ReflectiveOperationException {
		@Nullable Method removed = contentIsRemoved;
		if (removed == null) {
			Class<?> owner = Class.forName(SUB_LEVEL_CLASS, false, SableClientInternalAccess.class.getClassLoader());
			removed = owner.getMethod("isRemoved");
			if (removed.getReturnType() != boolean.class || Modifier.isStatic(removed.getModifiers())) {
				throw new NoSuchMethodException("isRemoved");
			}
			contentIsRemoved = removed;
		}
		return removed;
	}

	/** Live identity for Content and outline binding; independent of ordinary capture's API gate. */
	@Nullable Object contentSubLevel(ClientLevel parent, UUID id) throws ReflectiveOperationException {
		Method removed = removedMethod();
		@Nullable Object container = invoke(getContainer, null, parent);
		return activeSubLevel(parent, container, id, removed);
	}

	private @Nullable ClientSubLevelAccess activeSubLevel(ClientLevel parent, @Nullable Object container, UUID id,
		Method removed) throws ReflectiveOperationException {
		@Nullable Object subLevel = container == null ? null : invoke(getSubLevel, container, id);
		if (!(subLevel instanceof ClientSubLevelAccess access)
			|| !id.equals(access.getUniqueId()) || !Boolean.FALSE.equals(invoke(removed, subLevel))
			|| invoke(getLevel, subLevel) != parent) return null;
		return access;
	}

	/** Binds one active sublevel; does not depend on the picking or inventory APIs. */
	@Nullable BoundPlot bindPresentationPlot(ClientLevel parent, UUID id) throws ReflectiveOperationException {
		@Nullable Object container = invoke(getContainer, null, parent);
		Method removed = removedMethod();
		@Nullable ClientSubLevelAccess subLevel = activeSubLevel(parent, container, id, removed);
		if (container == null || subLevel == null) return null;
		@Nullable PlotMemberMethods methods = plotMemberMethods;
		if (methods == null) {
			Class<?> owner = Class.forName(SUB_LEVEL_CLASS, false, SableClientInternalAccess.class.getClassLoader());
			Method getPlot = owner.getMethod("getPlot");
			Method containerPlot = container.getClass().getMethod("getPlot", ChunkPos.class);
			Class<?> plotClass = getPlot.getReturnType();
			Method toLocal = plotClass.getMethod("toLocal", ChunkPos.class);
			Method getChunk = plotClass.getMethod("getChunk", ChunkPos.class);
			Method getBounds = plotClass.getMethod("getBoundingBox");
			if (Modifier.isStatic(getPlot.getModifiers()) || Modifier.isStatic(toLocal.getModifiers())
				|| Modifier.isStatic(getChunk.getModifiers()) || Modifier.isStatic(containerPlot.getModifiers())
				|| Modifier.isStatic(getBounds.getModifiers()) || toLocal.getReturnType() != ChunkPos.class
				|| !LevelChunk.class.isAssignableFrom(getChunk.getReturnType())
				|| !BoundingBox3ic.class.isAssignableFrom(getBounds.getReturnType())) {
				throw new NoSuchMethodException("Sable plot member lookup");
			}
			methods = new PlotMemberMethods(getPlot, containerPlot, toLocal, getChunk, getBounds);
			plotMemberMethods = methods;
		}
		@Nullable Object plot = invoke(methods.getPlot(), subLevel);
		return plot == null ? null : new BoundPlot(parent, container, subLevel, plot, removed, methods);
	}

	@NotNullByDefault
	private record PlotMemberMethods(Method getPlot, Method containerPlot, Method toLocal, Method getChunk, Method getBounds) {}

	@NotNullByDefault
	record BoundPlot(ClientLevel parent, Object container, ClientSubLevelAccess subLevel, Object plot,
		Method removed, PlotMemberMethods methods) {
		@Nullable LevelChunk loadedChunk(BlockPos pos) throws ReflectiveOperationException {
			if (parent.isOutsideBuildHeight(pos) || !Boolean.FALSE.equals(invoke(removed, subLevel))) return null;
			ChunkPos absolute = new ChunkPos(pos);
			if (invoke(methods.containerPlot(), container, absolute) != plot) return null;
			if (!(invoke(methods.getBounds(), plot) instanceof BoundingBox3ic bounds)
				|| pos.getX() < bounds.minX() || pos.getX() > bounds.maxX()
				|| pos.getY() < bounds.minY() || pos.getY() > bounds.maxY()
				|| pos.getZ() < bounds.minZ() || pos.getZ() > bounds.maxZ()) return null;
			// The plot table is indexed relatively, but LevelChunk's state and
			// resident-BE keys use the original absolute plot block coordinates.
			if (!(invoke(methods.toLocal(), plot, absolute) instanceof ChunkPos local)) return null;
			if (!(invoke(methods.getChunk(), plot, local) instanceof LevelChunk chunk)
				|| chunk.getLevel() != parent || !absolute.equals(chunk.getPos())) return null;
			return chunk;
		}
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

	private static @Nullable Object invoke(Method method, @Nullable Object receiver, Object... arguments)
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
