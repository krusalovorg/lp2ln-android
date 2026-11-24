package lp2ln_android.krusalov.org.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatConversationTest {
    @Test
    fun conversationsKeepPeersSeparateAndCountOnlyUnreadIncomingMessages() {
        val messages = listOf(
            ChatMessage("1", "alice", "Incoming", 10, false, "received", read = false),
            ChatMessage("2", "bob", "Later", 30, false, "received", read = true),
            ChatMessage("3", "alice", "Reply", 20, true, "delivered", read = false),
        )
        val conversations = chatConversations(messages)
        assertEquals(listOf("bob", "alice"), conversations.map { it.peerId })
        assertEquals("Reply", conversations[1].lastMessage.text)
        assertEquals(1, conversations[1].unreadCount)
        assertEquals(0, conversations[0].unreadCount)
    }
}
