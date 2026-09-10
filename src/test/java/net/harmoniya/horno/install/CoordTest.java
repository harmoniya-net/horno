package net.harmoniya.horno.install;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordTest {
    @Test
    void resolvesTheOrdinaryShape() {
        assertEquals("org/ow2/asm/asm/9.8/asm-9.8.jar", Coord.parse("org.ow2.asm:asm:9.8").path());
    }

    @Test
    void resolvesAClassifier() {
        assertEquals(
            "net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-client.jar",
            Coord.parse("net.minecraftforge:forge:1.20.1-47.4.10:client").path()
        );
    }

    /** `@zip` and `@txt` are how a profile names mappings and archives. */
    @Test
    void resolvesAnExtension() {
        assertEquals(
            "de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip",
            Coord.parse("de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412@zip").path()
        );
    }

    @Test
    void resolvesAClassifierAndAnExtensionTogether() {
        assertEquals(
            "de/oceanlabs/mcp/mcp_config/1.20.1/mcp_config-1.20.1-mappings.txt",
            Coord.parse("de.oceanlabs.mcp:mcp_config:1.20.1:mappings@txt").path()
        );
    }

    @Test
    void refusesSomethingThatIsNotACoordinate() {
        assertThrows(IllegalArgumentException.class, () -> Coord.parse("org.ow2.asm:asm"));
        assertThrows(IllegalArgumentException.class, () -> Coord.parse("a:b:c:d:e"));
    }
}
