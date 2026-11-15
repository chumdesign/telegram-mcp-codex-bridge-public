package dev.telegrammcp.server.model

data class GroupUpgradePreview(
    val chatId: Long,
    val title: String,
    val type: ChatType,
    val canUpgrade: Boolean,
    val isOwner: Boolean,
    val upgradedToChatId: Long? = null,
    // Basic groups have no global is_all_history_available setting.
    val historyVisibleToNewMembers: Boolean? = null,
    val reason: String? = null,
)

data class GroupUpgradeResult(
    val originalChatId: Long,
    val chatId: Long,
    val title: String,
    val upgraded: Boolean,
    val alreadyUpgraded: Boolean,
    val historyVisibleToNewMembers: Boolean,
    val historyVisibilityVerified: Boolean,
    val historySettingChangedByConnector: Boolean = false,
)
