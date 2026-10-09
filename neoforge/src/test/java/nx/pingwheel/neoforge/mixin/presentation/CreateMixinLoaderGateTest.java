package nx.pingwheel.neoforge.mixin.presentation;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.IClassBytecodeProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real ModLauncher bytecode-provider contract, with Create read only from its archive. */
class CreateMixinLoaderGateTest {
	private static final String TARGET = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
	private static final String CACHED = "nx.pingwheel.neoforge.mixin.presentation.KineticCachedNetworkMixin";
	private static final String RECEIPT = "nx.pingwheel.neoforge.mixin.presentation.KineticPreviewReceiptMixin";

	@Test
	void testedCreateArchiveHasTheActualAccessorAndReceiptShapes() throws Exception {
		ClassNode node = node(createBytes());
		assertTrue(CreatePresentationMixinPlugin.compatibleTarget(node));
		assertTrue(CreatePreviewMixinPlugin.compatibleTarget(node));
		for (String name : new String[] {"stress", "capacity"}) {
			var field = node.fields.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
			assertEquals("F", field.desc);
			assertEquals(Opcodes.ACC_PROTECTED, field.access);
		}
	}

	@Test
	void modLauncherRejectsUntransformedLookupBeforeReadingAnyClassBytes() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		var provider = modLauncher(createBytes(), reads);
		var failure = assertThrows(IllegalArgumentException.class, () -> provider.getClassNode(TARGET, false));
		assertTrue(failure.getMessage().contains("untransformed bytecode"));
		assertEquals(0, reads.get());
	}

	@Test
	void mixinRequestedBytecodeDoesNotReenterTheMixinTransformer() throws Exception {
		var provider = modLauncher(createBytes(), new AtomicInteger());
		var votes = new AtomicInteger();
		var observedReason = new AtomicReference<String>();
		installProcessor(provider, recordingProcessor(votes, observedReason));

		// Positive control: an ordinary ModLauncher reason reaches the processor,
		// so the exclusion below cannot pass through an empty processor list.
		var ordinary = handlesClass(provider, "classloading");
		assertEquals(java.util.Set.of(phaseAfter()), ordinary,
			"an ordinary reason must reach the installed Mixin class processor");
		assertEquals(1, votes.get());
		assertEquals("classloading", observedReason.get(), "the processor must receive the caller's reason");

		// ModLauncher tags the Mixin-owned provider lookup with the Mixin plugin
		// name, and that lookup must not reach the Mixin processors again.
		var mixin = handlesClass(provider, "mixin");
		assertEquals(java.util.Set.of(), mixin, "ModLauncher tags the provider lookup with its owning plugin name");
		assertEquals(1, votes.get(), "the Mixin-owned lookup must not reach the Mixin class processor");

		// The installed processor must not disturb the actual lookup the plugins use.
		assertEquals(TARGET.replace('.', '/'), provider.getClassNode(TARGET, true).name);
	}

	@Test
	void bothPluginDecisionsAcceptRealCreateThroughModLauncher() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		var provider = modLauncher(createBytes(), reads);
		assertTrue(cached(provider).shouldApplyMixin(TARGET, CACHED));
		assertTrue(receipt(provider).shouldApplyMixin(TARGET, RECEIPT));
		assertEquals(2, reads.get(), "both plugins must reach the loader's supported bytecode lookup");
	}

	@Test
	void actualBytecodeDriftClosesOnlyTheAffectedGate() throws Exception {
		ClassNode changed = node(createBytes());
		changed.fields.removeIf(field -> field.name.equals("stress"));
		var missingStress = modLauncher(bytes(changed), new AtomicInteger());
		assertFalse(cached(missingStress).shouldApplyMixin(TARGET, CACHED));
		assertTrue(receipt(missingStress).shouldApplyMixin(TARGET, RECEIPT));

		changed = node(createBytes());
		changed.methods.removeIf(method -> method.name.equals("warnOfMovement"));
		var missingInvalidation = modLauncher(bytes(changed), new AtomicInteger());
		assertTrue(cached(missingInvalidation).shouldApplyMixin(TARGET, CACHED));
		assertFalse(receipt(missingInvalidation).shouldApplyMixin(TARGET, RECEIPT));
	}

	@Test
	void untestedCreateStopsBeforeBytecodeDiscoveryAndProviderFailureStaysFailSoft() {
		java.util.function.Supplier<IClassBytecodeProvider> unexpected = () -> {
			throw new AssertionError("untested Create must not reach bytecode discovery");
		};
		assertFalse(new CreatePresentationMixinPlugin(() -> false, unexpected).shouldApplyMixin(TARGET, CACHED));
		assertFalse(new CreatePreviewMixinPlugin(() -> false, unexpected).shouldApplyMixin(TARGET, RECEIPT));
		java.util.function.Supplier<IClassBytecodeProvider> unavailable = () -> {
			throw new NoClassDefFoundError("unavailable loader provider");
		};
		assertFalse(new CreatePresentationMixinPlugin(() -> true, unavailable).shouldApplyMixin(TARGET, CACHED));
		assertFalse(new CreatePreviewMixinPlugin(() -> true, unavailable).shouldApplyMixin(TARGET, RECEIPT));
	}

	private static CreatePresentationMixinPlugin cached(IClassBytecodeProvider provider) {
		return new CreatePresentationMixinPlugin(() -> true, () -> provider);
	}

	private static CreatePreviewMixinPlugin receipt(IClassBytecodeProvider provider) {
		return new CreatePreviewMixinPlugin(() -> true, () -> provider);
	}

	private static byte[] createBytes() throws Exception {
		String artifact = System.getProperty("pingforit.test.createArtifact");
		assertNotNull(artifact, "Gradle must supply the tested compile-only Create artifact");
		try (var archive = new ZipFile(Path.of(artifact).toFile())) {
			var entry = archive.getEntry(TARGET.replace('.', '/') + ".class");
			assertNotNull(entry);
			try (var stream = archive.getInputStream(entry)) { return stream.readAllBytes(); }
		}
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static IClassBytecodeProvider modLauncher(byte[] bytes, AtomicInteger reads) throws Exception {
		// Bind only the provider's host byte-supply port, without starting Mixin or
		// defining/initializing Create. All getClassNode behavior is the real implementation.
		var provider = (IClassBytecodeProvider) Class.forName("org.spongepowered.asm.launch.MixinLaunchPlugin")
			.getConstructor().newInstance();
		var field = Class.forName("org.spongepowered.asm.launch.MixinLaunchPluginLegacy")
			.getDeclaredField("transformerLoader");
		field.setAccessible(true);
		Object loader = Proxy.newProxyInstance(field.getType().getClassLoader(), new Class<?>[] {field.getType()},
			(proxy, method, arguments) -> {
				assertEquals("buildTransformedClassNodeFor", method.getName());
				assertEquals(TARGET, arguments[0]);
				reads.incrementAndGet();
				return bytes;
			});
		field.set(provider, loader);
		return provider;
	}

	private static Object handlesClass(IClassBytecodeProvider provider, String reason) throws Exception {
		return provider.getClass().getMethod("handlesClass", Type.class, boolean.class, String.class)
			.invoke(provider, Type.getObjectType(TARGET.replace('.', '/')), false, reason);
	}

	private static Object recordingProcessor(AtomicInteger votes, AtomicReference<String> observedReason) throws Exception {
		Class<?> processor = Class.forName("org.spongepowered.asm.launch.IClassProcessor");
		return Proxy.newProxyInstance(processor.getClassLoader(), new Class<?>[] {processor},
			(proxy, method, arguments) -> {
				if (!"handlesClass".equals(method.getName())) {
					throw new AssertionError("unexpected class processor callback " + method);
				}
				votes.incrementAndGet();
				observedReason.set((String) arguments[2]);
				return afterOnlyVote();
			});
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void installProcessor(IClassBytecodeProvider provider, Object processor) throws Exception {
		var field = Class.forName("org.spongepowered.asm.launch.MixinLaunchPluginLegacy")
			.getDeclaredField("processors");
		field.setAccessible(true);
		((List) field.get(provider)).add(processor);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Object afterOnlyVote() throws Exception {
		Class phase = Class.forName("cpw.mods.modlauncher.serviceapi.ILaunchPluginService$Phase");
		EnumSet vote = EnumSet.noneOf(phase);
		vote.add(Enum.valueOf(phase, "AFTER"));
		return vote;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Enum<?> phaseAfter() throws Exception {
		return Enum.valueOf((Class) Class.forName("cpw.mods.modlauncher.serviceapi.ILaunchPluginService$Phase"), "AFTER");
	}
}
