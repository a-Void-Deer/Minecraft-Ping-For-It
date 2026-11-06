package nx.pingwheel.neoforge.integration.create;

import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Create-free report boundary; the domain gate retains only detached target/transition/timing facts. */
@NotNullByDefault
final class CreateEntityOutlineDiagnostics {
	private final CreateEntityOutlineDiagnosticGate gate;
	private final Supplier<String> backendDescription;

	CreateEntityOutlineDiagnostics(Supplier<String> backendDescription) {
		this(backendDescription, System::nanoTime);
	}

	CreateEntityOutlineDiagnostics(Supplier<String> backendDescription, LongSupplier clock) {
		this.backendDescription = backendDescription;
		gate = new CreateEntityOutlineDiagnosticGate(clock);
	}

	void report(@Nullable Entity entity, String reason, String backend, boolean scope, int vertexCount,
		@Nullable Throwable failure, @Nullable EntityBlockGeometryOutcome outcome) {
		String key = entity == null ? "<null>"
			: entity.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(entity));
		String result = outcome == null ? "UNKNOWN" : outcome.name();
		Level level = CreateEntityOutlineDiagnosticSeverity.level(reason, backend, failure, outcome);
		DiagnosticLogger selected = level == Level.DEBUG ? Diagnostics.client().atDebug() : Diagnostics.client().atNormal();
		if (!selected.isEnabled(level)) {
			// Observe recovery even below DEBUG, but never consume a report opportunity.
			gate.admit(key, reason, result, false, level == Level.DEBUG);
			return;
		}
		selected = selected.rateLimited(DiagnosticChannel.domain("create-entity-outline",
			() -> gate.admit(key, reason, result, true, level == Level.DEBUG)));
		Supplier<String> details = () -> fullDetails(entity, reason, result, backend, scope, vertexCount);
		if (failure == null) selected.event(level, "create-entity-outline", reason, details);
		else selected.failure(level, "create-entity-outline", "render-route-fallback", details, failure);
	}

	void clear() { gate.clear(); }

	private String fullDetails(@Nullable Entity entity, String reason, String outcome, String backend, boolean scope, int vertexCount) {
		StringBuilder details = new StringBuilder()
			.append("sourceId=").append(CreateEntityOutlineAdapter.SOURCE_ID)
			.append("; reason=").append(reason)
			.append("; outcome=").append(outcome)
			.append("; backend=").append(backend)
			.append("; backendProbeState=").append(backendDescription.get())
			.append("; scope=").append(scope)
			.append("; vertexCount=").append(vertexCount);
		if (entity == null) return details.append("; entity=<null>").toString();
		// Rendering already supplies its outcome, scope and vertex count. Higher
		// disclosure does not authorize another live entity descriptor read.
		details.append("; class=").append(entity.getClass().getName())
			.append("; identity=").append(Integer.toHexString(System.identityHashCode(entity)));
		return details.toString();
	}

}
