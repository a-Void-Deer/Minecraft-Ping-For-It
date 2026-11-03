package nx.pingwheel.common.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Accessor for the package-private {@code RenderType.create(String, RenderSetup)}
 * factory (M7-prep E2, decision D1: mixin accessor instead of an access widener so
 * the plain javac classpath of {@code tools/compile-check.sh} keeps working).
 */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {

	@Invoker("create")
	static RenderType pingforit$create(String name, RenderSetup setup) {
		throw new AssertionError();
	}
}
