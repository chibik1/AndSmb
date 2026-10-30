package dev.andsmb.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun md4() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", hex(Crypto.md4(ByteArray(0))))
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex(Crypto.md4("abc".toByteArray())))
        assertEquals("e33b4ddc9c38f2199c3e7b164fcc0536", hex(Crypto.md4("1234567890".repeat(8).toByteArray())))
    }

    @Test fun aesCmacRfc4493() {
        val key = unhex("2b7e151628aed2a6abf7158809cf4f3c")
        val m = unhex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")
        assertEquals("bb1d6929e95937287fa37d129b756746", hex(Crypto.aesCmac(key, ByteArray(0))))
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c", hex(Crypto.aesCmac(key, m.copyOf(16))))
        assertEquals("dfa66747de9ae63030ca32611497c827", hex(Crypto.aesCmac(key, m.copyOf(40))))
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe", hex(Crypto.aesCmac(key, m)))
    }

    @Test fun ntowfv2MsNlmp() {
        // MS-NLMP 4.2.4.1.1
        val ntowf = Crypto.hmacMd5(Crypto.md4("Password".utf16()), ("User".uppercase() + "Domain").utf16())
        assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", hex(ntowf))
    }

    @Test fun rc4() {
        assertEquals("bbf316e8d940af0ad3", hex(Crypto.rc4("Key".toByteArray(), "Plaintext".toByteArray())))
    }
}
