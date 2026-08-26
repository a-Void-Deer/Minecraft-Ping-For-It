package nx.pingwheel.common.integration.externalblock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.domain.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockServerProviderRegistryTest {

	@Test
	void registrationKeepsExplicitOrderAndFirstDuplicate() {
		ExternalBlockServerProviderRegistry registry = new ExternalBlockServerProviderRegistry();
		FakeProvider first = new FakeProvider("provider:first");
		FakeProvider duplicate = new FakeProvider("provider:first");
		FakeProvider second = new FakeProvider("provider:second");

		registry.register(first);
		registry.register(duplicate);
		registry.register(second);

		assertEquals(List.of(first, second), registry.providers());
		assertEquals(first, registry.find("provider:first"));
	}

	@Test
	void missingLevelAndCorruptCandidateAreFailSoft() {
		ExternalBlockServerProviderRegistry registry = new ExternalBlockServerProviderRegistry();
		registry.register(new FakeProvider("provider:test"));
		Target.ExternalBlockTarget corrupt = Target.ExternalBlockTarget.candidate(
			"minecraft:overworld", "provider:test", "minecraft:stone", "not-a-provider-locator", false);

		assertTrue(registry.validate(null, corrupt) instanceof ExternalBlockServerProvider.ValidationResult.Invalid);
		assertTrue(registry.materialize(null, corrupt) instanceof ExternalBlockServerProvider.MaterializationResult.Invalid);
	}

	private static final class FakeProvider implements ExternalBlockServerProvider {
		private final String id;

		private FakeProvider(String id) {
			this.id = id;
		}

		@Override
		public String providerId() {
			return id;
		}

		@Override
		public ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) {
			return new ValidationResult.Invalid();
		}

		@Override
		public MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget candidate) {
			return new MaterializationResult.Invalid();
		}

		@Override
		public RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
			return new RefreshResult.Invalid();
		}

		@Override
		public Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget committed) {
			return Optional.empty();
		}

		@Override
		public void release(MinecraftServer server, Target.ExternalBlockTarget committed) {
		}
	}
}
