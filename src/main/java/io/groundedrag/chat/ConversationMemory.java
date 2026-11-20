package io.groundedrag.chat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import io.groundedrag.generation.Turn;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Per-principal conversation history on Spring AI's {@link ChatMemory}: a message window over a
 * pluggable {@code ChatMemoryRepository} (bounded in-process by default, JDBC under the postgres
 * profile, so follow-ups survive restarts and work across replicas).
 *
 * <p>The memory key is a name-based UUID of principal and conversation id: one API key can never
 * read another's conversation even with the same id, and the key fits the repository's 36-char
 * column.
 */
public class ConversationMemory {

    private final ChatMemory memory;

    public ConversationMemory(ChatMemory memory) {
        this.memory = memory;
    }

    public List<Turn> history(String principal, String conversationId) {
        return Turn.fromMessages(memory.get(key(principal, conversationId)));
    }

    public void append(String principal, String conversationId, Turn turn) {
        memory.add(key(principal, conversationId),
                List.of(new UserMessage(turn.question()), new AssistantMessage(turn.answer())));
    }

    static String key(String principal, String conversationId) {
        return UUID.nameUUIDFromBytes((principal + "\u0000" + conversationId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }
}
