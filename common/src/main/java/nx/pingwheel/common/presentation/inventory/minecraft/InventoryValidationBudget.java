package nx.pingwheel.common.presentation.inventory.minecraft;

/**
 * One synchronous validation operation. Allocation is cumulative attempted
 * allocation, workspace is peak overlap, and work is never refunded on failure.
 * These are modeled owned costs, not JVM heap or callback-internal measurements.
 */
public final class InventoryValidationBudget {
	public static final Cost NONE = new Cost(0, 0, 0);
	private static final long O = InventoryItemBudget.OBJECT_BYTES, R = InventoryItemBudget.REFERENCE_BYTES;
	private InventoryValidationBudget() {}

	public record Cost(long allocationBytes, long workspaceBytes, long workUnits) {
		public Cost {
			if (allocationBytes < 0 || workspaceBytes < 0 || workUnits < 0) throw new IllegalArgumentException("negative validation cost");
		}
		public Cost plus(Cost other) {
			return new Cost(add(allocationBytes, other.allocationBytes), Math.max(workspaceBytes, other.workspaceBytes), add(workUnits, other.workUnits));
		}
		/** Nested state stays reachable while the next bounded operation runs. */
		public Cost overlap(Cost other) {
			return new Cost(add(allocationBytes, other.allocationBytes), add(workspaceBytes, other.workspaceBytes), add(workUnits, other.workUnits));
		}
		public Cost times(long count) { return new Cost(mul(allocationBytes, count), workspaceBytes, mul(workUnits, count)); }
	}
	public static final class Allowance {
		private Cost limit;
		private long allocation, work, peak;
		public Allowance(Cost limit) { this.limit = java.util.Objects.requireNonNull(limit); }
		/** Only the caller holding the additional work and workspace reservation may extend an operation. */
		public void admitAdditional(Cost admitted) { limit = limit.plus(admitted); }
		/** A settled synchronous stage cannot carry its refunded headroom into a later reservation. */
		public void releaseUnusedAdmission() { limit = new Cost(allocation, 0, work); }
		/** Call before entering the bounded sequence, including its first callback. */
		public void debit(Cost cost) {
			long nextAllocation, nextWork;
			try { nextAllocation = add(allocation, cost.allocationBytes); nextWork = add(work, cost.workUnits); }
			catch (ArithmeticException overflow) { throw new InventoryItemBudget.CaptureLimitException("inventory validation accounting overflow"); }
			if (nextAllocation > limit.allocationBytes || nextWork > limit.workUnits || cost.workspaceBytes > limit.workspaceBytes)
				throw new InventoryItemBudget.CaptureLimitException("inventory validation allowance exhausted");
			allocation = nextAllocation; work = nextWork; peak = Math.max(peak, cost.workspaceBytes);
		}
		public long attemptedAllocationBytes() { return allocation; }
		public long attemptedWorkUnits() { return work; }
		public long peakWorkspaceBytes() { return peak; }
	}
	private static Cost owned(long bytes, long work) { return new Cost(bytes, bytes, work); }
	private static long array(long cells) { return add(O, mul(cells, R)); }
	private static long list(long cells) { return add(O, array(cells)); }
	private static long copy(long cells) { return add(O, mul(2, array(cells))); }
	/** At most 32 fixed profile builds, each with at most 128 arithmetic Cost records. */
	public static Cost bookkeeping() { return owned(32 * 128 * (O + 3 * Long.BYTES), 32 * 128); }

