package dev.callstream.probe

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification

/** A WhatsApp call notification, reduced to what answering needs. [parse] is pure, for tests. */
data class WhatsAppNotice(val callType: Int, val answer: PendingIntent?, val hangUp: PendingIntent?, val actions: Int) {
    data class Action(val title: String, val semantic: Int, val intent: PendingIntent?)

    companion object {
        val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        /** Notification.CallStyle call types; [REMOVED] is ours. */
        const val INCOMING = 1
        const val ONGOING = 2
        const val REMOVED = 0

        /** Answer words when the notification isn't a CallStyle one (English and Urdu WhatsApp). */
        private val ANSWER_WORDS = listOf("answer", "accept", "جواب", "قبول")
        private val HANG_UP_WORDS = listOf("decline", "hang up", "end", "reject", "مسترد", "ختم", "بند")

        /** Null unless [sbn] is one of WhatsApp's call notifications. */
        fun of(sbn: StatusBarNotification): WhatsAppNotice? {
            if (sbn.packageName !in PACKAGES) return null
            val n = sbn.notification ?: return null
            return parse(
                category = n.category,
                callType = n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0),
                answer = n.extras.pendingIntent(Notification.EXTRA_ANSWER_INTENT),
                hangUp = n.extras.pendingIntent(Notification.EXTRA_HANG_UP_INTENT) ?: n.extras.pendingIntent(Notification.EXTRA_DECLINE_INTENT),
                actions = n.actions.orEmpty().map { Action(it.title?.toString().orEmpty(), it.semanticAction, it.actionIntent) },
            )
        }

        /**
         * Null unless it's a call notification. CallStyle carries its intents and type; otherwise a call notification
         * with an Answer action counts as ringing (semantic CALL action or an answer word), and one without as ongoing.
         */
        fun parse(category: String?, callType: Int, answer: PendingIntent?, hangUp: PendingIntent?, actions: List<Action>): WhatsAppNotice? {
            if (category != Notification.CATEGORY_CALL && callType == 0) return null
            if (callType != 0) return WhatsAppNotice(callType, answer, hangUp, actions.size)
            val answerAction = actions.firstOrNull { it.semantic == Notification.Action.SEMANTIC_ACTION_CALL }
                ?: actions.firstOrNull { a -> ANSWER_WORDS.any { a.title.contains(it, ignoreCase = true) } }
            val hangUpAction = actions.firstOrNull { a -> a !== answerAction && HANG_UP_WORDS.any { a.title.contains(it, ignoreCase = true) } }
            return WhatsAppNotice(if (answerAction != null) INCOMING else ONGOING, answerAction?.intent, hangUpAction?.intent, actions.size)
        }

        private fun android.os.Bundle.pendingIntent(key: String): PendingIntent? =
            if (android.os.Build.VERSION.SDK_INT >= 33) getParcelable(key, PendingIntent::class.java)
            else @Suppress("DEPRECATION") (getParcelable(key) as? PendingIntent)
    }
}
