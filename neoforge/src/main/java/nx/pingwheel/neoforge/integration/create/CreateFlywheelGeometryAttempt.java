package nx.pingwheel.neoforge.integration.create;

import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.client.outline.FlywheelDiagnosticReason;
import nx.pingwheel.common.client.outline.FlywheelDiagnostics;
import nx.pingwheel.common.client.outline.FlywheelInstanceResolution;
import nx.pingwheel.common.client.outline.FlywheelModelBudget;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** Synchronous, attempt-local diagnostic ownership; no failure or deferred detail escapes the attempt. */
@NotNullByDefault
final class CreateFlywheelGeometryAttempt {

	private @Nullable Throwable resolutionFailure;
	private Supplier<String> resolutionDetails = () -> "";
	final Evidence evidence = new Evidence();

	private CreateFlywheelGeometryAttempt() {}

	static EntityBlockGeometryOutcome run(Function<CreateFlywheelGeometryAttempt, Result> body, Consumer<Result> report) {
		CreateFlywheelGeometryAttempt attempt = new CreateFlywheelGeometryAttempt();
		Result result = body.apply(attempt);
		@Nullable Throwable firstFailure = attempt.resolutionFailure;
		if (firstFailure != null) {
			@Nullable Throwable finalFailure = result.failure();
			Throwable combined = finalFailure == null || finalFailure == firstFailure ? firstFailure
				: new CombinedFailure(finalFailure, firstFailure);
			FlywheelDiagnosticReason reason = result.reason() == FlywheelDiagnosticReason.RENDERED
				? FlywheelDiagnosticReason.INSTANCE_RESOLUTION_FAILED : result.reason();
			Result selected = result;
			FlywheelDiagnostics.FailureCategory category = result.category() == FlywheelDiagnostics.FailureCategory.NONE
				? FlywheelDiagnostics.FailureCategory.INSTANCE_RESOLUTION : result.category();
			result = new Result(result.outcome(), reason, category, result.target(),
				() -> attempt.resolutionDetails.get() + "; " + selected.details().get(), combined);
		}
		Result selected = result;
		LoggingProfile profile = Diagnostics.profile(DiagnosticSide.CLIENT);
		result = new Result(selected.outcome(), selected.reason(), selected.category(), selected.target(),
			() -> selected.details().get() + "; " + attempt.evidence.describe(profile == LoggingProfile.DEBUG), selected.failure());
		report.accept(result);
		return result.outcome();
	}

	<I, R> FlywheelInstanceResolution.Batch<R> resolve(List<I> instances, Function<I, @Nullable R> resolver) {
		FlywheelInstanceResolution.Batch<R> batch = FlywheelInstanceResolution.resolve(instances, resolver);
		resolutionFailure = batch.firstFailure();
		if (resolutionFailure != null) {
			resolutionDetails = batch::failureDetails;
		}
		return batch;
	}

	/** The production model loop, including its zero-geometry and target-budget early exits. */
	<R, G, E extends Exception> Result models(List<R> instances, ModelLoader<R, G, E> load, ToLongFunction<G> triangles,
		BiFunction<FlywheelDiagnosticReason, Supplier<String>, Result> empty,
		Function<List<G>, Result> ready) throws E {
		List<G> geometries = new ArrayList<>(instances.size());
		long triangleCount = 0L;
		for (R instance : instances) {
			G geometry = load.load(instance);
			try {
				triangleCount = Math.addExact(triangleCount, triangles.applyAsLong(geometry));
			} catch (ArithmeticException overflow) {
				return empty.apply(FlywheelDiagnosticReason.BUDGET_EXCEEDED, () -> "target triangle count overflow");
			}
			if (!FlywheelModelBudget.trianglesWithinBudget(triangleCount, FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET)) {
				long currentTriangleCount = triangleCount;
				return empty.apply(FlywheelDiagnosticReason.BUDGET_EXCEEDED,
					() -> "triangleCount=" + currentTriangleCount + "; maxTargetTriangles=" + FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET);
			}
			geometries.add(geometry);
		}
		if (triangleCount == 0L) {
			return empty.apply(FlywheelDiagnosticReason.NO_INSTANCES, () -> "instances have no indexed triangles");
		}
		return ready.apply(geometries);
	}

	@NotNullByDefault
	@FunctionalInterface
	interface ModelLoader<R, G, E extends Exception> {
		G load(R instance) throws E;
	}

	@NotNullByDefault
	record Result(EntityBlockGeometryOutcome outcome, FlywheelDiagnosticReason reason, FlywheelDiagnostics.FailureCategory category,
		Supplier<String> target, Supplier<String> details, @Nullable Throwable failure) {
		Result(EntityBlockGeometryOutcome outcome, FlywheelDiagnosticReason reason,
			Supplier<String> target, Supplier<String> details, @Nullable Throwable failure) {
			this(outcome, reason, FlywheelDiagnostics.category(reason, outcome, failure), target, details, failure);
		}
	}

	/** Fixed-size facts copied where business work already reads them; never calls back into a live graph. */
	@NotNullByDefault
	static final class Evidence {
		int resolutionAttempts;
		int resolvedInstances;
		int modelAttempts;
		int cacheHits;
		int meshCount = -1;
		int meshIndex = -1;
		int vertexCount = -1;
		int indexCount = -1;
		int extractedMeshes;
		long extractedTriangles;
		@Nullable Object instance;
		@Nullable Object handle;
		@Nullable Object backendState;
		@Nullable Object instancer;
		@Nullable Object model;
		@Nullable Object material;
		@Nullable ResourceLocation texture;
		@Nullable Object environmentOrigin;

		String describe(boolean debug) {
			String summary = "resolutionAttempts=" + resolutionAttempts + "; resolvedInstances=" + resolvedInstances
				+ "; modelAttempts=" + modelAttempts + "; cacheHits=" + cacheHits
				+ "; meshCount=" + meshCount + "; lastMeshIndex=" + meshIndex
				+ "; extractedMeshes=" + extractedMeshes + "; extractedTriangles=" + extractedTriangles
				+ "; lastInstanceClass=" + type(instance) + "; modelClass=" + type(model);
			if (!debug) return summary;
			return summary + "; handleClass=" + type(handle)
				+ "; backendStateClass=" + type(backendState) + "; instancerClass=" + type(instancer)
				+ "; materialClass=" + type(material)
				+ "; texture=" + texture + "; environmentOriginClass=" + type(environmentOrigin)
				+ "; vertices=" + vertexCount + "; indices=" + indexCount;
		}

		private static String type(@Nullable Object value) { return value == null ? "<unobserved>" : value.getClass().getName(); }
	}

	/** Only this fresh wrapper is mutated; both original exceptions remain untouched. */
	@NotNullByDefault
	private static final class CombinedFailure extends RuntimeException {
		private CombinedFailure(Throwable finalFailure, Throwable resolutionFailure) {
			// Preserve the earlier failure first if the later chain exhausts the
			// formatter's total budget. The terminal reason remains on Result.
			super("Flywheel attempt failed after an instance resolution failure", resolutionFailure, true, false);
			addSuppressed(finalFailure);
		}
	}
}
