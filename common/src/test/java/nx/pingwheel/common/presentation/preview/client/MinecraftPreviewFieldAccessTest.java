package nx.pingwheel.common.presentation.preview.client;

import java.util.List;
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
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftPreviewFieldAccessTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final class Pig extends LivingEntity {
		int healthReads, customReads;
		Pig() { super(EntityType.PIG, null); }
		@Override public float getHealth() { healthReads++; return super.getHealth(); }
		@Override public Component getCustomName() { customReads++; return super.getCustomName(); }
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
		var fields = Set.of(PresentationBasic.ITEM_ID, PresentationBasic.ITEM_COUNT, PresentationBasic.ITEM_ICON, PresentationBasic.NAME);
		var empty = MinecraftPreviewFieldAccess.observeEntity(item, fields, 0, name -> name.getString());
		assertTrue(empty.values().stream().allMatch(outcome -> outcome == PreviewFieldAccess.Missing.UNAVAILABLE));
		ItemStack stack = new ItemStack(Items.DIAMOND, 7); stack.set(DataComponents.CUSTOM_NAME, Component.literal("Custom")); item.setItem(stack);
		var observed = MinecraftPreviewFieldAccess.observeEntity(item, fields, 1, name -> Component.Serializer.toJson(name, RegistryAccess.EMPTY));
		assertEquals(new PresentationValue.NumberValue(7), value(observed.get(PresentationBasic.ITEM_COUNT)));
		assertEquals(new PresentationValue.Text("minecraft:diamond"), value(observed.get(PresentationBasic.ITEM_ID)));
		assertEquals(new PresentationValue.Flag(true), value(observed.get(PresentationBasic.ITEM_ICON)));
		assertTrue(((PresentationValue.Text) value(observed.get(PresentationBasic.NAME))).value().contains("Custom"));
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
		assertEquals(0, context.encodes);
	}
	@Test void deniedBlockFieldsDoNotResolveLocalSource() {
		Context context = new Context(); var block = new Target.BlockTarget(context.dimensionId(), 1, 2, 3, "minecraft:stone");
		assertTrue(new MinecraftPreviewFieldAccess().observe(block, Set.of(), context).isEmpty()); assertEquals(0, context.blockReads);
	}
}
