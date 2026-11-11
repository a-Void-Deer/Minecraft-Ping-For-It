package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;

import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Detached member topology and the exact, ordered slot view of a capture. */
@NotNullByDefault
public record InventorySnapshotLayout(String layoutId, String layoutData, @Nullable BlockPos controller, @Unmodifiable List<Member> members) {

	/** Finite engineering guards, including members that contribute no slots. */
	public static final int MAX_MEMBERS = 1024;
	/** Only the explicitly structured fluid capability route admits this actual-shape member count. */
	public static final int MAX_FLUID_MEMBERS = 4096;
	public static final int MAX_SEGMENTS_PER_MEMBER = 16;
	private static final int MAX_ID_CHARS = 256, MAX_PATH_CHARS = 1024, MAX_LAYOUT_DATA_CHARS = 65536;

	public InventorySnapshotLayout {
		text(layoutId, MAX_ID_CHARS, false, "layoutId", ProviderLimitSite.SNAPSHOT_LAYOUT_ID);
		int dataBound = layoutId.equals("forge:fluid_handler") || layoutId.equals("neoforge:fluid_capability")
			? 2048 + 11 * InventorySourceAccess.MAX_SLOTS : MAX_LAYOUT_DATA_CHARS;
		text(layoutData, dataBound, true, "layoutData", ProviderLimitSite.SNAPSHOT_LAYOUT_DATA);
		controller = controller == null ? null : controller.immutable();
		Objects.requireNonNull(members, "members");
		int memberBound = layoutId.equals("neoforge:fluid_capability") ? MAX_FLUID_MEMBERS : MAX_MEMBERS;
		if (members.size() > memberBound) throw InventoryProviderLimits.exceeded(ProviderLimitSite.SNAPSHOT_MEMBERS, members.size(), memberBound);
		if (members.isEmpty()) throw new IllegalArgumentException("snapshot member bound");
		members = List.copyOf(members);
		Set<BlockPos> positions = new HashSet<>();
		int visible = 0;
		for (Member member : members) {
			if (!positions.add(member.position())) throw new IllegalArgumentException("duplicate snapshot member position");
			for (Segment segment : member.segments()) {
				visible = Math.addExact(visible, segment.visibleSlots().size());
				if (visible > InventorySourceAccess.MAX_SLOTS) throw InventoryProviderLimits.exceeded(ProviderLimitSite.SNAPSHOT_VISIBLE_SLOTS, visible, InventorySourceAccess.MAX_SLOTS);
			}
		}
	}

	/** A structural member is allowed to contribute no inventory segments. */
	public record Member(BlockPos position, String blockId, String blockEntityId, String role, @Unmodifiable List<Segment> segments) {
		public Member {
			position = Objects.requireNonNull(position, "position").immutable();
			text(blockId, MAX_ID_CHARS, false, "blockId", ProviderLimitSite.SNAPSHOT_BLOCK_ID);
			text(blockEntityId, MAX_ID_CHARS, false, "blockEntityId", ProviderLimitSite.SNAPSHOT_ENTITY_ID);
			text(role, MAX_ID_CHARS, false, "role", ProviderLimitSite.SNAPSHOT_ROLE);
			Objects.requireNonNull(segments, "segments");
			if (segments.size() > MAX_SEGMENTS_PER_MEMBER) throw InventoryProviderLimits.exceeded(ProviderLimitSite.SNAPSHOT_SEGMENTS, segments.size(), MAX_SEGMENTS_PER_MEMBER);
			segments = List.copyOf(segments);
		}
	}

	/** visibleSlots maps this segment's visible indices to its own local slots. */
	public record Segment(String fieldPath, String schemaId, int localSlots, @Unmodifiable List<Integer> visibleSlots) {
		public Segment {
			text(fieldPath, MAX_PATH_CHARS, false, "fieldPath", ProviderLimitSite.SNAPSHOT_FIELD_PATH);
			text(schemaId, MAX_ID_CHARS, false, "schemaId", ProviderLimitSite.SNAPSHOT_SCHEMA_ID);
			if (localSlots > InventorySourceAccess.MAX_SLOTS) throw InventoryProviderLimits.exceeded(ProviderLimitSite.SNAPSHOT_LOCAL_SLOTS, localSlots, InventorySourceAccess.MAX_SLOTS);
			if (localSlots < 0) throw new IllegalArgumentException("snapshot local slot bound");
			Objects.requireNonNull(visibleSlots, "visibleSlots");
			if (visibleSlots.size() > localSlots) throw InventoryProviderLimits.exceeded(ProviderLimitSite.SNAPSHOT_MAPPING, visibleSlots.size(), localSlots);
			visibleSlots = List.copyOf(visibleSlots);
			boolean[] seen = new boolean[localSlots];
			for (int slot : visibleSlots) {
				if (slot < 0 || slot >= localSlots || seen[slot]) throw new IllegalArgumentException("invalid snapshot slot mapping");
				seen[slot] = true;
			}
		}
	}

	private static void text(String value, int bound, boolean emptyAllowed, String field, ProviderLimitSite site) {
		Objects.requireNonNull(value, field);
		if (value.length() > bound) throw InventoryProviderLimits.exceeded(site, value.length(), bound);
		if (!emptyAllowed && value.isBlank()) throw new IllegalArgumentException("snapshot " + field + " bound");
	}
}
