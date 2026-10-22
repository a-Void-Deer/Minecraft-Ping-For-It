package nx.pingwheel.common.presentation.inventory.minecraft;

/** Exact TextColor unnamed-color leaf; named colors never enter this path. */
public final class InventoryRgbColor {
	private InventoryRgbColor() {}
	public static String format(int value) {
		char[] result = new char[7]; result[0] = '#';
		for (int index = 6; index != 0; index--) {
			int nibble = value & 15;
			result[index] = (char) (nibble < 10 ? '0' + nibble : 'A' + nibble - 10);
			value >>>= 4;
		}
		return new String(result);
	}
}
