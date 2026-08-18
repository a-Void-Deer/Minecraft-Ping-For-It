package nx.pingwheel.common.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, code-defined ping type.
 *
 * <p>A ping type describes the presentation of one semantic ping choice: its
 * localization keys, its 24-bit outline/text colors, and an optional icon
 * identifier. The icon is explicit and validated: {@link Optional#empty()}
 * means "reuse and tint the default ping icon", while a present value names an
 * explicit icon resource. The {@link Optional} itself must not be null, and a
 * present value must not be blank. No icon resources are resolved or loaded in
 * this phase.
 */
public record PingType(
	String id,
	String phraseKey,
	String displayKey,
	int outlineColor,
	int textColor,
	Optional<String> iconId
) {

	public PingType {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(phraseKey, "phraseKey");
		Objects.requireNonNull(displayKey, "displayKey");
		Objects.requireNonNull(iconId, "iconId");

		if (id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}

		if (phraseKey.isBlank()) {
			throw new IllegalArgumentException("phraseKey must not be blank");
		}

		if (displayKey.isBlank()) {
			throw new IllegalArgumentException("displayKey must not be blank");
		}

		iconId.ifPresent(value -> {
			if (value.isBlank()) {
				throw new IllegalArgumentException("iconId must not be blank");
			}
		});

		validateColor("outlineColor", outlineColor);
		validateColor("textColor", textColor);
	}

	private static void validateColor(String name, int color) {
		if ((color & 0xFF000000) != 0) {
			throw new IllegalArgumentException(
				name + " must be a 24-bit RGB value, got 0x" + Integer.toHexString(color));
		}
	}
}
