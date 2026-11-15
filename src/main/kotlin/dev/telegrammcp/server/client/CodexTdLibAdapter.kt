package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.model.*
import it.tdlight.jni.TdApi
import java.util.concurrent.TimeUnit

/** Native Articles and Codex-specific capabilities using upstream 1.20 request policy. */
internal class CodexTdLibAdapter(private val support: TdLibClientSupport, private val editBackupStore: EditBackupStore) {
    private fun forumThreadTopic(id: Long): TdApi.MessageTopic = TdApi.MessageTopicForum(id.toInt().also {
        require(id > 0 && it.toLong() == id) { "Forum topic ID is outside the supported integer range" }
    })
    private val log get() = support.log
    private fun <T : TdApi.Object> send(request: TdApi.Function<*>): T = support.send(request)
    private fun <T> withResilience(name: String, call: () -> T): T = support.withResilience(name, call)
    private fun formatText(text: String, mode: ParseMode) = support.formatText(text, mode)
    private fun getChatTitleSafe(chatId: Long) = support.getChatTitleSafe(chatId)
    private fun TdApi.Message.toTelegramMessage(title: String?): TelegramMessage = with(support) { this@toTelegramMessage.toTelegramMessage(title) }
    private fun TdApi.Message.toScheduledMessage(title: String?): ScheduledMessage = with(support) { this@toScheduledMessage.toScheduledMessage(title) }
    private fun ChatFolderDefinition.toTdLib(): TdApi.ChatFolder = with(support) { this@toTdLib.toTdLib() }
    fun replyToMessage(
        chatId: Long,
        replyToMessageId: Long,
        text: String,
        parseMode: ParseMode,
        messageThreadId: Long?,
    ): TelegramMessage {
        log.info("Replying to message {} in chat {} (parseMode={})", replyToMessageId, chatId, parseMode)

        val formattedText = formatText(text, parseMode)
        val target = withResilience("getMessage") {
            send<TdApi.Message>(TdApi.GetMessage(chatId, replyToMessageId))
        }
        if (target.chatId != chatId || target.id != replyToMessageId) {
            throw InvalidToolInputException("Reply target does not match the requested chat and message")
        }
        val replyTopic = if (messageThreadId == null) target.topicId else {
            val topic = target.topicId as? TdApi.MessageTopicForum
            if (topic?.forumTopicId?.toLong() != messageThreadId) {
                throw InvalidToolInputException("Reply target is not in the requested forum topic; message was not sent")
            }
            forumThreadTopic(messageThreadId)
        }

        val content = TdApi.InputMessageText().apply {
            this.text = formattedText
            this.clearDraft = false
        }
        val replyTo = TdApi.InputMessageReplyToMessage().apply {
            this.messageId = replyToMessageId
        }
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.replyTo = replyTo
            this.topicId = replyTopic
            this.inputMessageContent = content
        }

