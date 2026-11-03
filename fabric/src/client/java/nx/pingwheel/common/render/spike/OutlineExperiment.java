package nx.pingwheel.common.render.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.MOD_ID;

/**
 * M7-prep E1/E2/E3 spike: a throw-away wireframe probe that answers the open
 * rendering questions before the real outline rebuild starts.
 *
 * <ol>
 *   <li><b>E1</b> - in 26.3, is a wireframe submitted through
 *       {@link SubmitNodeCollector#submitShapeOutline} occluded by terrain by
 *       default? Modes 1-4 expose the matrix: {@code linesTranslucent()} and
 *       {@code linesTranslucentNoDepthWrite()}, each with {@code afterTerrain}
 *       off and on.</li>
 *   <li><b>E2</b> - can a wireframe be made to draw through walls? Mode 5 is the
 *       custom no-depth-test pipeline attempt, which is not reachable in 26.3
 *       without an access widener (see the mode table below).</li>
 *   <li><b>E3</b> - is there a see-through route with public API only? Mode 6
 *       submits the same cube through {@link DrawableGizmoPrimitives} with
 *       {@code onTop = true}, i.e. the gizmo "always on top" pass.</li>
 * </ol>
 *
 * <p>The probe is opt-in through a file in the game directory and never runs
 * otherwise, so a normal install pays nothing:
 * <pre>
 *   &lt;gameDir&gt;/outline-experiment.txt   first line = mode 1..6
 * </pre>
 * The file is read exactly once during client initialisation (never per frame),
 * and deleting it plus restarting removes the probe entirely.
 *
 * <p>Everything below is defensive: a broken probe must never crash the client,
 * so registration and submission are wrapped, the first failure is reported
 * once as a WARN, and per-frame spam is avoided by a 5-second log budget.
 *
 * <p>This class is temporary M7-prep tooling: the real outline renderer must not
 * grow out of it.
 */
public final class OutlineExperiment {

	/** The opt-in switch file, resolved against the Fabric game directory. */
	private static final String SWITCH_FILE_NAME = "outline-experiment.txt";

	/** One INFO line per this many milliseconds at most (per-frame guard). */
	private static final long LOG_INTERVAL_MILLIS = 5_000L;

	/** Probe colour: opaque red (matches the M7-prep plan). */
	private static final int COLOR = 0xFFFF0000;

	/** Wire width in GUI/world units requested by the M7-prep plan. */
	private static final float LINE_WIDTH = 3.0f;

	/** The probed cube, one full block section (3x3x3). */
	private static final VoxelShape CUBE = Shapes.box(0.0, 0.0, 0.0, 3.0, 3.0, 3.0);

	/** The E3 gizmo mode: same cube, submitted through the on-top gizmo pass. */
	private static final int GIZMO_MODE = 6;

	/** Highest accepted mode value. */
	private static final int MAX_MODE = GIZMO_MODE;

	private static boolean registered;
	private static boolean failureLogged;
	private static boolean submissionDisabled;
	private static int mode;
	private static RenderType renderType;
	private static boolean afterTerrain;
	private static long submitted;
	private static long lastLogMillis;

	private OutlineExperiment() {}

