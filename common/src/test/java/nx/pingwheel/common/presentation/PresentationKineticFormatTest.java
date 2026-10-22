package nx.pingwheel.common.presentation;

import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationKineticFormatTest {
	private static final String CREATE = "create:presentation";
	private static final PresentationPropertyRef SPEED = PresentationPropertyRef.root(CREATE, "create:kinetic.speed");
	private static final PresentationPropertyRef STRESS = PresentationPropertyRef.root(CREATE, "create:kinetic.stress");
	private static final PresentationPropertyRef CAPACITY = PresentationPropertyRef.root(CREATE, "create:kinetic.capacity");
	private static final PresentationPropertyRef AVAILABLE = PresentationPropertyRef.root(CREATE, "create:kinetic.available_capacity");

	private static Component format(PresentationPropertyRef ref, double value, PresentationValue capacity) {
		return PresentationKineticFormat.value(ref, new PresentationValue.NumberValue(value), ignored -> capacity);
	}

	private static void assertFormatted(Component component, String key, String... args) {
		assertNotNull(component);
		assertInstanceOf(TranslatableContents.class, component.getContents());
		TranslatableContents contents = (TranslatableContents) component.getContents();
		assertEquals("presentation.pingforit.format." + key, contents.getKey());
		assertArrayEquals(args, contents.getArgs());
	}

	@Test void usedStressUsesTheSameProjectionCapacityForItsPercentage() {
		assertFormatted(format(STRESS, 3, new PresentationValue.NumberValue(8)), "su_percent", "3", "37.5");
		assertFormatted(format(STRESS, 12, new PresentationValue.NumberValue(10)), "su_percent", "12", "120");
	}

	@Test void missingZeroOrNonPositiveCapacityOmitsThePercentageInsteadOfInventingZero() {
		assertFormatted(format(STRESS, 12, null), "su", "12");
		assertFormatted(format(STRESS, 12, new PresentationValue.NumberValue(0)), "su", "12");
		assertFormatted(format(STRESS, 12, new PresentationValue.NumberValue(-4)), "su", "12");
		assertFormatted(format(STRESS, 12, new PresentationValue.Flag(true)), "su", "12");
	}

	@Test void capacityAndAvailableStayPlainSuAndNegativeAvailableIsNotClamped() {
		assertFormatted(format(CAPACITY, 10, null), "su", "10");
		assertFormatted(format(AVAILABLE, -5.25, null), "su", "-5.25");
	}

	@Test void hugeAndFractionalValuesUsePlainTwoDecimalRounding() {
		assertFormatted(format(CAPACITY, 1e20, null), "su", "100000000000000000000");
		assertFormatted(format(STRESS, 1, new PresentationValue.NumberValue(3)), "su_percent", "1", "33.33");
		assertFormatted(format(AVAILABLE, 0.5, null), "su", "0.5");
	}

	@Test void capacityAndAvailableNeverConsultTheCapacityLookup() {
		assertFormatted(PresentationKineticFormat.value(CAPACITY, new PresentationValue.NumberValue(10),
			ignored -> { throw new AssertionError("capacity must not be looked up"); }), "su", "10");
		assertFormatted(PresentationKineticFormat.value(AVAILABLE, new PresentationValue.NumberValue(-5),
			ignored -> { throw new AssertionError("capacity must not be looked up"); }), "su", "-5");
	}

	@Test void rootSpeedRecordFormatsItsEffectiveRpmWithoutConsultingCapacity() {
		assertFormatted(PresentationKineticFormat.value(SPEED, new PresentationValue.RecordValue(Map.of(
			"effective_rpm", new PresentationValue.NumberValue(128),
			"theoretical_rpm", new PresentationValue.NumberValue(64),
			"moving", new PresentationValue.Flag(true))), ignored -> {
				throw new AssertionError("speed must not look up capacity");
			}), "rpm", "128");
		assertFormatted(PresentationKineticFormat.value(SPEED, new PresentationValue.RecordValue(Map.of(
			"effective_rpm", new PresentationValue.NumberValue(-2.5),
			"theoretical_rpm", new PresentationValue.NumberValue(-2.5),
			"moving", new PresentationValue.Flag(true))), ignored -> null), "rpm", "-2.5");
		assertFormatted(PresentationKineticFormat.value(SPEED, new PresentationValue.RecordValue(Map.of(
			"effective_rpm", new PresentationValue.NumberValue(0),
			"theoretical_rpm", new PresentationValue.NumberValue(32),
			"moving", new PresentationValue.Flag(false))), ignored -> null), "rpm", "0");
	}

	@Test void speedWithoutANumericEffectiveRpmOrANestedRpmRefStaysUnformatted() {
		assertNull(PresentationKineticFormat.value(SPEED, new PresentationValue.RecordValue(Map.of(
			"theoretical_rpm", new PresentationValue.NumberValue(32))), ignored -> null),
			"a speed record without effective_rpm keeps the caller's generic record summary");
		assertNull(PresentationKineticFormat.value(SPEED, new PresentationValue.RecordValue(Map.of(
			"effective_rpm", new PresentationValue.Text("fast"))), ignored -> null),
			"a non-numeric effective_rpm is never fabricated into an RPM value");
		assertNull(PresentationKineticFormat.value(new PresentationPropertyRef(CREATE, "create:kinetic.speed",
			List.of("effective_rpm")), new PresentationValue.NumberValue(128), ignored -> null),
			"a nested effective_rpm ref keeps its typed scalar representation");
	}

	@Test void unrelatedOrNonNumericValuesStayUnformatted() {
		assertNull(PresentationKineticFormat.value(
			PresentationPropertyRef.root("minecraft:basic", "minecraft:entity.health"),
			new PresentationValue.NumberValue(5), ignored -> null));
		assertNull(PresentationKineticFormat.value(STRESS, new PresentationValue.Text("12"), ignored -> null));
		assertNull(PresentationKineticFormat.value(
			new PresentationPropertyRef(CREATE, "create:kinetic.stress", java.util.List.of("effective")),
			new PresentationValue.NumberValue(12), ignored -> null));
	}
}
