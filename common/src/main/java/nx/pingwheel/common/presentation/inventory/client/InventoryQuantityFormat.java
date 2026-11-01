package nx.pingwheel.common.presentation.inventory.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Display-only rational fluid quantities. No floating point or formatted value re-enters state. */
@NotNullByDefault
public final class InventoryQuantityFormat {

	private static final BigInteger BUCKET = BigInteger.valueOf(81_000);
	private InventoryQuantityFormat() {}

	@NotNullByDefault
	public record Formatted(String number, String unitKey, boolean approximate) {
		public Component component() {
			Component quantity = Component.translatable(unitKey, number);
			return approximate ? Component.translatable("pingforit.inventory.quantity.approximate", quantity) : quantity;
		}
	}

	public static Component quantity(InventoryResourceKind kind, @Nullable Long amount) {
		if (amount == null || amount < 0) return Component.empty();
		if (kind == InventoryResourceKind.ITEM) return Component.literal("x" + amount);
		String style = Language.getInstance().getOrDefault("pingforit.inventory.quantity.scale_style");
		Formatted formatted = fluid(amount, style.equals("cjk") ? "zh" : "en");
		String decimal = Language.getInstance().getOrDefault("pingforit.inventory.quantity.decimal_separator");
		return new Formatted(decimal.equals(",") ? formatted.number().replace('.', ',') : formatted.number(),
			formatted.unitKey(), formatted.approximate()).component();
	}

	/** At least six significant digits for scaled values; small quantities prefer whole mB. */
	public static Formatted fluid(long amount, String languageCode) {
		if (amount < 0) throw new IllegalArgumentException("negative fluid amount");
		BigInteger units = BigInteger.valueOf(amount);
		if (amount > 0 && amount < 81) return new Formatted("<1", key("millibuckets"), true);
		if (amount < 81_000) {
			BigDecimal millibuckets = new BigDecimal(units).divide(BigDecimal.valueOf(81), 0, RoundingMode.HALF_UP);
			return new Formatted(millibuckets.toPlainString(), key("millibuckets"), amount % 81 != 0);
		}
		boolean cjk = languageCode.startsWith("zh") || languageCode.startsWith("ja") || languageCode.startsWith("ko");
		int step = cjk ? 4 : 3;
		int power = 0;
		while (power < 12 && units.compareTo(BUCKET.multiply(BigInteger.TEN.pow(power + step))) >= 0) power += step;
		BigDecimal rounded;
		BigInteger divisor;
		while (true) {
			divisor = BUCKET.multiply(BigInteger.TEN.pow(power));
			BigInteger whole = units.divide(divisor);
			int digits = whole.toString().length();
			int decimals = power == 0 ? 3 : Math.max(0, 6 - digits);
			rounded = new BigDecimal(units).divide(new BigDecimal(divisor), decimals, RoundingMode.HALF_UP);
			if (power >= 12 || rounded.compareTo(BigDecimal.TEN.pow(step)) < 0) break;
			// Recompute from the authoritative rational after carry; never round a rounded value.
			power += step;
		}
		boolean approximate = rounded.multiply(new BigDecimal(divisor)).compareTo(new BigDecimal(units)) != 0;
		String number = (power == 0 ? rounded.stripTrailingZeros() : rounded).toPlainString();
		char decimal = DecimalFormatSymbols.getInstance(Locale.forLanguageTag(languageCode.replace('_', '-'))).getDecimalSeparator();
		if (decimal != '.') number = number.replace('.', decimal);
		String suffix = power == 0 ? "buckets" : (cjk ? "cjk_" : "western_") + power;
		return new Formatted(number, key(suffix), approximate);
	}

	private static String key(String suffix) { return "pingforit.inventory.quantity." + suffix; }
}
