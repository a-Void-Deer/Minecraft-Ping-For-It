package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;

import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;

/** Detached member topology and the exact, ordered slot view of a capture. */
public record InventorySnapshotLayout(String layoutId, String layoutData, BlockPos controller, List<Member> members) {

	/** Finite engineering guards, including members that contribute no slots. */
	public static final int MAX_MEMBERS = 256;
	public static final int MAX_SEGMENTS_PER_MEMBER = 16;
	private static final int MAX_ID_CHARS = 256, MAX_PATH_CHARS = 1024, MAX_LAYOUT_DATA_CHARS = 65536;

	public InventorySnapshotLayout {
		text(layoutId, MAX_ID_CHARS, false, "layoutId");
		text(layoutData, MAX_LAYOUT_DATA_CHARS, true, "layoutData");
		controller = controller == null ? null : controller.immutable();
		Objects.requireNonNull(members, "members");
		if (members.isEmpty() || members.size() > MAX_MEMBERS) throw new IllegalArgumentException("snapshot member bound");
		members = List.copyOf(members);
		Set<BlockPos> positions = new HashSet<>();
		int visible = 0;
		for (Member member : members) {
			if (!positions.add(member.position())) throw new IllegalArgumentException("duplicate snapshot member position");
			for (Segment segment : member.segments()) {
				visible = Math.addExact(visible, segment.visibleSlots().size());
				if (visible > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("snapshot visible slot bound");
			}
		}
	}

	/** A structural member is allowed to contribute no inventory segments. */
	public record Member(BlockPos position, String blockId, String blockEntityId, String role, List<Segment> segments) {
		public Member {
			position = Objects.requireNonNull(position, "position").immutable();
			text(blockId, MAX_ID_CHARS, false, "blockId");
			text(blockEntityId, MAX_ID_CHARS, false, "blockEntityId");
			text(role, MAX_ID_CHARS, false, "role");
			Objects.requireNonNull(segments, "segments");
			if (segments.size() > MAX_SEGMENTS_PER_MEMBER) throw new IllegalArgumentException("snapshot member segment bound");
			segments = List.copyOf(segments);
		}
	}

	/** visibleSlots maps this segment's visible indices to its own local slots. */
	public record Segment(String fieldPath, String schemaId, int localSlots, List<Integer> visibleSlots) {
		public Segment {
			text(fieldPath, MAX_PATH_CHARS, false, "fieldPath");
			text(schemaId, MAX_ID_CHARS, false, "schemaId");
			if (localSlots < 0 || localSlots > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("snapshot local slot bound");
			Objects.requireNonNull(visibleSlots, "visibleSlots");
			if (visibleSlots.size() > localSlots) throw new IllegalArgumentException("snapshot visible mapping bound");
			visibleSlots = List.copyOf(visibleSlots);
			boolean[] seen = new boolean[localSlots];
			for (int slot : visibleSlots) {
				if (slot < 0 || slot >= localSlots || seen[slot]) throw new IllegalArgumentException("invalid snapshot slot mapping");
				seen[slot] = true;
			}
		}
	}

	private static void text(String value, int bound, boolean emptyAllowed, String field) {
		Objects.requireNonNull(value, field);
		if (value.length() > bound || (!emptyAllowed && value.isBlank())) throw new IllegalArgumentException("snapshot " + field + " bound");
	}
}
