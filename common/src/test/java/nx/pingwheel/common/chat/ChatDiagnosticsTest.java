package nx.pingwheel.common.chat;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.testing.DiagnosticLogCapture;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ChatDiagnosticsTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void invalidTemplatesUseControlledEventsAndKeepTheirOwnFallbackFamilies() {
		try (var logs = new DiagnosticLogCapture()) {
			var type = PingTypeCatalog.builtIn().findById("attention").orElseThrow();
			Component target = Component.literal("Chest");
			Component ordinary = PingChatBuilder.build("{unknown}", "Author", type, target);
			assertEquals(PingChatBuilder.LEGACY_KEY, ((TranslatableContents) ordinary.getContents()).getKey());
			Component content = ContentChatTemplate.build("{unknown}", Component.literal("Author"), type, target, Component.literal("Selected"));
			assertTrue(content.getString().contains("Author"));
			assertTrue(content.getString().contains("Chest"));
			assertTrue(content.getString().contains("Selected"));
			assertTrue(logs.events().stream().allMatch(event -> event.getLevel() == Level.DEBUG && event.getThrown() == null));
			assertEquals(1, logs.operation("chat/whole-template").size());
			assertEquals(1, logs.operation("chat/content-template").size());
		}
	}

	@Test void throwingLocaleLookupRetainsCauseAndUsesSafeContentFallback() {
		try (var logs = new DiagnosticLogCapture()) {
			var type = PingTypeCatalog.builtIn().findById("attention").orElseThrow();
			var templates = ContentChatComposer.localized(key -> {
				throw new IllegalStateException("lookup-sentinel", new RuntimeException("lookup-root-sentinel"));
			}, key -> fail("selection failure cannot resolve another template"));
			Component message = ContentChatTemplate.build(templates.template(type), Component.literal("Author"), type,
				Component.literal("Chest"), Component.literal("Selected"));
			assertTrue(message.getString().contains("Selected"));
			assertEquals(1, logs.operation("chat/content-locale-lookup").size());
			var event = logs.operation("chat/content-locale-lookup").getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			assertTrue(event.getMessage().getFormattedMessage().contains("lookup-root-sentinel"));
		}
	}
}
