package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.Objects;

import com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlock;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Private NeoForge-only sampling bridge; must be loaded reflectively after the
 * Create mod check. Caller schedules on the server thread and only requests
 * authorized/subscribed sections. Never retain BE, level, handler or stacks.
 */
public final class CreatePresentationCollector {
	private CreatePresentationCollector() {}

	public static final CreateSamplingLimits DEFAULT_LIMITS =
		new CreateSamplingLimits(128, 128, 16, 16, 256, 2048);

	/**
	 * Detached values only. Nullable totals mean the guarded read accessor is
	 * absent; speed is signed RPM, cached stress and capacity are network totals
	 * in Create SU, and effective motion respects overstress/server tick freeze.
	 */
	public record Kinetic(boolean available, Float effectiveSpeed, Float theoreticalSpeed,
		Boolean moving, Boolean hasNetwork, Boolean overStressed, Float networkStress,
		Float networkCapacity) {
		public static Kinetic unavailable() {
			return new Kinetic(false, null, null, null, null, null, null, null);
		}
	}

	/** Fluid summary counts use NeoForge's 1.21.1 mB (1000 per bucket). */
	public record Sample(Kinetic kinetic, BoundedCreateSummary.Summary items,
		BoundedCreateSummary.Summary fluids, int workUsed, boolean sourcePresent) {
		public Sample {
			Objects.requireNonNull(kinetic, "kinetic");
			Objects.requireNonNull(items, "items");
			Objects.requireNonNull(fluids, "fluids");
			if (workUsed < 0) {
				throw new IllegalArgumentException("negative work used");
			}
		}
	}

	/**
	 * Capture only the requested sections. An absent or unloaded source is
	 * unavailable, not a verified-empty container. Never force-load a chunk.
	 */
	public static Sample capture(ServerLevel level, BlockPos pos, boolean kinetic,
		boolean items, boolean fluids, CreateSamplingLimits limits) {
		return capture(level, pos, kinetic, kinetic, kinetic, kinetic, kinetic,
			items, fluids, limits);
	}

