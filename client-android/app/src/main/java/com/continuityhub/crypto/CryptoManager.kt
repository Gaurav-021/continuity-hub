package com.continuityhub.crypto

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

class CryptoManager(private val context: Context) {

    private val keysDir = File(context.filesDir, "keys")
    private val certFile = File(keysDir, "cert.der")
    private val keyFile = File(keysDir, "key.pk8")

    val certificate: X509Certificate
    val privateKey: PrivateKey
    val fingerprint: String
    val keyStore: KeyStore

    init {
        if (!keysDir.exists()) {
            keysDir.mkdirs()
        }

        if (certFile.exists() && keyFile.exists()) {
            val certBytes = certFile.readBytes()
            val certFactory = CertificateFactory.getInstance("X.509")
            certificate = certFactory.generateCertificate(certBytes.inputStream()) as X509Certificate

            val keyBytes = keyFile.readBytes()
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            privateKey = keyFactory.generatePrivate(keySpec)
        } else {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair: KeyPair = keyPairGenerator.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 1000L * 60 * 60 * 24) // 1 day ago
            val notAfter = Date(now + 1000L * 60 * 60 * 24 * 365 * 10) // 10 years

            val serialNumber = BigInteger.valueOf(now)
            val name = X500Name("CN=Continuity-Android, O=Continuity Hub")

            val certBuilder = JcaX509v3CertificateBuilder(
                name,
                serialNumber,
                notBefore,
                notAfter,
                name,
                keyPair.public
            )

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certHolder = certBuilder.build(signer)
            certificate = JcaX509CertificateConverter().getCertificate(certHolder)
            privateKey = keyPair.private

            certFile.writeBytes(certificate.encoded)
            keyFile.writeBytes(privateKey.encoded)
        }

        fingerprint = calculateFingerprint(certificate.encoded)

        // Initialize in-memory KeyStore
        keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("continuity", privateKey, "password".toCharArray(), arrayOf(certificate))
        }
    }

    companion object {
        fun calculateFingerprint(der: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(der)
            return hash.joinToString(":") { "%02X".format(it) }
        }
    }
}
