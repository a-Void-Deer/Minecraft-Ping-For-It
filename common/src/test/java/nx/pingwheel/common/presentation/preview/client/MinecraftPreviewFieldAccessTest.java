package nx.pingwheel.common.presentation.preview.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.ClientPresentationPreview;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftPreviewFieldAccessTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final class Pig extends LivingEntity {
		int healthReads, customReads;
		boolean customUnavailable;
		Pig() { super(EntityType.PIG, null); }
		@Override public float getHealth() { healthReads++; return super.getHealth(); }
		@Override public Component getCustomName() {
			customReads++;
			if (customUnavailable) throw new IllegalStateException("custom name unavailable");
			return super.getCustomName();
		}
		@Override public void readAdditionalSaveData(CompoundTag tag) {}
		@Override public void addAdditionalSaveData(CompoundTag tag) {}
		@Override public Iterable<ItemStack> getArmorSlots() { return List.of(); }
		@Override public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }
		@Override public void setItemSlot(EquipmentSlot slot, ItemStack stack) {}
		@Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
	}
	private static PresentationValue value(PreviewFieldAccess.Outcome outcome) {
		return ((PreviewFieldAccess.Observed) outcome).observation().value();
	}
	@Test void realEntityNonDefaultSyncedHealthAndDemandBeforeGetters() {
		Pig pig = new Pig(); pig.setHealth(7.5F); pig.setCustomName(Component.literal("Example"));
		pig.healthReads = pig.customReads = 0;
		var typeOnly = MinecraftPreviewFieldAccess.observeEntity(pig, Set.of(PresentationBasic.ENTITY_TYPE), 4,
			name -> { fail("denied name must not encode"); return ""; });
		assertEquals(new PresentationValue.Text("minecraft:pig"), value(typeOnly.get(PresentationBasic.ENTITY_TYPE)));
		assertEquals(0, pig.healthReads); assertEquals(0, pig.customReads);
		var health = MinecraftPreviewFieldAccess.observeEntity(pig, Set.of(PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH), 4, name -> "unused");
		assertEquals(new PresentationValue.NumberValue(7.5), value(health.get(PresentationBasic.HEALTH)));
		assertEquals(new PresentationValue.NumberValue(pig.getMaxHealth()), value(health.get(PresentationBasic.MAX_HEALTH)));
		assertEquals(1, pig.healthReads); assertEquals(0, pig.customReads);
	}
	@Test void itemDataObservedButEmptyConstructorStackNeverZero() {
		ItemEntity item = new ItemEntity(EntityType.ITEM, null);
		var fields = Set.of(PresentationBasic.ITEM_ID, PresentationBasic.ITEM_COUNT, PresentationBasic.ITEM_ICON,
			PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME);
		var empty = MinecraftPreviewFieldAccess.observeEntity(item, fields, 0, name -> name.getString());
		assertTrue(empty.values().stream().allMatch(outcome -> outcome == PreviewFieldAccess.Missing.UNAVAILABLE));
		ItemStack stack = new ItemStack(Items.DIAMOND, 7); stack.set(DataComponents.CUSTOM_NAME, Component.literal("Custom")); item.setItem(stack);
		var observed = MinecraftPreviewFieldAccess.observeEntity(item, fields, 1, name -> Component.Serializer.toJson(name, RegistryAccess.EMPTY));
		assertEquals(new PresentationValue.NumberValue(7), value(observed.get(PresentationBasic.ITEM_COUNT)));
		assertEquals(new PresentationValue.Text("minecraft:diamond"), value(observed.get(PresentationBasic.ITEM_ID)));
		assertEquals(new PresentationValue.Flag(true), value(observed.get(PresentationBasic.ITEM_ICON)));
		assertTrue(((PresentationValue.Text) value(observed.get(PresentationBasic.NAME))).value().contains("Custom"));
		assertEquals(new PresentationValue.Text("Custom"), value(observed.get(PresentationBasic.CUSTOM_NAME)));
	}
	@Test void synchronizedEntityCustomNameIsRawKnownTextWithOneGetterAndNoNameEncoder() {
		Pig pig = new Pig(); String raw = "Synced (literal) \"name\"";
		pig.setCustomName(Component.literal(raw).withColor(0xFF0000).withStyle(style -> style.withItalic(true)));
		pig.customReads = 0;
		var fields = MinecraftPreviewFieldAccess.observeEntity(pig, Set.of(PresentationBasic.CUSTOM_NAME), 9,
			name -> { throw new AssertionError("custom-only NAME encoder"); });
		assertEquals(Map.of(PresentationBasic.CUSTOM_NAME, new PreviewFieldAccess.Observed(new PreviewObservation(
			new PresentationValue.Text(raw), PreviewObservation.Origin.CLIENT_SYNCED, 9, false))), fields);
		assertEquals(1, pig.customReads);
		pig.setCustomName(Component.empty());
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, MinecraftPreviewFieldAccess.observeEntity(pig,
			Set.of(PresentationBasic.CUSTOM_NAME), 10, name -> "unused").get(PresentationBasic.CUSTOM_NAME));
		pig.setCustomName(null);
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, MinecraftPreviewFieldAccess.observeEntity(pig,
			Set.of(PresentationBasic.CUSTOM_NAME), 10, name -> "unused").get(PresentationBasic.CUSTOM_NAME));
	}
	@Test void synchronizedDroppedItemCustomNameUsesOnlyStackNeverEntityFallback() {
		ItemEntity item = new ItemEntity(EntityType.ITEM, null);
		ItemStack stack = new ItemStack(Items.DIAMOND); item.setItem(stack);
		item.setCustomName(Component.literal("Ignored entity custom"));
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, MinecraftPreviewFieldAccess.observeEntity(item,
			Set.of(PresentationBasic.CUSTOM_NAME), 2, name -> "unused").get(PresentationBasic.CUSTOM_NAME));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Stack (literal)"));
		assertEquals(new PresentationValue.Text("Stack (literal)"), value(MinecraftPreviewFieldAccess.observeEntity(item,
			Set.of(PresentationBasic.CUSTOM_NAME), 2, name -> { throw new AssertionError("masked NAME encoder"); }).get(PresentationBasic.CUSTOM_NAME)));
		stack.set(DataComponents.CUSTOM_NAME, Component.empty());
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, MinecraftPreviewFieldAccess.observeEntity(item,
			Set.of(PresentationBasic.CUSTOM_NAME), 2, name -> "unused").get(PresentationBasic.CUSTOM_NAME));
	}
	@Test void failedCustomGetterPublishesNeitherNameFieldButKeepsIndependentHealth() {
		Pig pig = new Pig(); pig.setHealth(7.5F); pig.customUnavailable = true;
		var observed = MinecraftPreviewFieldAccess.observeEntity(pig,
			Set.of(PresentationBasic.HEALTH, PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME), 3,
			name -> { throw new AssertionError("failed getter must not encode a fabricated name"); });
		assertEquals(new PresentationValue.NumberValue(7.5), value(observed.get(PresentationBasic.HEALTH)));
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, observed.get(PresentationBasic.NAME));
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, observed.get(PresentationBasic.CUSTOM_NAME));
		assertEquals(1, pig.customReads);
	}
	private static final class Context implements MinecraftPreviewFieldAccess.WorldContext {
		Entity entity; BlockState state; int entityReads, blockReads, encodes;
		public Object levelIdentity() { return this; }
		public String dimensionId() { return "minecraft:overworld"; }
		public long tick() { return 0; }
		public Entity entity(EntityLocator locator) { entityReads++; return entity; }
		public BlockState blockState(Target.BlockTarget target) { blockReads++; return state; }
		public BlockEntity blockEntity(Target.BlockTarget target) { fail("Basic must not inspect generic BE for unsynced custom name"); return null; }
		public String encodeName(Component component) { encodes++; return Component.Serializer.toJson(component, RegistryAccess.EMPTY); }
	}
	@Test void identityAndDimensionGuardBeforeValuesAndMissingChunkNeverEmptyState() {
		var reader = new MinecraftPreviewFieldAccess(); Context context = new Context(); context.entity = new Pig();
		var wrongEntity = new Target.EntityTarget(context.dimensionId(), java.util.UUID.randomUUID());
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, reader.observe(wrongEntity, Set.of(PresentationBasic.HEALTH), context).get(PresentationBasic.HEALTH));
		assertEquals(0, ((Pig) context.entity).healthReads);
		var remoteDimension = new Target.BlockTarget("minecraft:the_nether", 1, 2, 3, "minecraft:stone");
		reader.observe(remoteDimension, Set.of(PresentationBasic.BLOCK_STATE), context); assertEquals(0, context.blockReads);
		var block = new Target.BlockTarget(context.dimensionId(), 1, 2, 3, "minecraft:stone");
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, reader.observe(block, Set.of(PresentationBasic.BLOCK_STATE), context).get(PresentationBasic.BLOCK_STATE));
		context.state = Blocks.DIRT.defaultBlockState();
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, reader.observe(block, Set.of(PresentationBasic.BLOCK_STATE), context).get(PresentationBasic.BLOCK_STATE));
		context.state = Blocks.CHEST.defaultBlockState();
		var chest = new Target.BlockTarget(context.dimensionId(), 1, 2, 3, "minecraft:chest");
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, reader.observe(chest, Set.of(PresentationBasic.NAME), context).get(PresentationBasic.NAME));
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, reader.observe(chest, Set.of(PresentationBasic.CUSTOM_NAME), context).get(PresentationBasic.CUSTOM_NAME));
		assertEquals(0, context.encodes);
	}
	private static PresentationPreviewAccess customAccess(String type) {
		PresentationField field = PresentationBasic.fields().stream().filter(entry -> entry.id().equals(PresentationBasic.CUSTOM_NAME))
			.findFirst().orElseThrow();
		return new PresentationPreviewAccess(23, 1, type, Map.of(PresentationBasic.ID,
			new PresentationPreviewAccess.Adapter(1, Map.of(field.id(), field))));
	}
	@Test void genericBlockEntityCustomFallsBackButReceivedEntityCustomIsLocallySelectable() {
		Context world = new Context(); world.state = Blocks.CHEST.defaultBlockState();
		var chest = new Target.BlockTarget(world.dimensionId(), 1, 2, 3, "minecraft:chest");
		List<PresentationPreviewC2SPacket> sent = new java.util.ArrayList<>();
		var blockPreview = new ClientPresentationPreview(type -> Optional.of(customAccess(type)), () -> world,
			List.of(new MinecraftPreviewFieldAccess()), (target, type) -> Optional.empty(), sent::add);
		Object token = new Object();
		blockPreview.begin(new ClientPresentationPreview.Binding(token, chest, "block", world));
		assertEquals(1, sent.size()); assertEquals(Set.of(PresentationBasic.CUSTOM_NAME), sent.getFirst().fields());
		var ref = PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.CUSTOM_NAME);
		assertTrue(blockPreview.intent(token, ref, null).isEmpty(), "generic BE presence never proves a custom name");
		assertTrue(blockPreview.accept(PresentationPreviewS2CPacket.result(sent.getFirst(), new PresentationSection(PresentationBasic.ID,
			1, Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Server-only chest")), false))));
		assertEquals(PreviewObservation.Origin.SERVER_PREVIEW, blockPreview.projection().orElseThrow().property(ref).orElseThrow().origin());
		assertEquals(new PresentationValue.Text("Server-only chest"), blockPreview.intent(token, ref, null).orElseThrow().observedValue());

		Pig pig = new Pig(); pig.setCustomName(Component.literal("Received entity")); world.entity = pig;
		var target = new Target.EntityTarget(world.dimensionId(), nx.pingwheel.common.interaction.MinecraftEntityTargetAdapter.locatorFor(pig));
		sent.clear();
		var entityPreview = new ClientPresentationPreview(type -> Optional.of(customAccess(type)), () -> world,
			List.of(new MinecraftPreviewFieldAccess()), (value, type) -> Optional.empty(), sent::add);
		entityPreview.begin(new ClientPresentationPreview.Binding(token, target, "entity", world));
		assertTrue(sent.isEmpty(), "synchronized entity custom must satisfy demand without server fallback");
		assertEquals(PreviewObservation.Origin.CLIENT_SYNCED, entityPreview.projection().orElseThrow().property(ref).orElseThrow().origin());
		assertEquals(new PresentationValue.Text("Received entity"), entityPreview.intent(token, ref, null).orElseThrow().observedValue());
	}
	@Test void deniedBlockFieldsDoNotResolveLocalSource() {
		Context context = new Context(); var block = new Target.BlockTarget(context.dimensionId(), 1, 2, 3, "minecraft:stone");
		assertTrue(new MinecraftPreviewFieldAccess().observe(block, Set.of(), context).isEmpty()); assertEquals(0, context.blockReads);
	}
	private static final class ExternalContext implements MinecraftPreviewFieldAccess.WorldContext {
		MinecraftPreviewFieldAccess.SafeBlockSource source; BlockState state;
		public Object levelIdentity() { return this; }
		public String dimensionId() { return "minecraft:overworld"; }
		public long tick() { return 3; }
		public Entity entity(EntityLocator locator) { fail("a block preview must not resolve entities"); return null; }
		public BlockState blockState(Target.BlockTarget target) { return state; }
		public BlockEntity blockEntity(Target.BlockTarget target) { fail("Basic must not inspect generic BE for unsynced custom name"); return null; }
		public String encodeName(Component component) { return Component.Serializer.toJson(component, RegistryAccess.EMPTY); }
		@Override public MinecraftPreviewFieldAccess.SafeBlockSource blockSource(Target target) { return source; }
	}
	@Test void externalProviderSourceUsesPhysicalStateAndNoGenericBlockEntityName() {
		var reader = new MinecraftPreviewFieldAccess();
		var physical = new Target.BlockTarget("minecraft:overworld", 100, 64, -200, "minecraft:chest");
		Target.ExternalBlockTarget external = Target.ExternalBlockTarget.candidate(
			"minecraft:overworld", "sable", "minecraft:chest", "opaque-locator", false);
		ExternalContext context = new ExternalContext();
		context.state = Blocks.CHEST.defaultBlockState();

		context.source = null;
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE,
			reader.observe(external, Set.of(PresentationBasic.BLOCK_STATE), context).get(PresentationBasic.BLOCK_STATE));
		context.source = new MinecraftPreviewFieldAccess.SafeBlockSource(physical, position -> true);
		var state = reader.observe(external, Set.of(PresentationBasic.BLOCK_STATE), context);
		assertTrue(value(state.get(PresentationBasic.BLOCK_STATE)) instanceof PresentationValue.RecordValue);
		// A chest owns a block entity, so a generic name is never observed evidence.
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE,
			reader.observe(external, Set.of(PresentationBasic.NAME), context).get(PresentationBasic.NAME));
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE,
			reader.observe(external, Set.of(PresentationBasic.CUSTOM_NAME), context).get(PresentationBasic.CUSTOM_NAME));
		// A provider binding outside its member scope or for a foreign registry is unavailable.
		context.source = new MinecraftPreviewFieldAccess.SafeBlockSource(physical, position -> false);
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE,
			reader.observe(external, Set.of(PresentationBasic.BLOCK_STATE), context).get(PresentationBasic.BLOCK_STATE));
		context.source = new MinecraftPreviewFieldAccess.SafeBlockSource(
			new Target.BlockTarget("minecraft:overworld", 100, 64, -200, "minecraft:dirt"), position -> true);
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE,
			reader.observe(external, Set.of(PresentationBasic.BLOCK_STATE), context).get(PresentationBasic.BLOCK_STATE));
		// A block without a block entity exposes the physical state name.
		context.source = new MinecraftPreviewFieldAccess.SafeBlockSource(physical, position -> true);
		context.state = Blocks.STONE.defaultBlockState();
		assertTrue(value(reader.observe(external, Set.of(PresentationBasic.NAME), context).get(PresentationBasic.NAME))
			instanceof PresentationValue.Text);
	}
}
