package com.cardprice.app.data.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's identity for the backup server: an EC P-256 key pair made inside the Android Keystore
 * (the phone's secure hardware where available). The private key can't be read or copied out, even by
 * this app; it can only sign. The server trusts the device once it's been paired with the public key.
 */
object DeviceKey {
    private const val ALIAS = "card_companion_device"
    private const val KEYSTORE = "AndroidKeyStore"
    private val random = SecureRandom()

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun privateKey(): PrivateKey {
        (keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it.privateKey }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair().private
    }

    /** The public key to register when pairing: base64 DER SubjectPublicKeyInfo. */
    fun publicKeyBase64(): String {
        privateKey()
        val cert = keyStore().getCertificate(ALIAS)
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Headers proving a request comes from this phone (see the server's devices.js). [path] includes any
     * query string; [body] is exactly the bytes sent.
     */
    fun signHeaders(deviceId: String, method: String, path: String, body: ByteArray): Map<String, String> {
        val time = System.currentTimeMillis().toString()
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val payload = "CC1\n${method.uppercase()}\n$path\n$time\n$nonce\n$bodyHash"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey())
            update(payload.toByteArray(Charsets.UTF_8))
            sign()
        }
        return mapOf(
            "X-CC-Device" to deviceId,
            "X-CC-Time" to time,
            "X-CC-Nonce" to nonce,
            "X-CC-Signature" to Base64.encodeToString(signature, Base64.NO_WRAP),
        )
    }
}
