package com.nextcraft.forge.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Passphrase-encrypted export of every stored secret, so keys survive a device change
 * (AndroidKeyStore entries never leave the device).
 */
object VaultBackup {
    data class ImportResult(val providers: Int, val keys: Int)

    fun export(vault: ApiKeyVault, passphrase: CharArray): String {
        require(passphrase.isNotEmpty()) { "A passphrase is required" }
        val payload = JSONObject()
        val providers = JSONArray()
        vault.providerIds().forEach { providerId ->
            val credentials = vault.credentials(providerId)
            if (credentials.isEmpty()) return@forEach
            val keys = JSONArray()
            credentials.forEach { credential ->
                keys.put(
                    JSONObject()
                        .put("name", credential.name)
                        .put("secret", credential.secret)
                        .put("active", credential.isActive),
                )
            }
            providers.put(JSONObject().put("provider", providerId).put("keys", keys))
        }
        payload.put("providers", providers)
        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val ciphertext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                doFinal(plaintext)
            }
        } finally {
            key.fill(0)
            plaintext.fill(0)
            passphrase.fill('\u0000')
        }
        return JSONObject()
            .put("v", 1)
            .put("kdf", KDF)
            .put("iter", ITERATIONS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
    }

    fun import(vault: ApiKeyVault, blob: String, passphrase: CharArray): ImportResult {
        require(passphrase.isNotEmpty()) { "A passphrase is required" }
        val envelope = runCatching { JSONObject(blob) }.getOrElse { throw IllegalArgumentException("Not a NextCraft backup") }
        require(envelope.optInt("v") == 1 && envelope.optString("kdf") == KDF) { "Unsupported backup version" }
        val iterations = envelope.optInt("iter", ITERATIONS).coerceIn(10_000, 2_000_000)
        val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.getString("ct"), Base64.NO_WRAP)
        val key = deriveKey(passphrase, salt, iterations)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }
        } catch (error: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Wrong passphrase or corrupted backup", error)
        } finally {
            key.fill(0)
            passphrase.fill('\u0000')
        }
        val payload = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
        val providers = payload.optJSONArray("providers") ?: JSONArray()
        var providerCount = 0
        var keyCount = 0
        for (index in 0 until providers.length()) {
            val item = providers.optJSONObject(index) ?: continue
            val providerId = item.optString("provider").takeIf(String::isNotBlank) ?: continue
            val keys = item.optJSONArray("keys") ?: continue
            val existing = vault.credentials(providerId).map { it.secret }.toSet()
            var activeId: String? = null
            for (keyIndex in 0 until keys.length()) {
                val entry = keys.optJSONObject(keyIndex) ?: continue
                val secret = entry.optString("secret")
                if (secret.isBlank() || secret in existing) continue
                val added = vault.add(providerId, entry.optString("name").ifBlank { "Imported key" }, secret)
                keyCount++
                if (entry.optBoolean("active")) activeId = added.id
            }
            activeId?.let { vault.activate(providerId, it) }
            providerCount++
        }
        return ImportResult(providerCount, keyCount)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 310_000
    private val random = SecureRandom()
}
