package dev.andsmb.smb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalAddressesTest {
    private fun local(s: String) = LocalAddresses.isLocal(InetAddress.getByName(s))

    @Test fun lanAddressesAreLocal() {
        listOf("192.168.1.8", "10.0.0.5", "172.16.0.1", "172.31.255.254", "169.254.10.1", "127.0.0.1",
            "fe80::1", "::1", "fd12:3456::1", "fc00::1").forEach { assertTrue(it, local(it)) }
    }

    @Test fun internetAddressesAreNotLocal() {
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "203.0.113.5", "2001:db8::1", "2400:4050::1")
            .forEach { assertFalse(it, local(it)) }
    }
}
