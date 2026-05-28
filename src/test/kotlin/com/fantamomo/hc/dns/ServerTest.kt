package com.fantamomo.hc.dns

import com.fantamomo.hc.dns.net.rootModule
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {

    @Test
    fun `test root endpoint`() = testApplication {
        application {
            rootModule()
        }
        // verify server root returns 200
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

}