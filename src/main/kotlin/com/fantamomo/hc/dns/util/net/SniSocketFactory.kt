package com.fantamomo.hc.dns.util.net

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

// this is a custom SSLSocketFactory that allows us to set the SNI hostname for the SSL connection
// it is used by the SharedConstants.proxyClient to make requests to hosts that would redirect to the HTTPS protocol if we try to use HTTP
// but we are not able to use HTTPS because it would use the host name (which is mostly going to be the ip), so the TLS handshake would always fail
// we get around this by using this custom SSLSocketFactory that allows us to override the SNI hostname for the SSL connection
class SniSocketFactory(
    private val delegate: SSLSocketFactory,
    val trustManager: X509TrustManager, // must be the same as the one used by the backend SSLSocketFactory
) : SSLSocketFactory() {

    var hosts: List<String> = emptyList()
        private set
    var targetSni: String? = null
        private set

    fun setTargetSni(hosts: List<String>, target: String) {
        this.hosts = hosts
        this.targetSni = target
    }

    private fun configure(socket: Socket, host: String): Socket {
        if (targetSni != null && hosts.contains(host)) {
            if (socket is SSLSocket) {
                socket.sslParameters = socket.sslParameters.apply {
                    serverNames = listOf(SNIHostName(targetSni))
                }
            } else {
                logger.warn("Could not configure SNI for non-SSL socket")
            }
        }

        return socket
    }

    override fun createSocket(
        socket: Socket,
        host: String,
        port: Int,
        autoClose: Boolean
    ): Socket =
        configure(
            delegate.createSocket(socket, host, port, autoClose),
            host
        )

    override fun createSocket(
        host: String,
        port: Int
    ): Socket =
        configure(delegate.createSocket(host, port), host)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket =
        configure(
            delegate.createSocket(
                host,
                port,
                localHost,
                localPort
            ),
            host
        )

    override fun createSocket(
        host: InetAddress,
        port: Int
    ): Socket =
        configure(delegate.createSocket(host, port), host.hostAddress)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket =
        configure(
            delegate.createSocket(
                address,
                port,
                localAddress,
                localPort
            ),
            address.hostAddress
        )

    override fun getDefaultCipherSuites(): Array<String> =
        delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> =
        delegate.supportedCipherSuites

    companion object {
        private val logger = LoggerFactory.getLogger(SniSocketFactory::class.java)
    }
}