	/** Scratch, closure, four frozen witness arrays, partner positions and ledger/callback state. */
	public static Cost fixed(InventorySnapshotLayout layout) {
		long m = layout == null ? 1 : layout.members().size();
		long scratch = add(O + R, add(mul(2, array(16)), mul(2, array(1))));
		long witnesses = add(mul(4, array(m)), mul(m, O));
		return owned(add(scratch, add(witnesses, mul(12, O + 8 * R))), 16 + m);
	}
	/**
	 * One reflected method lookup, including a failed inherited lookup. The model
	 * pays the Method, parameter/exception arrays, Class varargs, exception record,
	 * and signature text. ResidentReads checks the type/member/parameter name
	 * lengths before the lookup (and hence before a failed-lookup message).
	 * Concatenation and final message each own a String/UTF16 buffer.
	 * VM metadata caches, class loading and VM backtraces are outside this model.
	 */
	private static Cost reflection() {
		long type = nx.pingwheel.common.platform.IPlatformInventoryService.ResidentReads.MAX_TYPE_NAME_CHARS;
		long member = nx.pingwheel.common.platform.IPlatformInventoryService.ResidentReads.MAX_MEMBER_NAME_CHARS;
		long signature = 3 * type + member + 16; // declaring type, two parameter types, member and punctuation
		long bytes = 3 * O + 16 * R + 4 * array(2) + 4 * O + 2 * 2L * signature;
		return owned(bytes, 1 + 2L * signature);
	}
	/** 32 parent probes plus the public fallback; no unbounded inheritance walk. */
	private static Cost methodOwner() { return reflection().times(33); }
	/**
	 * One container node: two Compound, two Simple, item/size, four loot helpers,
	 * items/size and two mapping queries. Scope and audit each visit at most 32
	 * nodes. Fixed reflected arrays: Compound has 2 fields, native backing owners
	 * at most 22 fields, BaseContainer has 23 declared methods (1.21.1 artifact).
	 */
	public static Cost classification(long roots, boolean wrappers) {
		long nodes = wrappers ? 32 : 1;
		long fields = 2 * (array(2) + 2 * (O + 8 * R)) + array(22) + 22 * (O + 8 * R);
		long methods = array(23) + 23 * (O + 16 * R + 2 * array(8));
		Cost node = methodOwner().times(14).overlap(owned(fields + methods, 2 + 22 + 23 + 32));
		return node.times(mul(roots, nodes));
	}
	/** One local ItemStackHandler audit: three bounded method owners and one field. */
	public static Cost localClassification(long locals) {
		return methodOwner().times(3 * locals).plus(reflection().times(locals));
	}
	/**
	 * One Vault acquisition: 2 root/master loads, 3m member loads (including the frozen DTO), m offsets,
	 * two ArrayLists + two immutable copies + boxed counts + Layout. Each load
	 * includes MinecraftMember/GatedMember; each local includes GatedHandler.
	 * Reflected calls: 6 fixed (controller twice, isController,width,length,axis),
	 * then controller/axis/local per member. List equality/contains are linear.
	 */
	public static Cost vaultLayout(long members) {
		long bytes = 2 * list(members) + 2 * copy(members) + O + members * (O + O)
			+ (2 + 3 * members) * (2 * O + 4 * R) + members * (O + 3 * R);
		return reflection().times(6 + 3 * members).overlap(owned(bytes, 8 + 10 * members));
	}
	/** In-place frozen-layout check: no positions, lists, boxed counts or snapshot DTO are rebuilt. */
	public static Cost vaultValidation(long members) {
		long wrappers = (2 + 2 * members) * (2 * O + 4 * R) + members * (O + 3 * R);
		return reflection().times(6 + 3 * members).overlap(owned(wrappers, 8 + 9 * members));
	}
	/** Pure pre-resolution profile; selection is by the detached registry ID, never by an unadmitted world getter. */
	public static Cost resolution(String blockId) {
		boolean chest = blockId.equals("minecraft:chest") || blockId.equals("minecraft:trapped_chest");
		boolean vault = blockId.equals("create:item_vault");
		long members = chest ? 2 : vault ? 81 : InventorySnapshotLayout.MAX_MEMBERS;
		Cost structure = changedLayoutCopy(members, vault || chest ? members : members * InventorySnapshotLayout.MAX_SEGMENTS_PER_MEMBER)
			.overlap(fixed(null)).overlap(owned(4096L * (O + 2 * R) + 16 * list(members), 4096 + 32 * members));
		if (vault) return bookkeeping().plus(structure.overlap(vaultLayout(81).plus(vaultValidation(81).times(3))));
		if (chest) return bookkeeping().plus(structure.overlap(locks(2)));
		// Unknown capability internals are external-trusted. Our known wrapper graph and possible native Vault discovery remain owned.
		return bookkeeping().plus(structure.overlap(locks(members + 1)).overlap(classification(1, true).times(4))
			.overlap(vaultLayout(81).plus(vaultValidation(81).times(3))));
	}
	/** Fixed DTO rebuild: member/segment records, mappings, seen arrays, copies and position set. */
	public static Cost layoutCopy(InventorySnapshotLayout layout) {
		long m = layout.members().size(), bytes = list(m) + copy(m) + O + list(2 * m)
			+ m * (4 * O + 16 * R + 3 * (2 * O + 2 * 256L)), work = m * (1 + 3 * 256L) + 2 * (256L + 65536L);
		long seenTotal = 0, seenPeak = 0;
		for (var member : layout.members()) for (var segment : member.segments()) {
			long n = segment.visibleSlots().size();
			bytes = add(bytes, 6 * O + list(n) + copy(n) + n * (O + R) + 2 * (1024L + 256L));
			seenTotal = add(seenTotal, array(segment.localSlots())); seenPeak = Math.max(seenPeak, array(segment.localSlots()));
			work = add(work, n + segment.localSlots() + 1024L + 256L);
		}
		// An altered metadata getter may widen the returned mapping before the
		// frozen-layout comparison refuses it. Owned boxing/copies still have to
		// fit the finite global slot bound on that failed attempt.
		bytes = add(bytes, 4096L * (O + 4 * R + 1));
		work = add(work, 4096);
		// Bounded layout/identity strings (including layoutData) and stream/iterator state.
		bytes = add(bytes, 2 * (65536L + 3 * 256L) + 32 * O);
		return new Cost(add(bytes, seenTotal), add(bytes, seenPeak), work + 65536 + 3 * 256);
	}
	/** Native ordinary/Vault rebuilds have at most two/81 members before comparison. */
	private static Cost changedLayoutCopy(long members, long segments) {
		long slots = nx.pingwheel.common.presentation.inventory.InventorySourceAccess.MAX_SLOTS;
		long bytes = list(members) + copy(members) + list(2 * members) + O
			+ members * (4 * O + 16 * R + 3 * (2 * O + 2 * 256L))
			+ segments * (6 * O + list(0) + copy(0))
			+ slots * (O + 4 * R + 1) + 2 * (65536L + 3 * 256L) + 32 * O;
		// Segment's boolean seen array dies after each constructor. It is not
		// retained alongside every other segment's seen array.
		return new Cost(add(bytes, segments * array(slots)), add(bytes, array(slots)),
			members + segments * slots + slots + 65536 + 3 * 256);
	}
	private static boolean vanilla(InventorySnapshotLayout layout) {
		return layout != null && (layout.layoutId().equals("minecraft:container") || layout.layoutId().equals("minecraft:double_chest"));
	}
	/** A failed native single-to-double rebuild still checks two independent locks. */
	public static long viewLockMembers(InventorySnapshotLayout layout, long frozenLocks) {
		if (frozenLocks == 0) return 0;
		return vanilla(layout) ? Math.max(2, frozenLocks) : add(frozenLocks, 1); // provider root check precedes member precheck
	}
	/** One complete source.valid/current reacquisition, before the first world/provider callback. */
	public static Cost view(InventorySnapshotLayout layout, boolean vault) {
		if (layout == null) return layoutlessView();
		long m = layout.members().size(), n = InventoryObservationSnapshot.visibleSlots(layout);
		boolean vanilla = vanilla(layout);
		long attemptedMembers = vault ? 81 : vanilla ? 2 : m;
		// Generic provider DTOs are compared with the frozen descriptor before
		// owned member prechecks/scope arrays. A widened return never reaches a
		// max-members * max-segments * max-local-slots allocation.
		Cost dto = vanilla || vault ? changedLayoutCopy(attemptedMembers, attemptedMembers) : layoutCopy(layout);
		Cost cost = dto.overlap(fixed(layout))
			.overlap(owned(n * (O + 2 * R) + 8 * list(attemptedMembers), n + 8 * attemptedMembers));
		// Retain the conservative scope/scratch bound for every provider. The
		// short-lived scope supplier fits fixed()'s existing closure/wrapper reserve,
		// including providers that build the scope; declining it does not narrow admission.
		if (vault) return cost.overlap(vaultLayout(81)).overlap(vaultValidation(m).times(4));
		return vanilla ? cost : cost.overlap(classification(1, true).times(4))
			.overlap(changedLayoutCopy(81, 81)).overlap(vaultLayout(81)).overlap(vaultValidation(81).times(4));
	}
	/** No layout is still an owned native wrapper/view operation, never a zero grant. */
	public static Cost layoutlessView() {
		long scratch = O + R + 2 * array(16) + 2 * array(1);
		Cost structure = owned(4 * scratch + 4 * array(32) + 24 * (O + 8 * R) + 4 * list(1) + 4 * 256L,
			128 + InventorySnapshotLayout.MAX_MEMBERS * (1L + InventorySnapshotLayout.MAX_SEGMENTS_PER_MEMBER * 256L)
			+ 2 * (256L + 65536L));
		// A newly usable native Vault may rebuild its descriptor during lookup
		// before the returned-layout comparison rejects it. Unknown providers'
		// callback internals remain external, but this native rebuild is ours.
		return structure.overlap(classification(1, true).times(4))
			.overlap(changedLayoutCopy(81, 81)).overlap(vaultLayout(81)).overlap(vaultValidation(81).times(4));
	}
	/** Compare callback-returned evidence before touching its expanded members. */
	public static void requireLayout(InventorySnapshotLayout expected, InventorySnapshotLayout current) {
		if (!java.util.Objects.equals(expected, current)) throw new InventorySourceUnavailableException("inventory provider layout changed");
	}
	public static Cost layoutlessDiscovery() { return bookkeeping().plus(fixed(null)).plus(layoutlessView().times(3)); }
	public static Cost viewValidation(InventorySnapshotLayout layout, boolean vault, long frozenLocks) {
		return view(layout, vault).overlap(layout == null ? locks(frozenLocks) : members(layout, viewLockMembers(layout, frozenLocks)));
	}
	public static Cost supported(InventorySnapshotLayout layout, boolean provider, boolean vault) {
		long m = layout.members().size();
		if (vault) return vaultValidation(m).times(2).plus(localClassification(m))
			.plus(owned(m * (3 * O + 7 * R), 2 * m)); // loaded member + gated local in the classification loop
		return classification(provider ? 1 : m, provider);
	}
	/** Both the pre-read capability classification and the resident reader's own validation. */
	public static Cost read(InventorySnapshotLayout layout, boolean provider, boolean vault) {
		Cost cost = supported(layout, provider, vault);
		if (vault) return cost.plus(vaultValidation(layout.members().size()).times(2)).plus(localClassification(1))
			.plus(owned((2L * layout.members().size() + 1) * (2 * O + 4 * R), 3L * layout.members().size() + 2));
		// Native bridges may classify again in readResident, slots and the final
		// resolved-delegate boundary. Four is the enumerated maximum, not work/unit conversion.
		return provider ? cost.times(4) : cost;
	}
	/** A1's exported single-lock W/work; no coefficient or private root-limit assumption. */
	public static Cost locks(long members) {
		if (members == 0) return new Cost(0, 0, 0);
		int display = InventoryItemCodec.minimumDisplayBytes();
		long workspace = InventoryItemBudget.workspaceUpperBound(display);
		return new Cost(mul(members, workspace), workspace, mul(members, InventoryItemBudget.workUpperBound(display)));
	}
	public static Cost members(InventorySnapshotLayout layout, long lockableMembers) {
		long m = layout.members().size();
		return owned(2 * list(m) + 4 * O + 2 * 256L * m, 16 * m).overlap(locks(lockableMembers));
	}
	public static Cost authority(InventorySnapshotLayout layout, long lockableMembers) {
		return members(layout, lockableMembers).overlap(owned(array(nx.pingwheel.common.presentation.inventory.InventorySourceAccess.MAX_SLOTS),
			InventoryObservationSnapshot.visibleSlots(layout)));
	}
	/** Discovery: one view validation and one capability classification. */
	public static Cost discovery(InventorySnapshotLayout layout, boolean provider, boolean vault, long locks) {
		if (layout == null) return layoutlessDiscovery();
		return fixed(layout).overlap(bookkeeping().plus(viewValidation(layout, vault, locks)).plus(supported(layout, provider, vault)));
	}
	/** Capture: entry/boundary/closing views, two prechecks, n+1 authority gates, n read sequences and two fixed classifications. */
	public static Cost capture(InventorySnapshotLayout layout, boolean provider, boolean vault, long locks) {
		long n = InventoryObservationSnapshot.visibleSlots(layout);
		return fixed(layout).overlap(bookkeeping().plus(viewValidation(layout, vault, locks).times(3)).plus(members(layout, locks).times(2))
			.plus(authority(layout, locks).times(n + 1)).plus(read(layout, provider, vault).times(n))
			.plus(authority(layout, 0)).plus(supported(layout, provider, vault).times(2)));
	}
	private static long add(long a, long b) { return Math.addExact(a, b); }
	private static long mul(long a, long b) { return Math.multiplyExact(a, b); }
}
