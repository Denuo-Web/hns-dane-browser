package com.denuoweb.hnsdane.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsLocalCertificateRegistryTest {
    @Test
    fun pinnedFingerprintsAreScopedToNormalizedHost() {
        HnsLocalCertificateRegistry.clear()
        val fingerprint = ByteArray(32) { index -> index.toByte() }

        HnsLocalCertificateRegistry.trustHostCertificate("Welcome.", fingerprint)

        assertTrue(HnsLocalCertificateRegistry.hasPinnedFingerprint("welcome", fingerprint))
        assertTrue(HnsLocalCertificateRegistry.hasPinnedFingerprint("WELCOME.", fingerprint))
        assertFalse(HnsLocalCertificateRegistry.hasPinnedFingerprint("other", fingerprint))
        assertFalse(HnsLocalCertificateRegistry.hasPinnedFingerprint("welcome", ByteArray(32)))
    }

    @Test
    fun pinnedFingerprintRegistryIsBoundedAndRejectsInvalidHosts() {
        HnsLocalCertificateRegistry.clear()
        val fingerprint = ByteArray(32) { 7 }
        HnsLocalCertificateRegistry.trustHostCertificate("bad host", fingerprint)
        repeat(140) { index ->
            HnsLocalCertificateRegistry.trustHostCertificate("host-$index.hns", fingerprint)
        }

        assertTrue(HnsLocalCertificateRegistry.size() <= 128)
        assertFalse(HnsLocalCertificateRegistry.hasPinnedFingerprint("bad host", fingerprint))
        assertFalse(HnsLocalCertificateRegistry.hasPinnedFingerprint("host-0.hns", fingerprint))
        assertTrue(HnsLocalCertificateRegistry.hasPinnedFingerprint("host-139.hns", fingerprint))
        HnsLocalCertificateRegistry.clear()
    }
}
