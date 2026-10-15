package nx.pingwheel.common.presentation.inventory.minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonParser;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryStateAccessWideningShapeTest {
	private static Path root() {
		Path path = Path.of("").toAbsolutePath();
		while (path != null && !Files.isRegularFile(path.resolve("settings.gradle"))) path = path.getParent();
		assertNotNull(path, "repository root");
		return path;
	}

	private static String source(Path root, String path) throws IOException {
		return Files.readString(root.resolve(path));
	}

	private static String nativeMatcherApi(StatePropertiesPredicate.PropertyMatcher property) {
		StatePropertiesPredicate.ValueMatcher matcher = property.valueMatcher();
		if (matcher instanceof StatePropertiesPredicate.ExactMatcher exact)
			return property.name() + exact.value();
		if (matcher instanceof StatePropertiesPredicate.RangedMatcher ranged)
			return property.name() + ranged.minValue().orElse("") + ranged.maxValue().orElse("");
		throw new IllegalArgumentException("unsupported native matcher");
	}

	@Test void allLoaderMetadataWidensOnlyTheNativeStateMatcherClasses() throws IOException {
		Path root = root();
		List<String> types = List.of("PropertyMatcher", "ValueMatcher", "ExactMatcher", "RangedMatcher");
		List<String> accessTransformers = types.stream()
			.map(type -> "public net.minecraft.advancements.critereon.StatePropertiesPredicate$" + type).toList();
		assertEquals(accessTransformers, Files.readAllLines(root.resolve("common/src/main/resources/META-INF/accesstransformer.cfg")));

		List<String> accessWidener = new java.util.ArrayList<>();
		accessWidener.add("accessWidener v2 named");
		types.stream().map(type -> "accessible class net/minecraft/advancements/critereon/StatePropertiesPredicate$" + type)
			.forEach(accessWidener::add);
		assertEquals(accessWidener, Files.readAllLines(root.resolve("fabric/src/main/resources/pingforit.accesswidener")));

		var metadata = JsonParser.parseString(source(root, "fabric/src/main/resources/fabric.mod.json")).getAsJsonObject();
		assertEquals("pingforit.accesswidener", metadata.get("accessWidener").getAsString());
		assertTrue(source(root, "fabric/build.gradle").contains(
			"accessWidenerPath = file(\"src/main/resources/pingforit.accesswidener\")"));
		assertTrue(source(root, "forge/build.gradle").contains(
			"accessTransformers file(\"../common/src/main/resources/META-INF/accesstransformer.cfg\")"));
		assertTrue(source(root, "common/build.gradle").contains("accessTransformers.from(at.absolutePath)"));
		assertTrue(source(root, "neoforge/build.gradle").contains("accessTransformers.from(at.absolutePath)"));
	}

	@Test void commonCertificateUsesTheTypedNativeMatcherRecords() throws IOException {
		String certificate = source(root(),
			"common/src/main/java/nx/pingwheel/common/presentation/inventory/minecraft/InventoryComponentCertificate.java");
		for (String usage : List.of("StatePropertiesPredicate.PropertyMatcher.class",
			"StatePropertiesPredicate.ExactMatcher.class", ".value()",
			"StatePropertiesPredicate.RangedMatcher.class", ".minValue()", ".maxValue()")) {
			assertTrue(certificate.contains(usage), usage);
		}
		// Guard direct native record access, not the spelling of the typed local.
		var property = Pattern.compile("\\b(?:var|StatePropertiesPredicate\\.PropertyMatcher)\\s+(\\w+)"
			+ "\\s*=\\s*\\(\\s*StatePropertiesPredicate\\.PropertyMatcher\\s*\\)").matcher(certificate);
		assertTrue(property.find(), "typed native PropertyMatcher local");
		for (String accessor : List.of("name", "valueMatcher")) {
			assertTrue(Pattern.compile("\\b" + Pattern.quote(property.group(1)) + "\\s*\\.\\s*" + accessor + "\\s*\\(\\s*\\)")
				.matcher(certificate).find(), "typed native PropertyMatcher." + accessor + "()");
		}
		assertFalse(certificate.contains("InventoryStateMatcherAccess"));
	}
}
