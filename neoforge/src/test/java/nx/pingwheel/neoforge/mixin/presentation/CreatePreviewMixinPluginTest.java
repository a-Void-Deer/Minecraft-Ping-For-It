package nx.pingwheel.neoforge.mixin.presentation;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreatePreviewMixinPluginTest {
	private static ClassNode shape() {
		var node = new ClassNode(); node.name = "com/simibubi/create/content/kinetics/base/KineticBlockEntity";
		node.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "wasMoved", "Z", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PROTECTED, "read", "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "clearKineticInformation", "()V", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "warnOfMovement", "()V", null, null));
		return node;
	}
	@Test void incompatibleReceiptOrInvalidationMethodCannotOpenLocalReader() {
		assertTrue(CreatePreviewMixinPlugin.compatibleTarget(shape()));
		var node = shape(); node.methods.removeLast(); assertFalse(CreatePreviewMixinPlugin.compatibleTarget(node));
		node = shape(); node.fields.getFirst().desc = "I"; assertFalse(CreatePreviewMixinPlugin.compatibleTarget(node));
		node = shape(); node.methods.getFirst().access |= Opcodes.ACC_STATIC; assertFalse(CreatePreviewMixinPlugin.compatibleTarget(node));
	}
}
