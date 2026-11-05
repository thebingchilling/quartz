package app.quartz.core.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test
    fun comparesAcrossTagStyles() {
        assertTrue(VersionCompare.compare("5.8.3", "5.8.2") > 0)
        assertTrue(VersionCompare.compare("v1.19.33", "v1.19.4") > 0)
        assertTrue(VersionCompare.compare("release/3.11.8", "release/3.10") > 0)
        assertTrue(VersionCompare.compare("v2.4.1", "2.4.1") == 0)
        assertTrue(UpstreamVersion("mihomo", "v1.19.33", "v1.19.34").updateAvailable)
        assertFalse(UpstreamVersion("mihomo", "v1.19.33", "v1.19.33").updateAvailable)
        assertFalse(
            "not included yet",
            UpstreamVersion("OpenVPN 3", "", "release/3.11.8").updateAvailable,
        )
    }
}
