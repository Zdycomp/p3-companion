package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REGRESSION-CHECKLIST 6.3: the session-start and sign-in bytes the connect path sends stay the same. */
class SessionBytesTest {

    private fun be16(p: ByteArray, at: Int) = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)

    @Test fun sessionStartFrames() {
        val token = ByteArray(61) { 'A'.code.toByte() }
        val f1 = Xk.control(0x10, "0001", Xk.ACTION_EXECUTE, 1, token)
        assertEquals("0001", f1.node)
        assertEquals(1, f1.requestId)
        assertEquals(Xk.ACTION_EXECUTE, f1.action)
        assertEquals(61, be16(f1.payload, 14))
        assertEquals(0, f1.payload[16].toInt())

        val blob = ByteArray(64) { it.toByte() }
        val f2 = Xk.control(0x11, "0002", Xk.ACTION_EXECUTE, 4, blob)
        assertEquals("0002", f2.node)
        assertEquals(4, f2.requestId)
        assertEquals(64, be16(f2.payload, 14))
        assertArrayEquals(blob, f2.payload.copyOfRange(17, 17 + 64))
    }

    @Test fun bindFrameLayout() {
        val key = "0123456789abcdef0123456789abcdef"
        val d = Xk.userBindData(key)
        assertEquals(2, d[0].toInt()) // bind type 2: connect back
        assertTrue(d.copyOfRange(1, 17).all { it.toInt() == 0 })
        assertEquals(32, d[17].toInt())
        assertEquals(key, String(d, 18, 32, Charsets.US_ASCII))
        val f = Xk.control(0x12, "102E", Xk.ACTION_EXECUTE, 0x40, d)
        val bytes = f.encode()
        assertEquals(16 + 17 + d.size, bytes.size)
        assertEquals(Xk.crc16(f.payload), (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8))
    }

    @Test fun keyShapeCheck() {
        assertTrue(Xk.isValidUserId("0123456789abcdef0123456789abcdef"))
        assertTrue(!Xk.isValidUserId("0123456789ABCDEF0123456789abcdef"))
        assertTrue(!Xk.isValidUserId("0123456789abcdef0123456789abcde"))
    }
}
