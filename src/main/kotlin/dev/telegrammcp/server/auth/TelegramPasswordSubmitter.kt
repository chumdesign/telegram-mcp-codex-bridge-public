package dev.telegrammcp.server.auth

import it.tdlight.jni.TdApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Checks every explicitly submitted password with TDLib, including retries. Never stores the password. */
class TelegramPasswordSubmitter(private val stateHolder: TelegramAuthStateHolder) {
    private val inFlight = AtomicBoolean(false)

    fun submit(password: String, send: (String) -> CompletableFuture<TdApi.Error?>): String? {
        check(stateHolder.getState() is AuthState.WaitingPassword) { "Telegram is not waiting for a 2FA password" }
        if (!inFlight.compareAndSet(false, true)) return "A password check is already in progress. Please wait."
        val future = try {
            send(password)
        } catch (_: Exception) {
            inFlight.set(false)
            return report("Could not send the password check to Telegram. Please try again.")
        }
        // Keep the gate closed after an HTTP timeout until the actual TDLib request finishes.
        future.whenComplete { _, _ -> inFlight.set(false) }
        return try {
            val error = future.get(10, TimeUnit.SECONDS)
            if (error == null) null else report(safeError(error))
        } catch (_: TimeoutException) {
            report("Telegram has not confirmed the password check yet. Wait for the current request to finish.")
        } catch (_: ExecutionException) {
            report("Telegram could not complete the password check. Please try again.")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            report("Password check interrupted. Check the current login state before trying again.")
        }
    }

    private fun report(message: String): String {
        val current = stateHolder.getState()
        if (current is AuthState.WaitingPassword) stateHolder.setState(current.copy(errorMessage = message))
        return message
    }

    private fun safeError(error: TdApi.Error): String = when {
        error.message == "PASSWORD_HASH_INVALID" ->
            "Telegram rejected the cloud password. Check the password and keyboard layout, then try again."
        error.message.matches(Regex("FLOOD_WAIT_[0-9]+")) ->
            "Telegram requires a pause before another attempt (${error.message.removePrefix("FLOOD_WAIT_")} seconds)."
        else -> "Telegram rejected the password check (code ${error.code}). Check the login state before trying again."
    }
}
