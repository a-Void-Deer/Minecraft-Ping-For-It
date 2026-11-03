package nx.pingwheel.common.platform;

import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Base-fluid naming only: no stack, amount, world, storage or client dependency. */
@NotNullByDefault
public interface IPlatformFluidDescriptionService {

	@Nullable Description describeBase(Fluid fluid);

	@NotNullByDefault
	sealed interface Description permits Translation, Literal {}

	@NotNullByDefault
	record Translation(String key, @Nullable String fallback) implements Description {
		public Translation {
			if (!FluidDescriptions.validKey(key) || fallback != null && !FluidDescriptions.validPlainText(fallback))
				throw new IllegalArgumentException("invalid base fluid translation");
		}
	}

	@NotNullByDefault
	record Literal(String text) implements Description {
		public Literal {
			if (!FluidDescriptions.validPlainText(text)) throw new IllegalArgumentException("invalid base fluid literal");
		}
	}
}
