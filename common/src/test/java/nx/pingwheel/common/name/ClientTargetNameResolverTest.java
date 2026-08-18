package nx.pingwheel.common.name;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import nx.pingwheel.common.domain.Target;

class ClientTargetNameResolverTest {

	private static final Target.EntityTarget ENTITY = new Target.EntityTarget(
		"minecraft:overworld", UUID.fromString("00000000-0000-0000-0000-000000000001"));
	private static final Target.BlockTarget BLOCK = new Target.BlockTarget(
		"minecraft:overworld", 1, 2, 3, "minecraft:chest");

	@Test
	void resolvesLocationAndUsesUnknownForUnavailableTargets() {
		ClientTargetNameResolver.Lookup lookup = new ClientTargetNameResolver.Lookup() {
			@Override
			public Optional<Component> entity(Target.EntityTarget target) {
				return Optional.empty();
			}

			@Override
			public Optional<Component> block(Target.BlockTarget target) {
				return Optional.empty();
			}
		};

		assertEquals("pingforit.target.here", ((TranslatableContents) ClientTargetNameResolver.resolve(
			new Target.LocationTarget("minecraft:overworld", 1.0, 2.0, 3.0), lookup)
			.getContents()).getKey());
		assertEquals("pingforit.target.unknown", ((TranslatableContents) ClientTargetNameResolver.resolve(ENTITY, lookup)
			.getContents()).getKey());
		assertEquals("pingforit.target.unknown", ((TranslatableContents) ClientTargetNameResolver.resolve(BLOCK, lookup)
			.getContents()).getKey());
	}

	@Test
	void preservesEntityAndBlockCompositionFromTheLiveAdapter() {
		ClientTargetNameResolver.Lookup lookup = new ClientTargetNameResolver.Lookup() {
			@Override
			public Optional<Component> entity(Target.EntityTarget target) {
				return Optional.of(TargetNameComposer.compose(
					Component.literal("Named Entity"),
					Component.literal("Vanilla Entity")));
			}

			@Override
			public Optional<Component> block(Target.BlockTarget target) {
				return Optional.of(TargetNameComposer.compose(
					Component.literal("Named Block"),
					Component.literal("Vanilla Block")));
			}
		};

		assertEquals("Named Entity (Vanilla Entity)",
			ClientTargetNameResolver.resolve(ENTITY, lookup).getString());
		assertEquals("Named Block (Vanilla Block)",
			ClientTargetNameResolver.resolve(BLOCK, lookup).getString());
	}
}
