package dev.telegrammcp.server.auth

import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TelegramPasswordSubmitterTest {
    @Test
    fun `rejected password can be retried without a new interaction future`() {
        val holder = TelegramAuthStateHolder()
        holder.setState(AuthState.WaitingPassword())
        val submitter = TelegramPasswordSubmitter(holder)
        var calls = 0
        val send: (String) -> CompletableFuture<TdApi.Error?> = {
            calls++
            CompletableFuture.completedFuture(if (calls == 1) TdApi.Error(400, "PASSWORD_HASH_INVALID") else null)
        }

        assertNotNull(submitter.submit("first", send))
        assertTrue(AuthStateDto.from(holder.getState()).errorMessage!!.contains("Telegram rejected"))
        assertNull(submitter.submit("second", send))
        assertEquals(2, calls)
        assertTrue(holder.getState() is AuthState.WaitingPassword)
    }

    @Test
    fun `concurrent clicks send only one TDLib request`() {
        val holder = TelegramAuthStateHolder()
        holder.setState(AuthState.WaitingPassword())
        val submitter = TelegramPasswordSubmitter(holder)
        val started = CountDownLatch(1)
        val reply = CompletableFuture<TdApi.Error?>()
        val first = CompletableFuture.supplyAsync { submitter.submit("first") { started.countDown(); reply } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        try {
            assertTrue(submitter.submit("second") { fail("duplicate password dispatched") }!!.contains("in progress"))
        } finally { reply.complete(null) }
        assertNull(first.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun `stale page cannot submit after login completes`() {
        val holder = TelegramAuthStateHolder()
        holder.setState(AuthState.Ready())
        assertFailsWith<IllegalStateException> {
            TelegramPasswordSubmitter(holder).submit("unused") { fail("request sent after ready") }
        }
    }

    @Test
    fun `untrusted error details and passwords are not reflected into state`() {
        val holder = TelegramAuthStateHolder()
        holder.setState(AuthState.WaitingPassword())
        val secret = "private-test-password"
        val result = TelegramPasswordSubmitter(holder).submit(secret) {
            CompletableFuture.completedFuture(TdApi.Error(500, "unexpected $secret"))
        }
        assertFalse(result!!.contains(secret))
        assertFalse(AuthStateDto.from(holder.getState()).toString().contains(secret))
    }
}
