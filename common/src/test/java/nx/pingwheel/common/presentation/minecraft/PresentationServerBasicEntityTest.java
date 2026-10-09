package nx.pingwheel.common.presentation.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless regression coverage for {@link PresentationServer#basicEntity}, the
 * production Basic assembly for a live entity or dropped item. The world
 * lookup and registry-bound encoding stay in {@code basic}; this seam pins the
 * value and name rules, especially that an absent custom name keeps the
 * trusted localized base name instead of failing the whole Basic section.
 */
class PresentationServerBasicEntityTest {

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static PresentationSection basicEntity(Set<String> demand, Entity entity) {
		return PresentationServer.basicEntity(demand, entity,
			name -> TargetNameJsonCodec.encode(name, RegistryAccess.EMPTY).value());
	}

	private static Component decodeName(PresentationValue value) {
		assertTrue(value instanceof PresentationValue.Text,
			() -> "expected a text name value, got: " + value);
		return TargetNameJsonCodec.decode(
			new TargetNameJson(((PresentationValue.Text) value).value()), RegistryAccess.EMPTY);
	}

	private static String literalText(Component component) {
		assertTrue(component.getContents() instanceof PlainTextContents.LiteralContents,
			() -> "expected literal contents, got: " + component.getContents().getClass());
		return ((PlainTextContents.LiteralContents) component.getContents()).text();
	}

	private static String translatableKey(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents, "expected a translatable component");
		return ((TranslatableContents) component.getContents()).getKey();
	}

	@Test
	void unnamedLivingEntityKeepsBaseNameHealthAndMaxHealth() {
		LivingEntity pig = new TestLivingEntity();
		pig.setHealth(7.5F);

		PresentationSection section = basicEntity(Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME,
			PresentationBasic.ENTITY_TYPE, PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH), pig);

		Map<String, PresentationValue> fields = section.fields();
		assertEquals(PresentationBasic.ID, section.adapterId());
		assertFalse(section.stale());
		assertEquals(new PresentationValue.Text("minecraft:pig"), fields.get(PresentationBasic.ENTITY_TYPE));
		assertEquals(new PresentationValue.NumberValue(pig.getHealth()), fields.get(PresentationBasic.HEALTH));
		assertEquals(new PresentationValue.NumberValue(pig.getMaxHealth()), fields.get(PresentationBasic.MAX_HEALTH));

		Component name = decodeName(fields.get(PresentationBasic.NAME));
		assertEquals("entity.minecraft.pig", translatableKey(name));
		assertTrue(name.getSiblings().isEmpty(), "an unnamed target must keep the bare base name");
		assertFalse(fields.containsKey(PresentationBasic.CUSTOM_NAME));
	}

	@Test
	void unnamedItemEntityKeepsBaseNameItemIdCountAndIcon() {
		ItemEntity item = new ItemEntity(EntityType.ITEM, null);
		item.setItem(new ItemStack(Items.DIAMOND, 3));

		PresentationSection section = basicEntity(Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME, PresentationBasic.ENTITY_TYPE,
			PresentationBasic.ITEM_ID, PresentationBasic.ITEM_COUNT, PresentationBasic.ITEM_ICON), item);

		Map<String, PresentationValue> fields = section.fields();
		assertEquals(new PresentationValue.Text("minecraft:item"), fields.get(PresentationBasic.ENTITY_TYPE));
		assertEquals(new PresentationValue.Text("minecraft:diamond"), fields.get(PresentationBasic.ITEM_ID));
		assertEquals(new PresentationValue.NumberValue(3), fields.get(PresentationBasic.ITEM_COUNT));
		assertEquals(new PresentationValue.Flag(true), fields.get(PresentationBasic.ITEM_ICON));

		Component name = decodeName(fields.get(PresentationBasic.NAME));
		assertEquals("item.minecraft.diamond", translatableKey(name));
		assertTrue(name.getSiblings().isEmpty(), "an unnamed dropped item must keep the bare base name");
		assertFalse(fields.containsKey(PresentationBasic.CUSTOM_NAME));
	}

	@Test
	void customNamedEntityKeepsComposedCustomBaseFormat() {
		LivingEntity pig = new TestLivingEntity();
		String raw = "Bob (literal) \"quoted\" {text}";
		pig.setCustomName(Component.literal(raw).withColor(0xFF0000).withStyle(style -> style.withItalic(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/say unsafe"))
			.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("private hover")))));

		PresentationSection section = basicEntity(Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME,
			PresentationBasic.ENTITY_TYPE), pig);

		Component name = decodeName(section.fields().get(PresentationBasic.NAME));
		assertEquals(TargetNameComposer.compose(Component.literal(raw), pig.getType().getDescription()), name);
		assertEquals(pig.getType().getDescription(), name.getSiblings().get(1));
		assertEquals(raw, literalText(name));
		assertEquals(" (", literalText(name.getSiblings().get(0)));
		assertEquals(")", literalText(name.getSiblings().get(2)));
		assertTrue(name.getStyle().isEmpty(), "the composed root text must stay unstyled");
		assertEquals(new PresentationValue.Text(raw), section.fields().get(PresentationBasic.CUSTOM_NAME),
			"custom_name is literal text, not composed JSON or a stripped-parentheses approximation");
	}

	@Test
	void customNamedItemKeepsComposedCustomBaseFormat() {
		ItemEntity item = new ItemEntity(EntityType.ITEM, null);
		ItemStack stack = new ItemStack(Items.DIAMOND, 3);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Shiny"));
		item.setItem(stack);
		item.setCustomName(Component.literal("Ignored entity name"));

		PresentationSection section = basicEntity(Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME, PresentationBasic.ITEM_ID,
			PresentationBasic.ITEM_COUNT, PresentationBasic.ITEM_ICON), item);

		Component name = decodeName(section.fields().get(PresentationBasic.NAME));
		assertEquals(TargetNameComposer.compose(Component.literal("Shiny"),
			Component.translatable(stack.getDescriptionId())), name);
		assertEquals("item.minecraft.diamond", translatableKey(name.getSiblings().get(1)));
		assertEquals(new PresentationValue.Text("Shiny"), section.fields().get(PresentationBasic.CUSTOM_NAME));
	}

	@Test void customOnlyReadsOnceWithoutComposedNameEncodingAndEmptyIsOmitted() {
		TestLivingEntity pig = new TestLivingEntity();
		pig.setCustomName(Component.literal("Only (custom)"));
		pig.nameReads = 0;
		var customOnly = PresentationServer.basicEntity(Set.of(PresentationBasic.CUSTOM_NAME), pig,
			name -> { throw new AssertionError("masked NAME must not encode"); });
		assertEquals(Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Only (custom)")), customOnly.fields());
		assertEquals(1, pig.nameReads);
		pig.setCustomName(Component.empty());
		assertTrue(basicEntity(Set.of(PresentationBasic.CUSTOM_NAME), pig).fields().isEmpty());
	}

	@Test void droppedItemNeverFallsBackToEntityCustomNameForAbsentOrEmptyStackName() {
		ItemEntity item = new ItemEntity(EntityType.ITEM, null);
		ItemStack stack = new ItemStack(Items.DIAMOND);
		item.setItem(stack);
		item.setCustomName(Component.literal("Entity-only private name"));
		assertTrue(basicEntity(Set.of(PresentationBasic.CUSTOM_NAME), item).fields().isEmpty());
		stack.set(DataComponents.CUSTOM_NAME, Component.empty());
		assertTrue(basicEntity(Set.of(PresentationBasic.CUSTOM_NAME), item).fields().isEmpty());
	}

	@Test void failedCustomGetterOmitsNameFieldsAndKeepsIndependentHealth() {
		TestLivingEntity pig = new TestLivingEntity();
		pig.setHealth(7.5F);
		pig.customUnavailable = true;
		pig.nameReads = 0;

		PresentationSection customOnly = PresentationServer.basicEntity(
			Set.of(PresentationBasic.HEALTH, PresentationBasic.CUSTOM_NAME), pig,
			name -> { throw new AssertionError("a failed getter must not encode NAME"); });
		assertEquals(Map.of(PresentationBasic.HEALTH, new PresentationValue.NumberValue(7.5)), customOnly.fields(),
			"a failed custom-name acquisition must not discard independent health");
		assertEquals(1, pig.nameReads, "the demanded name source is attempted once");

		PresentationSection withName = PresentationServer.basicEntity(
			Set.of(PresentationBasic.HEALTH, PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME), pig,
			name -> { throw new AssertionError("a failed getter must not fabricate a composed name"); });
		assertEquals(Map.of(PresentationBasic.HEALTH, new PresentationValue.NumberValue(7.5)), withName.fields(),
			"a failed custom-name acquisition must not fall back to the base name");
		assertEquals(2, pig.nameReads, "a composed-name demand must not repeat the getter read");
	}

	@Test void failedNameEncoderRetainsIndependentPlainCustomNameAndHealth() {
		TestLivingEntity pig = new TestLivingEntity();
		pig.setHealth(7.5F);
		pig.setCustomName(Component.literal("Kept (custom)"));
		pig.nameReads = 0;

		PresentationSection section = PresentationServer.basicEntity(
			Set.of(PresentationBasic.HEALTH, PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME), pig,
			name -> { throw new LinkageError("name encoder unavailable"); });

		assertEquals(new PresentationValue.NumberValue(7.5), section.fields().get(PresentationBasic.HEALTH));
		assertEquals(new PresentationValue.Text("Kept (custom)"), section.fields().get(PresentationBasic.CUSTOM_NAME),
			"a failed composed encoding must retain the independently captured plain custom name");
		assertFalse(section.fields().containsKey(PresentationBasic.NAME),
			"a failed composed encoding leaves only NAME absent");
		assertEquals(1, pig.nameReads);
	}

	@Test void malformedCustomComponentOmitsOnlyNameFieldsAndKeepsIndependentHealth() {
		TestLivingEntity pig = new TestLivingEntity();
		pig.setHealth(7.5F);
		pig.setCustomName(new UnflattenableName());

		PresentationSection customOnly = PresentationServer.basicEntity(
			Set.of(PresentationBasic.HEALTH, PresentationBasic.CUSTOM_NAME), pig, name -> "unused");
		assertEquals(Map.of(PresentationBasic.HEALTH, new PresentationValue.NumberValue(7.5)), customOnly.fields(),
			"a failed plain reduction must not discard independent health");

		PresentationSection withName = PresentationServer.basicEntity(
			Set.of(PresentationBasic.HEALTH, PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME), pig,
			name -> { throw new AssertionError("a malformed custom component must not reach the encoder"); });
		assertEquals(Map.of(PresentationBasic.HEALTH, new PresentationValue.NumberValue(7.5)), withName.fields(),
			"a malformed custom component must not discard independent health even when NAME is demanded");
	}

	@Test
	void undemandedNameBuildsNoNameFieldAndSkipsTheEncoder() {
		LivingEntity pig = new TestLivingEntity();
		boolean[] encoded = {false};

		PresentationSection section = PresentationServer.basicEntity(
			Set.of(PresentationBasic.ENTITY_TYPE, PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH), pig,
			name -> { encoded[0] = true; return "unused"; });

		assertFalse(section.fields().containsKey(PresentationBasic.NAME));
		assertEquals(new PresentationValue.Text("minecraft:pig"), section.fields().get(PresentationBasic.ENTITY_TYPE));
		assertEquals(new PresentationValue.NumberValue(pig.getMaxHealth()), section.fields().get(PresentationBasic.MAX_HEALTH));
		assertFalse(encoded[0], "the name encoder must not run when the name field is not demanded");
	}

	@Test
	void optionalNameKeepsBaseForAbsentCustomNameAndComposesPresent() {
		Component base = Component.translatable("minecraft.zombie");

		assertSame(base, PresentationServer.optionalName(null, base));
		assertEquals(TargetNameComposer.compose(Component.literal("Bob"), base),
			PresentationServer.optionalName(Component.literal("Bob"), base));
	}

	@Test void deniedHealthAndNameGettersNeverRunForTypeOnlyDemand() {
		TestLivingEntity pig = new TestLivingEntity(); pig.setHealth(7.5F); pig.setCustomName(Component.literal("Private"));
		pig.healthReads = pig.nameReads = 0;
		var typeOnly = PresentationServer.basicEntity(Set.of(PresentationBasic.ENTITY_TYPE), pig,
			name -> { throw new AssertionError("denied name encoder"); });
		assertEquals(Map.of(PresentationBasic.ENTITY_TYPE, new PresentationValue.Text("minecraft:pig")), typeOnly.fields());
		assertEquals(0, pig.healthReads); assertEquals(0, pig.nameReads);
		var healthOnly = PresentationServer.basicEntity(Set.of(PresentationBasic.HEALTH), pig,
			name -> { throw new AssertionError("denied name encoder"); });
		assertEquals(Map.of(PresentationBasic.HEALTH, new PresentationValue.NumberValue(7.5)), healthOnly.fields());
		assertEquals(1, pig.healthReads); assertEquals(0, pig.nameReads);
	}

	@Test void deniedDroppedItemContentsNeverResolveStackForTypeOrIconOnly() {
		class CountingItem extends ItemEntity {
			int stackReads;
			CountingItem() { super(EntityType.ITEM, null); }
			@Override public ItemStack getItem() { stackReads++; return super.getItem(); }
		}
		var item = new CountingItem(); item.setItem(new ItemStack(Items.DIAMOND, 7)); item.stackReads = 0;
		assertEquals(Set.of(PresentationBasic.ENTITY_TYPE), basicEntity(Set.of(PresentationBasic.ENTITY_TYPE), item).fields().keySet());
		assertEquals(0, item.stackReads);
		assertEquals(Map.of(PresentationBasic.ITEM_ICON, new PresentationValue.Flag(true)), basicEntity(Set.of(PresentationBasic.ITEM_ICON), item).fields());
		assertEquals(0, item.stackReads);
		assertEquals(Map.of(PresentationBasic.ITEM_COUNT, new PresentationValue.NumberValue(7)), basicEntity(Set.of(PresentationBasic.ITEM_COUNT), item).fields());
		assertEquals(1, item.stackReads);
	}

	/**
	 * Minimal headless {@link LivingEntity} reusing the registered pig type;
	 * a vanilla {@code Pig} cannot be built without a level because
	 * {@code Mob} touches the level in its constructor.
	 */
	private static final class TestLivingEntity extends LivingEntity {
		int healthReads, nameReads;
		boolean customUnavailable;

		TestLivingEntity() {
			super(EntityType.PIG, null);
		}
		@Override public float getHealth() { healthReads++; return super.getHealth(); }
		@Override public Component getCustomName() {
			nameReads++;
			if (customUnavailable) throw new IllegalStateException("custom name unavailable");
			return super.getCustomName();
		}

		@Override
		public void readAdditionalSaveData(CompoundTag tag) {
			// intentionally empty
		}

		@Override
		public void addAdditionalSaveData(CompoundTag tag) {
			// intentionally empty
		}

		@Override
		public Iterable<ItemStack> getArmorSlots() {
			return List.of();
		}

		@Override
		public ItemStack getItemBySlot(EquipmentSlot slot) {
			return ItemStack.EMPTY;
		}

		@Override
		public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
			// intentionally empty
		}

		@Override
		public HumanoidArm getMainArm() {
			return HumanoidArm.RIGHT;
		}
	}

	/**
	 * A malformed custom name whose literal flattening always throws; every other
	 * component surface stays well formed so only the name fields can be affected.
	 */
	private static final class UnflattenableName implements Component {
		private final Component delegate = Component.literal("unavailable");

		@Override public String getString() {
			throw new IllegalStateException("custom text unavailable");
		}

		@Override public Style getStyle() { return delegate.getStyle(); }
		@Override public ComponentContents getContents() { return delegate.getContents(); }
		@Override public List<Component> getSiblings() { return delegate.getSiblings(); }
		@Override public FormattedCharSequence getVisualOrderText() { return delegate.getVisualOrderText(); }
	}
}
