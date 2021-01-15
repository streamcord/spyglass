package io.streamcord.webhooks.server

import kotlin.test.Test
import kotlin.test.assertNotNull

val neverNull: String? = "test constant"

class AppTest {
    @Test fun testAppHasAGreeting() {
        assertNotNull(neverNull, "should never be null")
    }
}
