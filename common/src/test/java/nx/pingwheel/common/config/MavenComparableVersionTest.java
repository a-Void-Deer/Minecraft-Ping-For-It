package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenComparableVersionTest {
    @Test
    void followsMavenQualifierAndNumericOrdering() {
        assertEquals(0, MavenComparableVersion.compare("1.0", "1.0.0"));
        assertTrue(MavenComparableVersion.compare("1.10", "1.2") > 0);
        assertTrue(MavenComparableVersion.compare("1.0-beta1", "1.0-beta2") < 0);
        assertTrue(MavenComparableVersion.compare("1.0-beta2", "1.0-beta10") < 0);
        assertTrue(MavenComparableVersion.compare("1.0-beta10", "1.0") < 0);
    }

    @Test
    void followsMavenAliasesAndRepresentativeSeparatorSemantics() {
        assertTrue(MavenComparableVersion.compare("1.0-alpha", "1.0-beta") < 0);
        assertTrue(MavenComparableVersion.compare("1.0-beta", "1.0-rc") < 0);
        assertTrue(MavenComparableVersion.compare("1.0-rc", "1.0-snapshot") < 0);
        assertTrue(MavenComparableVersion.compare("1.0-snapshot", "1.0") < 0);
        assertEquals(0, MavenComparableVersion.compare("1.0-ga", "1.0-release"));
        assertEquals(0, MavenComparableVersion.compare("1.0-cr1", "1.0-rc1"));
        assertTrue(MavenComparableVersion.compare("1.0.RC2", "1.0-RC3") < 0);
        assertTrue(MavenComparableVersion.compare("1.0-RC1", "1.0.1") < 0);
        // Maven treats the hyphen and dot forms as equivalent here.
        assertEquals(0, MavenComparableVersion.compare("1.0.0-RC1", "1.0.0.RC1"));
    }
}