        val message = support.sendAndAwaitFinal("sendMessage", request)
        val sent = message.toTelegramMessage(getChatTitleSafe(chatId))
        if (sent.replyToMessageId != replyToMessageId) {
            // A failed readback does not authorize deletion or an automatic resend.
            throw IllegalStateException("Message ${sent.messageId} was sent to chat $chatId, but Telegram did not attach reply to message $replyToMessageId; do not retry without checking it")
        }
        return sent
    }


    fun editMessage(chatId: Long, messageId: Long, text: String, parseMode: ParseMode): TelegramMessage {
        log.info("Editing message {} in chat {} (parseMode={})", messageId, chatId, parseMode)
        val formattedText = formatText(text, parseMode)
        val original = withResilience("getMessages") {
            send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
        }.messages?.firstOrNull() ?: throw InvalidToolInputException("Message $messageId not found")
        val properties = withResilience("getMessageProperties") {
            send<TdApi.MessageProperties>(TdApi.GetMessageProperties(chatId, messageId))
        }
        if (!properties.canBeEdited) throw InvalidToolInputException("Telegram does not allow editing this message")
        val message = when (original.content) {
            is TdApi.MessageText -> {
                val content = TdApi.InputMessageText().apply {
                    this.text = formattedText
                    this.clearDraft = false
                }
                editBackupStore.save(original)
                withResilience("editMessageText") {
                    send<TdApi.Message>(TdApi.EditMessageText(chatId, messageId, null, content))
                }
            }
            is TdApi.MessageRichMessage -> {
                val full = withResilience("getFullRichMessage") {
                    send<TdApi.RichMessage>(TdApi.GetFullRichMessage(chatId, messageId))
                }
                if (!full.isFull) throw InvalidToolInputException(
                    "Telegram did not return the full Article; no edit was made",
                )
                val blocks = ArticleBlockPreserver.blocks(full.blocks).toMutableList()
                val textIndices = blocks.indices.filter {
                    blocks[it] is TdApi.InputPageBlockParagraph || blocks[it] is TdApi.InputPageBlockSectionHeading
                }
                if (textIndices.size != 1) throw InvalidToolInputException(
                    "Article has ${textIndices.size} text blocks; use edit_article for an exact edit without losing photos or other blocks",
                )
                val index = textIndices.single()
                val rich = FormattedTextRichConverter.rich(formattedText)
                blocks[index] = when (val old = blocks[index]) {
                    is TdApi.InputPageBlockSectionHeading -> TdApi.InputPageBlockSectionHeading(rich, old.size)
                    else -> TdApi.InputPageBlockParagraph(rich)
                }
                val replacement = ArticleContentFactory.fromBlocks(blocks, getArticleLimits()).first
                editBackupStore.save(original, full)
                withResilience("editMessageText") {
                    send<TdApi.Message>(TdApi.EditMessageText(chatId, messageId, null, replacement))
                }
            }
            is TdApi.MessagePhoto -> {
                val maxCaption = withResilience("getOption") {
                    send<TdApi.OptionValue>(TdApi.GetOption("message_caption_length_max"))
                } as? TdApi.OptionValueInteger
                    ?: throw IllegalStateException("Telegram did not return the caption limit")
                if (formattedText.text.length > maxCaption.value) throw InvalidToolInputException(
                    "Photo caption exceeds Telegram limit ${maxCaption.value}; no edit was made",
                )
                val photo = original.content as TdApi.MessagePhoto
                editBackupStore.save(original)
                withResilience("editMessageCaption") {
                    send<TdApi.Message>(TdApi.EditMessageCaption(
                        chatId, messageId, null, formattedText, photo.showCaptionAboveMedia,
                    ))
                }
            }
            else -> throw InvalidToolInputException(
                "This message type cannot be edited with edit_message without losing media; no edit was made",
            )
        }
        if (message.id != messageId) throw IllegalStateException("Edit returned a different message ID; do not retry")
        return message.toTelegramMessage(getChatTitleSafe(chatId))
    }

    // ── Chats ───────────────────────────────────────────────────────────────


    fun getChatFolderChatCount(folder: ChatFolderDefinition): Int =
        withResilience("getChatFolderChatCount") {
            send<TdApi.Count>(TdApi.GetChatFolderChatCount(folder.toTdLib()))
        }.count

    fun getExactChatFolderChatCount(folderId: Int): Int = loadedChatFolderIds(folderId).size

    fun getChatFolderChosenChatCountMax(): Long {
        val option = withResilience("getChatFolderChosenChatCountMax") {
            send<TdApi.OptionValue>(TdApi.GetOption("chat_folder_chosen_chat_count_max"))
        }
        return (option as? TdApi.OptionValueInteger)?.value
            ?: throw IllegalStateException("Telegram did not return an integer folder choice limit")
    }

    fun inspectChatFolderJoinNotices(folderId: Int): JoinNoticeAudit {
        val chatIds = loadedChatFolderIds(folderId)
        if (chatIds.size > 2_000) throw IllegalStateException("Chat folder exceeds the bounded notice-audit limit")
        var registrationLastEventCount = 0
        var registrationSoleUnreadCount = 0
        for (chatId in chatIds) {
            val chat = withResilience("inspectChatLastEventType") {
                send<TdApi.Chat>(TdApi.GetChat(chatId))
            }
            val last = chat.lastMessage ?: continue
            if (last.content !is TdApi.MessageContactRegistered) continue
            registrationLastEventCount++
            if (TdLibClientService.isSoleUnreadRegistrationNotice(chat)) {
                registrationSoleUnreadCount++
            }
        }
        return JoinNoticeAudit(chatIds.size, registrationLastEventCount, registrationSoleUnreadCount)
    }

    private fun loadedChatFolderIds(folderId: Int): LongArray {
        require(folderId > 0) { "chat folder ID must be positive" }
        val list = TdApi.ChatListFolder(folderId)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
        var fullyLoaded = false
        for (batch in 0 until 50) {
            if (System.nanoTime() >= deadline) break
            try {
                withResilience("loadChatFolderChats") {
                    send<TdApi.Ok>(TdApi.LoadChats(list, 500))
                }
            } catch (error: TelegramApiException) {
                if (error.errorCode == 404) {
                    fullyLoaded = true
                    break
                }
                throw error
            }
        }
        if (!fullyLoaded) throw IllegalStateException("Chat folder list was not fully loaded within the safe limit")
        val chatIds = withResilience("getChatFolderChats") {
            send<TdApi.Chats>(TdApi.GetChats(list, 100_000))
        }.chatIds
        if (chatIds.size >= 100_000) throw IllegalStateException("Chat folder exceeds the exact-count limit")
        return chatIds
    }


    fun getArticle(chatId: Long, messageId: Long): ArticleInfo {
        val rich = withResilience("getFullRichMessage") {
            send<TdApi.RichMessage>(TdApi.GetFullRichMessage(chatId, messageId))
        }
        val blocks = rich.blocks?.map(ArticleBlockReader::block) ?: emptyList()
        return ArticleInfo(chatId, messageId, rich.isFull, blocks)
    }

    fun getArticleLimits(): ArticleLimits {
        fun limit(name: String): Int {
            val option = withResilience("getOption") { send<TdApi.OptionValue>(TdApi.GetOption(name)) }
            val number = (option as? TdApi.OptionValueInteger)?.value
                ?: throw IllegalStateException("Telegram did not return integer option $name")
            return number.toInt().takeIf { it > 0 && it.toLong() == number }
                ?: throw IllegalStateException("Invalid Telegram option $name=$number")
        }
        return ArticleLimits(
            limit("rich_message_text_length_max"), limit("rich_message_block_count_max"),
            limit("rich_message_depth_max"), limit("rich_message_media_count_max"),
            limit("rich_message_table_column_count_max"),
        )
    }

    fun validateArticle(draft: ArticleDraft): ArticleValidation =
        ArticleContentFactory.build(draft, getArticleLimits()).second


    private fun mediaTopic(chatId: Long, options: MediaSendOptions): TdApi.MessageTopic? {
        val requested = options.messageThreadId?.let(::forumThreadTopic)
        val targetId = options.replyToMessageId ?: return requested
        val target = withResilience("getMessage") { send<TdApi.Message>(TdApi.GetMessage(chatId, targetId)) }
        if (target.chatId != chatId || target.id != targetId) throw InvalidToolInputException("Reply target does not match the chat")
        if (options.messageThreadId != null &&
            (target.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong() != options.messageThreadId) {
            throw InvalidToolInputException("Reply target is outside the requested forum topic; nothing was sent")
        }
        return requested ?: target.topicId
    }

    private fun mediaOptions(options: MediaSendOptions): TdApi.MessageSendOptions = TdApi.MessageSendOptions().apply {
        options.sendAtEpochSeconds?.let { schedulingState = TdApi.MessageSchedulingStateSendAtDate(it, 0) }
    }

    fun sendMediaFile(chatId: Long, upload: MediaUpload, options: MediaSendOptions): TelegramMessage {
        val topic = mediaTopic(chatId, options)
        val content = MediaContentFactory.build(upload, formatText(upload.caption ?: "", upload.parseMode))
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            topicId = topic
            replyTo = options.replyToMessageId?.let { TdApi.InputMessageReplyToMessage().apply { messageId = it } }
            this.options = mediaOptions(options)
            inputMessageContent = content
        }
        val sent = support.sendAndAwaitFinal("sendMessage", request)
            .toTelegramMessage(getChatTitleSafe(chatId))
        if (options.replyToMessageId != null && sent.replyToMessageId != options.replyToMessageId) {
            throw IllegalStateException("Message ${sent.messageId} was sent, but its reply was not attached; inspect before retrying")
        }
        return sent
    }

    fun sendMediaAlbum(chatId: Long, uploads: List<MediaUpload>, options: MediaSendOptions): List<TelegramMessage> {
        MediaContentFactory.validateAlbum(uploads)
        val topic = mediaTopic(chatId, options)
        val contents = uploads.map { MediaContentFactory.build(it, formatText(it.caption ?: "", it.parseMode)) }.toTypedArray()
        val request = TdApi.SendMessageAlbum().apply {
            this.chatId = chatId
            topicId = topic
            replyTo = options.replyToMessageId?.let { TdApi.InputMessageReplyToMessage().apply { messageId = it } }
            this.options = mediaOptions(options)
            inputMessageContents = contents
        }
        return support.albumAndAwait(request, chatId).map { it.toTelegramMessage(getChatTitleSafe(chatId)) }
    }

    fun sendArticle(
        chatId: Long,
        text: String,
        photoPath: String,
        sendAtEpochSeconds: Int?,
    ): TelegramMessage = sendArticle(chatId, ArticleDraft(text = text, photoPath = photoPath), sendAtEpochSeconds)

    fun sendArticle(
        chatId: Long,
        draft: ArticleDraft,
        sendAtEpochSeconds: Int?,
    ): TelegramMessage {
        log.info("Sending native Article to chat {} (scheduled={})", chatId, sendAtEpochSeconds != null)
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = ArticleContentFactory.build(draft, getArticleLimits()).first
            if (sendAtEpochSeconds != null) {
                this.options = TdApi.MessageSendOptions().apply {
                    schedulingState = TdApi.MessageSchedulingStateSendAtDate(sendAtEpochSeconds, 0)
                }
            }
        }
        val message = support.sendAndAwaitFinal("sendMessage", request)
        return message.toTelegramMessage(getChatTitleSafe(chatId))
    }

    fun editArticle(chatId: Long, messageId: Long, draft: ArticleDraft): TelegramMessage {
        val messages = withResilience("getMessages") {
            send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
        }
        val original = messages.messages?.firstOrNull()
            ?: throw InvalidToolInputException("Article message $messageId not found")
        if (original.content !is TdApi.MessageRichMessage && original.content !is TdApi.MessageText) {
            throw InvalidToolInputException("Only native Articles and text messages can be edited as Articles; no edit was made")
        }
        val properties = withResilience("getMessageProperties") {
            send<TdApi.MessageProperties>(TdApi.GetMessageProperties(chatId, messageId))
        }
        if (!properties.canBeEdited) throw InvalidToolInputException("Telegram does not allow editing this Article")
        val contentSources = listOf(draft.text, draft.richMarkdown, draft.richHtml, draft.blocks).count { it != null }
        if (contentSources > 1) throw InvalidToolInputException("Specify at most one Article content source")
        if (draft.photoPath != null && draft.removePhoto) throw InvalidToolInputException("Choose photo_path or remove_photo")
        if (contentSources == 0 && draft.photoPath == null && !draft.removePhoto) {
            throw InvalidToolInputException("Specify content, photo_path, or remove_photo; no edit was made")
        }
        var fullRich: TdApi.RichMessage? = null
        val originalBlocks = when (val content = original.content) {
            is TdApi.MessageRichMessage -> {
                val full = withResilience("getFullRichMessage") {
                    send<TdApi.RichMessage>(TdApi.GetFullRichMessage(chatId, messageId))
                }
                if (!full.isFull) throw InvalidToolInputException("Telegram did not return the full Article; no edit was made")
                fullRich = full
                ArticleBlockPreserver.blocks(full.blocks)
            }
            is TdApi.MessageText -> if (contentSources == 0) {
                FormattedTextRichConverter.paragraphs(content.text)
            } else emptyList()
            else -> error("Checked above")
        }
        val limits = getArticleLimits()
        val blocks = if (contentSources == 0) originalBlocks.toMutableList() else {
            val replacementText = ArticleContentFactory.build(draft.copy(photoPath = null, removePhoto = false), limits)
                .first.message.source as TdApi.RichMessageSourceBlocks
            val photos = originalBlocks.filterIsInstance<TdApi.InputPageBlockPhoto>()
            if (photos.isNotEmpty() && originalBlocks.first() !is TdApi.InputPageBlockPhoto) {
                throw InvalidToolInputException("Article has embedded photos outside its cover; edit content with blocks to retain placement")
            }
            (photos + replacementText.blocks.toList()).toMutableList()
        }
        if (draft.removePhoto) blocks.removeAll { it is TdApi.InputPageBlockPhoto }
        draft.photoPath?.let { path ->
            val photo = ArticleContentFactory.photoBlock(path)
            val index = blocks.indexOfFirst { it is TdApi.InputPageBlockPhoto }
            if (index >= 0) blocks[index] = photo else blocks.add(0, photo)
        }
        val replacement = ArticleContentFactory.fromBlocks(blocks, limits).first
        editBackupStore.save(original, fullRich)
        val edited = withResilience("editMessageText") {
            send<TdApi.Message>(TdApi.EditMessageText(chatId, messageId, null, replacement))
        }
        if (edited.id != messageId || edited.content !is TdApi.MessageRichMessage) {
            throw IllegalStateException("Article edit returned an unexpected message; check the original message before retrying")
        }
        return edited.toTelegramMessage(getChatTitleSafe(chatId))
    }

    fun editPhotoPost(
        chatId: Long, messageId: Long, photoPath: String?, caption: String?, parseMode: ParseMode,
    ): TelegramMessage {
        if (photoPath == null && caption == null) throw InvalidToolInputException(
            "Specify photo_path or caption; no edit was made",
        )
        val original = withResilience("getMessages") {
            send<TdApi.Messages>(TdApi.GetMessages(chatId, longArrayOf(messageId)))
        }.messages?.firstOrNull() ?: throw InvalidToolInputException("Message $messageId not found")
        val photo = original.content as? TdApi.MessagePhoto ?: throw InvalidToolInputException(
            "Message $messageId is not an ordinary photo post; use edit_article for Articles",
        )
        if (photo.video != null) throw InvalidToolInputException("Video-backed photo posts are not supported; no edit was made")
        val properties = withResilience("getMessageProperties") {
            send<TdApi.MessageProperties>(TdApi.GetMessageProperties(chatId, messageId))
        }
        if (photoPath != null && !properties.canEditMedia) throw InvalidToolInputException(
            "Telegram does not allow replacing media in this post; no edit was made",
        )
        if (caption != null && !properties.canBeEdited) throw InvalidToolInputException(
            "Telegram does not allow editing this caption; no edit was made",
        )
        val newCaption = caption?.let { formatText(it, parseMode) } ?: photo.caption
        val maxCaption = withResilience("getOption") {
            send<TdApi.OptionValue>(TdApi.GetOption("message_caption_length_max"))
        } as? TdApi.OptionValueInteger ?: throw IllegalStateException("Telegram did not return the caption limit")
        if ((newCaption?.text?.length ?: 0) > maxCaption.value) throw InvalidToolInputException(
            "Photo caption exceeds Telegram limit ${maxCaption.value}; no edit was made",
        )
        editBackupStore.save(original)
        val edited = if (photoPath == null) {
            withResilience("editMessageCaption") {
                send<TdApi.Message>(TdApi.EditMessageCaption(
                    chatId, messageId, null, newCaption, photo.showCaptionAboveMedia,
                ))
            }
        } else {
            val inputPhoto = TdApi.InputPhoto().apply {
                this.photo = TdApi.InputFileLocal(photoPath)
                addedStickerFileIds = intArrayOf()
            }
            val replacement = TdApi.InputMessagePhoto(
                inputPhoto, newCaption, photo.showCaptionAboveMedia, null, photo.hasSpoiler,
            )
            withResilience("editMessageMedia") {
                send<TdApi.Message>(TdApi.EditMessageMedia(chatId, messageId, null, replacement))
            }
        }
        if (edited.id != messageId || edited.content !is TdApi.MessagePhoto) {
            throw IllegalStateException("Photo edit returned an unexpected message; check the original before retrying")
        }
        return edited.toTelegramMessage(getChatTitleSafe(chatId))
    }

    // ── Extended message operations ────────────────────────────────────────

    fun deleteMessages(chatId: Long, messageIds: List<Long>, revoke: Boolean): Boolean {
        log.info("Deleting {} messages from chat {} (revoke={})", messageIds.size, chatId, revoke)

        withResilience("deleteMessages") {
            send<TdApi.Ok>(TdApi.DeleteMessages(chatId, messageIds.toLongArray(), revoke))
        }
        return true
    }


    fun scheduleMessage(
        chatId: Long,
        text: String,
        sendAtEpochSeconds: Int,
        repeatPeriodSeconds: Int,
        disableNotification: Boolean,
        parseMode: ParseMode,
        messageThreadId: Long?,
    ): ScheduledMessage {
        log.info("Scheduling message for chat {} at {}", chatId, sendAtEpochSeconds)
        val content = TdApi.InputMessageText().apply {
            this.text = formatText(text, parseMode)
            this.clearDraft = false
        }
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = messageThreadId?.let(::forumThreadTopic)
            this.options = TdApi.MessageSendOptions().apply {
                this.disableNotification = disableNotification
                this.schedulingState = TdApi.MessageSchedulingStateSendAtDate(sendAtEpochSeconds, repeatPeriodSeconds)
            }
            this.inputMessageContent = content
        }
        val message = support.sendAndAwaitFinal("sendMessage", request)
        return message.toScheduledMessage(getChatTitleSafe(chatId))
    }


    fun findCustomEmoji(setName: String, emoji: String): List<CustomEmojiCandidate> {
        require(setName.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "Invalid sticker set name" }
        require(emoji.isNotBlank() && emoji.length <= 16) { "Invalid emoji" }
        val set = withResilience("searchStickerSet") {
            send<TdApi.StickerSet>(TdApi.SearchStickerSet(setName, false))
        }
        require(set.name.equals(setName, ignoreCase = true)) { "Sticker set name mismatch" }
        require(set.stickerType is TdApi.StickerTypeCustomEmoji) { "Sticker set is not a custom emoji set" }
        return set.stickers.orEmpty().mapNotNull { sticker ->
            val fullType = sticker.fullType as? TdApi.StickerFullTypeCustomEmoji ?: return@mapNotNull null
            if (sticker.emoji != emoji || fullType.customEmojiId <= 0L) return@mapNotNull null
            val markup = "<tg-emoji emoji-id=\"${fullType.customEmojiId}\">$emoji</tg-emoji>"
            val parsed = withResilience("parseTextEntities") {
                send<TdApi.FormattedText>(TdApi.ParseTextEntities(markup, TdApi.TextParseModeHTML()))
            }
            val entity = parsed.entities?.singleOrNull()
            require(parsed.text == emoji && (entity?.type as? TdApi.TextEntityTypeCustomEmoji)?.customEmojiId == fullType.customEmojiId) {
                "TDLib cannot parse the selected custom emoji"
            }
            CustomEmojiCandidate(fullType.customEmojiId, sticker.emoji, set.name, set.title)
        }
    }

    fun setChatPhoto(chatId: Long, filePath: String): Boolean {
        log.info("Setting chat photo for chat {} from '{}'", chatId, filePath)

        val photo = TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(filePath))
        withResilience("setChatPhoto") {
            send<TdApi.Ok>(TdApi.SetChatPhoto(chatId, photo))
        }
        return true
    }

    fun deleteChatPhoto(chatId: Long): Boolean {
        log.info("Deleting chat photo for chat {}", chatId)

        withResilience("deleteChatPhoto") {
            send<TdApi.Ok>(TdApi.SetChatPhoto(chatId, null))
        }
        return true
    }

    // ── Drafts & profile ───────────────────────────────────────────────────

    fun saveDraft(chatId: Long, text: String, replyToMessageId: Long?): Boolean {
        log.info("Saving draft on chat {} (replyTo={})", chatId, replyToMessageId)

        val inputText = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, emptyArray())
            this.clearDraft = false
        }
        val replyTo = replyToMessageId?.let {
            TdApi.InputMessageReplyToMessage().apply { this.messageId = it }
        }
        val draft = TdApi.DraftMessage().apply {
            this.replyTo = replyTo
            this.date = (System.currentTimeMillis() / 1000L).toInt()
            this.content = TdApi.DraftMessageContentText(inputText.text, null)
        }
        withResilience("setChatDraftMessage") {
            send<TdApi.Ok>(TdApi.SetChatDraftMessage(chatId, null, draft))
        }
        return true
    }


}
