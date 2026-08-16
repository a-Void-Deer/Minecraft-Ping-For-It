package nx.pingwheel.common.name;

import java.util.List;

import com.google.gson.JsonParseException;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetNameJsonCodecTest {

	/**
	 * The component JSON codec touches vanilla statics ({@code Style$Serializer}
	 * → {@code HoverEvent} → {@code ItemStack} → {@code BuiltInRegistries}), so
	 * the vanilla registry bootstrap must run once, exactly like the game does
	 * before using these classes. The version must be detected first because
	 * {@code Bootstrap.bootStrap()} itself does not do it.
	 */
	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void unknownPayloadIsTheTranslatingUnknownKey() {
		assertTrue(TargetNameJsonCodec.UNKNOWN.value().contains("pingforit.target.unknown"),
			() -> TargetNameJsonCodec.UNKNOWN.value());
	}

	@Test
	void encodeProducesJsonCarryingTheTranslationKey() {
		TargetNameJson json = TargetNameJsonCodec.encode(
			Component.translatable("pingforit.target.here"), RegistryAccess.EMPTY);

		assertTrue(json.value().contains("translate"), () -> json.value());
		assertTrue(json.value().contains("pingforit.target.here"), () -> json.value());
	}

	@Test
	void translatableRoundTrips() {
		Component original = Component.translatable("minecraft.zombie");

		Component decoded = TargetNameJsonCodec.decode(
			TargetNameJsonCodec.encode(original, RegistryAccess.EMPTY), RegistryAccess.EMPTY);

		assertEquals(original, decoded);
	}

	@Test
	void composedCustomNameRoundTrips() {
		Component base = Component.translatable("minecraft.zombie");
		Component original = TargetNameComposer.compose(Component.literal("Bob"), base);

		Component decoded = TargetNameJsonCodec.decode(
			TargetNameJsonCodec.encode(original, RegistryAccess.EMPTY), RegistryAccess.EMPTY);

		assertEquals(original, decoded);

		// Structure survives the round-trip: literal "Bob" + " (" + base + ")".
		assertTrue(decoded.getContents() instanceof PlainTextContents.LiteralContents);
		assertEquals("Bob", ((PlainTextContents.LiteralContents) decoded.getContents()).text());

		List<Component> siblings = decoded.getSiblings();
		assertEquals(3, siblings.size());
		assertEquals(base, siblings.get(1));
	}

	@Test
	void decodeMalformedJsonThrowsControlledJsonException() {
		assertThrows(JsonParseException.class,
			() -> TargetNameJsonCodec.decode(new TargetNameJson("{not json"), RegistryAccess.EMPTY));
	}

	@Test
	void decodeTruncatedJsonThrowsControlledJsonException() {
		assertThrows(JsonParseException.class,
			() -> TargetNameJsonCodec.decode(
				new TargetNameJson("{\"translate\":\"pingforit.target.here\""), RegistryAccess.EMPTY));
	}

	@Test
	void decodeValidLiteralJsonYieldsLiteralComponent() {
		Component decoded = TargetNameJsonCodec.decode(
			new TargetNameJson("{\"text\":\"hi\"}"), RegistryAccess.EMPTY);

		assertTrue(decoded.getContents() instanceof PlainTextContents.LiteralContents);
		assertEquals("hi", ((PlainTextContents.LiteralContents) decoded.getContents()).text());
	}

	@Test
	void decodeKeepsTranslatableContents() {
		Component decoded = TargetNameJsonCodec.decode(
			new TargetNameJson("{\"translate\":\"minecraft.zombie\"}"), RegistryAccess.EMPTY);

		assertTrue(decoded.getContents() instanceof TranslatableContents);
		assertEquals("minecraft.zombie", ((TranslatableContents) decoded.getContents()).getKey());
	}

	@Test
	void codecRejectsNulls() {
		assertThrows(NullPointerException.class,
			() -> TargetNameJsonCodec.encode(null, RegistryAccess.EMPTY));
		assertThrows(NullPointerException.class,
			() -> TargetNameJsonCodec.encode(Component.literal("a"), null));
		assertThrows(NullPointerException.class,
			() -> TargetNameJsonCodec.decode(null, RegistryAccess.EMPTY));
		assertThrows(NullPointerException.class,
			() -> TargetNameJsonCodec.decode(TargetNameJsonCodec.UNKNOWN, null));
	}

	@Test
	void unknownPayloadRoundTripsAsTheUnknownTranslatable() {
		Component decoded = TargetNameJsonCodec.decode(TargetNameJsonCodec.UNKNOWN, RegistryAccess.EMPTY);

		assertTrue(decoded.getContents() instanceof TranslatableContents);
		assertEquals("pingforit.target.unknown", ((TranslatableContents) decoded.getContents()).getKey());
	}
}
