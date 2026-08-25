package nx.pingwheel.common.resolve;

import java.util.List;
import java.util.Objects;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.registry.OptionalRegistryRef;
import nx.pingwheel.common.registry.RegistryLookup;

/**
 * A reusable matcher whose matching set is an ordered list of optional registry
 * references resolved lazily through an injected {@link RegistryLookup}.
 *
 * <p>It supports the coarse {@link TargetKind#BLOCK} and
 * {@link TargetKind#ENTITY} kinds only. It is {@linkplain #isActive() active}
 * iff at least one referenced entry exists; missing entries are ignored. A
 * target matches iff its concrete identity equals a <em>present</em>
	 * reference: {@code BlockTarget.blockRegistryId} (or an external target's
	 * {@code expectedBlockRegistryId}) for block kinds, or
 * {@code TargetMatchContext.entityTypeId} for entity kinds.
 *
 * <p>No optional-mod class is referenced; absence of all referenced content
 * simply makes the matcher inactive rather than throwing.
 */
public final class RegistryBackedTargetMatcher implements TargetMatcher {

	/**
	 * The vanilla registry id expected for the {@link TargetKind#BLOCK} kind.
	 */
	private static final String BLOCK_REGISTRY = "minecraft:block";

	/**
	 * The vanilla registry id expected for the {@link TargetKind#ENTITY} kind.
	 */
	private static final String ENTITY_REGISTRY = "minecraft:entity_type";

	private final TargetKind kind;
	private final List<OptionalRegistryRef> refs;
	private final RegistryLookup lookup;

	public RegistryBackedTargetMatcher(TargetKind kind, List<OptionalRegistryRef> refs, RegistryLookup lookup) {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(lookup, "lookup");
		refs = List.copyOf(Objects.requireNonNull(refs, "refs"));

		if (kind == TargetKind.LOCATION) {
			throw new IllegalArgumentException("RegistryBackedTargetMatcher supports BLOCK and ENTITY kinds only");
		}

		if (refs.isEmpty()) {
			throw new IllegalArgumentException("refs must not be empty");
		}

		String expectedRegistryId = expectedRegistryId(kind);

		for (OptionalRegistryRef ref : refs) {
			if (!ref.registryId().equals(expectedRegistryId)) {
				throw new IllegalArgumentException(
					"registry id mismatch for " + kind + " kind: expected '" + expectedRegistryId
						+ "' but got '" + ref.registryId() + "'");
			}
		}

		this.kind = kind;
		this.refs = refs;
		this.lookup = lookup;
	}

	@Override
	public boolean isActive() {
		for (OptionalRegistryRef ref : refs) {
			if (ref.isPresent(lookup)) {
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean matches(Target target, TargetMatchContext context) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(context, "context");

		String entryId = matchingEntryId(target, context);

		if (entryId == null) {
			return false;
		}

		return presentEntryEquals(entryId);
	}

	/**
	 * Queries each referenced entry at most once, deriving both the active
	 * state and the match decision from the same observations so a registry
	 * whose answers change between calls cannot produce an inconsistent
	 * active-then-not-matched result.
	 */
	@Override
	public TargetMatchResult evaluate(Target target, TargetMatchContext context) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(context, "context");

		String entryId = matchingEntryId(target, context);
		boolean anyPresent = false;

		for (OptionalRegistryRef ref : refs) {
			if (ref.isPresent(lookup)) {
				anyPresent = true;

				if (entryId != null && ref.entryId().equals(entryId)) {
					return TargetMatchResult.MATCH;
				}
			}
		}

		return anyPresent ? TargetMatchResult.NO_MATCH : TargetMatchResult.INACTIVE;
	}

	/**
	 * The concrete entry id this matcher compares against for the given target
	 * and context, or {@code null} when the target cannot satisfy this kind
	 * (wrong target kind, or an entity target without an entity type id).
	 */
	private String matchingEntryId(Target target, TargetMatchContext context) {
		if (kind == TargetKind.BLOCK) {
			if (target instanceof Target.BlockTarget block) {
				return block.blockRegistryId();
			}

			return target instanceof Target.ExternalBlockTarget external
				? external.expectedBlockRegistryId()
				: null;
		}

		if (!(target instanceof Target.EntityTarget)) {
			return null;
		}

		return context.entityTypeId().orElse(null);
	}

	private boolean presentEntryEquals(String entryId) {
		for (OptionalRegistryRef ref : refs) {
			if (ref.entryId().equals(entryId) && ref.isPresent(lookup)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The expected vanilla registry id for the given coarse kind.
	 */
	private static String expectedRegistryId(TargetKind kind) {
		return switch (kind) {
			case BLOCK -> BLOCK_REGISTRY;
			case ENTITY -> ENTITY_REGISTRY;
			case LOCATION -> throw new IllegalArgumentException(
				"RegistryBackedTargetMatcher supports BLOCK and ENTITY kinds only");
		};
	}
}