	/**
	 * Registers the probe when {@code outline-experiment.txt} exists.
	 *
	 * <p>Called once from the Fabric client initialiser. Any failure (missing
	 * file is the normal case) leaves the mod untouched.
	 */
	public static void registerIfEnabled() {
		if (registered) {
			return;
		}

		try {
			Path switchFile = FabricLoader.getInstance().getGameDir().resolve(SWITCH_FILE_NAME);

			if (!Files.isRegularFile(switchFile)) {
				// The overwhelmingly common case: no probe, no registration.
				return;
			}

			Integer parsedMode = parseMode(switchFile);

			if (parsedMode == null) {
				warnOnce("outline experiment: " + SWITCH_FILE_NAME
					+ " must contain a mode 1.." + MAX_MODE + " on the first non-blank line; probe disabled");
				return;
			}

			mode = parsedMode;
			Select selection = select(mode);

			renderType = selection.renderType();
			afterTerrain = selection.afterTerrain();

			if (mode == GIZMO_MODE) {
				// E3: the gizmo pass carries its own "on top" flag, so it is
				// registered on the gizmo barrier instead of the submit barrier.
				LevelRenderEvents.BEFORE_GIZMOS.register(OutlineExperiment::onBeforeGizmos);
			} else {
				LevelRenderEvents.COLLECT_SUBMITS.register(OutlineExperiment::onCollectSubmits);
			}

			registered = true;

			LOGGER.info("outline experiment: mode={} renderType={} afterTerrain={} (E1/E2/E3 spike active)",
				mode, selection.description(), afterTerrain);

			if (mode == 5) {
				// mode 5 is the documented custom-pipeline attempt (E2). In 26.3
				// both RenderPipelines.register(...) and RenderType.create(...) are
				// package-private, so a dependency-free mod cannot register its own
				// pipeline/type without an access widener or a mixin (see
				// docs/reviews/21-m7-prep-spike.md). Degrade to the closest public
				// type instead of failing, and say so exactly once.
				warnOnce("outline experiment: mode 5 (custom no-depth-test pipeline) is not reachable in 26.3"
					+ " without an access widener: RenderPipelines.register and RenderType.create are package-private;"
					+ " falling back to RenderTypes.linesTranslucentNoDepthWrite()");
			}
		} catch (RuntimeException | LinkageError failure) {
			warnOnce("outline experiment: registration failed (" + failure.getClass().getSimpleName() + ")");
		}
	}

	private static Integer parseMode(Path switchFile) {
		try {
			List<String> lines = Files.readAllLines(switchFile, StandardCharsets.UTF_8);

			for (String line : lines) {
				String trimmed = line.trim();

				if (trimmed.isEmpty()) {
					continue;
				}

				try {
					int value = Integer.parseInt(trimmed);

					return value >= 1 && value <= MAX_MODE ? value : null;
				} catch (NumberFormatException notANumber) {
					return null;
				}
			}

			return null;
		} catch (IOException unreadable) {
			return null;
		}
	}

	/**
	 * Maps a mode to the RenderType it probes.
	 *
	 * <p>Modes 1-4 are the E1 matrix; mode 5 is the E2 custom-pipeline attempt,
	 * which degrades to the no-depth-write line type in 26.3; mode 6 is the E3
	 * gizmo route and needs no RenderType at all (see
	 * {@link #registerIfEnabled()}).
	 */
	private static Select select(int mode) {
		return switch (mode) {
			case 1 -> new Select(RenderTypes.linesTranslucent(), false, "linesTranslucent(afterTerrain=false)");
			case 2 -> new Select(RenderTypes.linesTranslucent(), true, "linesTranslucent(afterTerrain=true)");
			case 3 -> new Select(RenderTypes.linesTranslucentNoDepthWrite(), false, "linesTranslucentNoDepthWrite(afterTerrain=false)");
			case 4 -> new Select(RenderTypes.linesTranslucentNoDepthWrite(), true, "linesTranslucentNoDepthWrite(afterTerrain=true)");
			case GIZMO_MODE -> new Select(null, true, "gizmos(DrawableGizmoPrimitives, onTop=true)");
			default -> new Select(RenderTypes.linesTranslucentNoDepthWrite(), true, "fallback(linesTranslucentNoDepthWrite,afterTerrain=true)");
		};
	}

	/** The per-frame submission, registered on {@code LevelRenderEvents.COLLECT_SUBMITS}. */
	private static void onCollectSubmits(LevelRenderContext context) {
		if (submissionDisabled || renderType == null) {
			return;
		}

		try {
			Minecraft game = Minecraft.getInstance();

			if (game == null || game.level == null || game.player == null) {
				return;
			}

			Camera camera = game.gameRenderer.mainCamera();

			if (camera == null || !camera.isInitialized()) {
				return;
			}

			BlockPos base = game.player.blockPosition().below();
			Vec3 cameraPosition = camera.position();
			PoseStack poseStack = context.poseStack();
			SubmitNodeCollector collector = context.submitNodeCollector();

			poseStack.pushPose();

			try {
				poseStack.translate(
					base.getX() - cameraPosition.x,
					base.getY() - cameraPosition.y,
					base.getZ() - cameraPosition.z);
				collector.submitShapeOutline(poseStack, CUBE, renderType, COLOR, LINE_WIDTH, afterTerrain);
			} finally {
				poseStack.popPose();
			}

			submitted++;
			logBudgeted();
		} catch (RuntimeException | LinkageError failure) {
			// One broken submission must not spam (or crash) the render thread.
			submissionDisabled = true;
			warnOnce("outline experiment: submission failed, probe disabled for this session ("
				+ failure.getClass().getSimpleName() + ")");
		}
	}

