package nx.pingwheel.neoforge.mixin.presentation;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.MixinService;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

/** Independent fail-soft receipt gate; does not alter the existing summary/accessor plugin. */
public final class CreatePreviewMixinPlugin implements IMixinConfigPlugin {
	private static final String TARGET = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
	private final BooleanSupplier testedCreate;
	private final Supplier<IClassBytecodeProvider> bytecodeProvider;
	public CreatePreviewMixinPlugin() {
		this(CreatePreviewMixinPlugin::testedCreatePresent, () -> MixinService.getService().getBytecodeProvider());
	}
	/** Same loader ports as production, without initializing optional Create classes. */
	CreatePreviewMixinPlugin(BooleanSupplier testedCreate, Supplier<IClassBytecodeProvider> bytecodeProvider) {
		this.testedCreate = testedCreate;
		this.bytecodeProvider = bytecodeProvider;
	}
	@Override public boolean shouldApplyMixin(String target, String mixin) {
		if (!TARGET.equals(target) || !mixin.equals("nx.pingwheel.neoforge.mixin.presentation.KineticPreviewReceiptMixin")) return false;
		try {
			if (!testedCreate.getAsBoolean()) return false;
			// NeoForge's ModLauncher provider supports transformed bytecode only;
			// that lookup excludes Mixin and never initializes the Create class.
			return compatibleTarget(bytecodeProvider.get().getClassNode(TARGET, true));
		} catch (ClassNotFoundException | IOException | RuntimeException | LinkageError unavailable) { return false; }
	}
	private static boolean testedCreatePresent() {
		var loading = LoadingModList.get();
		var loaded = loading == null ? ModList.get() : null;
		if (loading == null && loaded == null) return false;
		var mods = loading == null ? loaded.getMods() : loading.getMods();
		return mods.stream().anyMatch(mod -> mod.getModId().equals("create")
			&& (mod.getVersion().toString().equals("6.0.10") || mod.getVersion().toString().equals("6.0.10-281")));
	}
	static boolean compatibleTarget(ClassNode node) {
		if (node == null || !node.name.equals(TARGET.replace('.', '/'))) return false;
		boolean moved = node.fields.stream().anyMatch(field -> field.name.equals("wasMoved") && field.desc.equals("Z")
			&& (field.access & Opcodes.ACC_PROTECTED) != 0 && (field.access & Opcodes.ACC_STATIC) == 0);
		return moved && method(node, "read", "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V")
			&& method(node, "clearKineticInformation", "()V") && method(node, "warnOfMovement", "()V");
	}
	private static boolean method(ClassNode node, String name, String descriptor) {
		return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor)
			&& (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0);
	}
	@Override public void onLoad(String mixinPackage) {}
	@Override public String getRefMapperConfig() { return null; }
	@Override public void acceptTargets(Set<String> own, Set<String> others) {}
	@Override public List<String> getMixins() { return List.of(); }
	@Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
	@Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
