package com.emfitsolutions.gopreach.data.navigation

import com.emfitsolutions.gopreach.domain.navigation.RoutingException
import com.emfitsolutions.gopreach.domain.navigation.RoutingFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValhallaFallbackTest {
    @Test fun aDownOwnServerFallsBackToThePublicOne() = runTest {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { req -> hosts += req.url.host; respond("down", HttpStatusCode.ServiceUnavailable) }
        val service = ValhallaRoutingService(HttpClient(engine), baseUrl = "https://own.example", timeoutMillis = 0L, fallbackBaseUrl = "https://public.example")
        val e = assertFailsWith<RoutingException> { service.route(14.0, 121.0, 14.1, 121.1) }
        assertEquals(RoutingFailure.SERVER, e.failure)
        assertEquals(listOf("own.example", "public.example"), hosts)
    }

    @Test fun noRouteIsAnAnswerNotAnOutageSoTheFallbackIsNotTried() = runTest {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { req -> hosts += req.url.host; respond("none", HttpStatusCode.BadRequest) }
        val service = ValhallaRoutingService(HttpClient(engine), baseUrl = "https://own.example", timeoutMillis = 0L, fallbackBaseUrl = "https://public.example")
        val e = assertFailsWith<RoutingException> { service.route(14.0, 121.0, 14.1, 121.1, walking = true) }
        assertEquals(RoutingFailure.NO_ROUTE, e.failure)
        assertEquals(listOf("own.example"), hosts)
    }
}
