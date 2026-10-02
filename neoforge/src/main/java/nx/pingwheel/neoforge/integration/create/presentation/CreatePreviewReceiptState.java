package nx.pingwheel.neoforge.integration.create.presentation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Bounded receipt metadata only; no source values, world handles or private field extraction. */
public final class CreatePreviewReceiptState {
	private boolean received;
	private long tick;
	public void clear() { received = false; }
	public boolean received() { return received; }
	public long tick() { return tick; }
	public void accept(CompoundTag tag, boolean clientPacket, boolean clientWorld,
		boolean moved, boolean virtual, long receiptTick) {
		clear();
		if (!clientPacket || !clientWorld || moved || virtual || receiptTick < 0
			|| !tag.contains("Speed", Tag.TAG_FLOAT)) return;
		if (tag.contains("Network")) {
			if (!tag.contains("Network", Tag.TAG_COMPOUND)) return;
			CompoundTag network = tag.getCompound("Network");
			if (!network.contains("Id", Tag.TAG_LONG) || !network.contains("Stress", Tag.TAG_FLOAT)
				|| !network.contains("Capacity", Tag.TAG_FLOAT)) return;
		}
		received = true; tick = receiptTick;
	}
}
