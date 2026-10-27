package com.continuityhub.transport

import android.util.Log
import com.continuityhub.crypto.CryptoManager
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class TofuTrustManager(private val tofuManager: TofuManager) : X509TrustManager {

    private val tag = "TofuTrustManager"

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
        verifyChain(chain, "client")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        verifyChain(chain, "server")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    private fun verifyChain(chain: Array<X509Certificate>?, role: String) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty certificate chain from $role")
        }
        val endEntity = chain[0]
        val fingerprint = CryptoManager.calculateFingerprint(endEntity.encoded)

        when (val result = tofuManager.verifyAndPin(fingerprint)) {
            is TofuResult.Trusted -> {
                Log.i(tag, "mTLS: $role certificate verified for '${result.device.deviceName}' ($fingerprint)")
            }
            is TofuResult.FirstUsePinned -> {
                Log.i(tag, "mTLS: TOFU accepted and pinned new $role certificate ($fingerprint)")
            }
            is TofuResult.Mismatch -> {
                Log.e(tag, "mTLS REJECTED: Certificate mismatch for device ${result.deviceId}! Expected: ${result.expected}, Presented: ${result.presented}")
                throw CertificateException("TOFU Certificate Mismatch for ${result.deviceId}")
            }
            is TofuResult.Rejected -> {
                Log.e(tag, "mTLS REJECTED: Device explicitly marked untrusted ($fingerprint)")
                throw CertificateException("Untrusted certificate $fingerprint")
            }
        }
    }
}

class TlsManager(
    private val cryptoManager: CryptoManager,
    private val tofuManager: TofuManager
) {

    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(cryptoManager.keyStore, "password".toCharArray())

        val trustManagers = arrayOf(TofuTrustManager(tofuManager))
        init(kmf.keyManagers, trustManagers, null)
    }

    fun createServerSocket(port: Int): SSLServerSocket {
        val serverSocket = sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        // Enforce Mutual TLS (Client Authentication)
        serverSocket.needClientAuth = true
        serverSocket.reuseAddress = true
        return serverSocket
    }

    fun connect(host: String, port: Int, timeoutMs: Int = 5000): SSLSocket {
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.startHandshake()
        return socket
    }
}
