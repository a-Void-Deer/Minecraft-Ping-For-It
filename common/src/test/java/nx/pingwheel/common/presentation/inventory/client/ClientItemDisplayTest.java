package nx.pingwheel.common.presentation.inventory.client;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.Services;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nx.pingwheel.common.chat.ContentChatTemplate;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import org.jetbrains.annotations.NotNullByDefault;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real codec and name-policy coverage for the shared inventory display
 * projection: the production single-item codec, the chat naming composition,
 * the count-one icon stack and the bounded registry-scoped decode cache.
 */
@NotNullByDefault
class ClientItemDisplayTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@BeforeEach void clearCache() {
		ClientItemDisplay.reset();
		SkullBlockEntity.clear();
	}

	@AfterEach void restoreVanillaProfileStatics() { SkullBlockEntity.clear(); }

	private static String json(ItemStack stack) {
		return json(stack, RegistryAccess.EMPTY);
	}

	private static String json(ItemStack stack, HolderLookup.Provider registryAccess) {
		var encoded = ItemStack.SINGLE_ITEM_CODEC
			.encodeStart(registryAccess.createSerializationContext(JsonOps.INSTANCE), stack)
			.result().orElseThrow().getAsJsonObject();
		// Ordinary displays carry components even when empty; the id-only wire
		// spelling is reserved for the server's size-terminal registry-label icon.
		if (!encoded.has("components")) encoded.add("components", new com.google.gson.JsonObject());
		return encoded.toString();
	}

	/**
	 * Minimal dynamic enchantment registry so the real enchantment codec can
	 * resolve sharpness; the client level provides the same registry kind at
	 * runtime.
	 */
	private static HolderLookup.Provider registryWithSharpness() {
		MappedRegistry<Enchantment> enchantments = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
		enchantments.register(Enchantments.SHARPNESS, new Enchantment(
			Component.translatable("enchantment.minecraft.sharpness"),
			new Enchantment.EnchantmentDefinition(HolderSet.empty(), Optional.empty(), 10, 5,
				Enchantment.constantCost(1), Enchantment.constantCost(21), 1, List.of(EquipmentSlotGroup.MAINHAND)),
			HolderSet.empty(), DataComponentMap.EMPTY), RegistrationInfo.BUILT_IN);
		return new RegistryAccess.ImmutableRegistryAccess(List.of(enchantments.freeze())).freeze();
	}

	private static ItemEnchantments sharpness(int level, HolderLookup.Provider registryAccess) {
		ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		enchantments.set(registryAccess.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), level);
		return enchantments.toImmutable();
	}

	private static TranslatableContents contents(Component component) {
		assertInstanceOf(TranslatableContents.class, component.getContents(), component::getString);
		return (TranslatableContents) component.getContents();
	}

	@Test
	void missingOrBlankEncodedDataKeepsTheRegistryKeyWithoutAPrototypeIcon() {
		var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", null);
		assertTrue(display.stack().isEmpty(), "missing display data never authorizes prototype reconstruction");
		assertEquals("item.minecraft.stone", contents(display.name()).getKey());

		for (String blank : List.of("", "   ")) {
			var blankDisplay = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", blank);
			assertTrue(blankDisplay.stack().isEmpty());
			assertEquals("item.minecraft.stone", contents(blankDisplay.name()).getKey());
		}

		var absent = ClientItemDisplay.resolve(RegistryAccess.EMPTY, null, null, null);
		assertTrue(absent.stack().isEmpty());
		assertEquals("", absent.name().getString(), "a missing label never fabricates a translation key");
		var noRegistry = ClientItemDisplay.resolve(null, "minecraft:stone", "item.minecraft.stone", json(new ItemStack(Items.STONE)));
		assertTrue(noRegistry.stack().isEmpty());
		assertEquals("item.minecraft.stone", contents(noRegistry.name()).getKey());
	}

	@Test
	void malformedUnknownOrOversizedEncodedDataFallsBackWithoutRawText() {
		for (String malformed : List.of("{not json", "{\"id\":\"minecraft:stone\"", "[]",
			"{\"id\":\"pingforit:missing_item\"}",
			"{\"id\":\"minecraft:stone\",\"components\":{\"pingforit:missing_component\":1}}")) {
			var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", malformed);
			assertTrue(display.stack().isEmpty(), malformed);
			assertEquals("item.minecraft.stone", contents(display.name()).getKey(), malformed);
			assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, malformed).isEmpty(), malformed);
		}

		// The approved wire bound is the decode guard; an oversized payload is
		// never decoded and never cached.
		ItemStack oversized = new ItemStack(Items.STONE);
		oversized.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(InventoryS2CPacket.MAX_DISPLAY_BYTES + 64)));
		String oversizedJson = json(oversized);
		assertTrue(oversizedJson.length() > InventoryS2CPacket.MAX_DISPLAY_BYTES);
		var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", oversizedJson);
		assertTrue(display.stack().isEmpty());
		assertNull(display.stack().get(DataComponents.CUSTOM_NAME), "the oversized custom name never reaches the icon stack");
		assertEquals("item.minecraft.stone", contents(display.name()).getKey());
		assertEquals(0, ClientItemDisplay.cachedEntryCount());
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, oversizedJson).isEmpty(),
			"the shared decode applies the wire bound to the chat path as well");
	}

	@Test
	void validOrdinaryDisplayStillReturnsACountOneIconAndLocalizedName() {
		ItemStack stone = new ItemStack(Items.STONE);
		stone.remove(DataComponents.LORE);
		var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback.label", json(stone));
		assertFalse(display.stack().isEmpty());
		assertEquals(Items.STONE, display.stack().getItem());
		assertEquals(1, display.stack().getCount());
		assertEquals("block.minecraft.stone", contents(display.name()).getKey());
	}

	@Test
	void ordinaryNativeIconFamiliesRemainAvailableWithoutHoverCopyRestrictions() {
		for (var item : List.of(Items.DIAMOND_PICKAXE, Items.WOODEN_AXE, Items.BOW, Items.TRIDENT, Items.GOLDEN_APPLE,
			Items.ENCHANTED_GOLDEN_APPLE, Items.SUSPICIOUS_STEW, Items.FIREWORK_ROCKET, Items.FIREWORK_STAR,
			Items.WHITE_BANNER, Items.COMPASS, Items.DEBUG_STICK, Items.DECORATED_POT, Items.WRITABLE_BOOK,
			Items.WRITTEN_BOOK, Items.LEATHER_CHESTPLATE, Items.FILLED_MAP)) {
			ItemStack source = new ItemStack(item); String encoded = json(source);
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			for (int resolve = 0; resolve < 2; resolve++) {
				var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, id, "fallback", encoded);
				assertFalse(display.stack().isEmpty(), id);
				assertEquals(item, display.stack().getItem()); assertEquals(1, display.stack().getCount());
			}
		}
	}

	@Test
	void explicitNativeFoodEffectsDecodeToOwnedOrdinaryIconsWithoutLosingProbabilityOrHiddenState() throws ReflectiveOperationException {
		var hidden = new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 300, 0);
		var resident = new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 100, 1, false, true, true, hidden);
		var possible = new FoodProperties.PossibleEffect(resident, 0.25F);
		var food = new FoodProperties(4, 0.3F, false, 1.6F, Optional.empty(), List.of(possible));
		var ops = RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE);
		var foodJson = FoodProperties.DIRECT_CODEC.encodeStart(ops, food).result().orElseThrow();
		assertFalse(foodJson.getAsJsonObject().getAsJsonArray("effects").isEmpty(), "explicit nonempty native codec output, not a prototype-only test");
		var stackJson = new com.google.gson.JsonObject(); stackJson.addProperty("id", "minecraft:stone");
		var components = new com.google.gson.JsonObject(); components.add("minecraft:food", foodJson); stackJson.add("components", components);
		String encoded = stackJson.toString();
		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", encoded);
		assertFalse(first.stack().isEmpty()); assertEquals(1, first.stack().getCount());
		var actual = first.stack().get(DataComponents.FOOD).effects().getFirst();
		assertEquals(0.25F, actual.probability());
		var owned = ClientDisplayFoodAccess.resident(actual, 0, new ClientItemDisplay.Validation());
		assertNotSame(resident, owned); assertSame(resident.getEffect(), owned.getEffect()); assertEquals(100, owned.getDuration());
		var raw = InventoryResidentAccess.load();
		var retained = ClientItemDisplay.cachedTransmittedPatch(RegistryAccess.EMPTY, "minecraft:stone", encoded).orElseThrow().get(DataComponents.FOOD).orElseThrow();
		var source = ClientDisplayFoodAccess.resident(retained.effects().getFirst(), 0, new ClientItemDisplay.Validation());
		assertNotSame(source, owned);
		assertNull(raw.hidden(source), "native PossibleEffect encoding copies the effect and projects its hidden chain away");
		assertNull(raw.hidden(owned));
		owned.update(new net.minecraft.world.effect.MobEffectInstance(owned.getEffect(), 200, 1));
		assertEquals(100, source.getDuration());
		var next = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", encoded);
		assertFalse(next.stack().isEmpty());
		assertEquals(100, ClientDisplayFoodAccess.resident(next.stack().get(DataComponents.FOOD).effects().getFirst(), 0, new ClientItemDisplay.Validation()).getDuration());
		assertEquals(1, ClientItemDisplay.decodeMisses());
		// Direct ordinary detach tests both directions independently of codec's
		// own MobEffectInstance copy/projection semantics.
		ItemStack sourceStack = new ItemStack(Items.STONE); sourceStack.set(DataComponents.FOOD, food);
		var patch = ClientDisplayWorkingCopy.overlay(sourceStack.getComponents(), DataComponentPatch.EMPTY, 0, new ClientItemDisplay.Validation());
		var copied = ClientDisplayFoodAccess.resident(patch.get(DataComponents.FOOD).orElseThrow().effects().getFirst(), 0, new ClientItemDisplay.Validation());
		assertNotSame(hidden, raw.hidden(copied)); assertEquals(300, raw.hidden(copied).getDuration());
		resident.update(new net.minecraft.world.effect.MobEffectInstance(resident.getEffect(), 200, 1));
		hidden.update(new net.minecraft.world.effect.MobEffectInstance(hidden.getEffect(), 400, 0));
		assertEquals(100, copied.getDuration()); assertEquals(300, raw.hidden(copied).getDuration());
	}

	@Test void foodNativeWrapperReservesWorkBeforeConstruction() {
		var effect = new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 100);
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplayFoodAccess.wrap(effect, 0.25F, 0, new ClientItemDisplay.Validation(1, 4096)));
		var wrapped = ClientDisplayFoodAccess.wrap(effect, 0.25F, 0, new ClientItemDisplay.Validation(64, 4096));
		assertSame(effect, ClientDisplayFoodAccess.resident(wrapped, 0, new ClientItemDisplay.Validation()));
		assertEquals(0.25F, wrapped.probability());
	}

	@Test
	void omittedLoreAndShulkerContentsNeverReturnThroughDecodeFailureFallback() {
		ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
		assertNotNull(shulker.get(DataComponents.LORE), "vanilla provides an inherited Lore component");
		assertNotNull(shulker.get(DataComponents.CONTAINER), "vanilla provides an inherited container component");
		shulker.remove(DataComponents.LORE);
		shulker.remove(DataComponents.CONTAINER);
		String projected = json(shulker);
		var valid = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:shulker_box", "block.minecraft.shulker_box", projected);
		assertFalse(valid.stack().isEmpty());
		assertNull(valid.stack().get(DataComponents.LORE));
		assertNull(valid.stack().get(DataComponents.CONTAINER));
		for (String failed : new String[] { null, projected.substring(0, projected.length() - 1),
			projected + " ".repeat(InventoryS2CPacket.MAX_DISPLAY_BYTES + 1) }) {
			var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:shulker_box", "block.minecraft.shulker_box", failed);
			assertTrue(display.stack().isEmpty());
			assertNull(display.stack().get(DataComponents.LORE));
			assertNull(display.stack().get(DataComponents.CONTAINER));
			assertEquals("block.minecraft.shulker_box", contents(display.name()).getKey());
		}
	}

	@Test
	void unsafeIconKeepsItsTransmittedNameAndCodecOnlyChatName(@TempDir Path tempDir) {
		installVanillaProfileSeam(tempDir, UUID.randomUUID(), "Herobrine", Runnable::run);
		SkullBlockEntity.fetchGameProfile("herobrine").join();
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		ResolvableProfile profile = new ResolvableProfile(Optional.of("herobrine"), Optional.empty(), new PropertyMap());
		head.set(DataComponents.PROFILE, profile);
		head.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("must not render"))));
		String encoded = json(head);
		for (int resolve = 0; resolve < 2; resolve++) {
			var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "fallback.label", encoded);
			assertTrue(display.stack().isEmpty(), "actual Lore makes the icon unavailable");
			assertEquals("block.minecraft.player_head.named", contents(display.name()).getKey());
			assertEquals("herobrine", contents(display.name()).getArgs()[0], "empty icon does not suppress transmitted profile text");
			assertRetainedProfile(encoded, profile);
			ItemStack chat = ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).orElseThrow();
			assertEquals(profile, chat.get(DataComponents.PROFILE));
			assertEquals("herobrine", contents(ClientItemDisplay.displayName(chat)).getArgs()[0]);
		}
	}

	@Test
	void nestedContainerBundleProjectileAndFoodValuesCannotExposeProhibitedData() {
		ItemStack lore = new ItemStack(Items.STONE);
		lore.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("nested secret"))));
		ItemStack fullShulker = new ItemStack(Items.BLUE_SHULKER_BOX);
		fullShulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
		for (ItemStack nested : List.of(lore, fullShulker)) {
			for (ItemStack outer : nestedHolders(nested)) {
				String id = BuiltInRegistries.ITEM.getKey(outer.getItem()).toString();
				var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, id, "fallback.label", json(outer));
				assertTrue(display.stack().isEmpty(), id);
				assertEquals(ContentChatTemplate.CUSTOM_ITEM_NAME_KEY, contents(display.name()).getKey(),
					"terminal icon availability never removes a successfully decoded name");
			}
		}
		ItemStack redacted = new ItemStack(Items.BLUE_SHULKER_BOX);
		redacted.remove(DataComponents.LORE);
		redacted.remove(DataComponents.CONTAINER);
		for (ItemStack outer : nestedHolders(redacted)) {
			String id = BuiltInRegistries.ITEM.getKey(outer.getItem()).toString();
			assertFalse(ClientItemDisplay.resolve(RegistryAccess.EMPTY, id, "fallback.label", json(outer)).stack().isEmpty(),
				"non-shulker containers, bundles and other safe nested displays remain eligible: " + id);
		}
		ItemStack nestedBundle = nestedHolders(lore).get(1);
		ItemStack chest = nestedHolders(nestedBundle).get(0);
		assertTrue(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:chest", "fallback.label", json(chest)).stack().isEmpty(),
			"the safety check follows nested stacks beyond the first item holder");
	}

	private static List<ItemStack> nestedHolders(ItemStack nested) {
		ItemStack chest = new ItemStack(Items.CHEST);
		chest.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(nested)));
		ItemStack bundle = new ItemStack(Items.BUNDLE);
		bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(nested)));
		ItemStack crossbow = new ItemStack(Items.CROSSBOW);
		crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(List.of(nested)));
		ItemStack food = new ItemStack(Items.APPLE);
		food.set(DataComponents.FOOD, new FoodProperties(1, 0.1F, false, 1.6F, Optional.of(nested), List.of()));
		List<ItemStack> holders = List.of(chest, bundle, crossbow, food);
		for (ItemStack holder : holders) holder.set(DataComponents.CUSTOM_NAME, Component.literal("retained name"));
		return holders;
	}

	@Test
	void terminalProjectileSafetyAddsNoCopiedProfileCallbacksBeyondNestedDecode(@TempDir Path tempDir) {
		UUID headId = UUID.randomUUID();
		ResolvableProfile transmitted = new ResolvableProfile(Optional.empty(), Optional.of(headId), new PropertyMap());
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, transmitted);
		ItemStack lore = new ItemStack(Items.STONE);
		lore.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("prohibited later projectile"))));
		String laterLore = projectileJson(List.of(head, lore));
		head.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("prohibited head Lore"))));
		String headLore = projectileJson(List.of(head));

		// Build fixtures before installing the service: ChargedProjectiles.of
		// itself copies. Warm the future, but leave every checked-executor
		// callback queued so native nested heads remain genuinely unresolved.
		BlockingQueue<Runnable> callbacks = installVanillaProfileSeam(tempDir, headId, "ResolvedName");
		SkullBlockEntity.fetchGameProfile(headId).join();
		InventoryResidentAccess raw = InventoryResidentAccess.load();
		for (String encoded : List.of(headLore, laterLore)) {
			ClientItemDisplay.reset();
			callbacks.clear();
			ItemStack baseline = ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).orElseThrow();
			int decodeCallbacks = callbacks.size();
			assertEquals(1, decodeCallbacks, "the unchanged native nested codec already queues one head callback");
			ChargedProjectiles projectiles = baseline.get(DataComponents.CHARGED_PROJECTILES);
			ItemStack original = raw.projectiles(projectiles).getFirst();
			assertEquals(transmitted, original.get(DataComponents.PROFILE));
			assertFalse(original.get(DataComponents.PROFILE).isResolved());

			// Positive control: accessing the original vanilla transforming list
			// starts a second callback on a disposable copy before Lore is read.
			ItemStack copied = projectiles.getItems().getFirst();
			assertNotSame(original, copied);
			assertEquals(decodeCallbacks + 1, callbacks.size(), "the copying getter must expose the reviewed defect");
			callbacks.remove().run();
			assertFalse(original.get(DataComponents.PROFILE).isResolved(), "the native decode callback cannot mutate the independently detached returned nested head");
			assertFalse(copied.get(DataComponents.PROFILE).isResolved(), "the copy has a separate pending callback");
			callbacks.remove().run();
			assertTrue(copied.get(DataComponents.PROFILE).isResolved());

			var terminal = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:crossbow", "fallback.label", encoded);
			assertTrue(terminal.stack().isEmpty());
			assertEquals("item.minecraft.crossbow", contents(terminal.name()).getKey());
			assertEquals(decodeCallbacks, callbacks.size(), "terminal safety adds no callbacks beyond native nested decoding");
			assertEquals(1, ClientItemDisplay.cachedEntryCount());
			int misses = ClientItemDisplay.decodeMisses();
			for (int hit = 0; hit < 3; hit++) {
				assertTrue(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:crossbow", "fallback.label", encoded).stack().isEmpty());
				assertEquals(misses, ClientItemDisplay.decodeMisses());
				assertEquals(decodeCallbacks, callbacks.size(), "cache hits never repeat copied-profile work");
			}
		}
	}

	@Test
	void ordinaryProjectilesKeepTheirSupportedNestedDisplayContents() {
		ItemStack arrow = new ItemStack(Items.ARROW, 3);
		arrow.set(DataComponents.CUSTOM_NAME, Component.literal("kept projectile"));
		arrow.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(9));
		String encoded = projectileJson(List.of(arrow));
		for (int resolve = 0; resolve < 2; resolve++) {
			var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:crossbow", "fallback.label", encoded);
			assertFalse(display.stack().isEmpty());
			assertEquals(1, display.stack().getCount());
			var nested = InventoryResidentAccess.load().projectiles(display.stack().get(DataComponents.CHARGED_PROJECTILES));
			assertEquals(1, nested.size());
			assertEquals(Items.ARROW, nested.getFirst().getItem());
			assertEquals(3, nested.getFirst().getCount());
			assertEquals("kept projectile", nested.getFirst().get(DataComponents.CUSTOM_NAME).getString());
			assertEquals(new CustomModelData(9), nested.getFirst().get(DataComponents.CUSTOM_MODEL_DATA));
		}
	}

	@Test
	void everyOrdinaryItemHolderDetachesNestedTextStacksAndCountOnCacheHits() {
		for (ItemStack holder : nestedHolders(new ItemStack(Items.STONE, 3))) {
			String id = BuiltInRegistries.ITEM.getKey(holder.getItem()).toString();
			ItemStack nested = heldItem(holder);
			nested.set(DataComponents.CUSTOM_NAME, Component.literal("nested before"));
			String encoded = json(holder);
			// FOOD's native using_converts_to field is a SINGLE_ITEM_CODEC; the
			// other holders carry count-bearing nested stacks. Compare the actual
			// native wire semantics rather than this fixture's resident count.
			int expectedCount = holder.has(DataComponents.FOOD) ? 1 : 3;
			var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, id, "fallback", encoded);
			assertFalse(first.stack().isEmpty(), id);
			ItemStack firstNested = heldItem(first.stack());
			assertEquals(expectedCount, firstNested.getCount(), id);
			((MutableComponent) firstNested.get(DataComponents.CUSTOM_NAME)).append(" changed");
			firstNested.setCount(1);
			int misses = ClientItemDisplay.decodeMisses();
			var next = ClientItemDisplay.resolve(RegistryAccess.EMPTY, id, "fallback", encoded);
			assertFalse(next.stack().isEmpty(), id);
			assertNotSame(firstNested, heldItem(next.stack()), id);
			assertEquals("nested before", heldItem(next.stack()).get(DataComponents.CUSTOM_NAME).getString(), id);
			assertEquals(expectedCount, heldItem(next.stack()).getCount(), id);
			assertEquals(misses, ClientItemDisplay.decodeMisses(), "nested isolation keeps the cache: " + id);
		}
	}

	private static ItemStack heldItem(ItemStack holder) {
		InventoryResidentAccess raw = InventoryResidentAccess.load();
		if (holder.has(DataComponents.CONTAINER)) return raw.container(holder.get(DataComponents.CONTAINER)).getFirst();
		if (holder.has(DataComponents.BUNDLE_CONTENTS)) return raw.bundle(holder.get(DataComponents.BUNDLE_CONTENTS)).getFirst();
		if (holder.has(DataComponents.CHARGED_PROJECTILES)) return raw.projectiles(holder.get(DataComponents.CHARGED_PROJECTILES)).getFirst();
		return holder.get(DataComponents.FOOD).usingConvertsTo().orElseThrow();
	}

	@Test
	void cachedBooksProfileDataAndPotionEffectsOwnTheirMutableValues() {
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new net.minecraft.world.item.component.WrittenBookContent(
			net.minecraft.server.network.Filterable.passThrough("title"), "author", 0,
			List.of(new net.minecraft.server.network.Filterable<>(Component.literal("page"), Optional.of(Component.literal("filtered")))), true));
		String bookJson = json(book);
		ItemStack firstBook = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:written_book", "fallback", bookJson).stack();
		assertFalse(firstBook.isEmpty());
		var page = firstBook.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().getFirst();
		((MutableComponent) page.raw()).append(" changed"); ((MutableComponent) page.filtered().orElseThrow()).append(" changed");
		ItemStack nextBook = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:written_book", "fallback", bookJson).stack();
		assertEquals("page", nextBook.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().getFirst().raw().getString());
		assertEquals("filtered", nextBook.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().getFirst().filtered().orElseThrow().getString());

		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		PropertyMap properties = new PropertyMap(); properties.put("textures", new Property("textures", "before"));
		head.set(DataComponents.PROFILE, new ResolvableProfile(Optional.of("Name"), Optional.of(UUID.randomUUID()), properties));
		String headJson = json(head);
		ItemStack firstHead = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "fallback", headJson).stack();
		assertFalse(firstHead.isEmpty());
		firstHead.get(DataComponents.PROFILE).properties().clear();
		firstHead.get(DataComponents.PROFILE).gameProfile().getProperties().clear();
		ItemStack nextHead = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "fallback", headJson).stack();
		assertEquals("before", nextHead.get(DataComponents.PROFILE).properties().get("textures").iterator().next().value());
		assertEquals("before", nextHead.get(DataComponents.PROFILE).gameProfile().getProperties().get("textures").iterator().next().value());

		ItemStack stone = new ItemStack(Items.STONE); CompoundTag data = new CompoundTag(); data.putString("key", "before");
		stone.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); String dataJson = json(stone);
		ItemStack firstData = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", dataJson).stack();
		assertFalse(firstData.isEmpty()); firstData.get(DataComponents.CUSTOM_DATA).getUnsafe().putString("key", "changed");
		assertEquals("before", ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", dataJson).stack().get(DataComponents.CUSTOM_DATA).copyTag().getString("key"));

		ItemStack potion = new ItemStack(Items.POTION);
		potion.set(DataComponents.POTION_CONTENTS, new net.minecraft.world.item.alchemy.PotionContents(Optional.empty(), Optional.empty(),
			List.of(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 100, 0))));
		String potionJson = json(potion);
		ItemStack firstPotion = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:potion", "fallback", potionJson).stack();
		assertFalse(firstPotion.isEmpty());
		var raw = InventoryResidentAccess.load();
		assertTrue(raw.potion(firstPotion.get(DataComponents.POTION_CONTENTS)).getFirst().update(
			new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 200, 0)));
		assertEquals(100, raw.potion(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:potion", "fallback", potionJson).stack().get(DataComponents.POTION_CONTENTS)).getFirst().getDuration());
	}

	private static String projectileJson(List<ItemStack> projectiles) {
		ItemStack crossbow = new ItemStack(Items.CROSSBOW);
		crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(projectiles));
		return json(crossbow);
	}

	@Test
	void malformedNestedStackCannotRecoverToAnOuterPrototype() {
		String malformed = "{\"id\":\"minecraft:chest\",\"components\":{\"!minecraft:lore\":{},"
			+ "\"minecraft:container\":[{\"slot\":0,\"item\":{\"id\":\"pingforit:missing_item\"}}]}}";
		var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:chest", "block.minecraft.chest", malformed);
		assertTrue(display.stack().isEmpty());
		assertEquals("block.minecraft.chest", contents(display.name()).getKey());
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, malformed).isEmpty());
		assertEquals(0, ClientItemDisplay.cachedEntryCount());
	}

	@Test
	void customAndItemNameUseTheChatCompositionWithFlattenedPlainText() {
		ItemStack stack = new ItemStack(Items.GUNPOWDER);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("named{root}").withColor(0xAA0000)
			.append(Component.literal("[%s]child").withStyle(Style.EMPTY.withItalic(true))));
		var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:gunpowder",
			"item.minecraft.gunpowder", json(stack));
		TranslatableContents composed = contents(display.name());
		assertEquals(ContentChatTemplate.CUSTOM_ITEM_NAME_KEY, composed.getKey());
		TranslatableContents quoted = contents((Component) composed.getArgs()[0]);
		assertEquals(ContentChatTemplate.QUOTE_KEY, quoted.getKey());
		Component plain = (Component) quoted.getArgs()[0];
		assertEquals("named{root}[%s]child", plain.getString(), "the transmitted custom text is flattened to plain text");
		assertEquals(Style.EMPTY, plain.getStyle(), "incoming custom styling never reaches the display name");
		assertEquals("item.minecraft.gunpowder", contents((Component) composed.getArgs()[1]).getKey(),
			"the trusted base stays a registry translation key");
		assertEquals("named{root}[%s]child", display.stack().get(DataComponents.CUSTOM_NAME).getString(),
			"the icon stack keeps the transmitted component");

		ItemStack itemName = new ItemStack(Items.GUNPOWDER);
		itemName.set(DataComponents.ITEM_NAME, Component.literal("Item Name"));
		var itemNameDisplay = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:gunpowder",
			"item.minecraft.gunpowder", json(itemName));
		assertEquals(ContentChatTemplate.CUSTOM_ITEM_NAME_KEY, contents(itemNameDisplay.name()).getKey(),
			"ITEM_NAME is server-provided data and keeps the same precedence");
	}

	@Test
	void normalVariantKeepsItsStackDependentLocalizedName() {
		ItemStack potion = new ItemStack(Items.POTION);
		potion.set(DataComponents.POTION_CONTENTS, new net.minecraft.world.item.alchemy.PotionContents(
			net.minecraft.world.item.alchemy.Potions.WATER));
		var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:potion",
			"item.minecraft.potion", json(potion));
		TranslatableContents name = contents(display.name());
		assertEquals("item.minecraft.potion.effect.water", name.getKey(),
			"a normal variant keeps the item implementation's localized variant name");
		assertNull(display.name().getSiblings().stream()
			.filter(sibling -> sibling.getContents() instanceof TranslatableContents contents
				&& contents.getKey().equals(ContentChatTemplate.QUOTE_KEY)).findFirst().orElse(null),
			"a normal variant is not quoted as a custom name");
	}

	@Test
	void decodedStackKeepsEnchantmentsGlintOverrideCustomDataAndCustomModelData() {
		HolderLookup.Provider enchantedRegistry = registryWithSharpness();
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.set(DataComponents.ENCHANTMENTS, sharpness(3, enchantedRegistry));
		sword.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(7));
		CompoundTag custom = new CompoundTag();
		custom.putString("pingforit:marker", "kept");
		sword.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
		String encoded = json(sword, enchantedRegistry);
		assertTrue(encoded.contains("minecraft:sharpness"), "the transmitted payload carries the enchantment identity");

		ItemStack decoded = ClientItemDisplay.resolve(enchantedRegistry, "minecraft:diamond_sword",
			"item.minecraft.diamond_sword", encoded).stack();
		assertTrue(decoded.isEnchanted());
		assertTrue(decoded.hasFoil(), "the enchantment reaches the icon's native glint decision");
		assertEquals(3, decoded.getEnchantments().getLevel(
			enchantedRegistry.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)));
		assertEquals(new CustomModelData(7), decoded.get(DataComponents.CUSTOM_MODEL_DATA));
		assertEquals("kept", decoded.get(DataComponents.CUSTOM_DATA).copyTag().getString("pingforit:marker"),
			"the persistent custom data reaches the icon stack");
		assertEquals(1, decoded.getCount());

		// A registry context without the dynamic enchantment registry cannot
		// resolve that component, so the icon is unavailable instead of
		// half-decoding the variant or reconstructing prototype defaults.
		var withoutEnchantments = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:diamond_sword",
			"item.minecraft.diamond_sword", encoded);
		assertFalse(withoutEnchantments.stack().isEnchanted());
		assertTrue(withoutEnchantments.stack().isEmpty());
		assertEquals("item.minecraft.diamond_sword", contents(withoutEnchantments.name()).getKey());

		ItemStack overridden = new ItemStack(Items.STONE);
		overridden.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		ItemStack overriddenDecoded = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone",
			"item.minecraft.stone", json(overridden)).stack();
		assertEquals(Boolean.TRUE, overriddenDecoded.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
		assertTrue(overriddenDecoded.hasFoil(), "a glint override reaches the icon's native glint decision");
	}

	/**
	 * A transmitted patch can remove a prototype component. The retained
	 * snapshot must keep the removal, the native render stack must apply it, and
	 * the non-verifying naming stack must reach the same effective components,
	 * so no removed prototype value is resurrected for the icon or its name.
	 */
	@Test
	void transmittedComponentRemovalsSurviveTheRetainedNamingAndRenderStacks() {
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		assertNotNull(sword.get(DataComponents.MAX_DAMAGE), "the prototype carries the removable component");
		sword.remove(DataComponents.MAX_DAMAGE);
		String swordJson = json(sword);
		assertTrue(swordJson.contains("!minecraft:max_damage"), "the transmitted payload carries the removal");

		var swordDisplay = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:diamond_sword",
			"item.minecraft.diamond_sword", swordJson);
		assertNull(swordDisplay.stack().get(DataComponents.MAX_DAMAGE),
			"the native render stack applies the transmitted removal");
		assertFalse(swordDisplay.stack().isDamageableItem(), "the removal reaches native damageability");
		DataComponentPatch retained = ClientItemDisplay
			.cachedTransmittedPatch(RegistryAccess.EMPTY, "minecraft:diamond_sword", swordJson)
			.orElseThrow();
		assertNotNull(retained.get(DataComponents.MAX_DAMAGE), "the retained patch keeps an explicit removal entry");
		assertTrue(retained.get(DataComponents.MAX_DAMAGE).isEmpty(), "the retained removal marks the component removed");

		// A name-relevant prototype component exercises the naming stack's
		// removal path through the same transmitted form.
		ItemStack potion = new ItemStack(Items.POTION);
		assertNotNull(potion.get(DataComponents.POTION_CONTENTS), "the prototype carries the removable potion contents");
		potion.remove(DataComponents.POTION_CONTENTS);
		String potionJson = json(potion);
		assertTrue(potionJson.contains("!minecraft:potion_contents"), "the transmitted payload carries the removal");
		var potionDisplay = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:potion",
			"item.minecraft.potion", potionJson);
		assertNull(potionDisplay.stack().get(DataComponents.POTION_CONTENTS),
			"the render stack applies the potion-contents removal");
		assertEquals("item.minecraft.potion.effect.empty", contents(potionDisplay.name()).getKey(),
			"the naming stack applies the removal instead of resurrecting the prototype contents");
		assertEquals(potionDisplay.stack().getDescriptionId(), contents(potionDisplay.name()).getKey(),
			"the naming stack reaches the same removal-applied description as the render stack");

		// The non-removed control keeps the prototype component through the same path.
		var control = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:diamond_sword",
			"item.minecraft.diamond_sword", json(new ItemStack(Items.DIAMOND_SWORD)));
		assertNotNull(control.stack().get(DataComponents.MAX_DAMAGE));
		assertTrue(control.stack().isDamageableItem());
	}

	@Test
	void sameIdVariantsStayDistinctAndIdenticalPayloadsReuseTheTemplateWithoutAliasing() {
		ItemStack first = new ItemStack(Items.STONE);
		first.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(1));
		ItemStack second = new ItemStack(Items.STONE);
		second.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(2));
		String firstJson = json(first), secondJson = json(second);
		assertNotEquals(firstJson, secondJson);

		var firstDisplay = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", firstJson);
		var secondDisplay = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", secondJson);
		assertEquals(new CustomModelData(1), firstDisplay.stack().get(DataComponents.CUSTOM_MODEL_DATA));
		assertEquals(new CustomModelData(2), secondDisplay.stack().get(DataComponents.CUSTOM_MODEL_DATA));
		assertNotSame(firstDisplay.stack(), secondDisplay.stack(), "same-id variants never alias one decode");

		int missesBeforeRepeat = ClientItemDisplay.decodeMisses();
		var repeat = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", firstJson);
		assertEquals(missesBeforeRepeat, ClientItemDisplay.decodeMisses(), "an unchanged payload reuses its cached template");
		assertNotSame(firstDisplay.stack(), repeat.stack(), "a cache hit still returns an independently owned stack");
		assertEquals(new CustomModelData(1), repeat.stack().get(DataComponents.CUSTOM_MODEL_DATA));

		// Mutating one returned working stack never changes another caller's
		// result or the cached template behind the next resolution.
		repeat.stack().remove(DataComponents.CUSTOM_MODEL_DATA);
		repeat.stack().set(DataComponents.CUSTOM_NAME, Component.literal("mutated"));
		assertEquals(new CustomModelData(1), firstDisplay.stack().get(DataComponents.CUSTOM_MODEL_DATA),
			"another caller's result is unaffected");
		var afterMutation = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", firstJson);
		assertEquals(new CustomModelData(1), afterMutation.stack().get(DataComponents.CUSTOM_MODEL_DATA),
			"mutating a returned working stack never mutates the transmitted template");
		assertNull(afterMutation.stack().get(DataComponents.CUSTOM_NAME));

		// A metadata replacement revision with different components never serves
		// the old decode; the still-current variant keeps its own cached entry.
		ItemStack replacement = new ItemStack(Items.STONE);
		replacement.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(9));
		String replacementJson = json(replacement);
		var replaced = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", replacementJson);
		assertEquals(new CustomModelData(9), replaced.stack().get(DataComponents.CUSTOM_MODEL_DATA),
			"a replaced payload never aliases the old decode");
		assertEquals(new CustomModelData(1), ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone",
			"item.minecraft.stone", firstJson).stack().get(DataComponents.CUSTOM_MODEL_DATA),
			"the replacement does not evict an unrelated cached variant");
	}

	@Test
	void cacheStaysWithinItsEntryAndByteBounds() {
		for (int i = 0; i < ClientItemDisplay.MAX_CACHE_ENTRIES + 40; i++) {
			ItemStack stack = new ItemStack(Items.STONE);
			stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(i));
			ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", json(stack));
		}
		assertTrue(ClientItemDisplay.cachedEntryCount() <= ClientItemDisplay.MAX_CACHE_ENTRIES);
		assertTrue(ClientItemDisplay.cachedBytes() <= ClientItemDisplay.MAX_CACHE_BYTES);

		ClientItemDisplay.reset();
		for (int i = 0; i < 120; i++) {
			ItemStack stack = new ItemStack(Items.STONE);
			stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(i));
			stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("x".repeat(3000)))));
			ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", json(stack));
		}
		assertTrue(ClientItemDisplay.cachedEntryCount() <= ClientItemDisplay.MAX_CACHE_ENTRIES);
		assertTrue(ClientItemDisplay.cachedBytes() <= ClientItemDisplay.MAX_CACHE_BYTES,
			"large payloads are evicted instead of growing the cache past its byte bound");
	}

	@Test
	void registryIdentityAndExplicitResetClearTheCache() {
		ItemStack stack = new ItemStack(Items.GUNPOWDER);
		String encoded = json(stack);
		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:gunpowder", "item.minecraft.gunpowder", encoded);
		int missesAfterFirst = ClientItemDisplay.decodeMisses();
		var repeat = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:gunpowder", "item.minecraft.gunpowder", encoded);
		assertEquals(missesAfterFirst, ClientItemDisplay.decodeMisses(), "the same context serves the cached template");
		assertNotSame(first.stack(), repeat.stack(), "cache reuse never hands out a shared stack");

		ClientItemDisplay.reset();
		assertEquals(0, ClientItemDisplay.decodeMisses(), "the lifecycle reset also clears the miss accounting");
		var afterReset = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:gunpowder", "item.minecraft.gunpowder", encoded);
		assertEquals(1, ClientItemDisplay.decodeMisses(), "an explicit lifecycle reset decodes again");
		assertEquals(Items.GUNPOWDER, afterReset.stack().getItem());

		// A distinct registry provider is isolated from the first context's cache.
		var otherRegistry = new RegistryAccess.ImmutableRegistryAccess(List.of()).freeze();
		var other = ClientItemDisplay.resolve(otherRegistry, "minecraft:gunpowder", "item.minecraft.gunpowder", encoded);
		assertEquals(Items.GUNPOWDER, other.stack().getItem());
		assertEquals(2, ClientItemDisplay.decodeMisses(), "a different registry provider decodes for its own context");
	}

	@Test
	void decodedIdentityMustMatchTheSuppliedOpaqueItemId() {
		ItemStack diamond = new ItemStack(Items.DIAMOND);
		diamond.set(DataComponents.CUSTOM_NAME, Component.literal("Opaque Diamond"));
		ItemStack stone = new ItemStack(Items.STONE);
		stone.set(DataComponents.CUSTOM_NAME, Component.literal("Selected Stone"));
		String diamondJson = json(diamond), stoneJson = json(stone);

		// The decoded diamond must not be accepted under a stone selection
		// identity; the icon becomes unavailable and the name uses the supplied label.
		var mismatched = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", diamondJson);
		assertTrue(mismatched.stack().isEmpty(), "the mismatch never reconstructs the supplied item's prototype");
		assertEquals("item.minecraft.stone", contents(mismatched.name()).getKey(),
			"the mismatch keeps the supplied label, never the decoded custom name");
		assertEquals(0, ClientItemDisplay.cachedEntryCount(), "a mismatched decode is never cached");

		// The mismatch never poisons the correctly identified decode of the same payload.
		var correct = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:diamond", "item.minecraft.diamond", diamondJson);
		assertEquals(Items.DIAMOND, correct.stack().getItem());
		assertEquals("Opaque Diamond", correct.stack().get(DataComponents.CUSTOM_NAME).getString());
		assertEquals(ContentChatTemplate.CUSTOM_ITEM_NAME_KEY, contents(correct.name()).getKey());

		// A valid same-ID variant keeps its transmitted components and registry
		// name; the mismatch entry did not merge into it.
		ItemStack variant = new ItemStack(Items.STONE);
		variant.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(4));
		var variantDisplay = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", json(variant));
		assertEquals(Items.STONE, variantDisplay.stack().getItem());
		assertEquals(new CustomModelData(4), variantDisplay.stack().get(DataComponents.CUSTOM_MODEL_DATA));
		assertEquals("block.minecraft.stone", contents(variantDisplay.name()).getKey(),
			"a valid decode keeps the item implementation's own localized base name, not the supplied label");
		assertNull(variantDisplay.stack().get(DataComponents.CUSTOM_NAME));

		// An unbound or registry-unknown opaque ID cannot confirm a decode either.
		assertTrue(ClientItemDisplay.resolve(RegistryAccess.EMPTY, null, "item.minecraft.stone", stoneJson).stack().isEmpty());
		assertTrue(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "pingforit:missing_item", "item.minecraft.stone", stoneJson)
			.stack().isEmpty());

		// The codec-only API has no expected ID and keeps serving the chat path.
		assertEquals(Items.DIAMOND, ClientItemDisplay.decode(RegistryAccess.EMPTY, diamondJson).orElseThrow().getItem());
	}

	@Test
	void cachedTemplatesAreNeverHandedOutAsMutableRenderStacks() {
		ResolvableProfile profile = new ResolvableProfile(Optional.of("Herobrine"), Optional.of(UUID.randomUUID()), new PropertyMap());
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, profile);
		head.set(DataComponents.CUSTOM_NAME, Component.literal("Unresolved Head"));
		String encoded = json(head);
		assertTrue(encoded.contains("minecraft:profile"), "the transmitted payload carries the unresolved profile");

		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		var second = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals(1, first.stack().getCount());
		assertEquals(profile, first.stack().get(DataComponents.PROFILE));
		assertNotSame(first.stack(), second.stack(), "distinct callers never share one working stack");

		// The native player-head route removes and asynchronously replaces
		// PROFILE on the stack it was handed; that must not touch the
		// transmitted template, future resolutions or the display name.
		String nameBefore = first.name().getString();
		assertEquals(profile, first.stack().remove(DataComponents.PROFILE));
		first.stack().set(DataComponents.PROFILE, new ResolvableProfile(Optional.of("SomeoneElse"), Optional.empty(), new PropertyMap()));
		assertEquals(profile, second.stack().get(DataComponents.PROFILE), "another caller's working stack is untouched");

		var afterMutation = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals(profile, afterMutation.stack().get(DataComponents.PROFILE),
			"the transmitted profile survives every mutation of a returned working stack");
		assertEquals(nameBefore, afterMutation.name().getString(), "the display name stays based on the transmitted template");

		// A variant without a transmitted profile must not gain one from a
		// mutation of a returned stack.
		ItemStack bare = new ItemStack(Items.PLAYER_HEAD);
		bare.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(3));
		String bareJson = json(bare);
		var bareDisplay = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", bareJson);
		assertNull(bareDisplay.stack().get(DataComponents.PROFILE));
		bareDisplay.stack().set(DataComponents.PROFILE, profile);
		assertNull(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", bareJson)
			.stack().get(DataComponents.PROFILE), "an added component never enters the transmitted template");

		// Exposed name components are freshly composed from the template, so a
		// caller mutating one never changes a later resolution.
		MutableComponent name = (MutableComponent) afterMutation.name();
		name.append(" corrupted");
		var afterNameMutation = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals(nameBefore, afterNameMutation.name().getString(), "the name composition is rebuilt from the template");
	}

	/**
	 * The reported ownership defect: the vanilla {@link ItemStack} constructor
	 * runs {@code PlayerHeadItem.verifyComponentsAfterLoad}, which captures the
	 * constructed stack and schedules a callback writing a resolved PROFILE
	 * onto it. A constructed stack retained as the cache template was therefore
	 * mutable by a callback no consumer had handed it to, and later resolves
	 * could gain a fabricated player name. This test drives the real vanilla
	 * callback route end to end: the real constructor hook, the real
	 * {@link ResolvableProfile#resolve()} chain, the real
	 * {@link SkullBlockEntity} caches and the real checked main-thread executor
	 * hand-off, with only the network boundary replaced by an in-memory
	 * repository/session service and the main-thread executor replaced by a
	 * queue drained by the test.
	 */
	@Test
	void unresolvedIdOnlyProfileCallbackResolvesWorkingStacksButNeverTheCachedTransmission(@TempDir Path tempDir) throws InterruptedException {
		UUID headId = UUID.randomUUID();
		BlockingQueue<Runnable> callbacks = installVanillaProfileSeam(tempDir, headId, "ResolvedName");

		ResolvableProfile transmitted = new ResolvableProfile(Optional.empty(), Optional.of(headId), new PropertyMap());
		assertFalse(transmitted.isResolved(), "an ID-only profile is genuinely unresolved and must hit the vanilla callback");
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, transmitted);
		String encoded = json(head);

		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals(transmitted, first.stack().get(DataComponents.PROFILE), "the working stack starts from the transmitted identity");
		assertEquals("block.minecraft.player_head", contents(first.name()).getKey(),
			"an ID-only profile keeps the native base name instead of a fabricated player name");

		// Run the real queued player-head callback: it must be allowed to
		// resolve the independently owned working stack it captured.
		runQueuedProfileCallbacks(callbacks, first.stack());
		ResolvableProfile resolvedOnWorkingStack = first.stack().get(DataComponents.PROFILE);
		assertNotEquals(transmitted, resolvedOnWorkingStack, "the native callback resolves the working stack");
		assertTrue(resolvedOnWorkingStack.isResolved());
		assertEquals("ResolvedName", resolvedOnWorkingStack.name().orElseThrow(),
			"the live profile service result lands on the working stack, not the retained snapshot");

		// The transmitted snapshot behind the cache is untouched by that
		// callback, so later resolves keep the transmitted identity and name.
		int missesAfterFirst = ClientItemDisplay.decodeMisses();
		var second = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals(missesAfterFirst, ClientItemDisplay.decodeMisses(), "the callback does not invalidate the cached snapshot");
		assertNotSame(first.stack(), second.stack(), "every resolve still hands out its own working stack");
		assertEquals(transmitted, second.stack().get(DataComponents.PROFILE),
			"callback execution never reaches the cached transmitted components");
		assertEquals("block.minecraft.player_head", contents(second.name()).getKey(),
			"the name stays composed from the transmitted identity, not from a resolved working stack");

		runQueuedProfileCallbacks(callbacks, second.stack());
		var third = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals(transmitted, third.stack().get(DataComponents.PROFILE));
		assertEquals("block.minecraft.player_head", contents(third.name()).getKey(),
			"a second callback round still cannot mutate the retained transmission");
	}

	/**
	 * The name-only direction of the same defect: the live service canonicalizes
	 * the transmitted name, so a retained constructed template could change the
	 * display name after the callback. The transmitted snapshot keeps the
	 * transmitted name across resolves while the working stack may resolve.
	 */
	@Test
	void unresolvedNameOnlyProfileCallbackCannotChangeTheTransmittedDisplayName(@TempDir Path tempDir) throws InterruptedException {
		BlockingQueue<Runnable> callbacks = installVanillaProfileSeam(tempDir, UUID.randomUUID(), "Herobrine");

		ResolvableProfile transmitted = new ResolvableProfile(Optional.of("herobrine"), Optional.empty(), new PropertyMap());
		assertFalse(transmitted.isResolved(), "a name-only profile is genuinely unresolved and must hit the vanilla callback");
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, transmitted);
		String encoded = json(head);

		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals(transmitted, first.stack().get(DataComponents.PROFILE));
		TranslatableContents firstName = contents(first.name());
		assertEquals("block.minecraft.player_head.named", firstName.getKey(),
			"a name-only profile keeps the item implementation's native variant name");
		assertEquals("herobrine", firstName.getArgs()[0], "the transmitted name drives the displayed variant name");

		runQueuedProfileCallbacks(callbacks, first.stack());
		ResolvableProfile resolvedOnWorkingStack = first.stack().get(DataComponents.PROFILE);
		assertEquals("Herobrine", resolvedOnWorkingStack.name().orElseThrow(),
			"the live service canonicalizes the profile on the independently owned working stack");

		var second = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals(transmitted, second.stack().get(DataComponents.PROFILE),
			"the transmitted name-only profile survives callback execution");
		TranslatableContents secondName = contents(second.name());
		assertEquals("herobrine", secondName.getArgs()[0],
			"later resolves keep the transmitted name even after a working stack gained a canonical resolved name");
	}

	/**
	 * Warmed-cache inline coverage for the decode boundary: the retained
	 * snapshot is decoded with the single-item grammar itself, so no stack
	 * construction exists that the player-head hook could resolve before the
	 * transmitted patch is captured. The warmed completed profile future runs
	 * the callback inline on the independently owned render stack, while the
	 * retained transmission and the name derived from it stay original across
	 * the initial resolve and a later cache hit.
	 */
	@Test
	void warmedInlineIdOnlyCallbackCannotChangeTheRetainedTransmissionOrName(@TempDir Path tempDir) {
		UUID headId = UUID.randomUUID();
		installVanillaProfileSeam(tempDir, headId, "ResolvedName", Runnable::run);
		// Warm the real vanilla profile cache so the verification chain
		// completes synchronously and its callback executes inline.
		SkullBlockEntity.fetchGameProfile(headId).join();

		ResolvableProfile transmitted = new ResolvableProfile(Optional.empty(), Optional.of(headId), new PropertyMap());
		assertFalse(transmitted.isResolved(), "an ID-only profile is genuinely unresolved");
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, transmitted);
		String encoded = json(head);

		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		ResolvableProfile inlineResolved = first.stack().get(DataComponents.PROFILE);
		assertNotNull(inlineResolved, "the render stack keeps its transmitted profile component");
		assertTrue(inlineResolved.isResolved(),
			"the warmed completed future executes the native callback inline on the render stack");
		assertEquals("ResolvedName", inlineResolved.name().orElseThrow(),
			"the inline resolution lands on the independently owned render stack");
		assertRetainedProfile(encoded, transmitted);
		assertEquals("block.minecraft.player_head", contents(first.name()).getKey(),
			"the displayed name is derived from the transmitted snapshot, not the inline-resolved render stack");

		var second = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertTrue(second.stack().get(DataComponents.PROFILE).isResolved(),
			"a cache hit still resolves its own render stack inline");
		assertRetainedProfile(encoded, transmitted);
		assertEquals("block.minecraft.player_head", contents(second.name()).getKey());
	}

	/**
	 * Warmed-cache inline coverage for the naming boundary in the name-only
	 * direction: the live service canonicalizes the profile name, so a naming
	 * path that ran load verification could display the canonical name. The
	 * non-verifying naming stack keeps the transmitted name while the render
	 * stack resolves inline.
	 */
	@Test
	void warmedInlineNameOnlyCallbackCannotChangeTheRetainedTransmissionOrName(@TempDir Path tempDir) {
		installVanillaProfileSeam(tempDir, UUID.randomUUID(), "Herobrine", Runnable::run);
		SkullBlockEntity.fetchGameProfile("herobrine").join();

		ResolvableProfile transmitted = new ResolvableProfile(Optional.of("herobrine"), Optional.empty(), new PropertyMap());
		assertFalse(transmitted.isResolved(), "a name-only profile is genuinely unresolved");
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, transmitted);
		String encoded = json(head);

		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		ResolvableProfile inlineResolved = first.stack().get(DataComponents.PROFILE);
		assertNotNull(inlineResolved, "the render stack keeps its transmitted profile component");
		assertEquals("Herobrine", inlineResolved.name().orElseThrow(),
			"the live service canonicalizes the profile on the render stack inline");
		assertRetainedProfile(encoded, transmitted);
		TranslatableContents firstName = contents(first.name());
		assertEquals("block.minecraft.player_head.named", firstName.getKey());
		assertEquals("herobrine", firstName.getArgs()[0],
			"the transmitted name survives inline canonicalization");

		var second = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:player_head", "item.minecraft.player_head", encoded);
		assertEquals("Herobrine", second.stack().get(DataComponents.PROFILE).name().orElseThrow());
		assertRetainedProfile(encoded, transmitted);
		assertEquals("herobrine", contents(second.name()).getArgs()[0]);
	}

	/**
	 * The codec-only chat composition ({@code decode} then {@code displayName},
	 * the exact call shape of the content-message item decoder) must keep the
	 * transmitted name when the profile callback can run inline; the shared
	 * display-name invocation therefore never reads a verification-resolved
	 * profile.
	 */
	@Test
	void warmedInlineCallbackCannotChangeTheCodecOnlyChatName(@TempDir Path tempDir) {
		UUID headId = UUID.randomUUID();
		installVanillaProfileSeam(tempDir, headId, "ResolvedName", Runnable::run);
		SkullBlockEntity.fetchGameProfile(headId).join();

		ResolvableProfile transmitted = new ResolvableProfile(Optional.empty(), Optional.of(headId), new PropertyMap());
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, transmitted);
		String encoded = json(head);

		ItemStack decoded = ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).orElseThrow();
		assertEquals(transmitted, decoded.get(DataComponents.PROFILE),
			"the codec-only chat stack keeps the transmitted profile under a warmed inline executor");
		assertFalse(decoded.get(DataComponents.PROFILE).isResolved());
		assertEquals("block.minecraft.player_head", contents(ClientItemDisplay.displayName(decoded)).getKey(),
			"the chat name is the transmitted base name, not an inline-resolved player name");
	}

	/**
	 * Asserts the retained transmitted patch for {@code minecraft:player_head}
	 * still carries the original unresolved profile.
	 */
	private static void assertRetainedProfile(String encoded, ResolvableProfile transmitted) {
		DataComponentPatch retained = ClientItemDisplay
			.cachedTransmittedPatch(RegistryAccess.EMPTY, "minecraft:player_head", encoded)
			.orElseThrow();
		assertNotNull(retained.get(DataComponents.PROFILE), "the retained patch keeps the transmitted profile entry");
		ResolvableProfile retainedProfile = retained.get(DataComponents.PROFILE).orElseThrow();
		assertEquals(transmitted, retainedProfile, "the retained patch keeps the transmitted profile");
		assertFalse(retainedProfile.isResolved(), "the retained transmitted profile stays unresolved");
	}

	/**
	 * Installs the real vanilla player-head resolution route with only the
	 * network boundary replaced: an in-memory profile repository and session
	 * service, a real {@link GameProfileCache}, the real
	 * {@link SkullBlockEntity#setup} caches and the real
	 * {@code CHECKED_MAIN_THREAD_EXECUTOR} hand-off. No live profile HTTP is
	 * involved. The supplied executor is the seam's main-thread executor: a
	 * queue keeps callbacks pending for the delayed tests, while
	 * {@code Runnable::run} executes warmed completed futures inline.
	 */
	private static BlockingQueue<Runnable> installVanillaProfileSeam(Path tempDir, UUID resolvedId, String resolvedName) {
		BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
		installVanillaProfileSeam(tempDir, resolvedId, resolvedName, callbacks::add);
		return callbacks;
	}

	private static void installVanillaProfileSeam(Path tempDir, UUID resolvedId, String resolvedName, Executor mainThreadExecutor) {
		GameProfile resolved = new GameProfile(resolvedId, resolvedName);
		MinecraftSessionService sessionService = new MinecraftSessionService() {
			@Override public ProfileResult fetchProfile(UUID profileId, boolean requireSecure) {
				return resolvedId.equals(profileId) ? new ProfileResult(resolved) : null;
			}
			@Override public void joinServer(UUID profileId, String authenticationToken, String serverId) {
				throw new UnsupportedOperationException("not used by the profile-lookup seam");
			}
			@Override public ProfileResult hasJoinedServer(String profileName, String serverId, InetAddress address) {
				throw new UnsupportedOperationException("not used by the profile-lookup seam");
			}
			@Override public Property getPackedTextures(GameProfile profile) {
				throw new UnsupportedOperationException("not used by the profile-lookup seam");
			}
			@Override public MinecraftProfileTextures unpackTextures(Property property) {
				throw new UnsupportedOperationException("not used by the profile-lookup seam");
			}
			@Override public String getSecurePropertyValue(Property property) {
				throw new UnsupportedOperationException("not used by the profile-lookup seam");
			}
		};
		GameProfileRepository repository = (names, callback) -> {
			for (String name : names) {
				if (resolvedName.equalsIgnoreCase(name)) {
					callback.onProfileLookupSucceeded(resolved);
					return;
				}
			}
			for (String name : names) {
				callback.onProfileLookupFailed(name, new IllegalArgumentException("unknown profile: " + name));
			}
		};
		GameProfileCache profileCache = new GameProfileCache(repository, tempDir.resolve("usercache.json").toFile());
		profileCache.setExecutor(Runnable::run);
		Services services = new Services(sessionService, ServicesKeySet.EMPTY, repository, profileCache);
		SkullBlockEntity.setup(services, mainThreadExecutor);
	}

	/**
	 * Drains the queue until the captured working stack's PROFILE has been
	 * resolved by the real callback, failing after a bounded wait if the
	 * vanilla route never delivers it.
	 */
	private static void runQueuedProfileCallbacks(BlockingQueue<Runnable> callbacks, ItemStack workingStack) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
		while (true) {
			ResolvableProfile profile = workingStack.get(DataComponents.PROFILE);
			if (profile != null && profile.isResolved()) {
				return;
			}
			assertTrue(System.nanoTime() < deadline,
				"the vanilla player-head callback never resolved the independently owned working stack");
			Runnable callback = callbacks.poll(50L, TimeUnit.MILLISECONDS);
			if (callback != null) {
				callback.run();
			}
		}
	}

	@Test
	void cacheHitRefreshesRecencyAndPressureEvictsTheLeastRecentlyResolvedVariant() {
		for (int i = 0; i < ClientItemDisplay.MAX_CACHE_ENTRIES; i++) {
			ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", variantJson("variant-" + i));
		}
		assertEquals(ClientItemDisplay.MAX_CACHE_ENTRIES, ClientItemDisplay.cachedEntryCount());
		int missesAfterFill = ClientItemDisplay.decodeMisses();
		assertEquals(ClientItemDisplay.MAX_CACHE_ENTRIES, missesAfterFill);

		// Resolving the oldest inserted entry again must make it the most
		// recently used one.
		ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", variantJson("variant-0"));
		assertEquals(missesAfterFill, ClientItemDisplay.decodeMisses(), "the hot variant is served from the cache");

		// One more variant overflows the entry bound; the least recently used
		// entry leaves, not the just-accessed one.
		ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", variantJson("variant-overflow"));
		assertEquals(ClientItemDisplay.MAX_CACHE_ENTRIES, ClientItemDisplay.cachedEntryCount());
		assertEquals(missesAfterFill + 1, ClientItemDisplay.decodeMisses());

		ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", variantJson("variant-0"));
		assertEquals(missesAfterFill + 1, ClientItemDisplay.decodeMisses(), "the recently accessed variant survived the pressure");
		ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", variantJson("variant-1"));
		assertEquals(missesAfterFill + 2, ClientItemDisplay.decodeMisses(), "the least recently used variant is decoded again");
	}

	private static String variantJson(String name) {
		ItemStack stack = new ItemStack(Items.STONE);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return json(stack);
	}

	@Test
	void decodedStacksAreAlwaysCountOneRegardlessOfTheEncodedSourceCount() {
		ItemStack stacked = new ItemStack(Items.STONE, 64);
		String encoded = json(stacked);
		assertFalse(encoded.contains("count"), "the single-item display form never carries an authoritative count");
		ItemStack decoded = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "item.minecraft.stone", encoded).stack();
		assertEquals(1, decoded.getCount(), "the icon stack is count-one, never the authoritative long count");
	}
}
