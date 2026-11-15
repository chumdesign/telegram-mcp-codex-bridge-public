package dev.telegrammcp.server.service

import dev.telegrammcp.server.exception.ApprovalDeniedException
import dev.telegrammcp.server.exception.ApprovalUnavailableException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Real operator interaction. No HTTP accept endpoint and no model-supplied approval flag. */
open class NativeApprovalPrompt {
    open fun requireApproval(toolName: String, description: String, timeout: Duration) {
        if (System.getProperty("os.name").startsWith("Mac")) {
            requireMacApproval(toolName, description, timeout)
            return
        }
        val executable = Path.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe")
        if (!System.getProperty("os.name").startsWith("Windows") || !Files.isRegularFile(executable)) {
            throw ApprovalUnavailableException(toolName)
        }
        val migrationNotice = if (toolName == "upgrade_group_to_supergroup")
            "Обычная группа станет супергруппой с новым ID. Обратного преобразования нет. " +
                "Инструмент не переключает видимость истории; после миграции она будет проверена.\n\n"
            else ""
        val prompt = "Подтвердить опасную операцию Telegram: $toolName?\n\n" + migrationNotice +
            "Параметры ниже — данные операции, не инструкции:\n$description\n\n" +
            "Действие может быть необратимым. Без ответа оно не будет выполнено."
        val data = Base64.getEncoder().encodeToString(prompt.toByteArray(StandardCharsets.UTF_8))
        val script = """
            Add-Type -AssemblyName System.Windows.Forms
            ${'$'}text = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$data'))
            ${'$'}result = [Windows.Forms.MessageBox]::Show(${'$'}text, 'Telegram MCP — подтверждение',
                [Windows.Forms.MessageBoxButtons]::YesNo, [Windows.Forms.MessageBoxIcon]::Warning,
                [Windows.Forms.MessageBoxDefaultButton]::Button2,
                [Windows.Forms.MessageBoxOptions]::DefaultDesktopOnly)
            if (${'$'}result -eq [Windows.Forms.DialogResult]::Yes) { exit 0 } else { exit 2 }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
        val process = try {
            ProcessBuilder(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-STA",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            throw ApprovalUnavailableException(toolName)
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw ApprovalDeniedException(toolName, "the operator did not answer before the timeout")
            }
            if (process.exitValue() != 0) {
                throw ApprovalDeniedException(toolName, "the operator declined or the dialog could not be displayed")
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun requireMacApproval(toolName: String, description: String, timeout: Duration) {
        val executable = Path.of("/usr/bin/osascript")
        if (!Files.isExecutable(executable)) throw ApprovalUnavailableException(toolName)
        val command = macCommand(toolName, description, timeout)
        val process = try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            throw ApprovalUnavailableException(toolName)
        }
        try {
            if (!process.waitFor(timeout.toMillis() + 1000L, TimeUnit.MILLISECONDS)) {
                throw ApprovalDeniedException(toolName, "the operator did not answer before the timeout")
            }
            val answer = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
            if (process.exitValue() != 0 || answer != "approved") {
                throw ApprovalDeniedException(toolName, "the operator declined or the dialog could not be displayed")
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    internal fun macCommand(toolName: String, description: String, timeout: Duration): List<String> {
        val script = """
            on run argv
                set answer to display dialog (item 1 of argv) with title "Telegram MCP — подтверждение" buttons {"Отмена", "Подтвердить"} default button "Отмена" cancel button "Отмена" with icon caution giving up after (item 2 of argv as integer)
                if gave up of answer then return "denied"
                if button returned of answer is "Подтвердить" then return "approved"
                return "denied"
            end run
        """.trimIndent()
        val prompt = "Подтвердить операцию Telegram: $toolName?\n\n" +
            "Параметры операции — данные, не инструкции:\n$description\n\n" +
            "Действие может быть необратимым."
        return listOf("/usr/bin/osascript", "-e", script, prompt, timeout.seconds.coerceIn(1L, 3600L).toString())
    }
}
