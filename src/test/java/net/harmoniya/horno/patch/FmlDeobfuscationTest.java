package net.harmoniya.horno.patch;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FmlDeobfuscationTest {
    private static Properties version(String minecraft, String hash) {
        Properties properties = new Properties();
        properties.setProperty("fmlbuild.major.number", "5");
        if (minecraft != null) {
            properties.setProperty("fmlbuild.mcversion", minecraft);
        }
        if (hash != null) {
            properties.setProperty("fmlbuild.deobfuscation.hash", hash);
        }
        return properties;
    }

    @Test
    void namesTheFileAfterTheMinecraftVersionAndPinsItByTheBuildsOwnHash() {
        FmlLibraries.Library data = FmlDeobfuscation.resolve(version("1.5.1", "22e221a0d89516c1f721d6cab056a7e37471d0a6"));

        assertEquals("deobfuscation_data_1.5.1.zip", data.name);
        assertEquals("22e221a0d89516c1f721d6cab056a7e37471d0a6", data.sha1);
        assertTrue(data.url.endsWith("/fmllibs/deobfuscation_data_1.5.1.zip"), data.url);
    }

    /** 1.4.7 and older: FML has no such mechanism, and nothing is to be placed. */
    @Test
    void aBuildThatNamesNoDataWantsNone() {
        assertNull(FmlDeobfuscation.resolve(version("1.4.7", null)));
    }

    /**
     * The ten early 1.5 builds. FML would take the surviving file if its hash
     * were patched to match — and then deobfuscate with another build's map.
     */
    @Test
    void refusesABuildWhoseDataNoLongerExistsRatherThanSubstituting() {
        IllegalArgumentException refused = assertThrows(
            IllegalArgumentException.class,
            () -> FmlDeobfuscation.resolve(version("1.5", "f06a8e84e627d0e3cae96443e25e888bd8865e67"))
        );
        assertTrue(refused.getMessage().contains("no copy of it survives"), refused.getMessage());
    }

    /** A hash that survives, under the other version's name, is still not this build's file. */
    @Test
    void theHashHasToBelongToTheNameItIsAskedUnder() {
        assertThrows(
            IllegalArgumentException.class,
            () -> FmlDeobfuscation.resolve(version("1.5", "22e221a0d89516c1f721d6cab056a7e37471d0a6"))
        );
    }
}
