package nx.pingwheel.common.platform;

import java.util.ArrayList;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService.Description;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService.Literal;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService.Translation;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.CaptureLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Closed, non-recursive projection of one native base-name callback into immutable bounded strings. */
@NotNullByDefault
public final class FluidDescriptions {

	public static final int MAX_KEY_UNITS = 256;
	public static final int MAX_TEXT_BYTES = 1024;
	private FluidDescriptions() {}

	public static @Nullable Description describeBase(Fluid fluid) {
		try { return describeBase(fluid, ServiceHolder.service()); }
		catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) {
			rethrowHardFailure(unavailable); return null;
		}
	}

	/** One external name callback. Resource/capture admission refusals are not optional-name failures. */
	public static @Nullable Description describeBase(Fluid fluid, @Nullable IPlatformFluidDescriptionService service) {
		if (service == null) return null;
		try { return service.describeBase(fluid); }
		catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) {
			rethrowHardFailure(unavailable); return null;
		}
	}

	@NotNullByDefault
	private static final class ServiceHolder {
		private static @Nullable IPlatformFluidDescriptionService cached;
		private static boolean discovered;
		private static boolean discovering;
		private static synchronized @Nullable IPlatformFluidDescriptionService service() {
			// Do not run external discovery in <clinit>: a refused constructor must neither poison
			// the holder nor turn the next attempt into a cause-losing NoClassDefFoundError.
			if (!discovered && !discovering) {
				discovering = true;
				try { cached = discover(); discovered = true; }
				finally { discovering = false; }
			}
			return cached;
		}
		private static @Nullable IPlatformFluidDescriptionService discover() {
			try { return ServiceLoader.load(IPlatformFluidDescriptionService.class).findFirst().orElse(null); }
			catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) {
				rethrowHardFailure(unavailable); return null;
			}
		}
	}

	/** Fixed scratch/work, covered by the caller's existing display prepayment, including failures.
	 * Only exact JDK wrappers have their cause read; opaque exception overrides are never callbacks.
	 * A cyclic or over-depth standard-wrapper chain is not proved optional: propagate its original
	 * wrapper rather than publish successful fallback. Inspect each exposed node before either stop. */
	@SuppressWarnings("removal")
	private static void rethrowHardFailure(Throwable failure) {
		@Nullable Throwable[] seen = new Throwable[16];
		@Nullable Throwable current = failure;
		int count = 0;
		while (current != null) {
			if (current instanceof CaptureLimitException exhausted) throw exhausted;
			if (current instanceof VirtualMachineError fatal) throw fatal;
			if (current instanceof ThreadDeath death) throw death;
			Class<?> type = current.getClass();
			if (type != ServiceConfigurationError.class && type != ExceptionInInitializerError.class
				&& type != java.lang.reflect.InvocationTargetException.class && type != java.lang.reflect.UndeclaredThrowableException.class
				&& type != java.util.concurrent.ExecutionException.class && type != java.util.concurrent.CompletionException.class
				&& type != RuntimeException.class && type != IllegalStateException.class && type != IllegalArgumentException.class
				&& type != LinkageError.class && type != NoClassDefFoundError.class) return;
			for (int index = 0; index < count; index++) if (seen[index] == current) throwUnresolved(failure);
			if (count == seen.length) throwUnresolved(failure);
			seen[count++] = current;
			current = current.getCause();
		}
	}
	private static void throwUnresolved(Throwable failure) {
		if (failure instanceof RuntimeException runtime) throw runtime;
		throw (Error) failure; // entry catches admit only RuntimeException, LinkageError or ServiceConfigurationError
	}

	/** Never visits, serializes, flattens or calls getString on an external component. */
	public static @Nullable Description project(@Nullable Component component) {
		if (component == null || component.getClass() != MutableComponent.class) return null;
		@Nullable var siblings = component.getSiblings();
		if (siblings == null || siblings.getClass() != ArrayList.class || !siblings.isEmpty()) return null;
		@Nullable var contents = component.getContents();
		if (contents == null) return null;
		if (contents.getClass() == PlainTextContents.LiteralContents.class) {
			@Nullable String text = ((PlainTextContents.LiteralContents) contents).text();
			return validPlainText(text) ? new Literal(text) : null;
		}
		if (contents.getClass() != TranslatableContents.class) return null;
		var translation = (TranslatableContents) contents;
		@Nullable Object @Nullable [] args = translation.getArgs();
		if (args == null || args.length != 0 || !validKey(translation.getKey())) return null;
		@Nullable String fallback = translation.getFallback();
		if (fallback != null && !validPlainText(fallback)) return null;
		// Style, hover/click events and external component instances never escape this boundary.
		return new Translation(translation.getKey(), fallback);
	}

	public static boolean validKey(@Nullable String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_KEY_UNITS) return false;
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
				|| c == '_' || c == '.' || c == ':' || c == '/' || c == '-')) return false;
		}
		return true;
	}

	/** Length is checked before scanning; no encoded buffer or truncation of untrusted text. */
	public static boolean validPlainText(@Nullable String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_TEXT_BYTES) return false;
		int bytes = 0;
		boolean nonblank = false;
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			if (Character.isISOControl(c) || c == '\u00a7' || c == '\u2028' || c == '\u2029') return false;
			nonblank |= !Character.isWhitespace(c);
			if (Character.isHighSurrogate(c)) {
				if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
				bytes += 4;
			} else {
				if (Character.isLowSurrogate(c)) return false;
				bytes += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
			}
			if (bytes > MAX_TEXT_BYTES) return false;
		}
		return nonblank;
	}
}
