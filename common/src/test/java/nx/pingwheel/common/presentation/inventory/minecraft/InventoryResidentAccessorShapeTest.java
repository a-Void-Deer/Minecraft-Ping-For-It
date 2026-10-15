package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import nx.pingwheel.common.mixin.InventoryAdventurePredicatesAccessor;
import nx.pingwheel.common.mixin.InventoryBundleItemsAccessor;
import nx.pingwheel.common.mixin.InventoryComponentPatchAccessor;
import nx.pingwheel.common.mixin.InventoryCompoundTagAccessor;
import nx.pingwheel.common.mixin.InventoryContainerItemsAccessor;
import nx.pingwheel.common.mixin.InventoryHiddenEffectAccessor;
import nx.pingwheel.common.mixin.InventoryItemComponentsAccessor;
import nx.pingwheel.common.mixin.InventoryListTagAccessor;
import nx.pingwheel.common.mixin.InventoryNonNullListAccessor;
import nx.pingwheel.common.mixin.InventoryPatchedComponentsAccessor;
import nx.pingwheel.common.mixin.InventoryProjectileItemsAccessor;
import nx.pingwheel.common.mixin.InventoryReferenceMapCapacityAccessor;
import nx.pingwheel.common.mixin.InventorySimpleComponentsAccessor;

import static org.junit.jupiter.api.Assertions.*;

/** Mapped field/config shape evidence only: headless tests do not apply Mixin. */
class InventoryResidentAccessorShapeTest {
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void portsMatchNativeFieldDescriptorsAndAccessorAnnotations() throws Exception {
		shape(InventoryPatchedComponentsAccessor.class, "pingforit$prototype", PatchedDataComponentMap.class, "prototype");
		shape(InventoryPatchedComponentsAccessor.class, "pingforit$patch", PatchedDataComponentMap.class, "patch");
		shape(InventorySimpleComponentsAccessor.class, "pingforit$map",
			Class.forName("net.minecraft.core.component.DataComponentMap$Builder$SimpleMap"), "map");
		shape(InventoryComponentPatchAccessor.class, "pingforit$map", DataComponentPatch.class, "map");
		shape(InventoryItemComponentsAccessor.class, "pingforit$components", Item.class, "components");
		shape(InventoryContainerItemsAccessor.class, "pingforit$items", ItemContainerContents.class, "items");
		shape(InventoryProjectileItemsAccessor.class, "pingforit$items", ChargedProjectiles.class, "items");
		shape(InventoryBundleItemsAccessor.class, "pingforit$items", BundleContents.class, "items");
		shape(InventoryNonNullListAccessor.class, "pingforit$list", NonNullList.class, "list");
		shape(InventoryCompoundTagAccessor.class, "pingforit$tags", CompoundTag.class, "tags");
		shape(InventoryListTagAccessor.class, "pingforit$list", ListTag.class, "list");
		shape(InventoryHiddenEffectAccessor.class, "pingforit$hiddenEffect", MobEffectInstance.class, "hiddenEffect");
		shape(InventoryAdventurePredicatesAccessor.class, "pingforit$predicates", AdventureModePredicate.class, "predicates");
		shape(InventoryReferenceMapCapacityAccessor.class, "pingforit$capacity",
			it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap.class, "n");
		shape(nx.pingwheel.common.mixin.InventoryHolderReferenceAccessor.class, "pingforit$owner", net.minecraft.core.Holder.Reference.class, "owner");
		shape(nx.pingwheel.common.mixin.InventoryHolderReferenceAccessor.class, "pingforit$key", net.minecraft.core.Holder.Reference.class, "key");
		shape(nx.pingwheel.common.mixin.InventoryHolderReferenceAccessor.class, "pingforit$value", net.minecraft.core.Holder.Reference.class, "value");
		shape(nx.pingwheel.common.mixin.InventoryNamedHolderSetAccessor.class, "pingforit$owner", net.minecraft.core.HolderSet.Named.class, "owner");
		shape(nx.pingwheel.common.mixin.InventoryNamedHolderSetAccessor.class, "pingforit$key", net.minecraft.core.HolderSet.Named.class, "key");
		shape(nx.pingwheel.common.mixin.InventoryDirectHolderSetAccessor.class, "pingforit$contents", net.minecraft.core.HolderSet.Direct.class, "contents");
		shape(nx.pingwheel.common.mixin.InventoryRegistryAccessAccessor.class, "pingforit$registries", net.minecraft.core.RegistryAccess.ImmutableRegistryAccess.class, "registries");
		shape(nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor.class, "pingforit$key", net.minecraft.core.MappedRegistry.class, "key");
		shape(nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor.class, "pingforit$lookup", net.minecraft.core.MappedRegistry.class, "lookup");
		shape(nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor.class, "pingforit$lifecycle", net.minecraft.core.MappedRegistry.class, "registryLifecycle");
		shape(nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor.class, "pingforit$byKey", net.minecraft.core.MappedRegistry.class, "byKey");
		shape(nx.pingwheel.common.mixin.InventoryEnchantmentsAccessor.class, "pingforit$showInTooltip", net.minecraft.world.item.enchantment.ItemEnchantments.class, "showInTooltip");
		shape(nx.pingwheel.common.mixin.InventoryBuilderEntryAccessor.class, "pingforit$opsInfo", Class.forName("net.minecraft.core.RegistrySetBuilder$1Entry"), "opsInfo");
		shape(nx.pingwheel.common.mixin.InventoryBuilderOwnerAccessor.class, "pingforit$owner", Class.forName("net.minecraft.core.RegistrySetBuilder$EmptyTagLookup"), "owner");
	}

