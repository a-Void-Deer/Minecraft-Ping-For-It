package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAwareBlockModelOutlineAdapterRegistryTest {
	@Test
	void registrationOrderDuplicateAndCloseAreDeterministic() {
		WorldAwareBlockModelOutlineAdapterRegistry registry =
			new WorldAwareBlockModelOutlineAdapterRegistry();
		WorldAwareBlockModelOutlineAdapter first = adapter("mod:first");
		WorldAwareBlockModelOutlineAdapter second = adapter("mod:second");
		WorldAwareBlockModelOutlineAdapter duplicate = adapter("mod:first");

		WorldAwareBlockModelOutlineAdapterRegistry.Registration firstHandle = registry.register(first);
		WorldAwareBlockModelOutlineAdapterRegistry.Registration secondHandle = registry.register(second);
		WorldAwareBlockModelOutlineAdapterRegistry.Registration rejected = registry.register(duplicate);

		assertTrue(firstHandle.accepted());
		assertTrue(secondHandle.accepted());
		assertTrue(!rejected.accepted());
		assertEquals(List.of(first, second), registry.snapshot());

		rejected.close();
		firstHandle.close();
		firstHandle.close();
		assertTrue(firstHandle.accepted());
		assertEquals(List.of(second), registry.snapshot());

		secondHandle.close();
		assertEquals(List.of(), registry.snapshot());
	}

	@Test
	void snapshotIsImmutableAndInvalidIdsDoNotRegister() {
		WorldAwareBlockModelOutlineAdapterRegistry registry =
			new WorldAwareBlockModelOutlineAdapterRegistry();
		WorldAwareBlockModelOutlineAdapter first = adapter("mod:first");
		WorldAwareBlockModelOutlineAdapterRegistry.Registration handle = registry.register(first);

		assertEquals(List.of(first), registry.snapshot());
		org.junit.jupiter.api.Assertions.assertThrows(
			UnsupportedOperationException.class,
			() -> registry.snapshot().add(adapter("mod:second")));
		assertTrue(!registry.register(invalidAdapter()).accepted());
		assertEquals(List.of(first), registry.snapshot());

		handle.close();
	}

	@Test
	void unhandledKeepsVirtualPathAndEveryClaimedOutcomeSkipsIt() {
		for (WorldAwareBlockModelOutlineOutcome outcome : WorldAwareBlockModelOutlineOutcome.values()) {
			AtomicBoolean virtualAttempted = new AtomicBoolean();
			EntityBlockGeometryOutcome result =
				VirtualBlockDisplayRenderer.applyWorldAwareBakedModelOutcome(
					outcome,
					() -> {
						virtualAttempted.set(true);
						return EntityBlockGeometryOutcome.RENDERED;
					});

			if (outcome == WorldAwareBlockModelOutlineOutcome.UNHANDLED) {
				assertTrue(virtualAttempted.get());
				assertEquals(EntityBlockGeometryOutcome.RENDERED, result);
			} else {
				assertTrue(!virtualAttempted.get());
				assertEquals(
					EntityBlockGeometryOutcome.valueOf(outcome.name()),
					result);
			}
		}
	}

	@Test
	void failedClaimCheckIsSkippedBeforeTheNextAdapterIsAsked() {
		WorldAwareBlockModelOutlineAdapter failedFirst =
			WorldAwareBlockModelOutlineAdapter.of(
				"mod:failed-first",
				ignored -> {
					throw new IllegalStateException("claim check failed");
				},
				(ignoredContext, ignoredBuffer) -> {
					throw new AssertionError("failed adapter must not render");
				});
		WorldAwareBlockModelOutlineAdapter second = claimingAdapter("mod:second");
		AtomicBoolean attempted = new AtomicBoolean();

		WorldAwareBlockModelOutlineOutcome outcome =
			VirtualBlockDisplayRenderer.attemptWorldAwareBakedModel(
				List.of(failedFirst, second),
				EntityBlockGeometryContext.empty(),
				claimed -> {
					attempted.set(true);
					assertSame(second, claimed);
					return WorldAwareBlockModelOutlineOutcome.RENDERED;
				});

		assertTrue(attempted.get());
		assertEquals(WorldAwareBlockModelOutlineOutcome.RENDERED, outcome);
	}

	@Test
	void failedOrFalseClaimChecksLeaveTheSeamUnhandled() {
		WorldAwareBlockModelOutlineAdapter failed =
			WorldAwareBlockModelOutlineAdapter.of(
				"mod:failed",
				ignored -> {
					throw new AssertionError("claim check failed");
				},
				(ignoredContext, ignoredBuffer) -> {
					throw new AssertionError("failed adapter must not render");
				});
		WorldAwareBlockModelOutlineAdapter unhandled = adapter("mod:unhandled");
		AtomicBoolean attempted = new AtomicBoolean();

		WorldAwareBlockModelOutlineOutcome outcome =
			VirtualBlockDisplayRenderer.attemptWorldAwareBakedModel(
				List.of(failed, unhandled),
				EntityBlockGeometryContext.empty(),
				claimed -> {
					attempted.set(true);
					return WorldAwareBlockModelOutlineOutcome.RENDERED;
				});

		assertTrue(!attempted.get());
		assertEquals(WorldAwareBlockModelOutlineOutcome.UNHANDLED, outcome);
	}

	private static WorldAwareBlockModelOutlineAdapter adapter(String id) {
		return WorldAwareBlockModelOutlineAdapter.of(
			id,
			ignored -> false,
			(ignoredContext, ignoredBuffer) -> {
				// no-op
			});
	}

	private static WorldAwareBlockModelOutlineAdapter claimingAdapter(String id) {
		return WorldAwareBlockModelOutlineAdapter.of(
			id,
			ignored -> true,
			(ignoredContext, ignoredBuffer) -> {
				// no-op
			});
	}

	private static WorldAwareBlockModelOutlineAdapter invalidAdapter() {
		return new WorldAwareBlockModelOutlineAdapter() {
			@Override
			public String id() {
				return "not a resource id";
			}

			@Override
			public boolean handles(EntityBlockGeometryContext context) {
				return false;
			}

			@Override
			public void render(EntityBlockGeometryContext context, OutlineOnlyBufferSource buffer) {
				// no-op
			}
		};
	}
}
