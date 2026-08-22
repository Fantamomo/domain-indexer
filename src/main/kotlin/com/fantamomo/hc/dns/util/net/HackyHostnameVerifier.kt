package com.fantamomo.hc.dns.util.net

import okhttp3.internal.tls.OkHostnameVerifier
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

// this is a custom HostnameVerifier that is used to verify the hostnames of the hacky SNI socket factory
// see SniSocketFactory for more details

// also this mostly complete unsafe and unsecure how we are doing it (not only here, but also in SniSocketFactory and Routing.kt), but it works for now
// and unless I find a better way to do it, this is the best solution I have for now
class HackyHostnameVerifier(val sniSocketFactory: SniSocketFactory) : HostnameVerifier {

    override fun verify(hostname: String, session: SSLSession) =
        // first we delegate to the real hostname verifier to check if the hostname is valid that way
        OkHostnameVerifier.verify(hostname, session) ||
                // if that fails we check if the hostname is in the list of hosts that we are just trying to connect to
                sniSocketFactory.hosts.contains(hostname)
}