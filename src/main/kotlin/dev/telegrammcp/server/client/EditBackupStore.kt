package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import it.tdlight.jni.TdApi
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/** Durable, private pre-edit TDLib snapshot. A failed snapshot aborts the edit. */
class EditBackupStore(
    private val root: Path? = System.getenv("TELEGRAM_MCP_DATA_DIR")?.let(Path::of),
) {
    fun save(message: TdApi.Message, fullRich: TdApi.RichMessage? = null): Path {
        val base = root ?: throw InvalidToolInputException(
            "Private edit backup storage is not configured; no edit was made",
        )
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(base)) {
            throw InvalidToolInputException("Private edit backup storage is not a safe directory; no edit was made")
        }
        val directory = base.resolve("edit-backups")
        Files.createDirectories(directory)
        if (Files.isSymbolicLink(directory)) {
            throw InvalidToolInputException("Edit backup directory must not be a link; no edit was made")
        }
        val name = "before-${message.chatId}-${message.id}-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}.tdapi"
        val path = directory.resolve(name)
        DataOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).use { out ->
            out.writeUTF("telegram-mcp-edit-backup-v1")
            message.serialize(out)
            out.writeBoolean(fullRich != null)
            fullRich?.serialize(out)
        }
        return path
    }
}
