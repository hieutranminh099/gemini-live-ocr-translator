package com.gemini.ocr.settings

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiKeyBackupTest {
    @Test fun roundTripUsesFreshEncryptionAndRejectsWrongAccountPasswordAndTampering() {
        val password = "synthetic-password".toCharArray()
        val keys = listOf("synthetic-key-one", "", "synthetic-key-three")
        val backup = GeminiKeyBackup.seal(keys, password, "one@example.com")
        assertEquals(keys, GeminiKeyBackup.open(backup, password, "one@example.com"))
        assertFalse(backup.toString(Charsets.UTF_8).contains(keys[0]))
        assertFalse(backup.contentEquals(GeminiKeyBackup.seal(keys, password, "one@example.com")))
        for ((secret, account) in listOf("wrong-password" to "one@example.com", "synthetic-password" to "two@example.com")) {
            try {
                GeminiKeyBackup.open(backup, secret.toCharArray(), account)
                fail("Must reject mismatched credentials")
            } catch (_: IllegalArgumentException) { }
        }
        val modified = org.json.JSONObject(backup.toString(Charsets.UTF_8))
        val payload = java.util.Base64.getDecoder().decode(modified.getString("ciphertext"))
        payload[0] = (payload[0].toInt() xor 1).toByte()
        modified.put("ciphertext", java.util.Base64.getEncoder().encodeToString(payload))
        try {
            GeminiKeyBackup.open(modified.toString().toByteArray(), password, "one@example.com")
            fail("Must authenticate ciphertext")
        } catch (_: IllegalArgumentException) { }
    }
}
