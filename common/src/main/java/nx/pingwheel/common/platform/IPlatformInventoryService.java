package nx.pingwheel.common.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentItem;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.diagnostics.Diagnostics;

/**
 * Loader bridge for item sources that expose a native, read-only inventory
 * capability.
 *
 * <p>{@link #find} returns a server-thread-confined {@link Access} for the
 * physical source at one position. The vanilla container contract keeps its own
 * provider and is not replaced by this bridge; this bridge covers sources that
 * are reachable only through a loader capability. Implementations read raw
 * Minecraft stacks and never mutate the source, open a transaction, force-load
 * a chunk, or trigger loot generation.
 *
	 * <p>A specialized safe provider may establish a canonical alias, but the
	 * bridge owns no permission or lock decision and no consumer or packet state.
	 * The caller admits bounded provider work before resolution and composes the
	 * physical access with {@code SourceKey} identity.
 */
public interface IPlatformInventoryService {

	IPlatformInventoryService INSTANCE = ServiceLoader.load(IPlatformInventoryService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformInventoryService implementation found!"));

	/**
	 * Resolves the loader item capability at {@code pos} for the non-null frozen
	 * side. Returns empty when the position is not loaded, when no block entity
	 * or capability is present, when loot generation is pending, or when the
	 * provider fails. Must be called on the server thread; the caller owns
	 * target validation and every permission decision.
	 */
	Optional<Access> find(ServerLevel level, BlockPos pos, Direction side);

	/**
	 * Compatible overload for multi-member sources: {@code memberGate} must be
	 * consulted for the root and for every additional member position before
	 * that member's state, block entity, capability or content is read. The
	 * default single-block path gates before and after resolution and wraps the
	 * returned access. Native implementations gate between composite provider
	 * calls, and never widen to an unsided or different-face lookup.
	 */
	default Optional<Access> find(ServerLevel level, BlockPos pos, Direction side,
		java.util.function.Predicate<BlockPos> memberGate) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(side, "side");
		Objects.requireNonNull(memberGate, "memberGate");
		if (!memberGate.test(pos)) return Optional.empty();
		// The existing single-position implementation owns its loaded/loot gates.
		Optional<Access> found = find(level, pos, side);
		if (!memberGate.test(pos)) return Optional.empty();
		BlockPos position = pos.immutable();
		return found.map(access -> access.guardedBy(() -> memberGate.test(position)));
	}

	/**
	 * One detached observation of a source entry. {@code exemplar} is a
	 * single-item stack carrying item and component identity; {@code amount} is
	 * the provider-confirmed long count and stays authoritative even when it
	 * exceeds the integer stack limit. An amount of zero is the canonical empty
	 * observation and never carries an exemplar.
	 */
	record Entry(ItemStack exemplar, long amount) {

		public Entry {
			Objects.requireNonNull(exemplar, "exemplar");
			if (amount < 0) throw new IllegalArgumentException("negative item amount");
			if (amount == 0) {
				exemplar = ItemStack.EMPTY;
			} else if (exemplar.isEmpty()) {
				throw new IllegalArgumentException("non-empty amount requires an item exemplar");
			}
		}

		public static Entry empty() {
			return new Entry(ItemStack.EMPTY, 0L);
		}

		public boolean isEmpty() {
			return amount == 0L;
		}
	}

	/**
	 * One detached, bounded enumeration result. {@code complete} is true only
	 * when the whole source was traversed within the call's limit.
	 */
	record Budgeted(List<Entry> entries, boolean complete) {

		public Budgeted {
			Objects.requireNonNull(entries, "entries");
			entries = List.copyOf(entries);
		}
	}

	/**
	 * Ephemeral input to an immediately invoked item guard, not a detached value.
	 * Never retain, compare, hash or format this record or its resident item.
	 * The independent provider amount is not normalized to a Minecraft stack count.
	 */
	record ResidentEntry(@org.jetbrains.annotations.Nullable InventoryResidentItem item, long amount) {
		public ResidentEntry {
			if (amount < 0) throw new IllegalArgumentException("negative item amount");
			if ((amount == 0) != (item == null))
				throw new IllegalArgumentException("zero amount requires null item; positive amount requires an item");
		}
		public static ResidentEntry empty() { return new ResidentEntry(null, 0L); }
	}

	@FunctionalInterface
	interface ResidentVisitor {
		/** Consume/freeze synchronously; no provider metadata callback may precede this handoff. */
		boolean visit(ResidentEntry entry);
	}

	/** Counts consumed slots/views, including empties, not just non-empty items. */
	record ResidentVisit(int visited, boolean complete) {
		public ResidentVisit {
			if (visited < 0) throw new IllegalArgumentException("negative resident visit count");
		}
	}

