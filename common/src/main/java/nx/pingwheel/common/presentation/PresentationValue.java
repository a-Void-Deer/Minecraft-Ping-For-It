package nx.pingwheel.common.presentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Detached, immutable values. No game object, component or NBT can enter a snapshot. */
public sealed interface PresentationValue permits PresentationValue.Text, PresentationValue.NumberValue,
	PresentationValue.Flag, PresentationValue.Sequence, PresentationValue.RecordValue {
	record Text(String value) implements PresentationValue {
		public Text { Objects.requireNonNull(value); }
	}

	record NumberValue(double value) implements PresentationValue {
		public NumberValue {
			if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite number");
		}
	}

	record Flag(boolean value) implements PresentationValue {}

	record Sequence(List<PresentationValue> values) implements PresentationValue {
		public Sequence { values = List.copyOf(values); }
	}

	record RecordValue(Map<String, PresentationValue> values) implements PresentationValue {
		public RecordValue {
			values = Map.copyOf(new LinkedHashMap<>(values));
		}
	}
}
