package nx.pingwheel.common.presentation.minecraft;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.name.TargetNameJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationServerExternalBlockTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void detachedExternalTargetCarriesOnlyItsTypedCommittedIdentity() {
		Target.ExternalBlockTarget target = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "stable-id", "minecraft:chest", "opaque-locator", true);

		PresentationAdapter.DetachedTarget detached = PresentationServer.detached(target);

		assertEquals("minecraft:overworld", detached.dimension());
		assertEquals("block", detached.kind());
		assertEquals("minecraft:chest", detached.registryId());
		assertEquals(0, detached.x());
		assertEquals(0, detached.y());
		assertEquals(0, detached.z());
		assertEquals("", detached.locator());
		assertSame(target, detached.externalBlock());
	}

	@Test
	void externalBasicReadsOnlyDemandedStateAndUsesProviderName() {
		PresentationSection section = PresentationServer.assembleExternalBasic(
			Set.of(PresentationBasic.BLOCK_STATE), Blocks.CHEST.defaultBlockState(), null);
		assertTrue(section.fields().get(PresentationBasic.BLOCK_STATE) instanceof PresentationValue.RecordValue);
		assertEquals(new PresentationValue.Text("north"),
			((PresentationValue.RecordValue) section.fields().get(PresentationBasic.BLOCK_STATE)).values().get("facing"));
	}

	@Test
	void externalBasicNameOnlyNeverObservesBlockState() {
		AtomicBoolean observed = new AtomicBoolean();
		PresentationSection section = PresentationServer.basicExternal(Set.of(PresentationBasic.NAME),
			() -> { observed.set(true); return new ExternalBlockServerProvider.ObservationResult.Invalid(); },
			() -> new TargetNameJson("{\"text\":\"Target\"}"));

		assertFalse(observed.get());
		assertEquals(new PresentationValue.Text("{\"text\":\"Target\"}"),
			section.fields().get(PresentationBasic.NAME));
		assertFalse(section.fields().containsKey(PresentationBasic.BLOCK_STATE));
	}

	@Test
	void demandedExternalStateFailureDoesNotCreateFreshEmptyBasicAndStaleRetentionKeepsPreviousValues() {
		AtomicBoolean named = new AtomicBoolean();
		assertNull(PresentationServer.basicExternal(Set.of(PresentationBasic.BLOCK_STATE, PresentationBasic.NAME),
			() -> new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable(),
			() -> { named.set(true); return new TargetNameJson("{\"text\":\"Name\"}"); }));
		assertFalse(named.get(), "failed state observation must abort the whole demanded Basic section");

		PresentationSection previous = new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.BLOCK_STATE, new PresentationValue.RecordValue(Map.of("facing",
				new PresentationValue.Text("north"))), PresentationBasic.NAME, new PresentationValue.Text("Old")), false);
		PresentationSection stale = PresentationServer.retainStale(PresentationBasic.ID, 1,
			previous, Set.of(PresentationBasic.BLOCK_STATE));
		assertTrue(stale.stale());
		assertEquals(previous.fields().get(PresentationBasic.BLOCK_STATE), stale.fields().get(PresentationBasic.BLOCK_STATE));
		assertFalse(stale.fields().containsKey(PresentationBasic.NAME));
	}

	@Test
	void productionTransitionRetainsOnlyCurrentlyDemandedFieldsAfterFailedCapture() {
		PresentationSection previous = new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.BLOCK_STATE, new PresentationValue.Text("old-state"),
				PresentationBasic.NAME, new PresentationValue.Text("old-name")), false);
		PresentationSection captured = new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("new-name")), false);

		PresentationSection success = PresentationServer.transition(PresentationBasic.ID, 1,
			previous, Set.of(PresentationBasic.NAME), captured);
		PresentationSection failed = PresentationServer.transition(PresentationBasic.ID, 1,
			success, Set.of(PresentationBasic.NAME), null);
		PresentationSection demandRemoved = PresentationServer.transition(PresentationBasic.ID, 1,
			failed, Set.of(), null);

		assertEquals(captured, success);
		assertTrue(failed.stale());
		assertEquals(new PresentationValue.Text("new-name"), failed.fields().get(PresentationBasic.NAME));
		assertFalse(failed.fields().containsKey(PresentationBasic.BLOCK_STATE));
		assertTrue(demandRemoved.fields().isEmpty());
	}

	@Test
	void nullableExternalNameIsDefensivelyOmittedWithoutChangingStateAtomicity() {
		PresentationSection section = PresentationServer.basicExternal(Set.of(PresentationBasic.NAME),
			() -> { throw new AssertionError("state must not be observed"); }, () -> null);

		assertTrue(section.fields().isEmpty());
	}
}
