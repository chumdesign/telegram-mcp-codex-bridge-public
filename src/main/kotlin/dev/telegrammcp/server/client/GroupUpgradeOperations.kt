package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.model.GroupUpgradePreview
import dev.telegrammcp.server.model.GroupUpgradeResult
import it.tdlight.jni.TdApi
import it.tdlight.client.TelegramError
import dev.telegrammcp.server.exception.TelegramApiException

/** Metadata only. Never reads messages or toggles history/publicity/topics. */
internal class GroupUpgradeOperations(private val send: (TdApi.Function<*>) -> TdApi.Object) {
    private val migrationLock = Any()

    private inline fun <reified T : TdApi.Object> request(function: TdApi.Function<*>): T = send(function) as T

    fun inspect(chatId: Long): GroupUpgradePreview {
        val chat = request<TdApi.Chat>(TdApi.GetChat(chatId))
        return when (val type = chat.type) {
            is TdApi.ChatTypeBasicGroup -> {
                val group = request<TdApi.BasicGroup>(TdApi.GetBasicGroup(type.basicGroupId))
                val owner = group.status is TdApi.ChatMemberStatusCreator
                val migrated = group.upgradedToSupergroupId.takeIf { it != 0L }?.let {
                    request<TdApi.Chat>(TdApi.CreateSupergroupChat(it, false)).id
                }
                GroupUpgradePreview(
                    chat.id, chat.title, ChatType.GROUP,
                    canUpgrade = group.isActive && owner && migrated == null,
                    isOwner = owner, upgradedToChatId = migrated,
                    reason = when {
                        migrated != null -> "Already migrated; inspect upgradedToChatId before any further action"
                        !group.isActive -> "The basic group is inactive"
                        !owner -> "Only the group owner can migrate it"
                        else -> null
                    },
                )
            }
            is TdApi.ChatTypeSupergroup -> {
                if (type.isChannel) throw InvalidToolInputException("A channel cannot be upgraded from a basic group")
                val group = request<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId))
                val full = request<TdApi.SupergroupFullInfo>(TdApi.GetSupergroupFullInfo(type.supergroupId))
                GroupUpgradePreview(chat.id, chat.title, ChatType.SUPERGROUP, false,
                    group.status is TdApi.ChatMemberStatusCreator, chat.id, full.isAllHistoryAvailable,
                    "Already a supergroup; no migration is necessary")
            }
            else -> throw InvalidToolInputException("Only a basic group or an existing supergroup can be inspected")
        }
    }

    fun upgrade(chatId: Long): GroupUpgradeResult = synchronized(migrationLock) {
        val before = inspect(chatId)
        if (before.upgradedToChatId != null) {
            val existing = inspect(before.upgradedToChatId)
            return@synchronized GroupUpgradeResult(chatId, existing.chatId, existing.title,
                false, true, requireNotNull(existing.historyVisibleToNewMembers), true)
        }
        if (!before.canUpgrade) throw InvalidToolInputException(before.reason ?: "This group cannot be upgraded")

        // A single request, with no retry wrapper. A timeout may mean Telegram
        // already migrated the group; the caller must inspect the original ID.
        val created = try {
            request<TdApi.Chat>(TdApi.UpgradeBasicGroupChatToSupergroupChat(chatId))
        } catch (error: TelegramApiException) {
            val reason = "Telegram ${error.errorCode}: ${error.errorMessage.take(512)}"
            if (error.errorCode in setOf(400, 401, 403, 404, 406, 420, 429)) {
                throw InvalidToolInputException("Telegram rejected migration of chat $chatId ($reason). " +
                    "No automatic retry was made; inspect_group_upgrade can verify the current state.")
            }
            throw IllegalStateException("Migration of chat $chatId was not confirmed ($reason). " +
                "Inspect the original ID before retrying; no automatic retry was made.", error)
        } catch (error: TelegramError) {
            val reason = "Telegram ${error.errorCode}: ${error.errorMessage.take(512)}"
            if (error.errorCode in setOf(400, 401, 403, 404, 406, 420, 429)) {
                throw InvalidToolInputException("Telegram rejected migration of chat $chatId ($reason). " +
                    "No automatic retry was made; inspect_group_upgrade can verify the current state.")
            }
            throw IllegalStateException("Migration of chat $chatId was not confirmed ($reason). " +
                "Inspect the original ID before retrying; no automatic retry was made.", error)
        } catch (error: Exception) {
            throw IllegalStateException("Migration of chat $chatId was not confirmed. It may have completed. " +
                "Call inspect_group_upgrade with the original ID before retrying; no automatic retry was made.", error)
        }
        val after = try {
            inspect(created.id)
        } catch (error: Exception) {
            throw IllegalStateException("Migration returned new chat ${created.id} for $chatId, but readback failed. " +
                "Inspect that new ID; do not repeat migration.", error)
        }
        check(after.type == ChatType.SUPERGROUP) { "Migration returned unexpected type for new chat ${created.id}" }
        check(after.historyVisibleToNewMembers == false) {
            "Migration completed: original chat=$chatId, new chat=${created.id}, but Telegram reports full history " +
                "visible to new members. Verification failed; the connector did not toggle history. " +
                "Inspect the new chat before any further action; do not repeat migration."
        }
        GroupUpgradeResult(chatId, after.chatId, after.title, true, false, false, true)
    }
}
