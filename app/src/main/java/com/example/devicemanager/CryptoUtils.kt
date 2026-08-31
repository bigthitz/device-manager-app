package com.example.devicemanager

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object CryptoUtils {

    private const val SECURE_KEY_GETLOGININFO = "7bbf8372-8630-467f-ba94-e8c2dba989a9"
    private const val AES_KEY = "aa1afads23213fas"
    private const val APP_VERSION_SALT = "e26770fa-4c9f-4a96-ac66-75f06a020f11"
    private const val DES_KEY = "&G(D#d*t"

    fun createSecureToken(machineId: String, salt: String): String {
        val bytesData = machineId.toByteArray(Charsets.UTF_8).toMutableList()
        val length = bytesData.size
        val half = length / 2
        for (i in 0 until half) {
            bytesData[i] = ((bytesData[i].toInt() + 2) and 0xFF).toByte()
        }
        val start = half - 3
        for (i in start until length) {
            bytesData[i] = ((bytesData[i].toInt() - 3) and 0xFF).toByte()
        }
        val encryptedMachineId = String(bytesData.toByteArray(), Charsets.UTF_8)
        return getMd5Salt(encryptedMachineId, salt, 5)
    }

    private fun getMd5Salt(machineId: String, salt: String, times: Int): String {
        var result = salt + machineId + salt
        val md = MessageDigest.getInstance("MD5")
        repeat(times) {
            result = md.digest(result.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
        return result
    }

    fun createAppVersionToken(machineId: String): String {
        return createSecureToken(machineId, APP_VERSION_SALT)
    }

    fun createLoginInfoToken(machineId: String): String {
        return createSecureToken(machineId, SECURE_KEY_GETLOGININFO)
    }

    fun decryptAesCbc(encryptedData: String?, key: String): String? {
        if (encryptedData.isNullOrEmpty()) return null
        return try {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(keyBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(Base64.decode(encryptedData, Base64.DEFAULT))
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun desEncrypt(plain: String, key: String): String {
        var keyBytes = key.toByteArray(Charsets.UTF_8)
        if (keyBytes.size < 8) {
            keyBytes = keyBytes.copyOf(8)
        } else if (keyBytes.size > 8) {
            keyBytes = keyBytes.copyOf(8)
        }

        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "DES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun generateUnbindUrl(machineId: String): String {
        val raw = "S6?$machineId?0"
        val encrypted = desEncrypt(raw, DES_KEY)
        return "https://assistant-pad.eebbk.net/download/parent_manager.html?$encrypted"
    }
}