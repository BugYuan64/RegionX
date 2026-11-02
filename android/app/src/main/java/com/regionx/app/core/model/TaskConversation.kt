package com.regionx.app.core.model

/** Separates the original screenshot answer from later questions without discarding legacy history. */
data class TaskConversation(val answer: MessageRecord?, val followUps: List<MessageRecord>, val initial: MessageRecord?) {
    val orderedMessages: List<MessageRecord> get() = listOfNotNull(initial, answer) + followUps
    companion object {
        fun answerId(taskId: String): String = "$taskId:answer"

        fun from(taskId: String, messages: List<MessageRecord>): TaskConversation {
            val initial = messages.firstOrNull { it.role == ChatRole.User }
            val named = messages.firstOrNull { it.id == answerId(taskId) }
            // Older versions did not assign a stable answer ID. Only an assistant before
            // the next user message is an original answer, not a reply to a follow-up.
            val afterInitial = if (initial == null) messages else messages.dropWhile { it.id != initial.id }.drop(1)
            val legacy = afterInitial.takeWhile { it.role != ChatRole.User }.firstOrNull { it.role == ChatRole.Assistant }
            val answer = named ?: legacy
            return TaskConversation(answer, messages.filter { it.id != initial?.id && it.id != answer?.id }, initial)
        }
    }
}
