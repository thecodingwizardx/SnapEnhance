package me.rhunk.snapenhance.ui.menu.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import me.rhunk.snapenhance.core.database.objects.ConversationMessage
import me.rhunk.snapenhance.core.database.objects.FriendInfo
import me.rhunk.snapenhance.core.database.objects.UserConversationLink
import me.rhunk.snapenhance.core.util.snap.BitmojiSelfie
import me.rhunk.snapenhance.data.ContentType
import me.rhunk.snapenhance.data.FriendLinkType
import me.rhunk.snapenhance.features.impl.Messaging
import me.rhunk.snapenhance.ui.ViewAppearanceHelper
import me.rhunk.snapenhance.ui.applyTheme
import me.rhunk.snapenhance.ui.menu.AbstractMenu
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FriendFeedInfoMenu : AbstractMenu() {
    private fun getImageDrawable(url: String): Drawable {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connect()
        val input = connection.inputStream
        return BitmapDrawable(Resources.getSystem(), BitmapFactory.decodeStream(input))
    }

    private fun formatDate(timestamp: Long): String? {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date(timestamp))
    }

    private fun showProfileInfo(profile: FriendInfo) {
        var icon: Drawable? = null
        try {
            if (profile.bitmojiSelfieId != null && profile.bitmojiAvatarId != null) {
                icon = getImageDrawable(
                    BitmojiSelfie.getBitmojiSelfie(
                        profile.bitmojiSelfieId.toString(),
                        profile.bitmojiAvatarId.toString(),
                        BitmojiSelfie.BitmojiSelfieType.THREE_D
                    )!!
                )
            }
        } catch (e: Throwable) {
            context.log.error("Error loading bitmoji selfie", e)
        }
        val finalIcon = icon
        val translation = context.translation.getCategory("profile_info")

        context.runOnUiThread {
            val addedTimestamp: Long = profile.addedTimestamp.coerceAtLeast(profile.reverseAddedTimestamp)
            val builder = ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)
            builder.setIcon(finalIcon)
            builder.setTitle(profile.displayName ?: profile.username)

            val birthday = Calendar.getInstance()
            birthday[Calendar.MONTH] = (profile.birthday shr 32).toInt() - 1

            builder.setMessage(mapOf(
                translation["first_created_username"] to profile.firstCreatedUsername,
                translation["mutable_username"] to profile.mutableUsername,
                translation["display_name"] to profile.displayName,
                translation["added_date"] to formatDate(addedTimestamp),
                null to birthday.getDisplayName(
                    Calendar.MONTH,
                    Calendar.LONG,
                    context.translation.loadedLocale
                )?.let {
                    context.translation.format("profile_info.birthday",
                        "month" to it,
                        "day" to profile.birthday.toInt().toString())
                },
                translation["friendship"] to run {
                    translation.getCategory("friendship_link_type")[FriendLinkType.fromValue(profile.friendLinkType).shortName]
                },
                translation["add_source"] to context.database.getAddSource(profile.userId!!)?.takeIf { it.isNotEmpty() },
                translation["snapchat_plus"] to run {
                    translation.getCategory("snapchat_plus_state")[if (profile.postViewEmoji != null) "subscribed" else "not_subscribed"]
                }
            ).filterValues { it != null }.map {
                line -> "${line.key?.let { "$it: " } ?: ""}${line.value}"
            }.joinToString("\n"))

            builder.setPositiveButton(
                "OK"
            ) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
            builder.show()
        }
    }

    private fun showPreview(userId: String?, conversationId: String, androidCtx: Context?) {
        //query message
        val messages: List<ConversationMessage>? = context.database.getMessagesFromConversationId(
            conversationId,
            context.config.messaging.messagePreviewLength.get()
        )?.reversed()

        if (messages == null) {
            context.longToast("Can't fetch messages")
            return
        }

        val participants: Map<String, FriendInfo> = context.database.getConversationParticipants(conversationId)!!
            .map { context.database.getFriendInfo(it)!! }
            .associateBy { it.userId!! }
        
        val messageBuilder = StringBuilder()

        messages.forEach { message ->
            val sender: FriendInfo? = participants[message.senderId]

            var messageString: String = message.getMessageAsString() ?: ContentType.fromId(message.contentType).name

            if (message.contentType == ContentType.SNAP.id) {
                val readTimeStamp: Long = message.readTimestamp
                messageString = "\uD83D\uDFE5" //red square
                if (readTimeStamp > 0) {
                    messageString += " \uD83D\uDC40 " //eyes
                    messageString += DateFormat.getDateTimeInstance(
                        DateFormat.SHORT,
                        DateFormat.SHORT
                    ).format(Date(readTimeStamp))
                }
            }

            var displayUsername = sender?.displayName ?: sender?.usernameForSorting?: context.translation["conversation_preview.unknown_user"]

            if (displayUsername.length > 12) {
                displayUsername = displayUsername.substring(0, 13) + "... "
            }

            messageBuilder.append(displayUsername).append(": ").append(messageString).append("\n")
        }

        val targetPerson: FriendInfo? =
            if (userId == null) null else participants[userId]

        targetPerson?.streakExpirationTimestamp?.takeIf { it > 0 }?.let {
            val timeSecondDiff = ((it - System.currentTimeMillis()) / 1000 / 60).toInt()
            messageBuilder.append("\n")
                .append("\uD83D\uDD25 ") //fire emoji
                .append(
                    context.translation.format("conversation_preview.streak_expiration",
                    "day" to (timeSecondDiff / 60 / 24).toString(),
                    "hour" to (timeSecondDiff / 60 % 24).toString(),
                    "minute" to (timeSecondDiff % 60).toString()
                ))
        }

        messages.lastOrNull()?.let {
            messageBuilder
                .append("\n\n")
                .append(context.translation.format("conversation_preview.total_messages", "count" to it.serverMessageId.toString()))
                .append("\n")
        }

        //alert dialog
        val builder = ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)
        builder.setTitle(context.translation["conversation_preview.title"])
        builder.setMessage(messageBuilder.toString())
        builder.setPositiveButton(
            "OK"
        ) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
        targetPerson?.let {
            builder.setNegativeButton(context.translation["modal_option.profile_info"]) { _, _ ->
                context.executeAsync { showProfileInfo(it) }
            }
        }
        builder.show()
    }

    private fun getCurrentConversationInfo(): Pair<String, String?> {
        val messaging = context.feature(Messaging::class)
        val focusedConversationTargetUser: String? = messaging.lastFetchConversationUserUUID?.toString()

        //mapped conversation fetch (may not work with legacy sc versions)
        messaging.lastFetchGroupConversationUUID?.let {
            context.database.getFeedEntryByConversationId(it.toString())?.let { friendFeedInfo ->
                val participantSize = friendFeedInfo.participantsSize
                return it.toString() to if (participantSize == 1) focusedConversationTargetUser else null
            }
            throw IllegalStateException("No conversation found")
        }

        //old conversation fetch
        val conversationId = if (messaging.lastFetchConversationUUID == null && focusedConversationTargetUser != null) {
            val conversation: UserConversationLink = context.database.getConversationLinkFromUserId(focusedConversationTargetUser) ?: throw IllegalStateException("No conversation found")
            conversation.clientConversationId!!.trim().lowercase()
        } else {
            messaging.lastFetchConversationUUID.toString()
        }

        return conversationId to focusedConversationTargetUser
    }

    private fun createToggleFeature(viewConsumer: ((View) -> Unit), text: String, isChecked: () -> Boolean, toggle: (Boolean) -> Unit) {
        val switch = Switch(context.androidContext)
        switch.text = context.translation[text]
        switch.isChecked = isChecked()
        switch.applyTheme()
        switch.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
            toggle(checked)
        }
        viewConsumer(switch)
    }

    @SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode", "DefaultLocale", "InflateParams",
        "DiscouragedApi", "ClickableViewAccessibility"
    )
    fun inject(viewModel: View, viewConsumer: ((View) -> Unit)) {
        val modContext = context

        val friendFeedMenuOptions by context.config.userInterface.friendFeedMenuButtons
        if (friendFeedMenuOptions.isEmpty()) return

        val (conversationId, targetUser) = getCurrentConversationInfo()

        val previewButton = Button(viewModel.context).apply {
            text = modContext.translation["friend_menu_option.preview"]
            applyTheme(viewModel.width)
            setOnClickListener {
                showPreview(
                    targetUser,
                    conversationId,
                    context
                )
            }
        }

        if (friendFeedMenuOptions.contains("conversation_info")) {
            viewConsumer(previewButton)
        }

        modContext.features.getRuleFeatures().forEach { ruleFeature ->
            if (!friendFeedMenuOptions.contains(ruleFeature.ruleType.key)) return@forEach

            val ruleState = ruleFeature.getRuleState() ?: return@forEach
            createToggleFeature(viewConsumer,
                ruleFeature.ruleType.translateOptionKey(ruleState.key),
                { ruleFeature.getState(conversationId) },
                { ruleFeature.setState(conversationId, it) }
            )
        }
    }
}