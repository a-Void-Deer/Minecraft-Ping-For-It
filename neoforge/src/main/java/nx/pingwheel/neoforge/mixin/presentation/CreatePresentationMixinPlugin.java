package nx.pingwheel.neoforge.mixin.presentation;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.MixinService;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Fail-closed guard, evaluated before applying any Create accessor. Uses ASM
 * bytecode rather than loading Create's target class (which would be too early
 * for Mixin). A rejected accessor affects only cached stress/capacity.
 */
public final class CreatePresentationMixinPlugin implements IMixinConfigPlugin {
	private static final String TARGET = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
	private static final String MIXIN = "nx.pingwheel.neoforge.mixin.presentation.KineticCachedNetworkMixin";
	// The tested Maven artifact is 6.0.10-281; official mod metadata embeds
	// mod_version 6.0.10 without the artifact's build suffix.
	private static final String TESTED_MOD_VERSION = "6.0.10";
	private static final String TESTED_ARTIFACT_VERSION = "6.0.10-281";
	private final BooleanSupplier testedCreate;
	private final Supplier<IClassBytecodeProvider> bytecodeProvider;

	public CreatePresentationMixinPlugin() {
		this(CreatePresentationMixinPlugin::testedCreatePresent,
			() -> MixinService.getService().getBytecodeProvider());
	}

	/** Loader ports let tests exercise the actual plugin decision without launching a game. */
	CreatePresentationMixinPlugin(BooleanSupplier testedCreate, Supplier<IClassBytecodeProvider> bytecodeProvider) {
		this.testedCreate = testedCreate;
		this.bytecodeProvider = bytecodeProvider;
	}

	@Override
	public void onLoad(String mixinPackage) {}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!TARGET.equals(targetClassName) || !MIXIN.equals(mixinClassName)) {
			return false;
		}
		try {
			if (!testedCreate.getAsBoolean()) {
				return false;
			}
			// ModLauncher rejects untransformed lookups. Its bytecode-only path
			// excludes Mixin itself, so this does not define the target or recurse.
			ClassNode target = bytecodeProvider.get()
				.getClassNode(TARGET, true);
			return compatibleTarget(target);
		} catch (ClassNotFoundException | IOException | RuntimeException | LinkageError failure) {
			return false;
		}
	}

	private static boolean testedCreatePresent() {
		LoadingModList loading = LoadingModList.get();
		if (loading != null) {
			return loading.getMods().stream().anyMatch(mod -> "create".equals(mod.getModId())
				&& testedVersion(mod.getVersion().toString()));
		}
		ModList loaded = ModList.get();
		return loaded != null && loaded.getMods().stream().anyMatch(mod ->
			"create".equals(mod.getModId()) && testedVersion(mod.getVersion().toString()));
	}

	static boolean testedVersion(String version) {
		// The runtime 6.0.10 metadata is not an artifact fingerprint; the
		// separate ASM field check still refuses incompatible layouts.
		return TESTED_MOD_VERSION.equals(version) || TESTED_ARTIFACT_VERSION.equals(version);
	}

	/** Package-local test seam for the exact fields required by our shadows. */
	static boolean compatibleTarget(ClassNode target) {
		return target != null && TARGET.replace('.', '/').equals(target.name)
			&& matchingFloat(target, "stress") && matchingFloat(target, "capacity");
	}

	private static boolean matchingFloat(ClassNode target, String fieldName) {
		for (FieldNode field : target.fields) {
			if (fieldName.equals(field.name)) {
				return "F".equals(field.desc) && (field.access & Opcodes.ACC_PROTECTED) != 0
					&& (field.access & Opcodes.ACC_STATIC) == 0;
			}
		}
		return false;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return List.of();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
		IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
		IMixinInfo mixinInfo) {}
}
