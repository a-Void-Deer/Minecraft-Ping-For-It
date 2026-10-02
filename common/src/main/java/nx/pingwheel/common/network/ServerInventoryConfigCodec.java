package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;

import java.math.BigDecimal;
import java.util.EnumMap;

/** Fixed-shape v2 administration payload. No strings, defaults or mutable config objects cross it. */
final class ServerInventoryConfigCodec {
	private ServerInventoryConfigCodec() {}

	static InventoryConfigValues read(FriendlyByteBuf buf) {
		var values = new EnumMap<Field, Value>(Field.class);
		for (Field field : Field.values()) {
			boolean unlimited = field.supportsUnlimited() && readBoolean(buf);
			int encoded = ServerConfigVarNumbers.readInt(buf);
			BigDecimal value = field.isMultiplier()
				? field.grid().quantum().multiply(BigDecimal.valueOf(encoded))
				: BigDecimal.valueOf(encoded);
			var supplied = new Value(unlimited, value);
			if (!field.isSafe(supplied)) throw new IllegalArgumentException("invalid inventory setting");
			values.put(field, supplied);
		}
		return new InventoryConfigValues(values);
	}

	static void write(FriendlyByteBuf buf, InventoryConfigValues inventory) {
		if (inventory == null || !inventory.isSafe()) throw new IllegalArgumentException("unsafe inventory settings");
		for (Field field : Field.values()) {
			Value value = inventory.value(field);
			if (field.supportsUnlimited()) buf.writeBoolean(value.unlimited());
			buf.writeVarInt(field.isMultiplier() ? field.grid().quantaOf(value.value()) : value.value().intValueExact());
		}
	}

	static boolean readBoolean(FriendlyByteBuf buf) {
		int value = buf.readUnsignedByte();
		if (value > 1) throw new IllegalArgumentException("invalid boolean");
		return value == 1;
	}

	static InventoryConfigValues readComplete(FriendlyByteBuf buf) {
		InventoryConfigValues inventory = read(buf);
		requireEnd(buf);
		return inventory;
	}

	static void requireEnd(FriendlyByteBuf buf) {
		if (buf.isReadable()) throw new IllegalArgumentException("trailing server configuration bytes");
	}
}
