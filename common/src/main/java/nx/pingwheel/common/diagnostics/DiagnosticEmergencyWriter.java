package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort fallback, independent of Log4j and System.err. The descriptor may
 * block: this is not an asynchronous or guaranteed-delivery facility. Never
 * closes the process descriptor and never registers a shutdown hook.
 */
@NotNullByDefault
final class DiagnosticEmergencyWriter {
	static final int MAX_RECORD_BYTES = 4096;
	private final Object writeLock = new Object();
	private final Factory factory;
	private @Nullable OutputStream output;
	private boolean attempted;
	private boolean failed;
	private final AtomicLong failures = new AtomicLong();
	private final AtomicLong initializations = new AtomicLong();

	DiagnosticEmergencyWriter(Factory factory) {
		this.factory = factory;
	}

	static DiagnosticEmergencyWriter process() {
		return Holder.INSTANCE;
	}

	void write(String record) {
		// Encoding and all formatting happen before the descriptor's write lock.
		byte @Unmodifiable [] bytes = encode(record);
		synchronized (writeLock) {
			if (failed) return;
			try {
				if (!attempted) {
					attempted = true;
					initializations.incrementAndGet();
					output = factory.open();
				}
				if (output == null) throw new IOException("Emergency descriptor unavailable");
				output.write(bytes);
				output.flush();
			} catch (IOException | RuntimeException | LinkageError failure) {
				failed = true;
				failures.updateAndGet(DiagnosticFaultReporter::increment);
			}
		}
	}

	long failures() {
		return failures.get();
	}

	long initializations() {
		return initializations.get();
	}

	private static byte @Unmodifiable [] encode(String record) {
		ByteBuffer buffer = ByteBuffer.allocate(MAX_RECORD_BYTES);
		buffer.limit(MAX_RECORD_BYTES - 12);
		var encoder = StandardCharsets.UTF_8.newEncoder()
			.onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
		var result = encoder.encode(CharBuffer.wrap(record), buffer, true);
		buffer.limit(MAX_RECORD_BYTES);
		if (result.isOverflow()) buffer.put("[truncated]".getBytes(StandardCharsets.UTF_8));
		buffer.put((byte) '\n');
		return Arrays.copyOf(buffer.array(), buffer.position());
	}

	@NotNullByDefault
	@FunctionalInterface
	interface Factory {
		OutputStream open() throws IOException;
	}

	@NotNullByDefault
	private static final class Holder {
		// Only one wrapper can ever attach to FileDescriptor.err for this loader.
		// Construction itself does not access the descriptor or call user code.
		private static final DiagnosticEmergencyWriter INSTANCE =
			new DiagnosticEmergencyWriter(() -> new FileOutputStream(FileDescriptor.err));
	}
}
