package app.quartz.bridge

import app.quartz.core.data.EngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePlaceholderTest {
    /** Upstream parses quickConfig in ~25 places; the shell row's text must always parse. */
    @Test
    fun placeholderParsesAndNamesItsEngine() {
        assertTrue(EnginePlaceholder.parses(EngineProfile.ENGINE_MIHOMO))
        val config = EnginePlaceholder.config(EngineProfile.ENGINE_MIHOMO)
        assertEquals(EngineProfile.ENGINE_MIHOMO, EnginePlaceholder.engineOf(config))
        assertNull(EnginePlaceholder.engineOf("[Interface]\nPrivateKey = x\n"))
    }
}
