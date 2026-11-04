package nx.pingwheel.common.testing;

/** A block of code that may throw, the input of {@code assertThrows}/{@code assertDoesNotThrow}. */
@FunctionalInterface
public interface Executable {

	void execute() throws Throwable;
}
