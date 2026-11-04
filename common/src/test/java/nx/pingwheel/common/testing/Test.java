package nx.pingwheel.common.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test method for {@link TestRunner}.
 *
 * <p>Zero-dependency stand-in for {@code org.junit.jupiter.api.Test}. The name and
 * package differ on purpose: the ported test bodies are upstream copies whose
 * assertions and case names must not change, only their imports
 * ({@code org.junit.jupiter.api.*} -> {@code nx.pingwheel.common.testing.*}).
 *
 * <p>The runner creates a fresh instance per method (JUnit's per-test lifecycle)
 * and invokes {@code @BeforeEach} methods first if any are present.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
}
