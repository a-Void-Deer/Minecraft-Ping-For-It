package nx.pingwheel.common.integration.sable.client;

import java.lang.reflect.Field;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.sable.SableDiagnostics;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.interaction.candidate.CandidateCollector;
import nx.pingwheel.common.interaction.candidate.CandidateWorkBudget;
import nx.pingwheel.common.interaction.candidate.CandidateWorkLimits;
import nx.pingwheel.common.math.RaycastPolicy;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableClientDiagnosticsTest {

	private boolean previousSableLoaded;

	@BeforeEach
	void installRecordingDiagnostics() {
		previousSableLoaded = ModContext.HasSable;
		ModContext.HasSable = false;
	}

	@AfterEach
	void restoreDiagnosticsAndSableFlag() {
		SableClientProvider.setDiagnosticsForTests(SableDiagnostics.global(Diagnostics.client()));
		setAccess(null);
		ModContext.HasSable = previousSableLoaded;
	}

	@Test
	void supplementalIncompleteUsesItsFiniteHandlingAndKeepsTheGuardUsable() throws ReflectiveOperationException {
		SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		SableClientProvider.setDiagnosticsForTests(diagnostics);
		ModContext.HasSable = true;
		SableClientCompanionAccess access = allocate(SableClientCompanionAccess.class);
		SableClientInternalAccess internal = allocate(SableClientInternalAccess.class);
		Field internalField = SableClientCompanionAccess.class.getDeclaredField("internal");
		internalField.setAccessible(true);
		internalField.set(access, internal);
		setAccess(access);

		boolean complete = SableClientProvider.candidateBlocks().collectSupplemental(
			allocate(ClientLevel.class), Vec3.ZERO, new Vec3(1, 0, 0),
			new RaycastPolicy(RaycastPolicy.BlockMode.OUTLINE, RaycastPolicy.FluidMode.NONE, false),
			CollisionContext.empty(), Vec3.ZERO,
			new CandidateWorkBudget(new CandidateWorkLimits(64, 64, 4)), new CandidateCollector());

		assertFalse(complete);
		assertTrue(diagnostics.events().stream().anyMatch(event ->
			SableDiagnostics.SUPPLEMENTAL_INCOMPLETE.equals(event.handling())));
		assertTrue(SableClientProvider.capture(null, null, null, null).isEmpty());
		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.fields().contains("invalid-capture-input")),
			"the supplemental scan failure must not disable the shared guard");
	}

	@Test
	void disabledCaptureStillEmitsAttemptAndExternalFallbackStages() {
		SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		SableClientProvider.setDiagnosticsForTests(diagnostics);

		assertTrue(SableClientProvider.capture(null, null, null, null).isEmpty());
		SableClientProvider.logCaptureFallback(
			"PROJECTED_LOCATION", "external-capture-failed", "projection", "projected_position", "1,2,3");
		SableClientProvider.logCaptureFallback(
			"VANILLA_TARGET_FACTORY", "external-and-projected-capture-failed",
			"vanilla-target-factory", "block_pos", "1,2,3");

		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.prefix().equals(SableDiagnostics.CAPTURE_PREFIX)
				&& event.stage().equals("attempt")
				&& event.reason().equals("start")));
		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.reason().equals("EXTERNAL_CAPTURE_FAILED")));
		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.reason().equals("PROJECTED_LOCATION")));
		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.reason().equals("VANILLA_TARGET_FACTORY")));
	}

	private static void setAccess(@Nullable SableClientCompanionAccess value) {
		try {
			Field field = SableClientProvider.class.getDeclaredField("access");
			field.setAccessible(true);
			field.set(null, value);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("Sable access test seam unavailable", failure);
		}
	}

	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		Field field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
	}
}
