package dev.callstream.probe.adb

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * The app's ADB key: an RSA key and a self-signed certificate in the app's private files. Pairing makes adbd trust this
 * key for good, so the key IS the pairing: lose the files and Call Stream has to pair again (adbd still lists the old
 * key; only Settings → Wireless debugging → Forget removes it). Same approach as JemRec's AdbIdentity.
 */
internal class AdbIdentity private constructor(val privateKey: PrivateKey, val certificate: Certificate) {
    companion object {
        private const val TAG = "CallStreamAdb"
        private const val KEY_FILE = "adb_key.der"
        private const val CERT_FILE = "adb_cert.der"
        @Volatile private var cached: AdbIdentity? = null

        @Synchronized
        fun get(context: Context): AdbIdentity {
            cached?.let { return it }
            val keyFile = File(context.filesDir, KEY_FILE)
            val certFile = File(context.filesDir, CERT_FILE)
            val identity = load(keyFile, certFile) ?: generate().also {
                Log.i(TAG, "new ADB key")
                keyFile.writeBytes(it.privateKey.encoded) // key first: a crash before the cert just regenerates both
                certFile.writeBytes(it.certificate.encoded)
            }
            cached = identity
            return identity
        }

        private fun load(keyFile: File, certFile: File): AdbIdentity? {
            if (!keyFile.exists() || !certFile.exists()) return null
            return runCatching {
                AdbIdentity(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())),
                    CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes())))
            }.onFailure { Log.w(TAG, "ADB key unreadable; making a new one (pair again)", it) }.getOrNull()
        }

        private fun generate(): AdbIdentity {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=Call Stream")
            // BouncyCastle only builds the certificate; the platform's provider signs it (no provider registered).
            val holder = JcaX509v3CertificateBuilder(name, BigInteger(64, SecureRandom()), Date(now - 86_400_000L),
                Date(now + 30L * 365 * 86_400_000L), name, keys.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
            return AdbIdentity(keys.private, CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(holder.encoded)))
        }
    }
}
