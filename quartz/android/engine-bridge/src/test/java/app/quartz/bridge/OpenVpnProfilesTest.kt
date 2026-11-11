package app.quartz.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVpnProfilesTest {
    private val profile = "client\ndev tun\nremote vpn.example.com 1194\nauth-user-pass\n"

    @Test
    fun `inline credentials are split off and the auth-user-pass line kept`() {
        val (bare, credentials) =
            OpenVpnProfiles.split(profile + "<auth-user-pass>\nalice\ns3cret\n</auth-user-pass>\n")
        assertEquals(OpenVpnCredentials("alice", "s3cret"), credentials)
        assertTrue("<auth-user-pass>" !in bare)
        assertTrue(Regex("(?m)^auth-user-pass$").containsMatchIn(bare))
    }

    @Test
    fun `a profile without credentials is unchanged`() {
        val (bare, credentials) = OpenVpnProfiles.split(profile)
        assertEquals(profile, bare)
        assertNull(credentials)
    }

    @Test
    fun `a bare inline block still asks openvpn3 for credentials`() {
        val (bare, credentials) =
            OpenVpnProfiles.split(
                "client\nremote a.example 1194\n<auth-user-pass>\nbob\npw\n</auth-user-pass>\n"
            )
        assertEquals("bob", credentials?.username)
        assertTrue(Regex("(?m)^auth-user-pass$").containsMatchIn(bare))
    }

    @Test
    fun `routes are reduced to their network address`() {
        assertEquals("10.8.0.0", network("10.8.0.6", 24))
        assertEquals("192.168.1.128", network("192.168.1.200", 25))
        assertEquals("0.0.0.0", network("1.2.3.4", 0))
        assertEquals("2001:db8:0:0:0:0:0:0", network("2001:db8::1", 64))
    }
}
