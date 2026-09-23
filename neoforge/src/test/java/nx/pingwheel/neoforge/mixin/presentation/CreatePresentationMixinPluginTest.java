package nx.pingwheel.neoforge.mixin.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

class CreatePresentationMixinPluginTest {
	private static final String KINETIC = "com/simibubi/create/content/kinetics/base/KineticBlockEntity";

	@Test
	void onlyExactTargetWithBothProtectedInstanceFloatsIsCompatible() {
		ClassNode node = validNode();
		assertTrue(CreatePresentationMixinPlugin.compatibleTarget(node));
		node.name += "Other";
		assertFalse(CreatePresentationMixinPlugin.compatibleTarget(node));
	}

	@Test
	void missingChangedStaticOrNonprotectedFieldsDisableAccessor() {
		ClassNode node = validNode();
		node.fields.remove(0);
		assertFalse(CreatePresentationMixinPlugin.compatibleTarget(node));
		node = validNode();
		node.fields.set(0, new FieldNode(Opcodes.ACC_PROTECTED, "stress", "D", null, null));
		assertFalse(CreatePresentationMixinPlugin.compatibleTarget(node));
		node = validNode();
		node.fields.set(0, new FieldNode(Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC, "stress", "F", null, null));
		assertFalse(CreatePresentationMixinPlugin.compatibleTarget(node));
		node = validNode();
		node.fields.set(0, new FieldNode(Opcodes.ACC_PRIVATE, "stress", "F", null, null));
		assertFalse(CreatePresentationMixinPlugin.compatibleTarget(node));
		assertFalse(CreatePresentationMixinPlugin.compatibleTarget(null));
	}

	@Test
	void unexpectedMixinNameIsRejectedBeforeDiscovery() {
		assertFalse(new CreatePresentationMixinPlugin().shouldApplyMixin(
			"example.MissingTarget", "example.MissingMixin"));
	}

	@Test
	void mismatchedOrAbsentVersionsDisableOnlyTheAccessorRoute() {
		assertTrue(CreatePresentationMixinPlugin.testedVersion("6.0.10"));
		assertTrue(CreatePresentationMixinPlugin.testedVersion("6.0.10-281"));
		assertFalse(CreatePresentationMixinPlugin.testedVersion("6.0.10-282"));
		assertFalse(CreatePresentationMixinPlugin.testedVersion(null));
	}

	private static ClassNode validNode() {
		ClassNode node = new ClassNode();
		node.name = KINETIC;
		node.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "stress", "F", null, null));
		node.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "capacity", "F", null, null));
		return node;
	}
}
