package nx.pingwheel.common.presentation.minecraft;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.name.TargetNameJsonCodec;

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

	private static PresentationServer.ExternalNameObservation name(String text) {
		return new PresentationServer.ExternalNameObservation(new TargetNameJson("{\"text\":\"" + text + "\"}"), null);
	}

	private static PresentationServer.ExternalNameObservation providerNames(Set<String> demand, Optional<Component> custom) {
		return PresentationServer.externalName(Optional.of(new ExternalBlockServerProvider.ExternalBlockName(
			Component.translatable("block.minecraft.chest"), custom)), RegistryAccess.EMPTY, null, demand);
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
			() -> name("Target"));

		assertFalse(observed.get());
		assertEquals(new PresentationValue.Text("{\"text\":\"Target\"}"),
			section.fields().get(PresentationBasic.NAME));
		assertFalse(section.fields().containsKey(PresentationBasic.BLOCK_STATE));
	}

	@Test
	void demandedExternalStateFailureDoesNotCreateFreshEmptyBasicAndStaleRetentionKeepsPreviousValues() {
		AtomicBoolean named = new AtomicBoolean();
		assertNull(PresentationServer.basicExternal(Set.of(PresentationBasic.BLOCK_STATE, PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME),
			() -> new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable(),
			() -> { named.set(true); return providerNames(Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME), Optional.of(Component.literal("Custom"))); }));
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
	void basicPreviewUsesOnlyTheOriginalCandidateBindingAndAdmittedState() {
		Target.ExternalBlockTarget candidate = Target.ExternalBlockTarget.candidate(
			"minecraft:overworld", "sable", "minecraft:chest", "opaque-locator", false);
		BlockReadSource source = new BlockReadSource(candidate, "sable", "sublevel-id",
			new Target.BlockTarget(candidate.dimensionId(), 100, 64, -200, "minecraft:chest"), new MarkerAnchor(5, 6, 7));
		PresentationSection section = PresentationServer.basicPreview(source, candidate,
			Blocks.CHEST.defaultBlockState(), Set.of(PresentationBasic.BLOCK_STATE, PresentationBasic.NAME),
			() -> name("Chest"));
		assertTrue(section.fields().get(PresentationBasic.BLOCK_STATE) instanceof PresentationValue.RecordValue);
		assertEquals(new PresentationValue.Text("{\"text\":\"Chest\"}"), section.fields().get(PresentationBasic.NAME));
		assertFalse(section.stale());

		// Committed targets keep the lease-gated observe route, never the preview assembly.
		Target.ExternalBlockTarget committed = Target.ExternalBlockTarget.committed(
			candidate.dimensionId(), "sable", "stable", "minecraft:chest", "opaque-locator", false);
		BlockReadSource committedSource = new BlockReadSource(committed, "sable", "sublevel-id",
			new Target.BlockTarget(committed.dimensionId(), 100, 64, -200, "minecraft:chest"), new MarkerAnchor(5, 6, 7));
		assertNull(PresentationServer.basicPreview(committedSource, committed, Blocks.CHEST.defaultBlockState(),
			Set.of(PresentationBasic.BLOCK_STATE), () -> null));
		// A different candidate locator never reuses another candidate's observation.
		Target.ExternalBlockTarget other = Target.ExternalBlockTarget.candidate(
			candidate.dimensionId(), "sable", "minecraft:chest", "other-locator", false);
		BlockReadSource otherSource = new BlockReadSource(other, "sable", "sublevel-id",
			new Target.BlockTarget(other.dimensionId(), 100, 64, -200, "minecraft:chest"), new MarkerAnchor(5, 6, 7));
		assertNull(PresentationServer.basicPreview(otherSource, candidate, Blocks.CHEST.defaultBlockState(),
			Set.of(PresentationBasic.BLOCK_STATE), () -> null));
		// A demanded state without an admitted observation is unavailable.
		assertNull(PresentationServer.basicPreview(source, candidate, null,
			Set.of(PresentationBasic.BLOCK_STATE), () -> null));
		// Name-only demand never requires or fabricates a state.
		PresentationSection nameOnly = PresentationServer.basicPreview(source, candidate, null,
			Set.of(PresentationBasic.NAME), () -> name("Name"));
		assertEquals(new PresentationValue.Text("{\"text\":\"Name\"}"), nameOnly.fields().get(PresentationBasic.NAME));
		assertFalse(nameOnly.fields().containsKey(PresentationBasic.BLOCK_STATE));
	}

	@Test void candidateAndCommittedNamesShareOneObservationAndCustomOnlyDoesNotPublishName() {
		Target.ExternalBlockTarget candidate = Target.ExternalBlockTarget.candidate(
			"minecraft:overworld", "sable", "minecraft:chest", "opaque-locator", false);
		BlockReadSource source = new BlockReadSource(candidate, "sable", "sublevel-id",
			new Target.BlockTarget(candidate.dimensionId(), 100, 64, -200, "minecraft:chest"), new MarkerAnchor(5, 6, 7));
		String raw = "Provider (literal) \"custom\"";
		for (boolean preview : new boolean[] {false, true}) {
			for (Set<String> demand : java.util.List.of(Set.of(PresentationBasic.CUSTOM_NAME),
				Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME))) {
				int[] reads = {0};
				java.util.function.Supplier<PresentationServer.ExternalNameObservation> names = () -> {
					reads[0]++;
					return providerNames(demand, Optional.of(Component.literal(raw).withStyle(style -> style.withItalic(true))));
				};
				PresentationSection section = preview ? PresentationServer.basicPreview(source, candidate, null, demand, names)
					: PresentationServer.basicExternal(demand, () -> { throw new AssertionError("name-only state read"); }, names);
				assertEquals(1, reads[0], "both name fields must reuse one provider resolution");
				assertEquals(demand, section.fields().keySet());
				assertEquals(new PresentationValue.Text(raw), section.fields().get(PresentationBasic.CUSTOM_NAME));
				if (demand.contains(PresentationBasic.NAME)) {
					Component composed = TargetNameJsonCodec.decode(new TargetNameJson(
						((PresentationValue.Text) section.fields().get(PresentationBasic.NAME)).value()), RegistryAccess.EMPTY);
					assertEquals(raw, ((net.minecraft.network.chat.contents.PlainTextContents.LiteralContents) composed.getContents()).text());
					assertEquals(3, composed.getSiblings().size(), "composed name must keep its base suffix");
				}
			}
		}
		// A custom-only observation does not touch the registry-bound NAME encoder.
		assertEquals(raw, PresentationServer.externalName(Optional.of(new ExternalBlockServerProvider.ExternalBlockName(
			Component.translatable("block.minecraft.chest"), Optional.of(Component.literal(raw)))), null, null,
			Set.of(PresentationBasic.CUSTOM_NAME)).customName());
	}

	@Test void absentEmptyAndFailedProviderNamesNeverFabricateCustomText() {
		Set<String> demand = Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME);
		for (Optional<Component> custom : java.util.List.of(Optional.<Component>empty(), Optional.<Component>of(Component.empty()))) {
			var section = PresentationServer.assembleExternalBasic(demand, null, providerNames(demand, custom));
			assertFalse(section.fields().containsKey(PresentationBasic.CUSTOM_NAME));
			assertTrue(section.fields().containsKey(PresentationBasic.NAME), "the composed/base name remains independent");
		}
		var unavailable = PresentationServer.externalName(Optional.empty(), RegistryAccess.EMPTY,
			TargetNameJsonCodec.UNKNOWN, Set.of(PresentationBasic.CUSTOM_NAME));
		assertNull(unavailable.composedName(), "masked NAME must not gain even the committed fallback");
		assertTrue(PresentationServer.assembleExternalBasic(Set.of(PresentationBasic.CUSTOM_NAME), null, unavailable).fields().isEmpty());
		assertTrue(PresentationServer.assembleExternalBasic(demand, null,
			PresentationServer.externalName(Optional.empty(), RegistryAccess.EMPTY, null, demand)).fields().isEmpty());
		assertTrue(PresentationServer.basicExternal(Set.of(PresentationBasic.BLOCK_STATE),
			() -> new ExternalBlockServerProvider.ObservationResult.Available(new ExternalBlockServerProvider.BlockObservation(
				new Object(), net.minecraft.core.BlockPos.ZERO, Blocks.CHEST.defaultBlockState())),
			() -> { throw new AssertionError("undemanded provider name read"); }).fields().containsKey(PresentationBasic.BLOCK_STATE));
	}

	@Test void nameableBlockUsesActualCustomTextAndSkipsBlockEntityReadForStateOnly() {
		var state = Blocks.CHEST.defaultBlockState();
		var chest = new net.minecraft.world.level.block.entity.ChestBlockEntity(net.minecraft.core.BlockPos.ZERO, state);
		int[] reads = {0};
		Set<String> demand = Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME);
		chest.applyComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_NAME,
			Component.literal("Chest (literal) \"custom\"").withStyle(style -> style.withItalic(true))).build(), DataComponentPatch.EMPTY);
		var both = PresentationServer.basicBlock(demand, state, () -> { reads[0]++; return chest; },
			component -> TargetNameJsonCodec.encode(component, RegistryAccess.EMPTY).value());
		assertEquals(1, reads[0]);
		assertEquals(new PresentationValue.Text("Chest (literal) \"custom\""), both.fields().get(PresentationBasic.CUSTOM_NAME));
		Component composed = TargetNameJsonCodec.decode(new TargetNameJson(((PresentationValue.Text) both.fields().get(PresentationBasic.NAME)).value()), RegistryAccess.EMPTY);
		assertEquals(3, composed.getSiblings().size());
		assertTrue(composed.getStyle().isEmpty());
		var customOnly = PresentationServer.basicBlock(Set.of(PresentationBasic.CUSTOM_NAME), state, () -> chest,
			component -> { throw new AssertionError("masked NAME encoding"); });
		assertEquals(Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Chest (literal) \"custom\"")), customOnly.fields());
		chest.applyComponents(DataComponentMap.EMPTY, DataComponentPatch.EMPTY);
		assertTrue(PresentationServer.basicBlock(Set.of(PresentationBasic.CUSTOM_NAME), state, () -> chest,
			component -> "unused").fields().isEmpty(), "Nameable's ordinary base name is not custom");
		chest.applyComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_NAME, Component.empty()).build(), DataComponentPatch.EMPTY);
		assertTrue(PresentationServer.basicBlock(Set.of(PresentationBasic.CUSTOM_NAME), state, () -> chest,
			component -> "unused").fields().isEmpty());
		assertTrue(PresentationServer.basicBlock(Set.of(PresentationBasic.CUSTOM_NAME), state, Object::new,
			component -> "unused").fields().isEmpty());
		assertTrue(PresentationServer.basicBlock(Set.of(PresentationBasic.BLOCK_STATE), state,
			() -> { throw new AssertionError("undemanded block entity read"); }, component -> "unused")
			.fields().containsKey(PresentationBasic.BLOCK_STATE));
	}

	@Test
	void nullableExternalNameIsDefensivelyOmittedWithoutChangingStateAtomicity() {
		PresentationSection section = PresentationServer.basicExternal(Set.of(PresentationBasic.NAME),
			() -> { throw new AssertionError("state must not be observed"); }, () -> null);

		assertTrue(section.fields().isEmpty());
	}
}
