package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import it.tdlight.jni.TdApi
import it.tdlight.client.TelegramError
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class GroupUpgradeOperationsTest {
    private val oldId = -42L
    private val newId = -1000000000099L
    private val requests = mutableListOf<TdApi.Function<*>>()
    private var owner = true
    private var active = true
    private var migrated = false
    private var historyVisible = false
    private var uncertain = false
    private var readbackFails = false
    private var telegramError: TdApi.Error? = null
    private fun chat(id: Long) = TdApi.Chat().apply {
        this.id = id; title = "Test group"
        type = if (id == oldId) TdApi.ChatTypeBasicGroup(42) else TdApi.ChatTypeSupergroup(99, false)
    }
    private val ops = GroupUpgradeOperations { request ->
        requests += request
        when (request) {
            is TdApi.GetChat -> {
                if (request.chatId == newId && readbackFails) error("readback unavailable")
                chat(request.chatId)
            }
            is TdApi.GetBasicGroup -> TdApi.BasicGroup().apply {
                status = if (owner) TdApi.ChatMemberStatusCreator() else TdApi.ChatMemberStatusMember()
                isActive = active; upgradedToSupergroupId = if (migrated) 99 else 0
            }
            is TdApi.CreateSupergroupChat -> chat(newId)
            is TdApi.GetSupergroup -> TdApi.Supergroup().apply { status = TdApi.ChatMemberStatusCreator() }
            is TdApi.GetSupergroupFullInfo -> TdApi.SupergroupFullInfo().apply { isAllHistoryAvailable = historyVisible }
            is TdApi.UpgradeBasicGroupChatToSupergroupChat -> {
                telegramError?.let { throw TelegramError(it) }
                migrated = true
                if (uncertain) error("simulated lost response")
                chat(newId)
            }
            else -> error("Unexpected request: ${request.javaClass.simpleName}")
        }
    }

    private fun migrations() = requests.count { it is TdApi.UpgradeBasicGroupChatToSupergroupChat }
    private fun assertNoSettingChanges() = assertFalse(requests.any {
        it is TdApi.ToggleSupergroupIsAllHistoryAvailable || it is TdApi.ToggleSupergroupIsForum ||
            it is TdApi.SetSupergroupUsername || it is TdApi.GetChatHistory || it is TdApi.ViewMessages
    })

    @Test fun `preview reads only metadata and explains basic-group history setting`() {
        val result = ops.inspect(oldId)
        assertTrue(result.canUpgrade); assertTrue(result.isOwner)
        assertNull(result.historyVisibleToNewMembers)
        assertEquals(0, migrations()); assertNoSettingChanges()
    }
    @Test fun `upgrade returns new ID and verifies hidden history without toggling it`() {
        val result = ops.upgrade(oldId)
        assertEquals(oldId, result.originalChatId); assertEquals(newId, result.chatId)
        assertTrue(result.upgraded); assertTrue(result.historyVisibilityVerified)
        assertFalse(result.historyVisibleToNewMembers); assertFalse(result.historySettingChangedByConnector)
        assertEquals(1, migrations()); assertNoSettingChanges()
    }
    @Test fun `non-owner and inactive groups fail before migration`() {
        owner = false
        assertFailsWith<InvalidToolInputException> { ops.upgrade(oldId) }
        owner = true; active = false
        assertFailsWith<InvalidToolInputException> { ops.upgrade(oldId) }
        assertEquals(0, migrations())
    }
    @Test fun `existing supergroup is a no-op and keeps its current visible history`() {
        historyVisible = true
        val result = ops.upgrade(newId)
        assertTrue(result.alreadyUpgraded); assertFalse(result.upgraded)
        assertTrue(result.historyVisibleToNewMembers); assertEquals(0, migrations()); assertNoSettingChanges()
    }
    @Test fun `original ID of migrated group resolves safely without a second migration`() {
        migrated = true
        val result = ops.upgrade(oldId)
        assertEquals(newId, result.chatId); assertTrue(result.alreadyUpgraded); assertEquals(0, migrations())
    }
    @Test fun `uncertain response is not retried and inspection recovers the new ID`() {
        uncertain = true
        val error = assertFailsWith<IllegalStateException> { ops.upgrade(oldId) }
        assertTrue(error.message!!.contains("inspect_group_upgrade")); assertEquals(1, migrations())
        assertEquals(newId, ops.inspect(oldId).upgradedToChatId)
        assertTrue(ops.upgrade(oldId).alreadyUpgraded); assertEquals(1, migrations())
    }
    @Test fun `unexpected visible history reports completed migration without changing settings`() {
        historyVisible = true
        val error = assertFailsWith<IllegalStateException> { ops.upgrade(oldId) }
        assertTrue(error.message!!.contains("new chat=$newId")); assertEquals(1, migrations()); assertNoSettingChanges()
    }
    @Test fun `post-migration readback failure reports the new ID and never repeats`() {
        readbackFails = true
        val error = assertFailsWith<IllegalStateException> { ops.upgrade(oldId) }
        assertTrue(error.message!!.contains("new chat $newId")); assertEquals(1, migrations())
    }
    @Test fun `concurrent upgrades issue one migration`() {
        val a = CompletableFuture.supplyAsync { ops.upgrade(oldId) }
        val b = CompletableFuture.supplyAsync { ops.upgrade(oldId) }
        val results = listOf(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS))
        assertEquals(1, migrations()); assertEquals(1, results.count { it.upgraded })
    }
    @Test fun `Telegram rejection reports the real reason without retrying`() {
        telegramError = TdApi.Error(400, "CHANNELS_TOO_MUCH")
        val error = assertFailsWith<InvalidToolInputException> { ops.upgrade(oldId) }
        assertTrue(error.message!!.contains("CHANNELS_TOO_MUCH"))
        assertTrue(error.message!!.contains("400")); assertEquals(1, migrations())
        assertNull(ops.inspect(oldId).upgradedToChatId); assertNoSettingChanges()
    }
    @Test fun `server error retains the uncertain result and exposes the reason`() {
        for ((index, code) in listOf(408, 499, 500).withIndex()) {
            telegramError = TdApi.Error(code, "INTERNAL")
            val error = assertFailsWith<IllegalStateException> { ops.upgrade(oldId) }
            assertTrue(error.message!!.contains("$code: INTERNAL")); assertEquals(index + 1, migrations())
        }
    }
}
