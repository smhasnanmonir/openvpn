package com.smhasnan.openvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smhasnan.openvpn.nativevpn.NativeCallbacks
import com.smhasnan.openvpn.nativevpn.NativeEngine
import com.smhasnan.openvpn.nativevpn.NativeSession
import com.smhasnan.openvpn.nativevpn.OpenVpnNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentedTest {
    @Test
    fun engineIsLoadedAndCryptoSelfTestPasses() {
        assertTrue(OpenVpnNative.isAvailable)
        val info = NativeEngine.engineInfo()
        assertEquals("3.9", info["coreVersion"])
        assertTrue(NativeEngine.selfTest().isSuccess)
    }

    @Test
    fun malformedProfileIsRejectedByCore() {
        val result = NativeEngine.evalConfig("this is not an OpenVPN profile\nremote\n")
        assertTrue(result.error)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun sessionCanBeCreatedAndDestroyed() {
        val callbacks = object : NativeCallbacks {
            override fun onEngineEvent(name: String, info: String, error: Boolean, fatal: Boolean) = Unit
            override fun onEngineLog(line: String) = Unit
            override fun onTunEstablish(configJson: String): Int = -1
            override fun onProtectSocket(fd: Int): Boolean = false
            override fun onChallenge(challenge: String, echo: Boolean, responseRequired: Boolean, stateId: String) = Unit
        }
        val session = NativeSession(callbacks)
        assertFalse(session.isRunning())
        session.close()
    }
}