	@Test void injectedPortReturnsSameBackingObjectsRatherThanCombinedOrCopiedValues() {
		var access = new InventoryResidentAccessFields();
		var stack = new ItemStack(Items.STONE);
		stack.set(DataComponents.DAMAGE, 2);
		var components = (PatchedDataComponentMap) stack.getComponents();
		assertSame(InventoryResidentAccessFields.field(components, "prototype"), access.prototype(components));
		assertSame(InventoryResidentAccessFields.field(components, "patch"), access.patch(components));
		assertSame(InventoryResidentAccessFields.fieldAt(Items.STONE, Item.class, "components"),
			access.itemComponents(Items.STONE));
		DataComponentMap prototype = access.prototype(components);
		assertSame(InventoryResidentAccessFields.field(prototype, "map"), access.simple(prototype));
		assertSame(InventoryResidentAccessFields.field(DataComponentPatch.EMPTY, "map"), access.patch(DataComponentPatch.EMPTY));
		var compound = new CompoundTag();
		compound.putString("a", "b");
		assertSame(InventoryResidentAccessFields.field(compound, "tags"), access.compound(compound));
		var list = new ListTag();
		list.add(StringTag.valueOf("a"));
		assertSame(InventoryResidentAccessFields.field(list, "list"), access.list(list));
		NonNullList<ItemStack> slots = NonNullList.withSize(2, ItemStack.EMPTY);
		assertSame(InventoryResidentAccessFields.field(slots, "list"), access.nonNullList(slots));
	}

	@Test void allEffectiveConfigsRegisterRawPortsOnServerAndFoodOnlyOnMatchingLoaders() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		while (!Files.isRegularFile(root.resolve("settings.gradle"))
			&& !Files.isRegularFile(root.resolve("settings.gradle.kts"))) root = root.getParent();
		for (String module : List.of("common", "forge", "neoforge")) {
			var config = JsonParser.parseString(Files.readString(root.resolve(module + "/src/main/resources/pingforit.mixins.json")))
				.getAsJsonObject();
			var server = config.getAsJsonArray("mixins").asList().stream().map(value -> value.getAsString()).toList();
			var client = config.getAsJsonArray("client").asList().stream().map(value -> value.getAsString()).toList();
			for (Class<?> accessor : List.of(InventoryPatchedComponentsAccessor.class, InventorySimpleComponentsAccessor.class,
				InventoryComponentPatchAccessor.class, InventoryItemComponentsAccessor.class, InventoryContainerItemsAccessor.class,
				InventoryProjectileItemsAccessor.class, InventoryBundleItemsAccessor.class, InventoryNonNullListAccessor.class,
				InventoryCompoundTagAccessor.class, InventoryListTagAccessor.class, InventoryHiddenEffectAccessor.class,
				InventoryAdventurePredicatesAccessor.class, InventoryReferenceMapCapacityAccessor.class,
				nx.pingwheel.common.mixin.InventoryHolderReferenceAccessor.class, nx.pingwheel.common.mixin.InventoryNamedHolderSetAccessor.class,
				nx.pingwheel.common.mixin.InventoryDirectHolderSetAccessor.class, nx.pingwheel.common.mixin.InventoryRegistryAccessAccessor.class,
				nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor.class, nx.pingwheel.common.mixin.InventoryRegistryRootMixin.class,
				nx.pingwheel.common.mixin.InventoryMappedLookupMixin.class, nx.pingwheel.common.mixin.InventoryBuilderProviderMixin.class,
				nx.pingwheel.common.mixin.InventoryBuilderEntryAccessor.class, nx.pingwheel.common.mixin.InventoryBuilderLookupMixin.class,
				nx.pingwheel.common.mixin.InventoryBuilderOwnerAccessor.class)) {
				assertTrue(server.contains(accessor.getSimpleName()), module + ": " + accessor.getSimpleName());
				assertFalse(client.contains(accessor.getSimpleName()));
			}
			assertEquals(!module.equals("neoforge"), server.contains("InventoryFoodEffectAccessor"));
		}
	}

	private static void shape(Class<?> accessor, String method, Class<?> target, String name) throws Exception {
		Field field = target.getDeclaredField(name);
		assertEquals(field.getType(), accessor.getDeclaredMethod(method).getReturnType());
		ClassNode node = new ClassNode();
		try (var bytes = accessor.getResourceAsStream(accessor.getSimpleName() + ".class")) {
			new ClassReader(bytes).accept(node, 0);
		}
		var member = node.methods.stream().filter(value -> value.name.equals(method)).findFirst().orElseThrow();
		var annotations = new java.util.ArrayList<org.objectweb.asm.tree.AnnotationNode>();
		if (member.visibleAnnotations != null) annotations.addAll(member.visibleAnnotations);
		if (member.invisibleAnnotations != null) annotations.addAll(member.invisibleAnnotations);
		assertTrue(annotations.stream().anyMatch(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")
			&& value.values.contains(name)));
	}
}