	/**
	 * E3: the same 3x3x3 cube drawn as twelve gizmo lines with the on-top flag.
	 *
	 * <p>Route choice: {@code Gizmos.line(...) would write into vanilla's private ThreadLocal gizmo collector, whose lifecycle is an internal detail; DrawableGizmoPrimitives + submit(..., onTop=true) uses the same public submission path the vanilla on-top group uses, without depending on that scope}
	 * with {@code onTop = true} is the same submission the vanilla on-top gizmo
	 * group performs, with public API only.
	 */
	private static void onBeforeGizmos(LevelRenderContext context) {
		if (submissionDisabled) {
			return;
		}

		try {
			Minecraft game = Minecraft.getInstance();

			if (game == null || game.level == null || game.player == null) {
				return;
			}

			CameraRenderState cameraState = context.levelState().cameraRenderState;

			if (cameraState == null) {
				return;
			}

			BlockPos base = game.player.blockPosition().below();
			double x0 = base.getX();
			double y0 = base.getY();
			double z0 = base.getZ();
			double x1 = x0 + 3.0;
			double y1 = y0 + 3.0;
			double z1 = z0 + 3.0;

			DrawableGizmoPrimitives primitives = new DrawableGizmoPrimitives();

			// Bottom face.
			addEdge(primitives, x0, y0, z0, x1, y0, z0);
			addEdge(primitives, x1, y0, z0, x1, y0, z1);
			addEdge(primitives, x1, y0, z1, x0, y0, z1);
			addEdge(primitives, x0, y0, z1, x0, y0, z0);
			// Top face.
			addEdge(primitives, x0, y1, z0, x1, y1, z0);
			addEdge(primitives, x1, y1, z0, x1, y1, z1);
			addEdge(primitives, x1, y1, z1, x0, y1, z1);
			addEdge(primitives, x0, y1, z1, x0, y1, z0);
			// Verticals.
			addEdge(primitives, x0, y0, z0, x0, y1, z0);
			addEdge(primitives, x1, y0, z0, x1, y1, z0);
			addEdge(primitives, x1, y0, z1, x1, y1, z1);
			addEdge(primitives, x0, y0, z1, x0, y1, z1);

			// onTop = true: this is the "always on top" gizmo group, i.e. the
			// see-through route E3 is testing.
			primitives.submit(context.submitNodeCollector(), cameraState, true);

			submitted++;
			logBudgeted();
		} catch (RuntimeException | LinkageError failure) {
			submissionDisabled = true;
			warnOnce("outline experiment: gizmo submission failed, probe disabled for this session ("
				+ failure.getClass().getSimpleName() + ")");
		}
	}

	private static void addEdge(
		DrawableGizmoPrimitives primitives,
		double x0, double y0, double z0,
		double x1, double y1, double z1
	) {
		primitives.addLine(new Vec3(x0, y0, z0), new Vec3(x1, y1, z1), COLOR, LINE_WIDTH);
	}

	/** Emits at most one INFO line per {@link #LOG_INTERVAL_MILLIS}. */
	private static void logBudgeted() {
		long now = System.currentTimeMillis();

		if (now - lastLogMillis < LOG_INTERVAL_MILLIS) {
			return;
		}

		lastLogMillis = now;
		LOGGER.info("outline experiment: mode={} submitted={}", mode, submitted);
	}

	/** Reports one WARN for the first failure only. */
	private static void warnOnce(String message) {
		if (failureLogged) {
			return;
		}

		failureLogged = true;
		LOGGER.warn(String.format(Locale.ROOT, "%s (%s)", message, MOD_ID));
	}

	private record Select(RenderType renderType, boolean afterTerrain, String description) {}
}
