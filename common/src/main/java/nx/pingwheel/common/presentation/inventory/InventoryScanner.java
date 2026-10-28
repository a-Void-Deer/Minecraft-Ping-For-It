package nx.pingwheel.common.presentation.inventory;

import java.util.Objects;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Shared inventory identity, detached stack values and source-read contracts. */
@NotNullByDefault
public final class InventoryScanner {

	private InventoryScanner() {}

	public record Key(String itemId, String componentsKey) {
		public Key {
			Objects.requireNonNull(itemId, "itemId");
			Objects.requireNonNull(componentsKey, "componentsKey");
			if (itemId.isBlank() || itemId.length() > 256 || componentsKey.length() > 256) {
				throw new IllegalArgumentException("invalid item identity");
			}
		}
	}

	public record Stack(Key key, long count) {
		public Stack {
			Objects.requireNonNull(key, "key");
			if (count < 0) throw new IllegalArgumentException("negative item count");
		}
	}

	/** Reads must be side-effect free; returned stacks are detached values, not game stacks. */
	public interface Source {
		int slots();
		boolean stableCursor();
		boolean stableSnapshot();

		/**
		 * Reads one detached observation. {@code null} means the slot was
		 * actually observed as empty; it is not a cache miss or an unknown
		 * state. A source that cannot read the slot must throw instead of
		 * returning {@code null}, so an unavailable read is never mistaken
		 * for an observed empty slot.
		 */
		@Nullable Stack read(int slot);
	}
}
