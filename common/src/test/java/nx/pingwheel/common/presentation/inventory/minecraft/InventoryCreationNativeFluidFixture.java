package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Optional;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.jetbrains.annotations.NotNullByDefault;

/** Server creation test bridge into the actual native FLUID source boundary, without world-binding substitution. */
@NotNullByDefault
public final class InventoryCreationNativeFluidFixture {
	private InventoryCreationNativeFluidFixture() {}
	public static InventoryRuntime.SourceResolver resolver(InventoryFluidWorldTest.Fixture fixture) {
		return new InventoryRuntime.SourceResolver() {
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int ceiling) { throw new AssertionError("native admission required"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int ceiling, InventoryValidationBudget.Allowance allowance) {
				return InventoryFluidSources.resolve(fixture, fixture, input, ceiling, allowance);
			}
		};
	}
}