	/** Individual dynamic fields are read only when their IDs were demanded. */
	public static Sample capture(ServerLevel level, BlockPos pos, boolean speed,
		boolean hasNetwork, boolean overStressed, boolean stress, boolean capacity,
		boolean items, boolean fluids, CreateSamplingLimits limits) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(limits, "limits");
		if (!level.getServer().isSameThread()) {
			throw new IllegalStateException("Create sampling must run on the server thread");
		}
		if (!CreatePresentationAvailability.available()) {
			return unavailableSample(0);
		}
		return captureLoaded(level, pos, speed, hasNetwork, overStressed,
			stress, capacity, items, fluids, limits);
	}

	private static Sample captureLoaded(ServerLevel level, BlockPos pos, boolean speed,
		boolean hasNetwork, boolean overStressed, boolean stress, boolean capacity,
		boolean items, boolean fluids, CreateSamplingLimits limits) {
		if (!speed && !hasNetwork && !overStressed && !stress && !capacity
			&& !items && !fluids) {
			return unavailableSample(0);
		}
		if (!level.hasChunkAt(pos)) {
			return unavailableSample(0);
		}
		WorkBudget work = new WorkBudget(limits.maxWork());
		if (!work.take(1)) {
			return unavailableSample(0);
		}
		try {
			BlockEntity source = level.getBlockEntity(pos);
			if (source == null || source.isRemoved()) {
				return unavailableSample(work.used());
			}
			Kinetic k = (speed || hasNetwork || overStressed || stress || capacity) && work.take(1)
				? readKinetic(source, speed, hasNetwork, overStressed, stress, capacity)
				: Kinetic.unavailable();
			BoundedCreateSummary.Summary i = items && source instanceof ItemVaultBlockEntity vault
				? readVault(level, vault, limits, work) : BoundedCreateSummary.Summary.unavailable();
			BoundedCreateSummary.Summary f = fluids && source instanceof FluidTankBlockEntity tank
				? readTank(level, tank, limits, work) : BoundedCreateSummary.Summary.unavailable();
			return new Sample(k, i, f, work.used(), true);
		} catch (RuntimeException | LinkageError failure) {
			return unavailableSample(work.used());
		}
	}

	private static Sample unavailableSample(int workUsed) {
		return new Sample(Kinetic.unavailable(), BoundedCreateSummary.Summary.unavailable(),
			BoundedCreateSummary.Summary.unavailable(), workUsed, false);
	}

	private static Kinetic readKinetic(BlockEntity source, boolean speed,
		boolean networkRequested, boolean overStressed, boolean stressRequested,
		boolean capacityRequested) {
		if (!(source instanceof KineticBlockEntity be)) {
			return Kinetic.unavailable();
		}
		try {
			Float effective = null;
			Float theoretical = null;
			Boolean moving = null;
			if (speed) {
				float e = be.getSpeed();
				float t = be.getTheoreticalSpeed();
				if (Float.isFinite(e) && Float.isFinite(t)) {
					effective = e;
					theoretical = t;
					moving = e != 0;
				}
			}
			Boolean network = null;
			if (networkRequested || stressRequested || capacityRequested) {
				network = be.hasNetwork();
			}
			Float stress = null;
			Float capacity = null;
			if (Boolean.TRUE.equals(network) && (stressRequested || capacityRequested)) {
				try {
					if (be instanceof CreateCachedNetworkView cached) {
						if (stressRequested) {
							try {
								float s = cached.pingforit$cachedNetworkStress();
								if (Float.isFinite(s) && s >= 0) {
									stress = s;
								}
							} catch (RuntimeException | LinkageError ignored) {
								// A broken stress accessor cannot hide capacity or speed.
							}
						}
						if (capacityRequested) {
							try {
								float c = cached.pingforit$cachedNetworkCapacity();
								if (Float.isFinite(c) && c >= 0) {
									capacity = c;
								}
							} catch (RuntimeException | LinkageError ignored) {
								// A broken capacity accessor cannot hide stress or speed.
							}
						}
					}
				} catch (RuntimeException | LinkageError ignored) {
					// The public getter route remains usable if the bridge drifts.
				}
			}
			return new Kinetic(true, effective, theoretical, moving, network,
				overStressed ? be.isOverStressed() : null, stress, capacity);
		} catch (RuntimeException | LinkageError failure) {
			return Kinetic.unavailable();
		}
	}

	private static BoundedCreateSummary.Summary readVault(ServerLevel level,
		ItemVaultBlockEntity source, CreateSamplingLimits limits, WorkBudget work) {
		try {
			if (!work.take(1)) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			BlockPos controllerPos = source.getController();
			if (controllerPos == null || !level.hasChunkAt(controllerPos)) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			BlockEntity candidate = level.getBlockEntity(controllerPos);
			if (!(candidate instanceof ItemVaultBlockEntity controller) || controller.isRemoved()
				|| !controller.isController() || !controllerPos.equals(controller.getBlockPos())) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			int length = controller.getHeight();
			int width = controller.getWidth();
			Direction.Axis axis = controller.getMainConnectionAxis();
			if (axis != Direction.Axis.X && axis != Direction.Axis.Z) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			int x = axis == Direction.Axis.Z ? width : length;
			int y = width;
			int z = axis == Direction.Axis.Z ? length : width;
			if (!contains(controllerPos, source.getBlockPos(), x, y, z)) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			if (!limits.permitsShape(x, y, z, work.remaining)) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			int shapeWork = verifyVault(level, controllerPos, x, y, z, limits, work.remaining);
			if (shapeWork < 0) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			work.take(shapeWork);
			if (work.remaining == 0) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			if (!work.take(1)) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			// Create's controller BLOCK capability exposes the combined vault;
			// getInventoryOfBlock() would silently return only one block.
			IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, controllerPos, null);
			if (handler == null) {
				return BoundedCreateSummary.Summary.unavailable();
			}
		if (work.remaining == 0) {
			return BoundedCreateSummary.Summary.incomplete();
		}
		BoundedCreateSummary summary = new BoundedCreateSummary(limits.maxSlots(),
				limits.maxRegistryIds(), work.remaining, limits.maxOutputBytes());
			int slots = handler.getSlots();
			if (slots < 0) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			boolean interrupted = false;
			for (int slot = 0; slot < slots; slot++) {
				if (!summary.beginEntry()) {
					break;
				}
				try {
					ItemStack stack = handler.getStackInSlot(slot);
					if (stack == null) {
						summary.markPartial();
					} else if (stack.getCount() < 0) {
						summary.markPartial();
					} else if (!stack.isEmpty()) {
						var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
						summary.add(id == null ? null : id.toString(), stack.getCount());
					}
				} catch (RuntimeException | LinkageError failure) {
					summary.markPartial();
					interrupted = true;
					break;
				}
			}
			BoundedCreateSummary.Summary result = summary.snapshot();
			work.take(result.scanned());
			return interrupted ? BoundedCreateSummary.Summary.incomplete() : result;
		} catch (RuntimeException | LinkageError failure) {
			return BoundedCreateSummary.Summary.unavailable();
		}
	}

	private static BoundedCreateSummary.Summary readTank(ServerLevel level,
		FluidTankBlockEntity source, CreateSamplingLimits limits, WorkBudget work) {
		try {
			if (!work.take(1)) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			// Creative tanks expose unbounded/generated output rather than a
			// finite stored amount. Do not represent them as ordinary fluid stock.
			if (source instanceof CreativeFluidTankBlockEntity) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			BlockPos controllerPos = source.getController();
			if (controllerPos == null || !level.hasChunkAt(controllerPos)) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			BlockEntity candidate = level.getBlockEntity(controllerPos);
			if (!(candidate instanceof FluidTankBlockEntity controller) || controller.isRemoved()
				|| controller instanceof CreativeFluidTankBlockEntity
				|| !controller.isController() || !controllerPos.equals(controller.getBlockPos())) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			int width = controller.getWidth();
			int height = controller.getHeight();
			if (!contains(controllerPos, source.getBlockPos(), width, height, width)) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			if (!limits.permitsShape(width, height, width, work.remaining)) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			int shapeWork = verifyTank(level, controllerPos, width, height, limits, work.remaining);
			if (shapeWork < 0) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			work.take(shapeWork);
			if (work.remaining == 0) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			if (controller.boiler.isActive()) {
				// The boiler capability is an input-only water handler and reports
				// EMPTY even when the controller's internal tank has content.
				// Do not assert an empty inventory on that observation.
				return BoundedCreateSummary.Summary.unavailable();
			}
			if (!work.take(1)) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			// Read only the ordinary controller BLOCK capability after ruling out
			// the boiler's input-only wrapper and incomplete shapes.
			IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, controllerPos, null);
			if (handler == null) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			if (work.remaining == 0) {
				return BoundedCreateSummary.Summary.incomplete();
			}
			BoundedCreateSummary summary = new BoundedCreateSummary(limits.maxTanks(),
				limits.maxRegistryIds(), work.remaining, limits.maxOutputBytes());
			int tanks = handler.getTanks();
			if (tanks < 0) {
				return BoundedCreateSummary.Summary.unavailable();
			}
			boolean interrupted = false;
			for (int tank = 0; tank < tanks; tank++) {
				if (!summary.beginEntry()) {
					break;
				}
				try {
					FluidStack stack = handler.getFluidInTank(tank);
					if (stack == null) {
						summary.markPartial();
					} else if (stack.getAmount() < 0) {
						summary.markPartial();
					} else if (!stack.isEmpty()) {
						var id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
						// NeoForge 1.21.1 fluid units: FluidType.BUCKET_VOLUME = 1000.
						summary.add(id == null ? null : id.toString(), stack.getAmount());
					}
				} catch (RuntimeException | LinkageError failure) {
					summary.markPartial();
					interrupted = true;
					break;
				}
			}
			BoundedCreateSummary.Summary result = summary.snapshot();
			work.take(result.scanned());
			return interrupted ? BoundedCreateSummary.Summary.incomplete() : result;
		} catch (RuntimeException | LinkageError failure) {
			return BoundedCreateSummary.Summary.unavailable();
		}
	}

	/** Negative means untrusted/incomplete structure; never ask capability then. */
	private static int verifyVault(ServerLevel level, BlockPos origin,
		int x, int y, int z, CreateSamplingLimits limits, int remainingWork) {
		if (!limits.permitsShape(x, y, z, remainingWork)) {
			return -1;
		}
		for (int dx = 0; dx < x; dx++) {
			for (int dy = 0; dy < y; dy++) {
				for (int dz = 0; dz < z; dz++) {
					BlockPos part = origin.offset(dx, dy, dz);
					if (!level.hasChunkAt(part)) {
						return -1;
					}
					BlockEntity be = level.getBlockEntity(part);
					if (!(be instanceof ItemVaultBlockEntity vault) || vault.isRemoved()
						|| !ItemVaultBlock.isVault(level.getBlockState(part))
						|| !origin.equals(vault.getController())) {
						return -1;
					}
				}
			}
		}
		return x * y * z;
	}

	private static int verifyTank(ServerLevel level, BlockPos origin,
		int width, int height, CreateSamplingLimits limits, int remainingWork) {
		if (!limits.permitsShape(width, height, width, remainingWork)) {
			return -1;
		}
		for (int dx = 0; dx < width; dx++) {
			for (int dy = 0; dy < height; dy++) {
				for (int dz = 0; dz < width; dz++) {
					BlockPos part = origin.offset(dx, dy, dz);
					if (!level.hasChunkAt(part)) {
						return -1;
					}
					BlockEntity be = level.getBlockEntity(part);
					if (!(be instanceof FluidTankBlockEntity tank) || tank.isRemoved()
						|| tank instanceof CreativeFluidTankBlockEntity
						|| !FluidTankBlock.isTank(level.getBlockState(part))
						|| !origin.equals(tank.getController())) {
						return -1;
					}
				}
			}
		}
		return width * height * width;
	}

	private static boolean contains(BlockPos origin, BlockPos source, int x, int y, int z) {
		long dx = (long) source.getX() - origin.getX();
		long dy = (long) source.getY() - origin.getY();
		long dz = (long) source.getZ() - origin.getZ();
		return dx >= 0 && dx < x && dy >= 0 && dy < y && dz >= 0 && dz < z;
	}

	private static final class WorkBudget {
		private final int initial;
		private int remaining;

		private WorkBudget(int remaining) {
			this.initial = remaining;
			this.remaining = remaining;
		}

		private int used() {
			return initial - remaining;
		}

		private boolean take(int cost) {
			if (cost < 0 || cost > remaining) {
				return false;
			}
			remaining -= cost;
			return true;
		}
	}
}