	/**
	 * Read-only access to one physical source. The access, the provider objects
	 * behind it, and every returned stack stay confined to the server thread.
	 */
	interface Access {
		/** Source-owned evidence over an already resolved member set; never discovery. */
		default Access scopedResidents(ResidentScope scope) { Objects.requireNonNull(scope); return this; }
		/**
		 * Synchronous member guard for this operation's reacquired access. Native
		 * providers override to guard inside composite reads and cursorless iteration.
		 * The default preserves metadata and indexed reads; cursorless enumeration
		 * fails closed because a callback after an entry cannot authorize its read.
		 */
		default Access guardedBy(BooleanSupplier memberGate) { return new GuardedAccess(this, memberGate); }
		/** Canonical controller alias, only when established without force-loading. */
		default Optional<String> alias() { return Optional.empty(); }
		/**
		 * Optional detached description of an explicitly supported multipart NBT
		 * snapshot. This DTO contains positions and slot mappings only; it never
		 * carries live block entities or widens this access's frozen side.
		 */
		default Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.empty(); }
		/** Reacquire live provider segments on validation/read when the provider requires it. */
		default boolean valid() { return true; }
		/** Counts every visited view, including blanks; implementations override cursorless enumeration. */
		default Budgeted observe(int limit) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			List<Entry> entries = new ArrayList<>();
			if (stableCursor()) {
				int count = slots();
				for (int i = 0; i < Math.min(count, limit); i++) entries.add(read(i));
				return new Budgeted(entries, count <= limit);
			}
			throw new UnsupportedOperationException("cursorless observation must account blank views");
		}

		/**
		 * Number of stable slots, or {@code 0} when the source has no stable
		 * slot view. Never negative.
		 */
		int slots();

		/**
		 * True when {@link #read(int)} addresses stable slots across calls.
		 * False when the source can only be enumerated by {@link #visit} and
		 * each pass is a fresh, possibly shifted observation.
		 */
		boolean stableCursor();

		/**
		 * Provider change counter when the provider offers one; empty when the
		 * provider exposes none. Values are only comparable for this same
		 * access instance.
		 */
		OptionalLong version();

		/**
		 * Detached read of one stable slot. Only valid when
		 * {@link #stableCursor()} is true; an empty slot reports
		 * {@link Entry#empty()}.
		 */
		Entry read(int slot);

		/**
		 * No detached-read bridge: that would copy/convert before the item guard.
		 * A caller must admit and charge each attempted getter even if it throws.
		 * Unverified implementations remain LIVE under EXTERNAL_TRUST: only their
		 * invocation count is bounded, not their internal work or allocation.
		 * This compatibility return can already be stale after provider postchecks;
		 * consumers needing getter-time state use {@link #consumeResident} instead.
		 */
		default ResidentEntry readResident(int slot) {
			throw new UnsupportedOperationException("provider has no resident reader");
		}

		/**
		 * Consume the borrowed item immediately after the final content getter and
		 * authoritative amount read, before any provider postcheck. The visitor
		 * must detach everything it needs; its result is provisional until this
		 * call and the caller's final authority checks succeed. Native providers
		 * with postchecks override this compatibility default inside their read.
		 */
		default boolean consumeResident(int slot, ResidentVisitor visitor) {
			return Objects.requireNonNull(visitor, "visitor").visit(readResident(slot));
		}

		/**
		 * Indexed immediate-consumption default. Cursorless providers must gate
		 * iterator/hasNext/next handoffs themselves; no resident list is built.
		 * Visitor refusal is not proof of completeness, even on the last slot.
		 */
		default ResidentVisit observeResident(int limit, ResidentVisitor visitor) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			Objects.requireNonNull(visitor, "visitor");
			if (!stableCursor()) throw new UnsupportedOperationException("cursorless provider requires a resident observer");
			int count = slots();
			if (count < 0) throw new IllegalStateException("provider reported a negative slot count");
			int bound = Math.min(count, limit);
			for (int slot = 0; slot < bound; slot++) {
				if (!consumeResident(slot, visitor)) return new ResidentVisit(slot + 1, false);
			}
			return new ResidentVisit(bound, count <= limit);
		}

		/**
		 * Only an audited read graph may opt in. This is not atomicity evidence or
		 * permission to bypass member, topology, lock, loot or admission gates.
		 */
		default boolean supportsDetachedSlotCapture() { return false; }

		/**
		 * One bounded pass that visits at most {@code limit} non-empty entries
		 * and returns true only when the source was fully traversed inside that
		 * limit. A later call starts a new pass and is not promised the same
		 * views when {@link #stableCursor()} is false.
		 */
		boolean visit(int limit, Consumer<Entry> consumer);

		/**
		 * Detached convenience form of one {@link #visit} pass; the returned
		 * list holds at most {@code limit} entries.
		 */
		default Budgeted readBudgeted(int limit) {
			List<Entry> entries = new ArrayList<>();
			boolean complete = visit(limit, entries::add);
			return new Budgeted(entries, complete);
		}
	}
	interface ResidentScope {
		Direction face();
		/** Precheck every already resolved member, including lock/loot/mapping. */
		boolean valid();
		/** Only members already admitted by the source, not arbitrary wrapper delegates. */
		boolean allows(Container container);
		boolean audited(Container container);
		/** Frozen local mapping, null when no bounded member mapping was resolved. */
		List<Integer> slots(Container container);
	}
	/** Default indexed decorator; never retained by an external source across operations. */
	final class GuardedAccess implements Access {
		private final Access delegate;
		private final BooleanSupplier memberGate;
		GuardedAccess(Access delegate, BooleanSupplier memberGate) {
			this.delegate = Objects.requireNonNull(delegate); this.memberGate = Objects.requireNonNull(memberGate);
		}
		private void requireMember() { if (!memberGate.getAsBoolean()) throw new InventorySourceUnavailableException("inventory access outside the read scope"); }
		private <T> T checked(java.util.function.Supplier<T> operation) {
			requireMember(); T value = operation.get(); requireMember(); return value;
		}
		@Override public Optional<String> alias() { return checked(delegate::alias); }
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return checked(delegate::snapshotLayout); }
		@Override public boolean valid() {
			try { return checked(delegate::valid); }
			catch (RuntimeException | LinkageError unavailable) {
				if (!(unavailable instanceof InventorySourceUnavailableException))
					Diagnostics.warn("inventory-guarded-access-valid", delegate.getClass().getName(),
						() -> "delegateClass=" + delegate.getClass().getName() + " gateClass=" + memberGate.getClass().getName(), unavailable);
				return false;
			}
		}
		@Override public int slots() { return checked(delegate::slots); }
		@Override public boolean stableCursor() { return checked(delegate::stableCursor); }
		@Override public OptionalLong version() { return checked(delegate::version); }
		@Override public Entry read(int slot) { return checked(() -> delegate.read(slot)); }
		@Override public ResidentEntry readResident(int slot) { return checked(() -> delegate.readResident(slot)); }
		@Override public boolean consumeResident(int slot, ResidentVisitor visitor) {
			Objects.requireNonNull(visitor, "visitor");
			requireMember();
			boolean more = delegate.consumeResident(slot, visitor);
			requireMember(); return more;
		}
		@Override public Access scopedResidents(ResidentScope scope) { return checked(() -> delegate.scopedResidents(scope)).guardedBy(memberGate); }
		@Override public boolean supportsDetachedSlotCapture() { return checked(delegate::supportsDetachedSlotCapture); }
		@Override public ResidentVisit observeResident(int limit, ResidentVisitor visitor) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			Objects.requireNonNull(visitor, "visitor");
			if (!stableCursor()) throw new UnsupportedOperationException("cursorless provider requires internal member gates");
			// Re-enumerate through this decorator's indexed reads: delegating the
			// whole loop could skip the gate between delegate slots/read calls.
			ResidentVisit observed = Access.super.observeResident(limit, visitor);
			requireMember(); return observed;
		}
		@Override public Budgeted observe(int limit) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			if (!stableCursor()) throw new UnsupportedOperationException("cursorless provider requires internal member gates");
			Budgeted observed = Access.super.observe(limit); requireMember(); return observed;
		}
		@Override public boolean visit(int limit, Consumer<Entry> consumer) {
			Objects.requireNonNull(consumer); if (limit < 0) throw new IllegalArgumentException("negative visit limit");
			if (!stableCursor()) throw new UnsupportedOperationException("cursorless provider requires internal member gates");
			int count = slots();
			for (int slot = 0; slot < Math.min(count, limit); slot++) {
				Entry entry = read(slot); if (!entry.isEmpty()) consumer.accept(entry);
			}
			requireMember(); return count <= limit;
		}
	}

	/** Fixed, read-only implementation checks; never invokes a candidate getter. */
	final class ResidentReads {
		private static final int MAX_INHERITANCE_DEPTH = 32;
		public static final int MAX_TYPE_NAME_CHARS = 512, MAX_MEMBER_NAME_CHARS = 128, MAX_REFLECTION_PARAMETERS = 2;
		private static final Class<?> ARRAY_LIST_VIEW = java.util.Arrays.asList().getClass();
		private static final class NativeAccess {
			static final nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess INSTANCE =
				nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess.load();
		}
		private ResidentReads() {}
		/** Before reflection can allocate a missing-member signature/message. */
		public static boolean reflectionShape(Class<?> type, String name, Class<?>... arguments) {
			if (type == null || type.getName().length() > MAX_TYPE_NAME_CHARS || name == null
				|| name.length() > MAX_MEMBER_NAME_CHARS || arguments.length > MAX_REFLECTION_PARAMETERS)
				return false;
			for (int index = 0; index < arguments.length; index++)
				if (arguments[index] == null || arguments[index].getName().length() > MAX_TYPE_NAME_CHARS)
					return false;
			return true;
		}
		public static void requireReflectionShape(Class<?> type, String name, Class<?>... arguments) {
			if (!reflectionShape(type, name, arguments)) throw new IllegalStateException("inventory reflection metadata bound");
		}

		/**
		 * Bounded ownership evidence for one fixed library method. Only
		 * {@link #PROVEN_INHERITED} and {@link #PROVEN_OVERRIDE} are proofs about
		 * the concrete declaration; {@link #UNCLASSIFIED} is refusal, never an
		 * override proof, so no authority path may route content on it.
		 */
		public enum MethodOwnership { PROVEN_INHERITED, PROVEN_OVERRIDE, UNCLASSIFIED }

		/**
		 * First declaration over the concrete chain within the fixed depth/name
		 * bounds. A refused shape, absent declaration or security failure stays
		 * {@link MethodOwnership#UNCLASSIFIED} before any signature allocation.
		 */
		public static MethodOwnership methodOwnership(Object value, Class<?> owner, String name, Class<?>... arguments) {
			@org.jetbrains.annotations.Nullable Class<?> type = value.getClass();
			for (int depth = 0; type != null && depth < MAX_INHERITANCE_DEPTH; depth++, type = type.getSuperclass()) {
				if (!reflectionShape(type, name, arguments)) return MethodOwnership.UNCLASSIFIED;
				try {
					Class<?> declaring = type.getDeclaredMethod(name, arguments).getDeclaringClass();
					return declaring == owner ? MethodOwnership.PROVEN_INHERITED : MethodOwnership.PROVEN_OVERRIDE;
				} catch (NoSuchMethodException absent) { /* continue through the bounded parent chain */ }
				catch (SecurityException unavailable) {
					Diagnostics.warn("inventory-resident-method", owner.getName(), () -> "method=" + name + " valueClass=" + value.getClass().getName(), unavailable);
					return MethodOwnership.UNCLASSIFIED;
				}
			}
			return MethodOwnership.UNCLASSIFIED;
		}

		/**
		 * Compatibility boolean for callers that only need proven inheritance:
		 * includes protected virtual helpers, not just the final public getter.
		 * Refusal and a proven override both stay false. Safety-sensitive
		 * consumers must branch on {@link #methodOwnership} instead of reading
		 * false as an override proof.
		 */
		public static boolean inherits(Object value, Class<?> owner, String name, Class<?>... arguments) {
			return methodOwnership(value, owner, name, arguments) == MethodOwnership.PROVEN_INHERITED;
		}

		/** Only callers naming an inspected fixed library field may use this port. */
		public static @org.jetbrains.annotations.Nullable Object field(Object value, Class<?> owner, String name, Class<?> fieldType) {
			if (!reflectionShape(owner, name)) return null;
			try {
				var field = owner.getDeclaredField(name);
				if (field.getType() != fieldType || !field.trySetAccessible()) return null;
				return field.get(value);
			} catch (NoSuchFieldException absent) { return null; }
			catch (ReflectiveOperationException | RuntimeException unavailable) {
				Diagnostics.warn("inventory-resident-field", owner.getName(), () -> "field=" + name + " valueClass=" + value.getClass().getName(), unavailable);
				return null;
			}
		}

		/** size/get are constant-index native operations only for these exact backings. */
		public static boolean nativeStacks(@org.jetbrains.annotations.Nullable Object value) {
			return nativeStacks(value, NativeAccess.INSTANCE);
		}
		/** Same exact backing check through an injected raw-field port for headless tests. */
		public static boolean nativeStacks(@org.jetbrains.annotations.Nullable Object value,
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess raw) {
			if (value == null || value.getClass() != net.minecraft.core.NonNullList.class) return false;
			try {
				@org.jetbrains.annotations.Nullable List<?> list = raw.nonNullList((net.minecraft.core.NonNullList<?>) value);
				return list != null && (list.getClass() == ArrayList.class || list.getClass() == ARRAY_LIST_VIEW);
			} catch (RuntimeException | LinkageError unavailable) {
				Diagnostics.warn("inventory-resident-stacks", raw.getClass().getName(), () -> "valueClass=" + value.getClass().getName(), unavailable);
				return false;
			}
		}
		/** No copying, construction, component access or hashing before the item guard. */
		public static ResidentEntry stack(ItemStack stack) {
			if (stack == null) throw new IllegalStateException("provider returned a null item stack");
			long amount = stack.getCount();
			if (amount < 0) throw new IllegalStateException("provider reported a negative item count");
			return amount == 0 ? ResidentEntry.empty() : new ResidentEntry(new InventoryResidentItem.Stack(stack), amount);
		}
	}
}
