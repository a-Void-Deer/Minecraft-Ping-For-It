package nx.pingwheel.common.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationKineticFormatTest {
	private static final String CREATE = "create:presentation";
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
