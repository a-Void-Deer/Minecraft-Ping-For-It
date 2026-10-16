package nx.pingwheel.common.name;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.util.SafeExceptionReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTargetNameDecoderTest {

	/**
	 * Valid JSON decoding touches vanilla statics ({@code Style$Serializer} →
	 * {@code HoverEvent} → {@code ItemStack} → {@code BuiltInRegistries}), so
	 * the vanilla registry bootstrap must run once, exactly like the game does
	 * before using these classes. The version must be detected first because
	 * {@code Bootstrap.bootStrap()} itself does not do it.
	 */
	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void resetLogger() {
		ClientTargetNameDecoder.setLogger(ClientTargetNameDecoder.Logger.noop());
	}

	private record LogRecord(String message, Object[] args) {}

	@Test
	void decodesValidJsonIntoComponent() {
		Component component = ClientTargetNameDecoder.decode(
			new MarkerId(3L),
			new TargetNameJson("{\"translate\":\"minecraft.zombie\"}"),
			RegistryAccess.EMPTY);

		assertTrue(component.getContents() instanceof TranslatableContents);
		assertEquals("minecraft.zombie", ((TranslatableContents) component.getContents()).getKey());
	}

	@Test
	void malformedJsonFallsBackToUnknownTranslatable() {
		Component component = ClientTargetNameDecoder.decode(
			new MarkerId(3L),
			new TargetNameJson("{broken"),
			RegistryAccess.EMPTY);

		assertTrue(component.getContents() instanceof TranslatableContents);
		assertEquals("pingforit.target.unknown", ((TranslatableContents) component.getContents()).getKey());
	}

	@Test
	void decodeFailureLogsMarkerIdAndBoundedExceptionDetails() {
		List<LogRecord> records = new ArrayList<>();
		ClientTargetNameDecoder.setLogger((message, args) -> records.add(new LogRecord(message, args)));

		ClientTargetNameDecoder.decode(
			new MarkerId(42L),
			new TargetNameJson("{\"translate\":\"pingforit.target.here\""),
			RegistryAccess.EMPTY);

		assertEquals(1, records.size());
		LogRecord record = records.get(0);

		assertTrue(record.message().contains("markerId=42"));
		assertTrue(record.message().contains("Exception"));
		assertTrue(record.message().contains("\n    at "));
		assertTrue(record.message().contains("ClientTargetNameDecoder.decode"));
		assertTrue(record.message().length() <= SafeExceptionReport.MAX_CHARACTERS);
		assertEquals(0, record.args().length, "No second, unbounded throwable argument");
	}

	@Test
	void validJsonNeverLogs() {
		List<LogRecord> records = new ArrayList<>();
		ClientTargetNameDecoder.setLogger((message, args) -> records.add(new LogRecord(message, args)));

		ClientTargetNameDecoder.decode(
			new MarkerId(1L),
			new TargetNameJson("{\"translate\":\"minecraft.zombie\"}"),
			RegistryAccess.EMPTY);

		assertTrue(records.isEmpty());
	}

	@Test
	void decodeRejectsNulls() {
		assertThrows(NullPointerException.class,
			() -> ClientTargetNameDecoder.decode(null, TargetNameJsonCodec.UNKNOWN, RegistryAccess.EMPTY));
		assertThrows(NullPointerException.class,
			() -> ClientTargetNameDecoder.decode(new MarkerId(1L), null, RegistryAccess.EMPTY));
		assertThrows(NullPointerException.class,
			() -> ClientTargetNameDecoder.decode(new MarkerId(1L), TargetNameJsonCodec.UNKNOWN, null));
	}

	@Test
	void setLoggerRejectsNull() {
		assertThrows(NullPointerException.class, () -> ClientTargetNameDecoder.setLogger(null));
	}
}
